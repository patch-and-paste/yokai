package yokai.domain.source.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source groups live in a single JSON preference that rides along in backups, so the wire format is
 * user data. These tests pin the shape and make sure a bad payload can never take the app down.
 */
class SourceGroupTest {

    private val sample = SourceGroupList(
        groups = listOf(
            SourceGroup(
                id = "7c0c2ba1-3a5e-4c1a-9a4c-3d9b0a2f1e88",
                name = "NSFW",
                sourceIds = listOf(4096L, 1024L, 2048L),
                showInBrowse = true,
                includeInGlobalSearch = false,
            ),
        ),
    )

    @Test
    fun `round trip preserves every field including source order`() {
        val decoded = decodeSourceGroups(sample.encode())

        assertEquals(sample, decoded)
        assertEquals(listOf(4096L, 1024L, 2048L), decoded.groups.single().sourceIds)
    }

    @Test
    fun `encodes to the expected wire shape`() {
        // Renaming a field here silently drops user data through backups, so pin the exact bytes.
        val expected = """{"version":1,"groups":[{"id":"7c0c2ba1-3a5e-4c1a-9a4c-3d9b0a2f1e88",""" +
            """"name":"NSFW","sourceIds":[4096,1024,2048],"showInBrowse":true,""" +
            """"includeInGlobalSearch":false}]}"""

        assertEquals(expected, sample.encode())
    }

    @Test
    fun `decoding tolerates unknown keys from a newer version`() {
        val raw = """{"version":1,"groups":[{"id":"a","name":"A","sourceIds":[1],"futureFlag":true}]}"""

        val decoded = decodeSourceGroups(raw)

        assertEquals(1, decoded.groups.size)
        assertEquals("A", decoded.groups.single().name)
    }

    @Test
    fun `malformed payloads decode to an empty list instead of throwing`() {
        listOf("", "not json", "{", "[]", """{"groups":"nope"}""").forEach { raw ->
            assertEquals(SourceGroupList(), decodeSourceGroups(raw), "failed for: $raw")
        }
    }

    @Test
    fun `groupedSourceIds unions across groups and de-duplicates`() {
        val list = SourceGroupList(
            groups = listOf(
                SourceGroup(id = "a", name = "A", sourceIds = listOf(1L, 2L)),
                SourceGroup(id = "b", name = "B", sourceIds = listOf(2L, 3L)),
            ),
        )

        assertEquals(setOf(1L, 2L, 3L), list.groupedSourceIds())
    }

    @Test
    fun `exclusion wins when a source is in both an excluded and an included group`() {
        val list = SourceGroupList(
            groups = listOf(
                SourceGroup(id = "a", name = "A", sourceIds = listOf(1L, 2L), includeInGlobalSearch = false),
                SourceGroup(id = "b", name = "B", sourceIds = listOf(2L, 3L), includeInGlobalSearch = true),
            ),
        )

        assertEquals(setOf(1L, 2L), list.globalSearchExcludedSourceIds())
    }

    @Test
    fun `isNameTaken is case insensitive but ignores the group's own id`() {
        val list = SourceGroupList(groups = listOf(SourceGroup(id = "a", name = "NSFW")))

        assertTrue(list.isNameTaken("nsfw"))
        assertTrue(list.isNameTaken("NSFW", exceptId = "b"))
        // Renaming NSFW to nsfw is a legal case change, not a collision with itself
        assertFalse(list.isNameTaken("nsfw", exceptId = "a"))
    }

    @Test
    fun `withMembers de-duplicates and preserves order`() {
        val list = SourceGroupList(groups = listOf(SourceGroup(id = "a", name = "A")))

        val updated = list.withMembers("a", listOf(3L, 1L, 3L, 2L))

        assertEquals(listOf(3L, 1L, 2L), updated.groups.single().sourceIds)
    }

    @Test
    fun `upsert replaces in place and removeById drops only the target`() {
        val list = SourceGroupList(
            groups = listOf(
                SourceGroup(id = "a", name = "A"),
                SourceGroup(id = "b", name = "B"),
            ),
        )

        val renamed = list.upsert(list.findById("a")!!.copy(name = "A2"))
        assertEquals(listOf("A2", "B"), renamed.groups.map { it.name })

        assertEquals(listOf("B"), renamed.removeById("a").groups.map { it.name })
    }

    @Test
    fun `mutations on a missing id are a no-op`() {
        val list = SourceGroupList(groups = listOf(SourceGroup(id = "a", name = "A")))

        assertEquals(list, list.withName("missing", "X"))
        assertEquals(list, list.withMembers("missing", listOf(1L)))
        assertEquals(list, list.withShowInBrowse("missing", false))
        assertEquals(list, list.withIncludeInGlobalSearch("missing", false))
    }

    @Test
    fun `groupsContaining finds every group holding a source`() {
        val list = SourceGroupList(
            groups = listOf(
                SourceGroup(id = "a", name = "A", sourceIds = listOf(1L)),
                SourceGroup(id = "b", name = "B", sourceIds = listOf(1L, 2L)),
                SourceGroup(id = "c", name = "C", sourceIds = listOf(2L)),
            ),
        )

        assertEquals(listOf("A", "B"), list.groupsContaining(1L).map { it.name })
    }
}
