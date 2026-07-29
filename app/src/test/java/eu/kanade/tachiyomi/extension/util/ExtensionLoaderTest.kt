package eu.kanade.tachiyomi.extension.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionLoaderTest {

    @Test
    fun `reads the lib version out of the shapes repos actually publish`() {
        // Bare, as the tachiyomix manifest spec writes it
        assertEquals(1.6, ExtensionLoader.parseLibVersion("1.6"))
        // Store indexes and extension versionNames carry a third component
        assertEquals(1.6, ExtensionLoader.parseLibVersion("1.6.0"))
        assertEquals(1.4, ExtensionLoader.parseLibVersion("1.4.23"))
        // Tolerate decoration rather than dropping the whole repo over it
        assertEquals(1.6, ExtensionLoader.parseLibVersion("v1.6.2"))
        assertEquals(1.5, ExtensionLoader.parseLibVersion(" 1.5 "))
    }

    @Test
    fun `everything it parses stays comparable with the supported set`() {
        listOf("1.3", "1.4.0", "1.5.12", "v1.6.0").forEach {
            assertTrue(
                ExtensionLoader.parseLibVersion(it) in ExtensionLoader.SUPPORTED_LIB_VERSIONS,
                "$it should have been recognised",
            )
        }
    }

    @Test
    fun `unparseable versions are rejected rather than guessed at`() {
        assertNull(ExtensionLoader.parseLibVersion(""))
        assertNull(ExtensionLoader.parseLibVersion("nightly"))
        assertTrue(ExtensionLoader.parseLibVersion("9.9.9") !in ExtensionLoader.SUPPORTED_LIB_VERSIONS)
    }
}
