package eu.kanade.tachiyomi.source.model

/**
 * Result of [eu.kanade.tachiyomi.source.Source.getMangaUpdate].
 *
 * @since extensions-lib 1.6
 */
@Suppress("UNUSED")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
