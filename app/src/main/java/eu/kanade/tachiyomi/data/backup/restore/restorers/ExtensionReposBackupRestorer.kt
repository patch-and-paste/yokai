package eu.kanade.tachiyomi.data.backup.restore.restorers

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.extension.repo.ExtensionRepoRepository

class ExtensionReposBackupRestorer(
    private val extensionRepoRepository: ExtensionRepoRepository = Injekt.get(),
) {
    suspend fun restoreExtensionRepos(backupRepos: List<BackupExtensionRepo>, onComplete: () -> Unit) {
        backupRepos.forEach { backupRepo ->
            try {
                extensionRepoRepository.upsertRepository(backupRepo.toExtensionRepo())
            } catch (e: Exception) {
                // A repo already claiming the same signing key is the expected conflict here, and
                // it means the user already has it under a different URL.
                Logger.e(e) { "Failed to restore extension repo ${backupRepo.baseUrl}" }
            }
        }
        onComplete()
    }
}
