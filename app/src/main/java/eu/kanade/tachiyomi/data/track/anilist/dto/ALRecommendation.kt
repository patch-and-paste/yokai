package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALRecommendationResult(
    val data: ALRecommendationPage,
)

@Serializable
data class ALRecommendationPage(
    @SerialName("Page")
    val page: ALRecommendationMedia,
)

@Serializable
data class ALRecommendationMedia(
    val media: List<ALRecommendationSource>,
)

/** The searched title, carrying the entries AniList readers pointed at from it. */
@Serializable
data class ALRecommendationSource(
    val recommendations: ALRecommendationConnection,
)

@Serializable
data class ALRecommendationConnection(
    val nodes: List<ALRecommendationNode>,
)

/**
 * [mediaRecommendation] is null when the recommendation points at an anime, which the manga
 * query filters out on the server but the connection still lists.
 */
@Serializable
data class ALRecommendationNode(
    val rating: Int? = null,
    val mediaRecommendation: ALSearchItem? = null,
)
