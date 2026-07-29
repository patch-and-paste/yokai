package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backups are meant to be interchangeable with upstream, so the field numbers of the repo entry
 * have to line up with `BackupExtensionStore`, including its out-of-order website and signing-key
 * fields.
 */
class BackupExtensionRepoTest {

    /** Mirrors upstream's declaration, so a mismatch shows up as a decoding failure. */
    @Serializable
    private data class UpstreamExtensionStore(
        @ProtoNumber(1) val indexUrl: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val badgeLabel: String?,
        @ProtoNumber(5) val signingKey: String,
        @ProtoNumber(4) val contactWebsite: String,
        @ProtoNumber(6) val contactDiscord: String?,
        @ProtoNumber(7) val isLegacy: Boolean?,
        @ProtoNumber(8) val extensionListUrl: String?,
    )

    @Test
    fun `decodes a backup written by upstream`() {
        val upstream = UpstreamExtensionStore(
            indexUrl = "https://example.org/index.pb.gz",
            name = "Totally Real Extensions",
            badgeLabel = "TRE",
            signingKey = "abc123",
            contactWebsite = "https://example.org",
            contactDiscord = "https://discord.gg/example",
            isLegacy = false,
            extensionListUrl = "https://example.org/extensions.pb.gz",
        )

        val decoded = ProtoBuf.decodeFromByteArray<BackupExtensionRepo>(ProtoBuf.encodeToByteArray(upstream))

        assertEquals(upstream.indexUrl, decoded.baseUrl)
        assertEquals(upstream.name, decoded.name)
        assertEquals(upstream.badgeLabel, decoded.shortName)
        assertEquals(upstream.contactWebsite, decoded.website)
        assertEquals(upstream.signingKey, decoded.signingKeyFingerprint)
        assertEquals(upstream.contactDiscord, decoded.contactDiscord)
        assertEquals(upstream.extensionListUrl, decoded.extensionListUrl)

        val repo = decoded.toExtensionRepo()
        assertEquals("https://example.org/index.pb.gz", repo.baseUrl)
        assertTrue(!repo.isLegacy)
    }

    @Test
    fun `a backup without the legacy flag restores as a legacy repo`() {
        val old = BackupExtensionRepo(
            baseUrl = "https://example.org/repo",
            name = "Old Repo",
            website = "https://example.org",
            signingKeyFingerprint = "abc123",
        )

        val decoded = ProtoBuf.decodeFromByteArray<BackupExtensionRepo>(ProtoBuf.encodeToByteArray(old))

        assertTrue(decoded.toExtensionRepo().isLegacy)
    }
}
