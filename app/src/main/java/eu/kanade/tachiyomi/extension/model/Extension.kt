package eu.kanade.tachiyomi.extension.model

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable

sealed class Extension {

    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val libVersion: Double
    abstract val lang: String?
    abstract val contentRating: ContentRating

    /**
     * The sites this extension points at, one per distinct URL. Empty for an untrusted extension,
     * whose classes are never loaded, and for repos that don't publish source URLs.
     */
    open val browsableSources: List<BrowsableSource> get() = emptyList()

    /** A site belonging to one of an extension's sources, installed or not. */
    data class BrowsableSource(
        val id: Long,
        val name: String,
        val lang: String,
        val url: String,
    )

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentRating: ContentRating,
        val pkgFactory: String?,
        val sources: List<Source>,
        val icon: Drawable?,
        val hasUpdate: Boolean = false,
        /** No added repo offers this package anymore. */
        val isObsolete: Boolean = false,
        /** The repo it was installed from dropped it, but another added repo still offers it. */
        val isMoved: Boolean = false,
        val isShared: Boolean,
        /** The repo it was installed from, falling back to whichever repo currently offers it. */
        val repoUrl: String? = null,
        /** SHA256 of the certificate the installed APK is signed with, matched against repo keys. */
        val signatureHash: String? = null,
    ) : Extension() {

        override val browsableSources: List<BrowsableSource>
            get() = sources.filterIsInstance<HttpSource>()
                .mapNotNull { source ->
                    // Extension code, read while the extension list scrolls; one bad source
                    // shouldn't take down the screen you'd uninstall it from
                    val url = runCatching { source.getHomeUrl() }.getOrNull()
                    url?.takeIf { it.isNotBlank() }
                        ?.let { BrowsableSource(source.id, source.name, source.lang, it) }
                }
                .distinctBy { it.url }
    }

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentRating: ContentRating,
        /** Absolute URL of the APK; legacy repos have it composed from the repo base URL. */
        val apkUrl: String,
        val iconUrl: String,
        val sources: List<AvailableSource>,
        val repoUrl: String? = null,
    ) : Extension() {

        override val browsableSources: List<BrowsableSource>
            get() = sources.filter { it.baseUrl.isNotBlank() }
                .distinctBy { it.baseUrl }
                .map { BrowsableSource(it.id, it.name, it.lang, it.baseUrl) }
    }

    @Serializable
    data class AvailableSource(
        val name: String,
        val id: Long,
        val lang: String,
        val baseUrl: String,
    )

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        val signatureHash: String,
        override val lang: String? = null,
        override val contentRating: ContentRating = ContentRating.SAFE,
    ) : Extension()
}
