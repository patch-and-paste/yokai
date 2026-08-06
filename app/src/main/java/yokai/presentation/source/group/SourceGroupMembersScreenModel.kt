package yokai.presentation.source.group

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.source.interactor.GetSourceGroups
import yokai.domain.source.interactor.UpdateSourceGroups
import yokai.domain.source.model.SourceGroupList

class SourceGroupMembersScreenModel(private val groupId: String) :
    StateScreenModel<SourceGroupMembersScreenModel.State>(State.Loading) {

    private val getSourceGroups: GetSourceGroups by injectLazy()
    private val updateSourceGroups: UpdateSourceGroups by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    init {
        // changes() replays the current value, so this covers the initial load too
        screenModelScope.launchIO {
            getSourceGroups.changes().collectLatest { refresh(it) }
        }
    }

    private fun refresh(stored: SourceGroupList) {
        val group = stored.findById(groupId)
        if (group == null) {
            mutableState.update { State.Missing }
            return
        }

        val selected = group.sourceIds.toSet()
        val installed = sourceManager.getCatalogueSources()

        // Group by owning extension so ticking an extension can move all its sources at once.
        val byExtension = extensionManager.installedExtensionsFlow.value
            .sortedBy { it.name.lowercase() }
            .mapNotNull { extension ->
                val sources = extension.sources
                    .filterIsInstance<CatalogueSource>()
                    .sortedBy { "(${it.lang}) ${it.name}" }
                if (sources.isEmpty()) return@mapNotNull null
                ExtensionEntry(
                    label = extension.name,
                    sources = sources.map { it.toEntry(stored, selected) }.toImmutableList(),
                )
            }

        // Anything not owned by an installed extension, most notably the local source.
        val accountedFor = byExtension.flatMapTo(mutableSetOf()) { entry -> entry.sources.map { it.id } }
        val orphans = installed
            .filterNot { it.id in accountedFor }
            .sortedBy { "(${it.lang}) ${it.name}" }
            .map { it.toEntry(stored, selected) }

        val entries = buildList {
            addAll(byExtension)
            if (orphans.isNotEmpty()) {
                // null label means the screen falls back to the localized "Other"
                add(ExtensionEntry(label = null, sources = orphans.toImmutableList()))
            }
        }

        mutableState.update {
            State.Success(
                groupName = group.name,
                extensions = entries.toImmutableList(),
            )
        }
    }

    private fun CatalogueSource.toEntry(stored: SourceGroupList, selected: Set<Long>) = SourceEntry(
        id = id,
        name = name,
        lang = lang,
        source = this,
        isSelected = id in selected,
        otherGroups = stored.groupsContaining(id)
            .filterNot { it.id == groupId }
            .map { it.name }
            .toImmutableList(),
    )

    fun toggleSource(sourceId: Long, selected: Boolean) = screenModelScope.launchIO {
        val current = getSourceGroups.byId(groupId)?.sourceIds.orEmpty()
        val next = if (selected) current + sourceId else current - sourceId
        updateSourceGroups.setMembers(groupId, next)
        SourcePresenter.invalidateCache()
    }

    fun toggleExtension(sourceIds: List<Long>, selected: Boolean) = screenModelScope.launchIO {
        val current = getSourceGroups.byId(groupId)?.sourceIds.orEmpty()
        val next = if (selected) current + sourceIds else current - sourceIds.toSet()
        updateSourceGroups.setMembers(groupId, next)
        SourcePresenter.invalidateCache()
    }

    @Immutable
    data class SourceEntry(
        val id: Long,
        val name: String,
        val lang: String,
        val source: CatalogueSource,
        val isSelected: Boolean,
        val otherGroups: ImmutableList<String>,
    )

    /** @param label null for sources with no installed extension behind them. */
    @Immutable
    data class ExtensionEntry(
        val label: String?,
        val sources: ImmutableList<SourceEntry>,
    ) {
        val allSelected: Boolean get() = sources.all { it.isSelected }
        val noneSelected: Boolean get() = sources.none { it.isSelected }
    }

    sealed interface State {

        @Immutable
        data object Loading : State

        /** The group was deleted while the picker was open. */
        @Immutable
        data object Missing : State

        @Immutable
        data class Success(
            val groupName: String,
            val extensions: ImmutableList<ExtensionEntry>,
        ) : State
    }
}
