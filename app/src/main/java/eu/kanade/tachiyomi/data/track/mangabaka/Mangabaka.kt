package eu.kanade.tachiyomi.data.track.mangabaka

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.updateNewTrackInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Mangabaka publishes metadata and cross-tracker ids, with no accounts and no reading lists. It is
 * bound like any other tracker, but status and progress stay in Yokai's database rather than being
 * pushed anywhere, and there is nothing to sign in to.
 */
class Mangabaka(private val context: Context, id: Long) : TrackService(id) {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5
    }

    private val api by lazy { MangabakaApi(client) }

    override fun nameRes() = MR.strings.mangabaka

    override fun getLogo() = R.drawable.ic_tracker_mangabaka

    // Sampled from the logo rather than guessed: the mid teal reads on a chart, the dark one
    // sits behind the icon the way the other trackers' backgrounds do
    override fun getTrackerColor() = Color.rgb(79, 99, 105)

    override fun getLogoColor() = Color.rgb(32, 44, 50)

    override fun getStatusList() = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

    override fun isCompletedStatus(index: Int) = getStatusList()[index] == COMPLETED

    override fun completedStatus() = COMPLETED
    override fun readingStatus() = READING
    override fun planningStatus() = PLAN_TO_READ

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(MR.strings.reading)
            COMPLETED -> getString(MR.strings.completed)
            ON_HOLD -> getString(MR.strings.on_hold)
            DROPPED -> getString(MR.strings.dropped)
            PLAN_TO_READ -> getString(MR.strings.plan_to_read)
            else -> ""
        }
    }

    override fun getGlobalStatus(status: Int) = getStatus(status)

    private val _scoreList = (0..10).map { it.toString() }.toImmutableList()

    override fun getScoreList(): ImmutableList<String> = _scoreList

    override fun indexToScore(index: Int): Float = index.toFloat()

    override fun displayScore(track: Track): String = track.score.toInt().toString()

    override suspend fun add(track: Track): Track {
        track.status = READING
        updateNewTrackInfo(track)
        return track
    }

    /** Kept local: the API has no list to write to. */
    override suspend fun update(track: Track, setToRead: Boolean): Track {
        updateTrackStatus(track, setToRead, setToComplete = true, mustReadToComplete = true)
        return track
    }

    override suspend fun bind(track: Track): Track = add(track)

    override suspend fun search(query: String): List<TrackSearch> = api.search(query)

    /** Only the series metadata can change upstream, so progress is carried over untouched. */
    override suspend fun refresh(track: Track): Track {
        val remote = api.getSeries(track.media_id)
        return track.apply {
            total_chapters = remote.total_chapters
            title = remote.title
        }
    }

    override val isLogged: Boolean
        get() = true

    override suspend fun login(username: String, password: String) = true

    override fun logout() = Unit
}
