package eu.kanade.tachiyomi.util.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DiskUtilTest {

    // U+1F512, the lock a few sources put in front of a rented chapter
    private val lock = "🔒"

    @Test
    fun `Short names are returned unchanged`() {
        assertEquals("Chapter 1", DiskUtil.takeWholeChars("Chapter 1", 240))
        assertEquals("abcd", DiskUtil.takeWholeChars("abcd", 4))
    }

    @Test
    fun `A cut landing inside an emoji drops the whole emoji`() {
        // "ab" plus the high half of the lock would be a lone surrogate
        assertEquals("ab", DiskUtil.takeWholeChars("ab$lock", 3))
    }

    @Test
    fun `A cut landing after an emoji keeps it whole`() {
        assertEquals("ab$lock", DiskUtil.takeWholeChars("ab${lock}cd", 4))
    }

    @Test
    fun `buildValidFilename never ends on a lone surrogate`() {
        val name = "x".repeat(239) + lock
        val built = DiskUtil.buildValidFilename(name)

        assertEquals(239, built.length)
        assertFalse(built.last().isHighSurrogate(), "Filename ended on half of a surrogate pair")
    }

    @Test
    fun `buildValidFilename keeps replacing invalid characters`() {
        assertEquals("a_b_c", DiskUtil.buildValidFilename("a/b:c"))
        assertEquals("(invalid)", DiskUtil.buildValidFilename("  ..  "))
    }
}
