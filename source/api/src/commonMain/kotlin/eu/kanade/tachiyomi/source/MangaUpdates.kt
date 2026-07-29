package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Calls [Source.getMangaUpdate], guaranteeing at most one in-flight call per entry.
 *
 * Extensions targeting lib 1.6 may keep per-entry state for one update, including a response reused
 * for the details and chapter halves. They may reject overlapping calls with
 * "getMangaUpdate must not be called concurrently for same manga". Browse
 * initialization, the details screen, migration and the library updater can all target the same
 * entry at the same time, so every call site has to funnel through here.
 *
 * Nested calls for an entry already locked by the current coroutine run directly instead of
 * deadlocking, which keeps [eu.kanade.tachiyomi.source.online.DelegatedHttpSource] safe.
 */
suspend fun Source.awaitMangaUpdate(
    manga: SManga,
    chapters: List<SChapter> = emptyList(),
    fetchDetails: Boolean = false,
    fetchChapters: Boolean = false,
): SMangaUpdate {
    val key = MangaUpdateLocks.keyOf(this, manga)
        ?: return getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)

    val held = coroutineContext[HeldMangaUpdateLocks]?.keys.orEmpty()
    if (key in held) {
        return getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
    }

    val mutex = MangaUpdateLocks.acquire(key)
    try {
        return mutex.withLock {
            withContext(HeldMangaUpdateLocks(held + key)) {
                getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
            }
        }
    } finally {
        MangaUpdateLocks.release(key)
    }
}

private class HeldMangaUpdateLocks(val keys: Set<String>) :
    AbstractCoroutineContextElement(HeldMangaUpdateLocks) {
    companion object Key : CoroutineContext.Key<HeldMangaUpdateLocks>
}

/**
 * Reference counted registry of per-entry locks, so browsing a large catalogue doesn't leave a
 * [Mutex] behind for every entry that was ever looked at.
 */
private object MangaUpdateLocks {

    private val locks = HashMap<String, Entry>()

    fun keyOf(source: Source, manga: SManga): String? {
        val url = try {
            manga.url
        } catch (_: UninitializedPropertyAccessException) {
            // Nothing identifies this entry yet, so nothing can be racing with it either.
            return null
        }
        // The id is all digits, so the first separator unambiguously ends it
        return "${source.id}|$url"
    }

    @Synchronized
    fun acquire(key: String): Mutex = locks.getOrPut(key) { Entry() }.also { it.refs++ }.mutex

    @Synchronized
    fun release(key: String) {
        val entry = locks[key] ?: return
        if (--entry.refs <= 0) locks.remove(key)
    }

    private class Entry {
        val mutex = Mutex()
        var refs = 0
    }
}
