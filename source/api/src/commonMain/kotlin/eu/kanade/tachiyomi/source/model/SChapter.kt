package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.util.EMPTY
import java.io.Serializable
import kotlinx.serialization.json.JsonObject

interface SChapter : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    /**
     * Extra metadata associated with the chapter.
     *
     * The JSON object is not visible to users and intended for internal or source-specific
     * purposes. Apps may define their own namespaced keys (e.g., `"yokai.*"`) for sources to
     * populate.
     *
     * This allows apps to attach and ask for custom information without affecting the visible
     * chapter data.
     *
     * @since extensions-lib 1.6
     */
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        memo = other.safeMemo()
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}

/**
 * Reads [SChapter.memo] from extension binaries compiled before the property existed, where
 * touching it raises a [LinkageError] rather than returning a value.
 */
fun SChapter.safeMemo(): JsonObject = try {
    memo
} catch (_: LinkageError) {
    JsonObject.EMPTY
}
