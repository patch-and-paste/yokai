package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.awaitSingle
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.6
     * @param page the page number to retrieve.
     */
    suspend fun getPopularManga(page: Int): MangasPage

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since extensions-lib 1.6
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): MangasPage

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.6
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage

    /**
     * Fetches updated information for a manga.
     *
     * Depending on the provided flags or source availability, this may include
     * updated manga metadata, available chapters, or both.
     *
     * If a value is not requested, the existing provided value can be returned as-is.
     * The host app may apply any returned updates regardless of the flags,
     * so care should be taken to only return accurate and intentional changes.
     *
     * @since extensions-lib 1.6
     * @param manga The manga to fetch updates for.
     * @param chapters Existing chapters of the manga.
     * @param fetchDetails Whether to fetch updated manga details.
     * @param fetchChapters Whether to fetch available chapters.
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        // Each branch gets its own copy: sources on this bridge are used to being handed an
        // instance of their own and some of them write to it.
        val deferredManga = if (fetchDetails) async { getMangaDetails(manga.copy()) } else null
        val deferredChapters = if (fetchChapters) async { getChapterList(manga.copy()) } else null
        SMangaUpdate(deferredManga?.await() ?: manga, deferredChapters?.await() ?: chapters)
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.6
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).awaitSingle()
    }

    /**
     * Get the updated details for a manga.
     *
     * Kept around as a compatibility bridge for extensions targeting lib 1.5 and older, which
     * are allowed to override this instead of [getMangaUpdate]. Callers within the app should
     * always go through [getMangaUpdate].
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).awaitSingle()
    }

    /**
     * Get all the available chapters for a manga.
     *
     * Kept around as a compatibility bridge for extensions targeting lib 1.5 and older, which
     * are allowed to override this instead of [getMangaUpdate]. Callers within the app should
     * always go through [getMangaUpdate].
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).awaitSingle()
    }

    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getMangaUpdate"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getMangaUpdate"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}

fun Source.preferenceKey(): String = "source_$id"
