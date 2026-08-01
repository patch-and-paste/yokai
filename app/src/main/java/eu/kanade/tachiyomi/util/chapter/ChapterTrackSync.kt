package eu.kanade.tachiyomi.util.chapter

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.DelayedTrackingUpdateJob
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.w
import eu.kanade.tachiyomi.util.system.withIOContext
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack

/**
 * Helper method for syncing a remote track with the local chapters, and back
 *
 * @param db the database.
 * @param chapters a list of chapters from the source.
 * @param remoteTrack the remote Track object.
 * @param service the tracker service.
 */
/**
 * Marks local chapters read up to whatever [remoteTrack] reports, for the case where the tracker
 * was moved on elsewhere. Only pulls: pushing local progress back up is already handled when a
 * chapter is read, and doing both here would have the two fighting over the same number.
 *
 * @return how many chapters changed.
 */
suspend fun syncReadProgressFromTracker(
    chapters: List<Chapter>,
    remoteTrack: Track,
    updateChapter: UpdateChapter = Injekt.get(),
): Int = withIOContext {
    val remoteRead = remoteTrack.last_chapter_read
    if (remoteRead <= 0f) return@withIOContext 0

    val behind = chapters.filter { !it.read && it.isRecognizedNumber && it.chapter_number <= remoteRead }
    if (behind.isEmpty()) return@withIOContext 0

    behind.forEach { it.read = true }
    updateChapter.awaitAll(behind.map(Chapter::toProgressUpdate))
    behind.size
}

suspend fun syncChaptersWithTrackServiceTwoWay(
    chapters: List<Chapter>,
    remoteTrack: Track,
    service: TrackService,
    updateChapter: UpdateChapter = Injekt.get(),
    insertTrack: InsertTrack = Injekt.get()
) = withIOContext {
    if (service !is EnhancedTrackService) {
        return@withIOContext
    }

    val sortedChapters = chapters.sortedBy { it.chapter_number }
    sortedChapters
        .filter { chapter -> chapter.chapter_number <= remoteTrack.last_chapter_read && !chapter.read }
        .forEach { it.read = true }
    updateChapter.awaitAll(sortedChapters.map(Chapter::toProgressUpdate))

    // only take into account continuous reading
    val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapter_number ?: 0F

    // update remote
    remoteTrack.last_chapter_read = localLastRead

    try {
        service.update(remoteTrack)
        insertTrack.await(remoteTrack)
    } catch (e: Throwable) {
        Logger.w(e)
    }
}

private var trackingJobs = HashMap<Long, Pair<Job?, Float?>>()

/**
 * Starts the service that updates the last chapter read in sync services. This operation
 * will run in a background thread and errors are ignored.
 */
fun updateTrackChapterMarkedAsRead(
    preferences: PreferencesHelper,
    newLastChapter: Chapter?,
    mangaId: Long?,
    delay: Long = 3000,
    fetchTracks: (suspend () -> Unit)? = null,
) {
    if (!preferences.trackMarkedAsRead().get()) return
    mangaId ?: return

    val newChapterRead = newLastChapter?.chapter_number ?: 0f

    // To avoid unnecessary calls if multiple marked as read for same manga
    if ((trackingJobs[mangaId]?.second ?: 0f) < newChapterRead) {
        trackingJobs[mangaId]?.first?.cancel()

        // We want these to execute even if the presenter is destroyed
        trackingJobs[mangaId] = launchIO {
            delay(delay)
            updateTrackChapterRead(preferences, mangaId, newChapterRead)
            fetchTracks?.invoke()
            trackingJobs.remove(mangaId)
        } to newChapterRead
    }
}

suspend fun updateTrackChapterRead(
    preferences: PreferencesHelper,
    mangaId: Long?,
    newChapterRead: Float,
    retryWhenOnline: Boolean = false,
    getTrack: GetTrack = Injekt.get(),
    insertTrack: InsertTrack = Injekt.get(),
): List<Pair<TrackService, String?>> {
    val trackManager = Injekt.get<TrackManager>()
    val trackList = getTrack.awaitAllByMangaId(mangaId)
    val failures = mutableListOf<Pair<TrackService, String?>>()
    trackList.map { track ->
        val service = trackManager.getService(track.sync_id)
        if (service != null && service.isLogged && newChapterRead > track.last_chapter_read) {
            if (retryWhenOnline && !preferences.context.isOnline()) {
                delayTrackingUpdate(preferences, mangaId, newChapterRead, track)
            } else if (preferences.context.isOnline()) {
                try {
                    track.last_chapter_read = newChapterRead
                    stampReadingDates(track)
                    service.update(track, true)
                    insertTrack.await(track)
                } catch (e: Exception) {
                    Logger.e(e) { "Unable to update tracker [tracker id ${track.sync_id}]" }
                    failures.add(service to e.localizedMessage)
                    if (retryWhenOnline) {
                        delayTrackingUpdate(preferences, mangaId, newChapterRead, track)
                    }
                }
            }
        }
    }
    return failures
}

/**
 * Fills in the reading dates the trackers already accept but nothing was setting. Existing values
 * are left alone: a date the reader entered, or one the service sent back, outranks a guess made
 * from local progress.
 */
private fun stampReadingDates(track: Track) {
    val now = Date().time
    if (track.started_reading_date <= 0L) {
        track.started_reading_date = now
    }
    val isFinished = track.total_chapters > 0 && track.last_chapter_read >= track.total_chapters
    if (isFinished && track.finished_reading_date <= 0L) {
        track.finished_reading_date = now
    }
}

private fun delayTrackingUpdate(
    preferences: PreferencesHelper,
    mangaId: Long?,
    newChapterRead: Float,
    track: Track,
) {
    val trackings = preferences.trackingsToAddOnline().get().toMutableSet()
    val currentTracking = trackings.find { it.startsWith("$mangaId:${track.sync_id}:") }
    trackings.remove(currentTracking)
    trackings.add("$mangaId:${track.sync_id}:$newChapterRead")
    preferences.trackingsToAddOnline().set(trackings)
    DelayedTrackingUpdateJob.setupTask(preferences.context)
}
