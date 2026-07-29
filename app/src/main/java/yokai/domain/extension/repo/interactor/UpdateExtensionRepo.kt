package yokai.domain.extension.repo.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import yokai.domain.extension.repo.ExtensionRepoRepository
import yokai.domain.extension.repo.exception.SaveExtensionRepoException
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.domain.extension.repo.service.ExtensionRepoService

class UpdateExtensionRepo(
    private val extensionRepoRepository: ExtensionRepoRepository,
    networkService: NetworkHelper,
) {
    private val extensionRepoService = ExtensionRepoService(networkService.client)

    suspend fun awaitAll() = coroutineScope {
        extensionRepoRepository.getAll()
            .map { async { await(it) } }
            .awaitAll()
    }

    suspend fun await(repo: ExtensionRepo) {
        val newRepo = extensionRepoService.refresh(repo) ?: return
        if (!repo.canBeReplacedBy(newRepo)) return

        try {
            if (newRepo.baseUrl != repo.baseUrl) {
                extensionRepoRepository.migrateRepository(repo.baseUrl, newRepo)
            } else {
                extensionRepoRepository.upsertRepository(newRepo)
            }
        } catch (e: SaveExtensionRepoException) {
            Logger.e(e) { "Failed to refresh extension repo ${repo.baseUrl}" }
        }
    }

    /**
     * Whether the repo we just read is allowed to take the place of the one we already had.
     *
     * A matching signing key allows a repo to move to any host. Repos carried over by
     * `RepoJsonMigration` have a `NOFINGERPRINT-n` placeholder and no key to compare, so they may
     * follow `index_v2` only within their current host.
     */
    private fun ExtensionRepo.canBeReplacedBy(newRepo: ExtensionRepo): Boolean {
        if (signingKeyFingerprint == newRepo.signingKeyFingerprint) return true
        if (!signingKeyFingerprint.startsWith(NO_FINGERPRINT_PREFIX)) return false

        val host = baseUrl.toHttpUrlOrNull()?.host
        val newHost = newRepo.baseUrl.toHttpUrlOrNull()?.host
        if (host == null || newHost == null || host != newHost) {
            Logger.w { "Refusing to re-point unverified repo $baseUrl to ${newRepo.baseUrl}" }
            return false
        }
        return true
    }

    companion object {
        const val NO_FINGERPRINT_PREFIX = "NOFINGERPRINT"
    }
}
