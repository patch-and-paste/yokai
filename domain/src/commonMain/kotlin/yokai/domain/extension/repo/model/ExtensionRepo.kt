package yokai.domain.extension.repo.model

data class ExtensionRepo(
    /**
     * Identifies the repo. For a tachiyomix store this is the index URL as added by the user; for a
     * legacy repo it is the base URL, with `index.min.json` and friends resolved against it.
     */
    val baseUrl: String,
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
    /** True for `index.min.json` style repos, false for tachiyomix stores. */
    val isLegacy: Boolean = true,
    /** Set when a store publishes its extension list separately from the index. */
    val extensionListUrl: String? = null,
)
