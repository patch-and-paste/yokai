package yokai.data.extension.repo.model

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.model.ContentRating
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import yokai.domain.extension.repo.model.ExtensionRepo

/**
 * A tachiyomix extension store index, served either as protobuf or as JSON and optionally gzipped.
 *
 * Field numbers have to stay in sync with upstream, they are the wire format.
 */
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String? = null,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact = Contact(),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {

    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<StoreExtension> = emptyList(),
    )

    @Serializable
    data class StoreExtension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<StoreSource> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class StoreSource(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
        ;

        fun toContentRating(): ContentRating = when (this) {
            UNSPECIFIED, SAFE -> ContentRating.SAFE
            MIXED -> ContentRating.MIXED
            NSFW -> ContentRating.NSFW
        }
    }

    fun toExtensionRepo(indexUrl: String) = ExtensionRepo(
        baseUrl = indexUrl,
        name = name,
        shortName = badgeLabel,
        website = contact.website,
        signingKeyFingerprint = signingKey,
        isLegacy = false,
        extensionListUrl = extensionListUrl,
    )
}

fun NetworkExtensionStore.ExtensionList.toAvailableExtensions(repo: ExtensionRepo): List<Extension.Available> {
    return extensions.filter { extension ->
        // A store hands us the download URL outright, so it is the only thing standing between the
        // installer and an arbitrary host. Repos themselves are HTTPS only; APKs have to be too.
        val secure = extension.resources.apkUrl.startsWith("https://", ignoreCase = true)
        if (!secure) {
            Logger.w { "Dropping ${extension.packageName} from ${repo.baseUrl}; APK URL does not use HTTPS" }
        }
        secure
    }.map { extension ->
        val langs = extension.sources.map { it.language }.toSet()
        Extension.Available(
            name = extension.name,
            pkgName = extension.packageName,
            versionName = extension.versionName,
            versionCode = extension.versionCode,
            libVersion = ExtensionLoader.parseLibVersion(extension.extensionLib) ?: 0.0,
            lang = if (langs.size == 1) langs.first() else "all",
            contentRating = extension.contentWarning.toContentRating(),
            apkUrl = extension.resources.apkUrl,
            iconUrl = extension.resources.iconUrl,
            sources = extension.sources.map { source ->
                Extension.AvailableSource(
                    name = source.name,
                    id = source.id,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            },
            repoUrl = repo.baseUrl,
        )
    }
}

/**
 * The `repo.json` served next to a legacy `index.min.json`. [indexV2] is how a legacy repo points
 * at its replacement [NetworkExtensionStore] index.
 */
@Serializable
data class NetworkLegacyExtensionRepo(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta,
) {

    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String,
        val signingKeyFingerprint: String,
    )

    fun toExtensionRepo(baseUrl: String) = ExtensionRepo(
        baseUrl = baseUrl,
        name = meta.name,
        shortName = meta.shortName,
        website = meta.website,
        signingKeyFingerprint = meta.signingKeyFingerprint,
        isLegacy = true,
        extensionListUrl = null,
    )
}

/** An entry of a legacy `index.min.json` listing. */
@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int = 0,
    /** Explicit lib version, published by repos that build against extensions-lib 1.6 or newer. */
    val lib: Double? = null,
    /** Tri-state rating using the manifest numbering (0 = safe, 1 = mixed, 2 = NSFW). */
    val contentWarning: Int? = null,
    val sources: List<Extension.AvailableSource>? = null,
) {
    fun toAvailableExtension(repo: ExtensionRepo): Extension.Available {
        val baseUrl = repo.baseUrl
        return Extension.Available(
            name = name.substringAfter("Tachiyomi: "),
            pkgName = pkg,
            versionName = version,
            versionCode = code,
            libVersion = lib ?: ExtensionLoader.parseLibVersion(version) ?: 0.0,
            lang = lang,
            contentRating = contentWarning
                ?.let(ContentRating::fromManifestContentWarning)
                ?: ContentRating.fromNsfwFlag(nsfw == 1),
            apkUrl = "$baseUrl/apk/$apk",
            iconUrl = "$baseUrl/icon/$pkg.png",
            sources = sources.orEmpty(),
            repoUrl = baseUrl,
        )
    }
}
