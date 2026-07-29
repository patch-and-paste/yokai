package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.extension.repo.service.ExtensionRepoService

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()

    private val extensionRepoService by lazy { ExtensionRepoService(networkService.client) }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { extensionRepoService.getExtensions(it) } }
                .awaitAll()
                .flatten()
                .filter { it.libVersion in ExtensionLoader.SUPPORTED_LIB_VERSIONS }
        }
    }

    suspend fun checkForUpdates(context: Context, prefetchedExtensions: List<Extension.Available>? = null): List<Extension.Available> {
        return withIOContext {
            val extensions = prefetchedExtensions ?: findExtensions()

            // Update extension repo details
            updateExtensionRepo.awaitAll()

            val extensionManager: ExtensionManager = Injekt.get()
            val installedExtensions = extensionManager.installedExtensionsFlow.value.ifEmpty {
                ExtensionLoader.loadExtensionAsync(context)
                    .filterIsInstance<LoadResult.Success>()
                    .map { it.extension }
            }

            // Several repos can offer the same package; an update from any of them counts
            val newestPerPkgName = extensions
                .groupBy { it.pkgName }
                .mapValues { (_, listings) ->
                    listings.maxWith(compareBy({ it.versionCode }, { it.libVersion }))
                }

            val extensionsWithUpdate = mutableListOf<Extension.Available>()
            for (installedExt in installedExtensions) {
                val pkgName = installedExt.pkgName
                val availableExt = newestPerPkgName[pkgName] ?: continue
                val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
                val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
                val hasUpdate = hasUpdatedVer || hasUpdatedLib
                if (hasUpdate) {
                    extensionsWithUpdate.add(availableExt)
                }
            }

            extensionsWithUpdate
        }
    }

    fun getApkUrl(extension: ExtensionManager.ExtensionInfo): String = extension.apkUrl.ifBlank {
        // Install jobs queued before this app version stored the file name and the repo base
        "${extension.repoUrl}/apk/${extension.apkName}"
    }
}
