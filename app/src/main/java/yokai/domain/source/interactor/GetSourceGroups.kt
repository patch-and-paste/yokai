package yokai.domain.source.interactor

import kotlinx.coroutines.flow.Flow
import yokai.domain.source.SourcePreferences
import yokai.domain.source.model.SourceGroup
import yokai.domain.source.model.SourceGroupList

class GetSourceGroups(private val sourcePreferences: SourcePreferences) {

    fun all(): List<SourceGroup> = sourcePreferences.sourceGroups().get().groups

    /** Replays the current value before emitting changes. */
    fun changes(): Flow<SourceGroupList> = sourcePreferences.sourceGroups().changes()

    fun byId(id: String): SourceGroup? = sourcePreferences.sourceGroups().get().findById(id)

    /** Every source that belongs to a group, and so is hidden from the main Browse list. */
    fun groupedSourceIds(): Set<Long> = sourcePreferences.sourceGroups().get().groupedSourceIds()

    /** Sources global search skips unless the user asks for all sources explicitly. */
    fun globalSearchExcludedSourceIds(): Set<Long> =
        sourcePreferences.sourceGroups().get().globalSearchExcludedSourceIds()

    fun groupsContaining(sourceId: Long): List<SourceGroup> =
        sourcePreferences.sourceGroups().get().groupsContaining(sourceId)
}
