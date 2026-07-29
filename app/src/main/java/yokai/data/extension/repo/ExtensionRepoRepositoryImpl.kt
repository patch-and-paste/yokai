package yokai.data.extension.repo

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import yokai.data.DatabaseHandler
import yokai.domain.extension.repo.ExtensionRepoRepository
import yokai.domain.extension.repo.exception.SaveExtensionRepoException
import yokai.domain.extension.repo.model.ExtensionRepo

class ExtensionRepoRepositoryImpl(private val handler: DatabaseHandler): ExtensionRepoRepository {
    override fun subscribeAll(): Flow<List<ExtensionRepo>> =
        handler.subscribeToList { extension_reposQueries.findAll(::mapExtensionRepo) }

    override suspend fun getAll(): List<ExtensionRepo> =
        handler.awaitList { extension_reposQueries.findAll(::mapExtensionRepo) }

    override suspend fun getRepository(baseUrl: String): ExtensionRepo? =
        handler.awaitOneOrNull { extension_reposQueries.findOne(baseUrl, ::mapExtensionRepo) }

    override suspend fun getRepositoryBySigningKeyFingerprint(fingerprint: String): ExtensionRepo? =
        handler.awaitOneOrNull { extension_reposQueries.findOneBySigningKeyFingerprint(fingerprint, ::mapExtensionRepo) }

    override fun getCount(): Flow<Int> =
        handler.subscribeToOne { extension_reposQueries.count() }.map { it.toInt() }

    override suspend fun insertRepository(repo: ExtensionRepo) {
        try {
            handler.await {
                extension_reposQueries.insert(
                    base_url = repo.baseUrl,
                    name = repo.name,
                    short_name = repo.shortName,
                    website = repo.website,
                    fingerprint = repo.signingKeyFingerprint,
                    isLegacy = repo.isLegacy,
                    extensionListUrl = repo.extensionListUrl,
                )
            }
        } catch (exc: SQLiteException) {
            throw SaveExtensionRepoException(exc)
        }
    }

    override suspend fun upsertRepository(repo: ExtensionRepo) {
        try {
            handler.await {
                extension_reposQueries.upsert(
                    base_url = repo.baseUrl,
                    name = repo.name,
                    short_name = repo.shortName,
                    website = repo.website,
                    fingerprint = repo.signingKeyFingerprint,
                    isLegacy = repo.isLegacy,
                    extensionListUrl = repo.extensionListUrl,
                )
            }
        } catch (exc: SQLiteException) {
            throw SaveExtensionRepoException(exc)
        }
    }

    override suspend fun replaceRepository(newRepo: ExtensionRepo) {
        handler.await {
            extension_reposQueries.replace(
                base_url = newRepo.baseUrl,
                name = newRepo.name,
                short_name = newRepo.shortName,
                website = newRepo.website,
                fingerprint = newRepo.signingKeyFingerprint,
                isLegacy = newRepo.isLegacy,
                extensionListUrl = newRepo.extensionListUrl,
            )
        }
    }

    override suspend fun migrateRepository(oldBaseUrl: String, newRepo: ExtensionRepo) {
        try {
            handler.await(inTransaction = true) {
                extension_reposQueries.delete(oldBaseUrl)
                extension_reposQueries.upsert(
                    base_url = newRepo.baseUrl,
                    name = newRepo.name,
                    short_name = newRepo.shortName,
                    website = newRepo.website,
                    fingerprint = newRepo.signingKeyFingerprint,
                    isLegacy = newRepo.isLegacy,
                    extensionListUrl = newRepo.extensionListUrl,
                )
            }
        } catch (exc: SQLiteException) {
            throw SaveExtensionRepoException(exc)
        }
    }

    override suspend fun deleteRepository(baseUrl: String) {
        handler.await { extension_reposQueries.delete(baseUrl) }
    }

    private fun mapExtensionRepo(
        baseUrl: String,
        name: String,
        shortName: String?,
        website: String,
        signingKeyFingerprint: String,
        isLegacy: Boolean,
        extensionListUrl: String?,
    ): ExtensionRepo = ExtensionRepo(
        baseUrl = baseUrl,
        name = name,
        shortName = shortName,
        website = website,
        signingKeyFingerprint = signingKeyFingerprint,
        isLegacy = isLegacy,
        extensionListUrl = extensionListUrl,
    )
}
