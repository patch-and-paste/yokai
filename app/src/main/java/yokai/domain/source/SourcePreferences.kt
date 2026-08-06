package yokai.domain.source

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import yokai.domain.source.model.SourceGroupList
import yokai.domain.source.model.decodeSourceGroups
import yokai.domain.source.model.encode

class SourcePreferences(private val preferenceStore: PreferenceStore) {
    fun trustedExtensions() = preferenceStore.getStringSet("trusted_extensions", emptySet())

    /**
     * Records each installed extension's source repo as `pkgName|repoBaseUrl`. The record remains
     * available after a repo stops listing the extension.
     */
    fun extensionInstallSources() = preferenceStore.getStringSet("extension_install_sources", emptySet())

    fun externalLocalSource() = preferenceStore.getBoolean("pref_external_local_source", false)

    /**
     * User-defined groups of sources, stored as one JSON document. Not prefixed private or app
     * state, so it rides along in backups like every other source-organisation setting.
     */
    fun sourceGroups() = preferenceStore.getObject(
        key = "source_groups",
        defaultValue = SourceGroupList(),
        serializer = SourceGroupList::encode,
        deserializer = ::decodeSourceGroups,
    )
}
