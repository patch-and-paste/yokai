{
  description = "Yōkai Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";

      # These have to match what the build asks for. When they do not, Gradle
      # tries to install the missing component into the read-only Nix store and
      # fails with an unrelated-looking "SDK directory is not writable" error.
      #   platform, NDK  ->  buildSrc/src/main/kotlin/AndroidConfig.kt
      #   build-tools    ->  AGP 8.12.2's default, and .github/workflows/*.yml
      platformVersion = "36";
      buildToolsVersion = "35.0.0";
      ndkVersion = "27.2.12479018";

      pkgs = import nixpkgs {
        inherit system;
        # Both settings apply only to this flake's package set. The SDK
        # components are redistributed under Google's terms, and androidenv
        # reads accept_license from here rather than from an env var.
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      androidSdk =
        (pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ platformVersion ];
          buildToolsVersions = [ buildToolsVersion ];

          # Dependencies ship .so files, and AGP strips them with the NDK named
          # in AndroidConfig.kt. Without it the build still succeeds but packages
          # the libraries unstripped. This is the bulk of the closure.
          includeNDK = true;
          ndkVersions = [ ndkVersion ];

          # No module declares externalNativeBuild, and emulators are out of scope.
          includeCmake = false;
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
        }).androidsdk;

      sdkRoot = "${androidSdk}/libexec/android-sdk";
      jdk = pkgs.jdk17;

      # Not in nixpkgs. The release workflow gets this binary from
      # taiki-e/install-action, and `just changelog` reads the same CHANGELOG.md
      # sections the same way, so build it here rather than let the recipe fail.
      parse-changelog = pkgs.rustPlatform.buildRustPackage rec {
        pname = "parse-changelog";
        version = "0.6.17";

        src = pkgs.fetchCrate {
          inherit pname version;
          hash = "sha256-cy6XR/Qwt+r/fvP6eofCLkKcwpoY7T2lWGQZvBowyY0=";
        };

        cargoHash = "sha256-S5tJeQX8Xcho2Rmpan/DnzEsCq1V7Bw3GJ4igJTBuNo=";

        # The crates.io tarball ships the cli test target but not the in-repo
        # dev-dependency it calls assert_success on, so the test build cannot
        # compile. The library and binary themselves build clean.
        doCheck = false;
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        name = "yokai";

        packages = [
          # adb, sdkmanager, apkanalyzer, apksigner, zipalign, lint, retrace
          androidSdk
          # The JVM Gradle runs on, and keytool for the signing recipes
          jdk
          pkgs.just
          # app/build.gradle.kts shells out to git for versionName and commit count
          pkgs.git
          # `just keystore-new` generates its password with this
          pkgs.openssl
          # Turns `just bundle` output into installable APKs
          pkgs.bundletool
          parse-changelog
        ];

        JAVA_HOME = jdk.home;
        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";

        # Skips the justfile's probe for a build-tools directory that runs.
        ANDROID_BUILD_TOOLS = buildToolsVersion;

        # AGP resolves aapt2 from Maven as a prebuilt glibc binary, which needs
        # an interpreter at /lib64 that NixOS does not have. Machines running
        # nix-ld paper over this; the rest get a cryptic ENOENT from a path that
        # plainly exists. The SDK's own aapt2 is patched, so point AGP at it.
        # Kept here rather than in gradle.properties so CI, which does not need
        # the override, stays untouched.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

        LANG = "C.UTF-8";
        LC_ALL = "C.UTF-8";

        shellHook = ''
          # Android Studio and any Gradle run read sdk.dir from local.properties
          # and never see ANDROID_HOME. The path moves every time this flake's
          # inputs change, and a stale one survives until the store path is
          # garbage collected, so rewrite it when it drifts. The file is
          # gitignored user-local state.
          if [ -f settings.gradle.kts ] &&
             [ "$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)" != "${sdkRoot}" ]; then
            printf 'sdk.dir=%s\n' "${sdkRoot}" > local.properties
            echo "flake: local.properties now points at ${sdkRoot}"
          fi
        '';
      };

      formatter.${system} = pkgs.nixfmt;
    };
}
