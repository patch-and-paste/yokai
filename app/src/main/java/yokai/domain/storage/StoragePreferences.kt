package yokai.domain.storage

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.storage.FolderProvider

class StoragePreferences(
    private val folderProvider: FolderProvider,
    private val preferenceStore: PreferenceStore,
) {
    fun baseStorageDirectory() = preferenceStore.getString(Preference.appStateKey("storage_dir"), folderProvider.path())

    /**
     * Where backups go, when the reader wants them somewhere a cloud sync can watch without
     * dragging downloads along. Blank keeps them under [baseStorageDirectory].
     */
    fun backupsDirectory() = preferenceStore.getString(Preference.appStateKey("backups_dir"), "")

    /**
     * Where saved pages go. Blank keeps them under [baseStorageDirectory].
     */
    fun pagesDirectory() = preferenceStore.getString(Preference.appStateKey("pages_dir"), "")
}
