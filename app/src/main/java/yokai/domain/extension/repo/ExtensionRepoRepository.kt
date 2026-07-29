package yokai.domain.extension.repo

import kotlinx.coroutines.flow.Flow
import yokai.domain.extension.repo.model.ExtensionRepo

interface ExtensionRepoRepository {
    fun subscribeAll(): Flow<List<ExtensionRepo>>
    suspend fun getAll(): List<ExtensionRepo>
    suspend fun getRepository(baseUrl: String): ExtensionRepo?
    suspend fun getRepositoryBySigningKeyFingerprint(fingerprint: String): ExtensionRepo?
    fun getCount(): Flow<Int>
    suspend fun insertRepository(repo: ExtensionRepo)
    suspend fun upsertRepository(repo: ExtensionRepo)
    suspend fun replaceRepository(newRepo: ExtensionRepo)

    /**
     * Moves a repo to a new [ExtensionRepo.baseUrl] in one transaction. Deleting first is
     * required because the old row holds the unique signing key fingerprint, so the two halves
     * must not be separately abortable.
     */
    suspend fun migrateRepository(oldBaseUrl: String, newRepo: ExtensionRepo)
    suspend fun deleteRepository(baseUrl: String)
}
