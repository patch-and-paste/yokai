package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.Serializable

@Serializable
data class MBSearchResult(
    val data: List<MBSeries> = emptyList(),
)

@Serializable
data class MBSeriesResult(
    val data: MBSeries,
)

/**
 * Only the fields Yokai shows. Mangabaka returns a great deal more per series, including the ids
 * it holds on other trackers, which is left out until something here needs it.
 */
@Serializable
data class MBSeries(
    val id: Long,
    val title: String = "",
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
    val year: Int? = null,
    /** Percentage across the sites Mangabaka aggregates, so 80.6 rather than 8.06. */
    val rating: Double? = null,
    val cover: MBCover? = null,
    /** Sent as text because the count is unknown for many ongoing series. */
    val total_chapters: String? = null,
)

@Serializable
data class MBCover(
    val raw: MBCoverImage? = null,
)

@Serializable
data class MBCoverImage(
    val url: String = "",
)
