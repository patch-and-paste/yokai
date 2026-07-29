package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.manga.MangaUtil
import kotlin.math.max
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.data.DatabaseHandler
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.InsertChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.history.interactor.GetHistory
import yokai.domain.history.interactor.UpsertHistory
import yokai.domain.library.custom.model.CustomMangaInfo
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack

class MangaBackupRestorer(
    private val customMangaManager: CustomMangaManager = Injekt.get(),
    private val handler: DatabaseHandler = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val insertChapter: InsertChapter = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val insertManga: InsertManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val getTrack: GetTrack = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
) {
    suspend fun restoreManga(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        onComplete: (Manga) -> Unit,
        onError: (Manga, Throwable) -> Unit,
    ) {
        val manga = backupManga.getMangaImpl()
        val chapters = backupManga.getChaptersImpl()
        val categories = backupManga.categories
        val history =
            backupManga.brokenHistory.map { BackupHistory(it.url, it.lastRead, it.readDuration) } + backupManga.history
        val tracks = backupManga.getTrackingImpl()
        val customManga = backupManga.getCustomMangaInfo()
        val filteredScanlators = backupManga.excludedScanlators

        try {
            // Keep every change for one manga in one transaction to avoid partial restores and
            // repeated query notifications.
            handler.await(inTransaction = true) {
                val dbManga = getManga.awaitByUrlAndSource(manga.url, manga.source)
                if (dbManga == null) {
                    // Manga not in database
                    restoreNewManga(manga, chapters, categories, history, tracks, backupCategories, filteredScanlators, customManga)
                } else {
                    // Manga in database
                    // Copy information from manga already in database
                    manga.id = dbManga.id
                    manga.filtered_scanlators = dbManga.filtered_scanlators
                    manga.copyFrom(dbManga)
                    updateManga.await(manga.toMangaUpdate())
                    // Fetch rest of manga information
                    restoreExistingManga(manga, chapters, categories, history, tracks, backupCategories, filteredScanlators, customManga)
                }
            }
        } catch (e: Exception) {
            onError(manga, e)
        }

        onComplete(manga)
    }

    /**
     * Fetches manga information
     *
     * @param manga manga that needs updating
     * @param chapters chapters of manga that needs updating
     * @param categories categories that need updating
     */
    private suspend fun restoreNewManga(
        manga: Manga,
        chapters: List<Chapter>,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        filteredScanlators: List<String>,
        customManga: CustomMangaInfo?,
    ) {
        val fetchedManga = manga.also {
            it.initialized = it.description != null
            it.id = insertManga.await(it)
        }
        fetchedManga.id ?: return

        val chapterIdsByUrl = restoreChapters(fetchedManga, chapters)
        restoreExtras(fetchedManga, categories, history, tracks, backupCategories, filteredScanlators, customManga, chapterIdsByUrl)
    }

    private suspend fun restoreExistingManga(
        backupManga: Manga,
        chapters: List<Chapter>,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        filteredScanlators: List<String>,
        customManga: CustomMangaInfo?,
    ) {
        val chapterIdsByUrl = restoreChapters(backupManga, chapters)
        restoreExtras(backupManga, categories, history, tracks, backupCategories, filteredScanlators, customManga, chapterIdsByUrl)
    }

    /** Restores the manga's chapters and returns a map from chapter URL to ID for history matching. */
    private suspend fun restoreChapters(manga: Manga, chapters: List<Chapter>): Map<String, Long> {
        val dbChapters = getChapter.awaitAll(manga)

        chapters.forEach { chapter ->
            val dbChapter = dbChapters.find { it.url == chapter.url }
            if (dbChapter != null) {
                chapter.id = dbChapter.id
                chapter.copyFrom(dbChapter as SChapter)
                if (dbChapter.read && !chapter.read) {
                    chapter.read = dbChapter.read
                    chapter.last_page_read = dbChapter.last_page_read
                } else if (chapter.last_page_read == 0 && dbChapter.last_page_read != 0) {
                    chapter.last_page_read = dbChapter.last_page_read
                }
                if (!chapter.bookmark && dbChapter.bookmark) {
                    chapter.bookmark = dbChapter.bookmark
                }
            }

            chapter.manga_id = manga.id
        }

        val (existingChapters, newChapters) = chapters.partition { it.id != null }
        if (existingChapters.isNotEmpty()) updateChapter.awaitAll(existingChapters.map(Chapter::toProgressUpdate))
        val insertedChapters = if (newChapters.isNotEmpty()) insertChapter.awaitBulk(newChapters) else emptyList()

        return buildMap(chapters.size) {
            existingChapters.forEach { chapter -> chapter.id?.let { put(chapter.url, it) } }
            insertedChapters.forEach { chapter -> chapter.id?.let { put(chapter.url, it) } }
        }
    }

    private suspend fun restoreExtras(
        manga: Manga,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        filteredScanlators: List<String>,
        customManga: CustomMangaInfo?,
        chapterIdsByUrl: Map<String, Long>,
    ) {
        restoreCategories(manga, categories, backupCategories)
        restoreHistoryForManga(manga.id!!, history, chapterIdsByUrl)
        restoreTrackForManga(manga, tracks)
        restoreFilteredScanlatorsForManga(manga, filteredScanlators)
        customManga?.let {
            it.mangaId = manga.id!!
            // Called directly rather than through launchNow, which would resume on the main
            // dispatcher and escape the transaction this runs in
            customMangaManager.saveMangaInfo(it)
        }
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(manga: Manga, categories: List<Int>, backupCategories: List<BackupCategory>) {
        val dbCategories = getCategories.await()
        val mangaCategoriesToUpdate = ArrayList<MangaCategory>(categories.size)
        categories.forEach { backupCategoryOrder ->
            backupCategories.firstOrNull {
                it.order == backupCategoryOrder
            }?.let { backupCategory ->
                dbCategories.firstOrNull { dbCategory ->
                    dbCategory.name == backupCategory.name
                }?.let { dbCategory ->
                    mangaCategoriesToUpdate += MangaCategory.create(manga, dbCategory)
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            setMangaCategories.awaitAll(listOf(manga.id!!), mangaCategoriesToUpdate)
        }
    }

    /**
     * Restore history from Json
     *
     * Resolves restored chapter IDs from [chapterIdsByUrl] and scopes existing history to [mangaId].
     *
     * @param mangaId id of the manga the history belongs to
     * @param history list containing history to be restored
     * @param chapterIdsByUrl mapping from chapter URL to ID for the chapters restored for this manga
     */
    internal suspend fun restoreHistoryForManga(
        mangaId: Long,
        history: List<BackupHistory>,
        chapterIdsByUrl: Map<String, Long>,
    ) {
        if (history.isEmpty()) return

        val dbHistoryByChapterId = getHistory.awaitAllByMangaId(mangaId).associateBy { it.chapter_id }

        // List containing history to be updated
        val historyToBeUpdated = ArrayList<History>(history.size)
        for ((url, lastRead, readDuration) in history) {
            // Fall back to a lookup for entries pointing outside this manga's restored chapters,
            // which is an indexed query now that chapters.url is indexed
            val chapterId = chapterIdsByUrl[url] ?: getChapter.awaitByUrl(url, false)?.id ?: continue

            val dbHistory = dbHistoryByChapterId[chapterId]
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory.apply {
                    last_read = max(lastRead, dbHistory.last_read)
                    time_read = max(readDuration, dbHistory.time_read)
                }
                historyToBeUpdated.add(dbHistory)
            } else {
                // If not in database create
                historyToBeUpdated.add(
                    History.create().apply {
                        chapter_id = chapterId
                        last_read = lastRead
                        time_read = readDuration
                    },
                )
            }
        }
        upsertHistory.awaitBulk(historyToBeUpdated)
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    private suspend fun restoreTrackForManga(manga: Manga, tracks: List<Track>) {
        // Fix foreign keys with the current manga id
        tracks.map { it.manga_id = manga.id!! }

        // Get tracks from database
        val dbTracks = getTrack.awaitAllByMangaId(manga.id!!)
        val trackToUpdate = mutableListOf<Track>()

        tracks.forEach { track ->
            var isInDatabase = false
            for (dbTrack in dbTracks) {
                if (track.sync_id == dbTrack.sync_id) {
                    // The sync is already in the db, only update its fields
                    if (track.media_id != dbTrack.media_id) {
                        dbTrack.media_id = track.media_id
                    }
                    if (track.library_id != dbTrack.library_id) {
                        dbTrack.library_id = track.library_id
                    }
                    dbTrack.last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read)
                    isInDatabase = true
                    trackToUpdate.add(dbTrack)
                    break
                }
            }
            if (!isInDatabase) {
                // Insert new sync. Let the db assign the id
                track.id = null
                trackToUpdate.add(track)
            }
        }
        // Update database
        if (trackToUpdate.isNotEmpty()) {
            insertTrack.awaitBulk(trackToUpdate)
        }
    }

    private suspend fun restoreFilteredScanlatorsForManga(manga: Manga, filteredScanlators: List<String>) {
        val existing = ChapterUtil.getScanlators(manga.filtered_scanlators)
        // Skip the write entirely when there is no filter on either side, which is the common case
        if (existing.isEmpty() && filteredScanlators.isEmpty()) return

        MangaUtil.setScanlatorFilter(updateManga, manga, (existing + filteredScanlators).toSet())
    }
}
