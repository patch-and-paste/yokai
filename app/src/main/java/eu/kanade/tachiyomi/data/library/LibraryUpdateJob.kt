package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.prepareCoverUpdate
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.awaitMangaUpdate
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.cover
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack
import yokai.i18n.MR
import yokai.util.lang.getString

class LibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getCategories: GetCategories = Injekt.get()
    private val getChapter: GetChapter = Injekt.get()

    private val coverCache: CoverCache = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val preferences: PreferencesHelper = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val trackManager: TrackManager = Injekt.get()
    private val mangaShortcutManager: MangaShortcutManager = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val getTrack: GetTrack = Injekt.get()
    private val insertTrack: InsertTrack by injectLazy()

    // Everything below is touched by several source coroutines at once, so none of it can be a
    // plain mutable collection. Concurrent writes to a LinkedHashMap can throw or corrupt it, and
    // one of those writes happens inside a catch block where a throw would escape every guard.
    private val extraDeferredJobs = CopyOnWriteArrayList<Deferred<Any>>()

    private val extraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val emitScope = MainScope()

    private val mangaToUpdate = CopyOnWriteArrayList<LibraryManga>()

    private val mangaToUpdateMap = ConcurrentHashMap<Long, List<LibraryManga>>()

    private val categoryIds = ConcurrentHashMap.newKeySet<Int>()

    // List containing new updates
    private val newUpdates = ConcurrentHashMap<LibraryManga, Array<Chapter>>()

    // List containing failed updates. Values are never null: a null reason would collapse every
    // unrelated failure under one "null" heading in the error file, and ConcurrentHashMap rejects
    // it anyway.
    private val failedUpdates = ConcurrentHashMap<Manga, String>()

    // List containing skipped updates
    private val skippedUpdates = ConcurrentHashMap<Manga, String>()

    val count = AtomicInteger(0)

    /** How far each source has got through its list, so an abandoned source can report the rest. */
    private val sourceProgress = ConcurrentHashMap<Long, Int>()

    /** One worker per source at a time, so a source never sees more traffic than it used to. */
    private val sourceLocks = ConcurrentHashMap<Long, Mutex>()

    /** When any entry anywhere in the run last finished. Drives the stall watchdog. */
    private val lastProgressAt = AtomicLong(0L)

    // Boolean to determine if user wants to automatically download new chapters.
    private val downloadNew: Boolean = preferences.downloadNewChapters().get()

    // Boolean to determine if DownloadManager has downloads
    private val hasDownloads = AtomicBoolean(false)

    /** Global cap on in-flight network requests, held only around the fetch itself. */
    private val requestSemaphore = Semaphore(5)

    // For updates delete removed chapters if not preference is set as well
    private val deleteRemoved by lazy { preferences.deleteRemovedChapters().get() != 1 }

    private val notifier = LibraryUpdateNotifier(context.localeContext)

    override suspend fun doWork(): Result {
        if (tags.contains(WORK_NAME_AUTO)) {
            val preferences = Injekt.get<PreferencesHelper>()
            val restrictions = preferences.libraryUpdateDeviceRestriction().get()
            if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                return Result.failure()
            }

            // Find a running manual worker. If exists, try again later
            if (instance != null) {
                return Result.retry()
            }
        }

        if (!tryToSetForeground()) {
            // Without a foreground service the system reclaims the job at the ~10 minute execution
            // cap. From the outside that is indistinguishable from a source crashing, so say so.
            Logger.w { "Library update is not a foreground service, it may be killed at the execution cap" }
        }

        instance = WeakReference(this)

        val target = inputData.getString(KEY_TARGET)?.let { Target.valueOf(it) } ?: Target.CHAPTERS

        // If this is a chapter update, set the last update time to now
        if (target == Target.CHAPTERS) {
            preferences.libraryUpdateLastTimestamp().set(Date().time)
        }

        val savedMangasList = inputData.getLongArray(KEY_MANGAS)?.asList()?.plus(extraManga)
        extraManga = emptyList()

        val mangaList = (
            if (savedMangasList != null) {
                val mangas =
                    getLibraryManga.await()
                        .filter { it.manga.id in savedMangasList }
                        .distinctBy { it.manga.id }
                val categoryId = inputData.getInt(KEY_CATEGORY, -1)
                if (categoryId > -1) categoryIds.add(categoryId)
                mangas
            } else {
                getMangaToUpdate()
            }
            ).sortedBy { it.manga.title }

        return withIOContext {
            try {
                launchTarget(target, mangaList)
                Result.success()
            } catch (e: Throwable) {
                // Catching Throwable, not Exception: extensions are separately compiled APKs, so a
                // stale one throws NoClassDefFoundError or AbstractMethodError rather than an
                // Exception, and those used to bypass every catch here and kill the run in silence.
                if (e is CancellationException) {
                    // Assume success although cancelled
                    Result.success()
                } else {
                    Logger.e(e) { "Failed to update library" }
                    Result.failure()
                }
            } finally {
                // The report has to survive both cancellation and an unexpected throw, otherwise
                // the run vanishes with no result notification and no error file — which is what
                // made these failures impossible to diagnose on a release build.
                withContext(NonCancellable) {
                    try {
                        if (target == Target.CHAPTERS) finishUpdates(wasStopped = isStopped)
                    } catch (e: Throwable) {
                        // Reporting must never take the cleanup below down with it.
                        Logger.e(e) { "Failed to report library update results" }
                    }
                    instance = null
                    sendUpdate(null)
                    notifier.cancelProgressNotification()
                    extraScope.cancel()
                }
            }
        }
    }

    private suspend fun launchTarget(target: Target, mangaToAdd: List<LibraryManga>) {
        if (target == Target.CHAPTERS) {
            sendUpdate(STARTING_UPDATE_SOURCE)
        }
        when (target) {
            Target.CHAPTERS -> updateChaptersJob(filterMangaToUpdate(mangaToAdd))
            Target.DETAILS -> updateDetails(mangaToAdd)
            else -> updateTrackings(mangaToAdd)
        }
    }

    private suspend fun sendUpdate(mangaId: Long?) {
        if (isStopped) {
            updateMutableFlow.tryEmit(mangaId)
        } else {
            emitScope.launch { updateMutableFlow.emit(mangaId) }
        }
    }

    private suspend fun updateChaptersJob(mangaToAdd: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.
        mangaToUpdate.addAll(mangaToAdd)
        mangaToUpdateMap.putAll(mangaToAdd.groupBy { it.manga.source })
        checkIfMassiveUpdate()
        noteProgress()

        // Source jobs run detached on purpose. A request stuck in the legacy Rx path blocks its
        // thread and ignores cancellation, so under structured concurrency the whole update would
        // hang waiting for it. Detached, the watchdog below can stop waiting on it and still
        // report everything that did finish.
        val jobs = mangaToUpdateMap.keys.associateWith { source ->
            extraScope.async { runSourceJob(source) }
        }
        awaitSourcesOrStall(jobs)
    }

    /**
     * Runs one source's queue to completion, recording anything it doesn't reach. A source that
     * threw part way through used to drop its remaining entries with nothing written anywhere, so
     * a partial update was indistinguishable from a finished one.
     */
    private suspend fun runSourceJob(source: Long) {
        try {
            if (updateMangaInSource(source)) hasDownloads.set(true)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            failRemainingInSource(source, e)
            Logger.e(e) { "Unable to update source ${sourceManager.getOrStub(source)}" }
        }
    }

    /**
     * Waits for every source, giving up once the run as a whole has stopped making progress. The
     * stuck request itself can't be killed, but abandoning the wait turns a silent hang into a
     * named entry in the error file.
     */
    private suspend fun awaitSourcesOrStall(jobs: Map<Long, Deferred<Unit>>) {
        while (true) {
            val pending = jobs.filterValues { !it.isCompleted }
            if (pending.isEmpty()) return

            val remaining = STALL_TIMEOUT_MS - (System.currentTimeMillis() - lastProgressAt.get())
            if (remaining <= 0) {
                pending.forEach { (source, job) ->
                    failRemainingInSource(source, StalledSourceException(STALL_TIMEOUT_MS / 60_000))
                    Logger.e { "Abandoning stalled source ${sourceManager.getOrStub(source)}" }
                    // Best effort: unsticks a source that suspends properly, no-op for one blocked
                    // on a synchronous call.
                    job.cancel()
                }
                return
            }
            withTimeoutOrNull(remaining) { pending.values.awaitAll() }
        }
    }

    /**
     * Marks every entry this source never got to as failed, so it reaches the error notification
     * and `tachiyomi_update_errors.txt` instead of disappearing.
     */
    private fun failRemainingInSource(source: Long, e: Throwable) {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        val reached = sourceProgress[source] ?: 0
        mangaToUpdateMap[source].orEmpty().drop(reached).forEach { failedUpdates[it.manga] = reason }
    }

    private fun noteProgress() = lastProgressAt.set(System.currentTimeMillis())

    /**
     * Method that updates the details of the given list of manga. It's called in a background
     * thread, so it's safe to do heavy operations or network calls here.
     *
     * @param mangaToUpdate the list to update
     */
    private suspend fun updateDetails(mangaToUpdate: List<LibraryManga>) = supervisorScope {
        // Initialize the variables holding the progress of the updates.
        val count = AtomicInteger(0)
        val asyncList = mangaToUpdate.groupBy { it.manga.source }.values.map { list ->
            async {
                try {
                    list.forEach { manga ->
                        ensureActive()
                        // finishUpdates calls this from a NonCancellable block, where ensureActive
                        // can never trip, so stopping the update has to be checked directly.
                        if (isStopped) return@async
                        val source = sourceManager.get(manga.manga.source) as? CatalogueSource ?: return@async
                        notifier.showProgressNotification(
                            manga.manga,
                            count.andIncrement,
                            mangaToUpdate.size,
                        )
                        ensureActive()
                        val networkManga = try {
                            requestSemaphore.withPermit {
                                source.awaitMangaUpdate(manga.manga.copy(), fetchDetails = true).manga
                            }
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Logger.e(e) { "Failed to refresh details for ${manga.manga.title}" }
                            null
                        }
                        if (networkManga != null) {
                            manga.manga.prepareCoverUpdate(coverCache, networkManga, false)
                            val thumbnailUrl = manga.manga.thumbnail_url
                            manga.manga.copyFrom(networkManga)
                            manga.manga.initialized = true
                            val request: ImageRequest =
                                if (thumbnailUrl != manga.manga.thumbnail_url) {
                                    // load new covers in background
                                    ImageRequest.Builder(context).data(manga.manga.cover())
                                        .memoryCachePolicy(CachePolicy.DISABLED).build()
                                } else {
                                    ImageRequest.Builder(context).data(manga.manga.cover())
                                        .memoryCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                                        .build()
                                }
                            context.imageLoader.execute(request)
                            updateManga.await(manga.manga.toMangaUpdate())
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Logger.e(e) { "Failed to refresh details for source ${sourceManager.getOrStub(list.first().manga.source)}" }
                }
            }
        }
        asyncList.awaitAll()
        notifier.cancelProgressNotification()
    }

    /**
     * Method that updates the metadata of the connected tracking services. It's called in a
     * background thread, so it's safe to do heavy operations or network calls here.
     */
    private suspend fun updateTrackings(mangaToUpdate: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.
        var count = 0

        val loggedServices = trackManager.services.filter { it.isLogged }

        mangaToUpdate.forEach { manga ->
            notifier.showProgressNotification(manga.manga, count++, mangaToUpdate.size)

            val tracks = getTrack.awaitAllByMangaId(manga.manga.id!!)

            tracks.forEach { track ->
                val service = trackManager.getService(track.sync_id)
                if (service != null && service in loggedServices) {
                    try {
                        val newTrack = service.refresh(track)
                        insertTrack.await(newTrack)

                        syncChaptersWithTrackServiceTwoWay(getChapter.awaitAll(manga.manga.id!!, false), track, service)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Logger.e(e) { "Failed to refresh tracking for ${manga.manga.title}" }
                    }
                }
            }
        }
        notifier.cancelProgressNotification()
    }

    private suspend fun finishUpdates(wasStopped: Boolean = false) {
        if (!wasStopped && !isStopped) {
            // Bounded, and bounded by what is *left* of the stall budget: a source queued mid-run
            // can wedge like any other, and this runs from a finally block, so an unbounded await
            // here would hang the worker instead of the loop. If the run already stalled there is
            // no budget left and this returns at once.
            val budget = (STALL_TIMEOUT_MS - (System.currentTimeMillis() - lastProgressAt.get()))
                .coerceAtLeast(0)
            withTimeoutOrNull(budget) { extraDeferredJobs.awaitAll() }
        }
        if (newUpdates.isNotEmpty()) {
            notifier.showResultNotification(newUpdates)
            if (!wasStopped && preferences.refreshCoversToo().get() && !isStopped) {
                updateDetails(newUpdates.keys.toList())
                notifier.cancelProgressNotification()
                if (downloadNew && hasDownloads.get()) {
                    DownloadJob.start(context, runExtensionUpdatesAfter)
                    runExtensionUpdatesAfter = false
                }
            } else if (downloadNew && hasDownloads.get()) {
                DownloadJob.start(applicationContext, runExtensionUpdatesAfter)
                runExtensionUpdatesAfter = false
            }
        }
        newUpdates.clear()
        // The report files are written whether or not their channel is on. They are the only
        // durable record of what went wrong, and gating the file on a notification setting left a
        // user who had muted the channel with no way at all to find out.
        if (skippedUpdates.isNotEmpty()) {
            val skippedFile = writeErrorFile(
                skippedUpdates,
                "skipped",
                context.getString(MR.strings.learn_why) + " - " + LibraryUpdateNotifier.HELP_SKIPPED_URL,
            )
            if (skippedFile.exists() &&
                Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_SKIPPED)
            ) {
                notifier.showUpdateSkippedNotification(skippedUpdates.map { it.key.title }, skippedFile.getUriCompat(context))
            }
        }
        if (failedUpdates.isNotEmpty()) {
            Logger.e { "Library update finished with ${failedUpdates.size} failed entries" }
            val errorFile = writeErrorFile(failedUpdates)
            if (errorFile.exists() &&
                Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_ERROR)
            ) {
                notifier.showUpdateErrorNotification(failedUpdates.map { it.key.title }, errorFile.getUriCompat(context))
            }
        }
        mangaShortcutManager.updateShortcuts(context)
        failedUpdates.clear()
        notifier.cancelProgressNotification()
        if (runExtensionUpdatesAfter && !DownloadJob.isRunning(context)) {
            ExtensionUpdateJob.runJobAgain(context, NetworkType.CONNECTED)
            runExtensionUpdatesAfter = false
        }
    }

    private fun checkIfMassiveUpdate() {
        val largestSourceSize = mangaToUpdate
            .groupBy { it.manga.source }
            .filterKeys { sourceManager.get(it) !is UnmeteredSource }
            .maxOfOrNull { it.value.size } ?: 0
        if (largestSourceSize > MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotification()
        }
    }

    private suspend fun updateMangaInSource(source: Long): Boolean {
        val sourceObj = sourceManager.get(source) as? CatalogueSource
        if (sourceObj == null) {
            // Usually an extension that was uninstalled or failed to load. These entries used to be
            // dropped without a word, which looks identical to them being up to date.
            failRemainingInSource(source, SourceUnavailableException())
            mangaToUpdateMap[source] = emptyList()
            return false
        }
        // One worker per source. Entries added mid-run append to the existing queue rather than
        // starting a second job, but that check races with this loop finishing, so hold the lock
        // to keep the guarantee that a source never sees two concurrent requests.
        return sourceLocks.computeIfAbsent(source) { Mutex() }.withLock {
            var hasDownloadsForSource = false
            var index = 0
            // addManga can append to this source's queue while we work through it, so re-read the
            // list each round instead of holding a snapshot, and run off the end rather than
            // indexing a list that was swapped underneath us.
            while (true) {
                val manga = mangaToUpdateMap[source]?.getOrNull(index) ?: break
                if (updateMangaChapters(manga, sourceObj)) {
                    hasDownloadsForSource = true
                }
                index++
                sourceProgress[source] = index
                noteProgress()
            }
            mangaToUpdateMap[source] = emptyList()
            hasDownloadsForSource
        }
    }

    private suspend fun updateMangaChapters(
        manga: LibraryManga,
        source: CatalogueSource,
    ): Boolean = coroutineScope {
        try {
            var hasDownloads = false
            ensureActive()
            val shouldDownload = manga.manga.shouldDownloadNewChapters(preferences)
            val dbChapters = getChapter.awaitAll(manga.manga.id!!, false)
            // The permit is taken before the timeout starts so queueing behind other sources can't
            // be mistaken for a slow source. The timeout itself only bites for sources that suspend
            // properly: extensions on the legacy Rx path block their thread inside call.execute()
            // and never reach a cancellation point, so the stall watchdog is what covers those.
            val fetchedChapters = requestSemaphore.withPermit {
                // Numbered once the fetch actually starts. Every source is queued up front now, so
                // counting at call time would run the progress bar ahead of the real work.
                notifier.showProgressNotification(manga.manga, count.andIncrement, mangaToUpdate.size)
                withTimeout(FETCH_TIMEOUT_MS) {
                    source.awaitMangaUpdate(
                        manga = manga.manga.copy(),
                        chapters = dbChapters,
                        fetchChapters = true,
                    )
                }
            }.chapters

            if (fetchedChapters.isNotEmpty()) {
                val newChapters = syncChaptersWithSource(fetchedChapters, manga.manga, source)
                // A chapter the source re-released under a new url comes back already read when
                // "mark duplicate read chapters as read" is on. Downloading it again and listing
                // it as an update would undo the point of that setting.
                val unreadNewChapters = newChapters.first.filterNot { it.read }
                if (unreadNewChapters.isNotEmpty()) {
                    if (shouldDownload) {
                        downloadChapters(
                            manga.manga,
                            unreadNewChapters.sortedBy { it.chapter_number },
                        )
                        hasDownloads = true
                    }
                    newUpdates[manga] =
                        unreadNewChapters.sortedBy { it.chapter_number }.toTypedArray()
                }
                if (deleteRemoved && newChapters.second.isNotEmpty()) {
                    val removedChapters = newChapters.second.filter {
                        downloadManager.isChapterDownloaded(it, manga.manga) &&
                            newChapters.first.none { newChapter ->
                                newChapter.chapter_number == it.chapter_number && it.scanlator.isNullOrBlank()
                            }
                    }
                    if (removedChapters.isNotEmpty()) {
                        downloadManager.deleteChapters(removedChapters, manga.manga, source)
                    }
                }
                if (newChapters.first.size + newChapters.second.size > 0) {
                    sendUpdate(manga.manga.id)
                }
            }
            return@coroutineScope hasDownloads
        } catch (e: Throwable) {
            // TimeoutCancellationException is a CancellationException, so it has to be picked off
            // before the cancellation check or a timed-out entry reads as "the user stopped the
            // update" and is never reported.
            when {
                e is TimeoutCancellationException -> {
                    failedUpdates[manga.manga] = "Timed out after ${FETCH_TIMEOUT_MS / 1000}s"
                    Logger.e(e) { "Timed out updating: ${manga.manga.title}" }
                }
                e is CancellationException -> throw e
                else -> {
                    failedUpdates[manga.manga] = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
                    Logger.e(e) { "Failed updating: ${manga.manga.title}" }
                }
            }
            return@coroutineScope false
        }
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    private fun filterMangaToUpdate(mangaToAdd: List<LibraryManga>): List<LibraryManga> {
        val restrictions = preferences.libraryUpdateMangaRestriction().get()
        return mangaToAdd.filter { manga ->

            if (tags.contains(WORK_NAME_AUTO) && manga.manga.isLocal()) {
                // This prevents data loss if files are temporarily moved when a background job runs.
                 return@filter false
            }

            if (!tags.contains(WORK_NAME_AUTO) && manga.manga.isLocal()) {
                return@filter true
            }

            when {
                MANGA_NON_COMPLETED in restrictions && manga.manga.status == SManga.COMPLETED -> {
                    skippedUpdates[manga.manga] = context.getString(MR.strings.skipped_reason_completed)
                }
                MANGA_HAS_UNREAD in restrictions && manga.unread != 0 -> {
                    skippedUpdates[manga.manga] = context.getString(MR.strings.skipped_reason_not_caught_up)
                }
                MANGA_NON_READ in restrictions && manga.totalChapters > 0 && !manga.hasRead -> {
                    skippedUpdates[manga.manga] = context.getString(MR.strings.skipped_reason_not_started)
                }
                manga.manga.update_strategy != UpdateStrategy.ALWAYS_UPDATE -> {
                    skippedUpdates[manga.manga] = context.getString(MR.strings.skipped_reason_not_always_update)
                }
                else -> {
                    return@filter true
                }
            }
            return@filter false
        }
    }

    private suspend fun getMangaToUpdate(): List<LibraryManga> {
        val categoryId = inputData.getInt(KEY_CATEGORY, -1)
        return getMangaToUpdate(categoryId)
    }

    /**
     * Returns the list of manga to be updated.
     *
     * @param categoryId the category to update
     * @return a list of manga to update
     */
    private suspend fun getMangaToUpdate(categoryId: Int): List<LibraryManga> {
        val libraryManga = getLibraryManga.await()

        val listToUpdate = if (categoryId != -1) {
            categoryIds.add(categoryId)
            libraryManga.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate =
                preferences.libraryUpdateCategories().get().map(String::toInt)
            if (categoriesToUpdate.isNotEmpty()) {
                categoryIds.addAll(categoriesToUpdate)
                libraryManga.filter { it.category in categoriesToUpdate }.distinctBy { it.manga.id }
            } else {
                categoryIds.addAll(getCategories.await().mapNotNull { it.id } + 0)
                libraryManga.distinctBy { it.manga.id }
            }
        }

        val categoriesToExclude =
            preferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
        // libraryManga holds one row per manga and category, and listToUpdate kept the row for the
        // included category, so excluding has to match on the manga rather than on the row.
        val mangaIdsToExclude = if (categoriesToExclude.isNotEmpty() && categoryId == -1) {
            libraryManga.filter { it.category in categoriesToExclude }.map { it.manga.id }.toSet()
        } else {
            emptySet()
        }

        return listToUpdate.filterNot { it.manga.id in mangaIdsToExclude }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotificationBuilder.build()
        val id = Notifications.ID_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: Map<Manga, String?>, fileName: String = "errors", additionalInfo: String? = null): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("tachiyomi_update_$fileName.txt")
                file.bufferedWriter().use { out ->
                    additionalInfo?.let { out.write("$it\n\n") }
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.toList().groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (e: Throwable) {
            // Callers check exists() on the result, so a failure here degrades to "no file" rather
            // than taking the rest of the report down with it. Still worth a line: this file is
            // often the only evidence of why an update went wrong.
            Logger.e(e) { "Failed to write library update $fileName file" }
        }
        return File("")
    }

    private fun addMangaToQueue(categoryId: Int, manga: List<LibraryManga>) {
        val mangas = filterMangaToUpdate(manga).sortedBy { it.manga.title }
        categoryIds.add(categoryId)
        addManga(mangas)
    }

    private fun addCategory(categoryId: Int) {
        val mangas = filterMangaToUpdate(runBlocking { getMangaToUpdate(categoryId) }).sortedBy { it.manga.title }
        categoryIds.add(categoryId)
        addManga(mangas)
    }

    private fun addManga(mangaToAdd: List<LibraryManga>) {
        val distinctManga = mangaToAdd.filter { it !in mangaToUpdate }
        mangaToUpdate.addAll(distinctManga)
        checkIfMassiveUpdate()
        distinctManga.groupBy { it.manga.source }.forEach {
            // if added queue items is a new source not in the async list or an async list has
            // finished running
            if (mangaToUpdateMap[it.key].isNullOrEmpty()) {
                mangaToUpdateMap[it.key] = it.value
                sourceProgress[it.key] = 0
                extraDeferredJobs.add(extraScope.async { runSourceJob(it.key) })
            } else {
                val list = mangaToUpdateMap[it.key] ?: emptyList()
                mangaToUpdateMap[it.key] = (list + it.value)
            }
        }
    }

    enum class Target {

        CHAPTERS, // Manga chapters

        DETAILS, // Manga metadata

        TRACKING, // Tracking metadata
    }

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://tachiyomi.org/help/guides/troubleshooting"

        private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /**
         * How long the whole run may go without finishing a single entry before the sources still
         * outstanding are written off. Only one source has to be alive to keep this from firing, so
         * this is time with nothing at all happening — generous enough that a heavily rate limited
         * source won't trip it, short enough to beat the execution cap when there is no foreground
         * service.
         */
        private const val STALL_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Upper bound on a single entry's fetch, above OkHttp's own two minute callTimeout so the
         * client's timeout wins wherever it applies.
         */
        private const val FETCH_TIMEOUT_MS = 3 * 60 * 1000L

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "category"
        const val STARTING_UPDATE_SOURCE = -5L

        /**
         * Emitted once when many entries changed at the same time, such as after a backup restore.
         * Collectors should reload whatever they are showing instead of reacting to a single entry,
         * which is why entry-scoped collectors need to watch for this alongside their own id.
         */
        const val BULK_CHANGE = -6L

        /**
         * Key that defines what should be updated.
         */
        private const val KEY_TARGET = "target"

        private const val KEY_MANGAS = "mangas"

        private var instance: WeakReference<LibraryUpdateJob>? = null

        private var extraManga = emptyList<Long>()

        val updateMutableFlow = MutableSharedFlow<Long?>(
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val updateFlow = updateMutableFlow.asSharedFlow()

        private var runExtensionUpdatesAfter = false

        fun runExtensionUpdatesAfterJob() { runExtensionUpdatesAfter = true }

        /**
         * Milliseconds from now until [hour] o'clock, today if it is still ahead and tomorrow
         * otherwise. This only sets where the repeating window starts; WorkManager still decides
         * when within it the job actually runs.
         */
        private fun millisUntilHour(hour: Int): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<PreferencesHelper>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestriction().get()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                    .build()

                val request = PeriodicWorkRequestBuilder<LibraryUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .apply {
                        val startHour = preferences.libraryUpdateStartHour().get()
                        if (startHour in 0..23) {
                            setInitialDelay(millisUntilHour(startHour), TimeUnit.MILLISECONDS)
                        }
                    }
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    request,
                )
            } else {
                WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME_AUTO)
            }
        }

        fun cancelAllWorks(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { list ->
                list.any { it.state == WorkInfo.State.RUNNING }
            }
        }

        fun isRunning(context: Context): Boolean {
            val list = WorkManager.getInstance(context).getWorkInfosByTag(TAG).get()
            return list.any { it.state == WorkInfo.State.RUNNING }
        }

        fun categoryInQueue(id: Int?) = id != null && instance?.get()?.categoryIds?.contains(id) == true

        fun startNow(
            context: Context,
            category: Category? = null,
            target: Target = Target.CHAPTERS,
            mangaToUse: List<LibraryManga>? = null,
        ): Boolean {
            if (isRunning(context)) {
                if (target == Target.CHAPTERS) {
                    category?.id?.let {
                        if (mangaToUse != null) {
                            instance?.get()?.addMangaToQueue(it, mangaToUse)
                        } else {
                            instance?.get()?.addCategory(it)
                        }
                    }
                }
                // Already running either as a scheduled or manual job
                return false
            }

            val builder = Data.Builder()
            builder.putString(KEY_TARGET, target.name)
            category?.id?.let { id ->
                builder.putInt(KEY_CATEGORY, id)
                if (mangaToUse != null) {
                    builder.putLongArray(
                        KEY_MANGAS,
                        mangaToUse.firstOrNull()?.manga?.id?.let { longArrayOf(it) } ?: longArrayOf(),
                    )
                    extraManga = mangaToUse.subList(1, mangaToUse.size).mapNotNull { it.manga.id }
                }
            }
            val inputData = builder.build()
            val request = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun stop(context: Context) {
            val wm = WorkManager.getInstance(context)
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}

/** Reason recorded against entries of a source that was written off by the stall watchdog. */
private class StalledSourceException(minutes: Long) :
    Exception("Source stopped responding, gave up after $minutes minutes with no progress")

/** Reason recorded when a source's extension could not be loaded at all. */
private class SourceUnavailableException :
    Exception("Source is not installed or failed to load")
