package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MBSearchResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MBSeries
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MBSeriesResult
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import okhttp3.OkHttpClient

/**
 * Mangabaka aggregates series metadata across the other trackers. It serves no accounts and holds
 * no reading lists, so this reads only; progress lives in Yokai's own database.
 */
class MangabakaApi(private val client: OkHttpClient) {

    suspend fun search(query: String): List<TrackSearch> = withIOContext {
        client.newCall(GET("$API_URL/series/search?q=$query"))
            .awaitSuccess()
            .parseAs<MBSearchResult>()
            .data
            .filter { it.title.isNotBlank() }
            .map { it.toTrackSearch() }
    }

    suspend fun getSeries(id: Long): TrackSearch = withIOContext {
        client.newCall(GET("$API_URL/series/$id"))
            .awaitSuccess()
            .parseAs<MBSeriesResult>()
            .data
            .toTrackSearch()
    }

    private fun MBSeries.toTrackSearch() = TrackSearch.create(TrackManager.MANGABAKA).apply {
        media_id = this@toTrackSearch.id
        title = this@toTrackSearch.title
        cover_url = cover?.raw?.url.orEmpty()
        summary = description.orEmpty()
        // Mangabaka rates out of 100 across the sites it aggregates; Yokai shows out of 10
        score = rating?.div(10)?.toFloat() ?: 0f
        total_chapters = this@toTrackSearch.total_chapters?.toLongOrNull() ?: 0L
        tracking_url = seriesUrl(this@toTrackSearch.id)
        publishing_status = this@toTrackSearch.status.orEmpty()
        publishing_type = type.orEmpty()
        start_date = year?.toString().orEmpty()
    }

    companion object {
        private const val API_URL = "https://api.mangabaka.org/v1"

        fun seriesUrl(id: Long) = "https://mangabaka.org/series/$id"
    }
}
