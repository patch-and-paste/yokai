package eu.kanade.tachiyomi.extension.model

import yokai.domain.extension.repo.model.ExtensionRepo

/**
 * Describes an installed extension relative to one configured repo, including the offered version
 * and available action.
 */
data class ExtensionRepoStatus(
    val repoUrl: String,
    val repoName: String,
    /** What this repo currently offers, or null once it has dropped the package. */
    val available: Extension.Available?,
    /** True for the repo the installed APK came from. */
    val isInstallSource: Boolean,
    /** True when the install source was matched by signing key rather than recorded at install. */
    val isInferredInstallSource: Boolean,
    /** False when this repo signs with a different key than the installed APK. */
    val signatureMatches: Boolean,
    val action: Action,
) {

    /**
     * Android replaces an APK in place only when the new one is signed with the same key and isn't
     * a downgrade. Anything else has to remove the extension first.
     */
    val requiresReinstall: Boolean
        get() = action != Action.NONE && !(action == Action.UPDATE && signatureMatches)

    enum class Action {
        /** No action is available because this version is installed or the repo dropped the package. */
        NONE,
        UPDATE,
        SWITCH,
        DOWNGRADE,
    }

    companion object {

        /**
         * Lists the install-source repo first, even if it no longer offers [installed], followed by
         * the other repos that offer it, newest version first. Repos unrelated to the package are
         * omitted.
         *
         * [installSourceUrl] is the recorded install source. When it is null, a single repo whose
         * signing key matches the installed APK is marked as the inferred source.
         */
        fun listFor(
            installed: Extension.Installed,
            repos: List<ExtensionRepo>,
            candidates: List<Extension.Available>,
            installSourceUrl: String?,
        ): List<ExtensionRepoStatus> {
            val reposByUrl = repos.associateBy { it.baseUrl }
            val repoOrder = repos.withIndex().associate { (index, repo) -> repo.baseUrl to index }

            val inferredSource = when (installSourceUrl) {
                null -> repos.singleOrNull { it.signs(installed) }
                else -> null
            }
            val sourceUrl = installSourceUrl ?: inferredSource?.baseUrl

            // A repo lists a package once, but don't let a malformed index produce two rows for it
            val offered = candidates
                .filterNot { it.repoUrl.isNullOrBlank() }
                .groupBy { it.repoUrl!! }
                .mapValues { (_, listings) -> listings.maxByOrNull { it.versionCode }!! }

            val urls = LinkedHashSet<String>()
            sourceUrl?.let(urls::add)
            repos.forEach { if (it.baseUrl in offered) urls += it.baseUrl }
            urls += offered.keys

            return urls
                .map { url ->
                    val repo = reposByUrl[url]
                    val available = offered[url]
                    val isSource = url == sourceUrl
                    ExtensionRepoStatus(
                        repoUrl = url,
                        repoName = repo?.name ?: url,
                        available = available,
                        isInstallSource = isSource,
                        isInferredInstallSource = isSource && inferredSource != null,
                        signatureMatches = repo != null && repo.signs(installed),
                        action = actionFor(installed, available, isSource),
                    )
                }
                .sortedWith(
                    compareByDescending<ExtensionRepoStatus> { it.isInstallSource }
                        .thenByDescending { it.available?.versionCode ?: Long.MIN_VALUE }
                        .thenBy { repoOrder[it.repoUrl] ?: Int.MAX_VALUE },
                )
        }

        private fun actionFor(
            installed: Extension.Installed,
            available: Extension.Available?,
            isInstallSource: Boolean,
        ): Action = when {
            available == null -> Action.NONE
            available.versionCode > installed.versionCode ||
                available.libVersion > installed.libVersion -> Action.UPDATE
            available.versionCode < installed.versionCode -> Action.DOWNGRADE
            isInstallSource -> Action.NONE
            else -> Action.SWITCH
        }
    }
}

/**
 * The listing to install or update a package from when the user hasn't picked a repo themselves:
 * the newest one, preferring a repo that can replace the installed APK in place over one that would
 * need it removed first.
 */
fun pickExtensionCandidate(
    candidates: List<Extension.Available>,
    installed: Extension.Installed?,
    repos: List<ExtensionRepo>,
): Extension.Available? {
    if (candidates.size <= 1) return candidates.firstOrNull()

    val repoOrder = repos.withIndex().associate { (index, repo) -> repo.baseUrl to index }
    val signedByRepo = repos.filter { installed != null && it.signs(installed) }.mapTo(HashSet()) { it.baseUrl }
    return candidates.minWithOrNull(
        compareBy<Extension.Available> { installed?.signatureHash != null && it.repoUrl !in signedByRepo }
            .thenByDescending { it.versionCode }
            .thenByDescending { it.libVersion }
            .thenBy { repoOrder[it.repoUrl] ?: Int.MAX_VALUE },
    )
}

/**
 * Uses the same signing-key comparison as
 * [yokai.domain.extension.interactor.TrustExtension]. A repo trusted to sign this extension can
 * also update it.
 */
private fun ExtensionRepo.signs(installed: Extension.Installed): Boolean =
    installed.signatureHash != null && signingKeyFingerprint == installed.signatureHash
