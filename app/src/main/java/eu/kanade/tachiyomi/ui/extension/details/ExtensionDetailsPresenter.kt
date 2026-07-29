package eu.kanade.tachiyomi.ui.extension.details

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.ExtensionRepoStatus
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.util.system.launchUI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.extension.repo.interactor.GetExtensionRepo

class ExtensionDetailsPresenter(
    val pkgName: String,
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getExtensionRepo: GetExtensionRepo = Injekt.get(),
) : BaseCoroutinePresenter<ExtensionDetailsController>() {

    val extension = extensionManager.installedExtensionsFlow.value.find { it.pkgName == pkgName }

    private val currentExtension: Extension.Installed?
        get() = extensionManager.installedExtensionsFlow.value.find { it.pkgName == pkgName }

    private val _repoStatuses = MutableStateFlow(emptyList<ExtensionRepoStatus>())

    /** The install source and repos that currently offer this package, most relevant first. */
    val repoStatuses = _repoStatuses.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        bindToExtensionChanges()
        presenterScope.launch { refreshRepoStatuses() }
    }

    private fun bindToExtensionChanges() {
        extensionManager.installedExtensionsFlow
            .drop(1)
            .onEach { extensions ->
                if (extensions.none { it.pkgName == pkgName }) {
                    presenterScope.launchUI { view?.onExtensionUninstalled() }
                } else {
                    // Refresh the displayed repo status after an update or repo switch.
                    refreshRepoStatuses()
                }
            }
            .launchIn(presenterScope)

        extensionManager.availableExtensionsFlow
            .drop(1)
            .onEach { refreshRepoStatuses() }
            .launchIn(presenterScope)
    }

    private suspend fun refreshRepoStatuses() {
        val installed = currentExtension
        if (installed == null) {
            _repoStatuses.value = emptyList()
            return
        }

        _repoStatuses.value = ExtensionRepoStatus.listFor(
            installed = installed,
            repos = getExtensionRepo.getAll(),
            candidates = extensionManager.candidatesFor(pkgName),
            installSourceUrl = extensionManager.installSourceOf(pkgName),
        )
    }

    /**
     * Installs the version represented by [status]. Downgrades and signing-key changes remove the
     * installed extension first.
     */
    fun installFrom(status: ExtensionRepoStatus) {
        val installed = currentExtension ?: return
        val target = status.available ?: return

        if (status.requiresReinstall) {
            extensionManager.replaceExtension(installed, target)
        } else {
            presenterScope.launch {
                extensionManager
                    .installExtension(ExtensionManager.ExtensionInfo(target), presenterScope)
                    .collect()
            }
        }
    }

    fun uninstallExtension() {
        val extension = extension ?: return
        extensionManager.uninstallExtension(extension.pkgName)
    }
}
