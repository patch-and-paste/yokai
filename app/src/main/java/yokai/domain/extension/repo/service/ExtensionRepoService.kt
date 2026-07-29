package yokai.domain.extension.repo.service

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okio.BufferedSource
import okio.buffer
import okio.gzip
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.extension.repo.model.NetworkExtensionStore
import yokai.data.extension.repo.model.NetworkLegacyExtension
import yokai.data.extension.repo.model.NetworkLegacyExtensionRepo
import yokai.data.extension.repo.model.toAvailableExtensions
import yokai.domain.extension.repo.model.ExtensionRepo

/**
 * Reads extension repos in both the tachiyomix store format (protobuf or JSON, optionally gzipped)
 * and the legacy `index.min.json` + `repo.json` pair.
 *
 * The format isn't declared anywhere, so it's sniffed from the first byte of the response: `[` is a
 * legacy listing, `{` is JSON, anything else is protobuf.
 */
class ExtensionRepoService(
    private val client: OkHttpClient,
    private val json: Json = Injekt.get(),
) {

    /**
     * Resolves [url] into a repo, following a legacy repo's `index_v2` pointer to its replacement.
     *
     * @return the repo, or null if [url] doesn't serve anything we recognise.
     */
    suspend fun fetchRepoDetails(url: String): ExtensionRepo? = withIOContext {
        try {
            resolve(url, depth = 0)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to fetch repo details from $url" }
            null
        }
    }

    private suspend fun resolve(url: String, depth: Int): ExtensionRepo? {
        if (depth > MAX_REDIRECTS) {
            Logger.e { "Gave up resolving $url, too many index_v2 redirects" }
            return null
        }

        return client.newCall(GET(url)).awaitSuccess().body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte()) {
                LEGACY_LIST_PREFIX -> {
                    // A bare listing, its metadata lives in repo.json next to it
                    val baseUrl = url.removeSuffix("/$LEGACY_INDEX_FILE")
                    if (baseUrl == url) {
                        Logger.e { "$url is a legacy listing but isn't named $LEGACY_INDEX_FILE" }
                        null
                    } else {
                        resolve("$baseUrl/$LEGACY_REPO_FILE", depth + 1)
                    }
                }
                JSON_OBJECT_PREFIX -> {
                    val legacy = runCatching {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                    }.getOrNull()

                    when {
                        legacy == null ->
                            json.decodeFromBufferedSource<NetworkExtensionStore>(source).toExtensionRepo(url)
                        legacy.indexV2 != null -> resolve(legacy.indexV2, depth + 1)
                        else -> legacy.toExtensionRepo(url.removeSuffix("/$LEGACY_REPO_FILE"))
                    }
                }
                else -> ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                    .toExtensionRepo(url)
            }
        }
    }

    /**
     * Re-reads an already known repo. A legacy repo that has grown an `index_v2` pointer comes back
     * as a store, with a different [ExtensionRepo.baseUrl] than it was stored under.
     */
    suspend fun refresh(repo: ExtensionRepo): ExtensionRepo? = fetchRepoDetails(
        if (repo.isLegacy) "${repo.baseUrl}/$LEGACY_REPO_FILE" else repo.baseUrl,
    )

    /**
     * Lists everything [repo] currently offers. Failures are logged and swallowed so one unreachable
     * repo doesn't take the whole list down with it.
     */
    suspend fun getExtensions(repo: ExtensionRepo): List<Extension.Available> = withIOContext {
        try {
            val extensionListUrl = repo.extensionListUrl
            when {
                extensionListUrl != null -> readExtensionList(extensionListUrl, repo)
                !repo.isLegacy -> readStore(repo.baseUrl).extensionList?.toAvailableExtensions(repo).orEmpty()
                // A repo restored from a backup that predates the store format is flagged legacy
                // even when its url is a store index, so fall back rather than showing it empty
                else -> try {
                    readLegacyExtensions(repo)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    readStore(repo.baseUrl).extensionList?.toAvailableExtensions(repo).orEmpty()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Keep a fetch or parse failure in one repo from discarding the other repos' results.
            Logger.e(e) { "Failed to get extensions from ${repo.baseUrl}" }
            emptyList()
        }
    }

    private suspend fun readStore(url: String): NetworkExtensionStore =
        client.newCall(GET(url)).awaitSuccess().body.source().decompressIfGzipped().use { source ->
            if (source.peek().readByte() == JSON_OBJECT_PREFIX) {
                json.decodeFromBufferedSource(source)
            } else {
                ProtoBuf.decodeFromByteArray(source.readByteArray())
            }
        }

    private suspend fun readExtensionList(url: String, repo: ExtensionRepo): List<Extension.Available> =
        client.newCall(GET(url)).awaitSuccess().body.source().decompressIfGzipped().use { source ->
            val list = if (source.peek().readByte() == JSON_OBJECT_PREFIX) {
                json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
            } else {
                ProtoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(source.readByteArray())
            }
            list.toAvailableExtensions(repo)
        }

    private suspend fun readLegacyExtensions(repo: ExtensionRepo): List<Extension.Available> =
        client.newCall(GET("${repo.baseUrl}/$LEGACY_INDEX_FILE")).awaitSuccess().body.source().use { source ->
            json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                .map { it.toAvailableExtension(repo) }
        }

    /**
     * Stores may serve their index gzipped without saying so in the headers, in which case OkHttp
     * hands it over untouched.
     */
    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = try {
            peek().readShort().toInt() and 0xFFFF == GZIP_MAGIC
        } catch (_: Exception) {
            false
        }
        return if (isGzip) gzip().buffer() else this
    }

    companion object {
        private const val LEGACY_INDEX_FILE = "index.min.json"
        private const val LEGACY_REPO_FILE = "repo.json"

        private const val LEGACY_LIST_PREFIX = '['.code.toByte()
        private const val JSON_OBJECT_PREFIX = '{'.code.toByte()
        private const val GZIP_MAGIC = 0x1f8b

        private const val MAX_REDIRECTS = 3
    }
}
