# Yokai build tasks. Run `just` for the list.
#
# Variants are <flavor><BuildType>, e.g. standardRelease.
#   flavors     standard (Firebase + in-app updater), dev (en-only resources, no Firebase)
#   build types debug, release, beta, nightly
#
# What comes out already signed:
#   debug, nightly  -> signed with the local Android debug key
#   release, beta   -> unsigned; run `just sign <variant>` to sign with the release keystore

set shell := ["bash", "-euo", "pipefail", "-c"]

# Override any of these via environment.
keystore := env_var_or_default("YOKAI_KEYSTORE", home_directory() / ".keystores/yokai/yokai-release.jks")
creds := env_var_or_default("YOKAI_KEYSTORE_CREDS", home_directory() / ".keystores/yokai/credentials.txt")
key_alias := env_var_or_default("YOKAI_KEY_ALIAS", "yokai")
sdk := env_var_or_default("ANDROID_HOME", home_directory() / "Android/Sdk")

gradle := "./gradlew"
outdir := "app/build/outputs/apk"

# Show all recipes
default:
    @just --list --unsorted

# Assemble any variant, e.g. `just assemble standardBeta`
[group('build')]
assemble variant="standardRelease":
    #!/usr/bin/env bash
    set -euo pipefail
    v="{{ variant }}"
    {{ gradle }} "assemble${v^}"

# standard + debug (debug-signed, .debugYokai)
[group('build')]
debug: (assemble "standardDebug")

# standard + release (unsigned, .yokai)
[group('build')]
release: (assemble "standardRelease")

# standard + beta (unsigned, .yokai)
[group('build')]
beta: (assemble "standardBeta")

# standard + nightly (debug-signed, .nightlyYokai)
[group('build')]
nightly: (assemble "standardNightly")

# dev + debug (skips Firebase and non-en resources)
[group('build')]
dev: (assemble "devDebug")

# dev + release (unsigned)
[group('build')]
dev-release: (assemble "devRelease")

# Every variant of every flavor (slow)
[group('build')]
assemble-all:
    {{ gradle }} assemble

# Android App Bundle, e.g. `just bundle standardRelease`
[group('build')]
bundle variant="standardRelease":
    #!/usr/bin/env bash
    set -euo pipefail
    v="{{ variant }}"
    {{ gradle }} "bundle${v^}"

