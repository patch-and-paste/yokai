package yokai.domain.source

import eu.kanade.tachiyomi.core.preference.PreferenceStore

class SourcePreferences(private val preferenceStore: PreferenceStore) {
    fun trustedExtensions() = preferenceStore.getStringSet("trusted_extensions", emptySet())

    /**
     * Records each installed extension's source repo as `pkgName|repoBaseUrl`. The record remains
     * available after a repo stops listing the extension.
     */
    fun extensionInstallSources() = preferenceStore.getStringSet("extension_install_sources", emptySet())

    fun externalLocalSource() = preferenceStore.getBoolean("pref_external_local_source", false)
}
