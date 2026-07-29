package yokai.data.extension.repo.model

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The store index is an external wire format, so these pin down the bits that are easy to break
 * silently: protobuf field numbers, the JSON spelling of the content warning enum, and the shape
 * that tells a legacy `repo.json` apart from a store index.
 */
class ExtensionStoreFormatTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val store = NetworkExtensionStore(
        name = "Totally Real Extensions",
        badgeLabel = "TRE",
        signingKey = "abc123",
        contact = NetworkExtensionStore.Contact(website = "https://example.org", discord = null),
        extensionList = NetworkExtensionStore.ExtensionList(
            listOf(
                NetworkExtensionStore.StoreExtension(
                    name = "Example",
                    packageName = "org.example.extension",
                    resources = NetworkExtensionStore.Resources(
                        apkUrl = "https://example.org/apk/example.apk",
                        iconUrl = "https://example.org/icon/example.png",
                    ),
                    extensionLib = "1.6",
                    versionCode = 7,
                    versionName = "1.6.2",
                    contentWarning = NetworkExtensionStore.ContentWarning.MIXED,
                    sources = listOf(
                        NetworkExtensionStore.StoreSource(1, "Example EN", "en", "https://example.org"),
                        NetworkExtensionStore.StoreSource(2, "Example JA", "ja"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `protobuf round trips`() {
        val decoded = ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(ProtoBuf.encodeToByteArray(store))

        assertEquals(store, decoded)
    }

    @Test
    fun `json decodes the upstream content warning spelling`() {
        val decoded = json.decodeFromString<NetworkExtensionStore>(
            """
            {
              "name": "Totally Real Extensions",
              "badgeLabel": "TRE",
              "signingKey": "abc123",
              "contact": { "website": "https://example.org" },
              "extensionList": {
                "extensions": [{
                  "name": "Example",
                  "packageName": "org.example.extension",
                  "resources": {
                    "apkUrl": "https://example.org/apk/example.apk",
                    "iconUrl": "https://example.org/icon/example.png"
                  },
                  "extensionLib": "1.6",
                  "versionCode": 7,
                  "versionName": "1.6.2",
                  "contentWarning": "CONTENT_WARNING_NSFW",
                  "sources": [{ "id": 1, "name": "Example EN", "language": "en" }]
                }]
              }
            }
            """.trimIndent(),
        )

        val extension = decoded.extensionList!!.extensions.single()
        assertEquals(NetworkExtensionStore.ContentWarning.NSFW, extension.contentWarning)
        assertEquals("https://example.org/apk/example.apk", extension.resources.apkUrl)
        assertNull(decoded.extensionListUrl)
    }

    @Test
    fun `a store index is not mistaken for a legacy repo`() {
        // How the service tells the two apart: both are JSON objects, only repo.json has `meta`.
        val storeJson = json.encodeToString(NetworkExtensionStore.serializer(), store)

        val asLegacy = runCatching {
            json.decodeFromString<NetworkLegacyExtensionRepo>(storeJson)
        }

        assert(asLegacy.isFailure) { "Store index was decoded as a legacy repo.json" }
    }

    @Test
    fun `legacy repo json decodes including the index_v2 pointer`() {
        val decoded = json.decodeFromString<NetworkLegacyExtensionRepo>(
            """
            {
              "meta": {
                "name": "Totally Real Extensions",
                "shortName": "TRE",
                "website": "https://example.org",
                "signingKeyFingerprint": "abc123"
              },
              "index_v2": "https://example.org/index.pb.gz"
            }
            """.trimIndent(),
        )

        assertEquals("https://example.org/index.pb.gz", decoded.indexV2)
        assertEquals("TRE", decoded.meta.shortName)
    }
}
