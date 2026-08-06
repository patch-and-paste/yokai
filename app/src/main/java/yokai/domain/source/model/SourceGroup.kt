package yokai.domain.source.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A user-defined bundle of catalogue sources. A grouped source leaves the main Browse list and is
 * only reachable from the group's own screen.
 *
 * @param id stays fixed for the lifetime of the group so a rename can't orphan an open group screen.
 * @param sourceIds a list rather than a set: JSON has no set type, and keeping it ordered means the
 * stored document round-trips unchanged.
 * @param showInBrowse whether a row for this group appears in the Browse list. When false the group
 * is still reachable from the Browse toolbar.
 * @param includeInGlobalSearch whether global search reaches into this group's sources by default.
 */
@Serializable
data class SourceGroup(
    val id: String,
    val name: String,
    val sourceIds: List<Long> = emptyList(),
    val showInBrowse: Boolean = true,
    val includeInGlobalSearch: Boolean = true,
) {
    companion object {
        fun create(name: String) = SourceGroup(id = UUID.randomUUID().toString(), name = name)
    }
}

/**
 * Envelope for the serialized form. [version] is the escape hatch for a change that
 * [Json.ignoreUnknownKeys] can't absorb on its own, such as renaming or dropping a field.
 */
@Serializable
data class SourceGroupList(
    val version: Int = CURRENT_VERSION,
    val groups: List<SourceGroup> = emptyList(),
) {
    fun findById(id: String): SourceGroup? = groups.find { it.id == id }

    /** Every source that belongs to at least one group. */
    fun groupedSourceIds(): Set<Long> = groups.flatMapTo(mutableSetOf()) { it.sourceIds }

    /**
     * Sources global search should skip by default. A source in both an excluded and an included
     * group stays excluded, the safer reading of the switch.
     */
    fun globalSearchExcludedSourceIds(): Set<Long> = groups
        .filterNot { it.includeInGlobalSearch }
        .flatMapTo(mutableSetOf()) { it.sourceIds }

    fun groupsContaining(sourceId: Long): List<SourceGroup> = groups.filter { sourceId in it.sourceIds }

    fun isNameTaken(name: String, exceptId: String? = null): Boolean = groups.any {
        it.id != exceptId && it.name.equals(name, ignoreCase = true)
    }

    fun upsert(group: SourceGroup): SourceGroupList {
        val index = groups.indexOfFirst { it.id == group.id }
        return copy(
            groups = if (index == -1) groups + group else groups.toMutableList().apply { this[index] = group },
        )
    }

    fun removeById(id: String): SourceGroupList = copy(groups = groups.filterNot { it.id == id })

    fun withName(id: String, name: String): SourceGroupList =
        findById(id)?.let { upsert(it.copy(name = name)) } ?: this

    fun withMembers(id: String, sourceIds: List<Long>): SourceGroupList =
        findById(id)?.let { upsert(it.copy(sourceIds = sourceIds.distinct())) } ?: this

    fun withShowInBrowse(id: String, value: Boolean): SourceGroupList =
        findById(id)?.let { upsert(it.copy(showInBrowse = value)) } ?: this

    fun withIncludeInGlobalSearch(id: String, value: Boolean): SourceGroupList =
        findById(id)?.let { upsert(it.copy(includeInGlobalSearch = value)) } ?: this

    companion object {
        const val CURRENT_VERSION = 1
    }
}

private val sourceGroupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun SourceGroupList.encode(): String = sourceGroupJson.encodeToString(this)

/**
 * Never throws. A hand-edited or truncated payload degrades to "no groups", which puts Browse back
 * to normal rather than crash-looping on every launch.
 */
fun decodeSourceGroups(raw: String): SourceGroupList = try {
    sourceGroupJson.decodeFromString<SourceGroupList>(raw)
} catch (_: Exception) {
    SourceGroupList()
}