# Highest build-tools directory whose apksigner actually runs. Not simply the
# highest version: SDK-manager-installed build-tools carry a /bin/bash shebang
# that does not exist on NixOS, while the nix-provided ones are patched.
# Pin explicitly with ANDROID_BUILD_TOOLS=35.0.0 to skip the probe.
[private]
_buildtools:
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
        echo "{{ sdk }}/build-tools/$ANDROID_BUILD_TOOLS"
        exit 0
    fi

    # Glob rather than `ls`, which is commonly aliased to something decorated.
    # sort -V so 9.0.0 orders below 10.0.0; the last one that runs wins.
    best=""
    while read -r d; do
        [ -x "$d/apksigner" ] || continue
        "$d/apksigner" version >/dev/null 2>&1 || continue
        best="$d"
    done < <(printf '%s\n' "{{ sdk }}"/build-tools/*/ | sed 's:/$::' | sort -V)

    if [ -z "$best" ]; then
        echo "no working apksigner under {{ sdk }}/build-tools" >&2
        exit 1
    fi
    echo "$best"

# Zipalign + sign a variant's unsigned APKs with the release keystore
[group('sign')]
sign variant="standardRelease":
    #!/usr/bin/env bash
    set -euo pipefail

    dir="{{ outdir }}/$(echo '{{ variant }}' | sed 's/\([A-Z]\)/\/\l\1/')"
    [ -d "$dir" ] || { echo "no output dir $dir; build it first: just assemble {{ variant }}"; exit 1; }

    shopt -s nullglob
    apks=("$dir"/app-*-unsigned.apk)
    if [ ${#apks[@]} -eq 0 ]; then
        echo "no unsigned APKs in $dir"
        echo "(debug and nightly are already signed with the debug key)"
        exit 1
    fi

    [ -f "{{ keystore }}" ] || { echo "keystore not found: {{ keystore }}"; exit 1; }

    bin="$(just _buildtools)"

    # Password from the environment, else from the credentials file.
    if [ -n "${YOKAI_KEYSTORE_PASSWORD:-}" ]; then
        export YOKAI_KS_PW="$YOKAI_KEYSTORE_PASSWORD"
    elif [ -f "{{ creds }}" ]; then
        export YOKAI_KS_PW="$(grep '^KEY_STORE_PASSWORD' "{{ creds }}" | sed 's/.*= //')"
    else
        echo "no password: set YOKAI_KEYSTORE_PASSWORD or provide {{ creds }}"; exit 1
    fi

    for apk in "${apks[@]}"; do
        out="${apk%-unsigned.apk}-signed.apk"
        "$bin/zipalign" -p -f 4 "$apk" "$out.tmp"
        "$bin/apksigner" sign \
            --ks "{{ keystore }}" --ks-key-alias "{{ key_alias }}" \
            --ks-pass env:YOKAI_KS_PW --key-pass env:YOKAI_KS_PW \
            --v1-signing-enabled true --v2-signing-enabled true \
            --v3-signing-enabled true --v4-signing-enabled false \
            --out "$out" "$out.tmp"
        rm -f "$out.tmp" "$out.tmp.idsig"
        echo "signed $(basename "$out")"
    done

# Build a variant and sign it
[group('sign')]
signed variant="standardRelease": (assemble variant) (sign variant)

# Verify signatures and print the signing certificate
[group('sign')]
verify variant="standardRelease":
    #!/usr/bin/env bash
    set -euo pipefail

    dir="{{ outdir }}/$(echo '{{ variant }}' | sed 's/\([A-Z]\)/\/\l\1/')"
    bin="$(just _buildtools)"

    shopt -s nullglob
    apks=("$dir"/*-signed.apk "$dir"/*-debug.apk "$dir"/*-nightly.apk)
    if [ ${#apks[@]} -eq 0 ]; then echo "nothing signed in $dir"; exit 1; fi

    for apk in "${apks[@]}"; do
        echo "== $(basename "$apk")"
        "$bin/apksigner" verify --print-certs -v "$apk" \
            | grep -E "Verified using v[123] scheme|certificate DN|certificate SHA-256"
    done

# Fingerprint and validity of the release keystore
[group('keystore')]
keystore-info:
    #!/usr/bin/env bash
    set -euo pipefail
    [ -f "{{ keystore }}" ] || { echo "keystore not found: {{ keystore }}"; exit 1; }
    pw="${YOKAI_KEYSTORE_PASSWORD:-$(grep '^KEY_STORE_PASSWORD' "{{ creds }}" | sed 's/.*= //')}"
    keytool -list -v -keystore "{{ keystore }}" -storepass "$pw" -alias "{{ key_alias }}" \
        | grep -E "Alias name|Valid from|Signature algorithm|SHA256:"

# Print the keystore base64 for the SIGNING_KEY GitHub Actions secret
[group('keystore')]
keystore-base64:
    @base64 -w0 "{{ keystore }}"; echo

# Create a new release keystore (refuses to overwrite an existing one)
[group('keystore')]
keystore-new dname="CN=patch-and-paste, O=patch-and-paste, OU=Yokai":
    #!/usr/bin/env bash
    set -euo pipefail

    if [ -e "{{ keystore }}" ]; then echo "refusing to overwrite {{ keystore }}"; exit 1; fi
    mkdir -p "$(dirname "{{ keystore }}")" && chmod 700 "$(dirname "{{ keystore }}")"

    pw="$(openssl rand -base64 24 | tr -d '/+=' | head -c 28)"
    keytool -genkeypair -v -keystore "{{ keystore }}" -storetype PKCS12 \
        -alias "{{ key_alias }}" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
        -validity 10000 -dname "{{ dname }}" -storepass "$pw" -keypass "$pw"

    # PKCS12 cannot hold separate store and key passwords, so both secrets get the same value.
    # Written with printf, not a heredoc: recipe indentation would leak into the file and
    # break the `^KEY_STORE_PASSWORD` lookup the sign recipe does.
    printf 'Keystore : %s\nAlias    : %s\n\nALIAS               = %s\nKEY_STORE_PASSWORD  = %s\nKEY_PASSWORD        = %s\n' \
        "{{ keystore }}" "{{ key_alias }}" "{{ key_alias }}" "$pw" "$pw" > "{{ creds }}"

    chmod 600 "{{ keystore }}" "{{ creds }}"
    echo "created {{ keystore }}; back it up. Losing it means installed builds cannot be updated"

# Unit tests, same set CI runs
[group('test')]
test:
    {{ gradle }} testReleaseUnitTest testStandardReleaseUnitTest

# Unit tests for every module and variant
[group('test')]
test-all:
    {{ gradle }} test

# Android lint
[group('test')]
lint:
    {{ gradle }} lint

# Run tests and lint configured by Gradle's `check` task
[group('test')]
check:
    {{ gradle }} check

# What .github/workflows/build_check.yml runs on a PR
[group('test')]
ci: release test

# List built APKs with sizes
[group('info')]
apks:
    @find {{ outdir }} -name "*.apk" -printf "%10s  %p\n" 2>/dev/null | numfmt --to=iec --field=1 | sort -k2 || echo "nothing built yet"

# Version name, version code, and the commit count nightly tags use
[group('info')]
version:
    @grep -E '^val _versionName|versionCode = ' app/build.gradle.kts | sed 's/^ *//'
    @echo "commit count: $(git rev-list --count HEAD)  ($(git rev-parse --short HEAD))"

# Changelog section for a version, as CI parses it
[group('info')]
changelog version="Unreleased":
    @command -v parse-changelog >/dev/null || { echo "parse-changelog is not installed" >&2; exit 127; }
    @parse-changelog CHANGELOG.md {{ version }} \
        --version-format '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?(-[0-9A-Za-z\.-]+)?(\+[0-9A-Za-z\.-]+)?$|^Unreleased$' \
        || { status=$?; echo "parse-changelog failed for {{ version }} (exit $status)" >&2; exit "$status"; }

# Dependency update report -> build/dependencyUpdates
[group('info')]
deps:
    {{ gradle }} dependencyUpdates

# Gradle clean
[group('clean')]
clean:
    {{ gradle }} clean

# Clean, stop the daemon, and drop every build directory
[group('clean')]
deep-clean:
    -{{ gradle }} clean
    -{{ gradle }} --stop
    find . -type d -name build -not -path "./.git/*" -prune -exec rm -rf {} +
    rm -rf .gradle
