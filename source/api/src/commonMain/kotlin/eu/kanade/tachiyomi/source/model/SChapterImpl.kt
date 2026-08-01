package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.util.EMPTY
import kotlin.jvm.Transient
import kotlinx.serialization.json.JsonObject

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    @Transient
    override var memo: JsonObject = JsonObject.EMPTY
}
