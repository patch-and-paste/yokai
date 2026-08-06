package yokai.domain.source.interactor

import yokai.domain.source.SourcePreferences
import yokai.domain.source.model.SourceGroup
import yokai.domain.source.model.SourceGroupList

class UpdateSourceGroups(private val sourcePreferences: SourcePreferences) {

    fun create(name: String): Result {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return Result.NameBlank

        val current = sourcePreferences.sourceGroups().get()
        if (current.isNameTaken(trimmed)) return Result.NameTaken

        val group = SourceGroup.create(trimmed)
        set(current.upsert(group))
        return Result.Success(group)
    }

    fun rename(id: String, name: String): Result {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return Result.NameBlank

        val current = sourcePreferences.sourceGroups().get()
        if (current.isNameTaken(trimmed, exceptId = id)) return Result.NameTaken

        val renamed = current.withName(id, trimmed)
        set(renamed)
        return renamed.findById(id)?.let { Result.Success(it) } ?: Result.NotFound
    }

    fun delete(id: String) = set(sourcePreferences.sourceGroups().get().removeById(id))

    fun setMembers(id: String, sourceIds: List<Long>) =
        set(sourcePreferences.sourceGroups().get().withMembers(id, sourceIds))

    fun setShowInBrowse(id: String, value: Boolean) =
        set(sourcePreferences.sourceGroups().get().withShowInBrowse(id, value))

    fun setIncludeInGlobalSearch(id: String, value: Boolean) =
        set(sourcePreferences.sourceGroups().get().withIncludeInGlobalSearch(id, value))

    private fun set(value: SourceGroupList) = sourcePreferences.sourceGroups().set(value)

    sealed interface Result {
        data class Success(val group: SourceGroup) : Result
        data object NameBlank : Result
        data object NameTaken : Result
        data object NotFound : Result
    }
}
