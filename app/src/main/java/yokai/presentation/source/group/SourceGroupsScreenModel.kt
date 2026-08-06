package yokai.presentation.source.group

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.source.interactor.GetSourceGroups
import yokai.domain.source.interactor.UpdateSourceGroups
import yokai.domain.source.model.SourceGroup
import yokai.i18n.MR

class SourceGroupsScreenModel : StateScreenModel<SourceGroupsScreenModel.State>(State.Loading) {

    private val getSourceGroups: GetSourceGroups by injectLazy()
    private val updateSourceGroups: UpdateSourceGroups by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    private val eventChannel = Channel<SourceGroupEvent>(Channel.BUFFERED)
    val event: Flow<SourceGroupEvent> = eventChannel.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getSourceGroups.changes().collectLatest { stored ->
                val installedIds = sourceManager.getCatalogueSources().mapTo(mutableSetOf()) { it.id }
                mutableState.update {
                    State.Success(
                        groups = stored.groups
                            .map { group ->
                                SourceGroupListItem(
                                    group = group,
                                    installedCount = group.sourceIds.count { id -> id in installedIds },
                                )
                            }
                            .toImmutableList(),
                    )
                }
            }
        }
    }

    fun createGroup(name: String) = screenModelScope.launchIO {
        handle(updateSourceGroups.create(name))
    }

    fun renameGroup(id: String, name: String) = screenModelScope.launchIO {
        handle(updateSourceGroups.rename(id, name))
    }

    fun deleteGroup(id: String) = screenModelScope.launchIO {
        updateSourceGroups.delete(id)
        invalidateBrowse()
    }

    fun setShowInBrowse(id: String, value: Boolean) = screenModelScope.launchIO {
        updateSourceGroups.setShowInBrowse(id, value)
        invalidateBrowse()
    }

    fun setIncludeInGlobalSearch(id: String, value: Boolean) = screenModelScope.launchIO {
        updateSourceGroups.setIncludeInGlobalSearch(id, value)
    }

    private suspend fun handle(result: UpdateSourceGroups.Result) {
        when (result) {
            is UpdateSourceGroups.Result.Success -> invalidateBrowse()
            is UpdateSourceGroups.Result.NameBlank -> eventChannel.send(SourceGroupEvent.NameBlank)
            is UpdateSourceGroups.Result.NameTaken -> eventChannel.send(SourceGroupEvent.NameTaken)
            is UpdateSourceGroups.Result.NotFound -> Unit
        }
    }

    /** Browse caches its item list across instances, so it has to be told the grouping moved. */
    private fun invalidateBrowse() = SourcePresenter.invalidateCache()

    @Immutable
    data class SourceGroupListItem(val group: SourceGroup, val installedCount: Int)

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Success(val groups: ImmutableList<SourceGroupListItem>) : State {
            val isEmpty: Boolean
                get() = groups.isEmpty()
        }
    }
}

sealed class SourceGroupEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : SourceGroupEvent()
    data object NameBlank : LocalizedMessage(MR.strings.source_group_name_cannot_be_blank)
    data object NameTaken : LocalizedMessage(MR.strings.source_group_with_name_exists)
}
