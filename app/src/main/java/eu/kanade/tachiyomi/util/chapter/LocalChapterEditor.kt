package eu.kanade.tachiyomi.util.chapter

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.withIOContext

/**
 * Restructures the folders behind local source chapters, for readers who keep collected works as
 * one entry and want the pieces regrouped.
 *
 * Only folder chapters are handled. An archive would have to be unpacked and repacked, which is a
 * different operation with different failure modes, and only the local source is touched at all:
 * anywhere else the folder is a download cache that the next library update would rebuild.
 */
object LocalChapterEditor {

    class NotEditable(message: String) : Exception(message)

    /**
     * Moves every page of [chapters] after the first into the first, in the order given, and
     * removes the folders left empty.
     *
     * Pages are renamed to a zero-padded running index so the reader keeps the order the chapters
     * were in rather than re-sorting on the original filenames.
     *
     * @return the folder everything ended up in.
     */
    suspend fun merge(source: LocalSource, chapters: List<Chapter>): UniFile = withIOContext {
        if (chapters.size < 2) throw NotEditable("Need at least two chapters to merge")

        val folders = chapters.map { it.asFolder(source) }
        val target = folders.first()

        var index = target.imagePages().size
        folders.drop(1).forEach { folder ->
            folder.imagePages().forEach { page ->
                index++
                page.moveInto(target, "%04d.%s".format(index, page.extension))
            }
            folder.delete()
        }
        target
    }

    /**
     * Moves the pages from [pageIndex] onwards into a new folder beside [chapter], named after it.
     *
     * @param pageIndex zero based, so 10 leaves ten pages behind.
     * @return the folder holding the second half.
     */
    suspend fun split(source: LocalSource, chapter: Chapter, pageIndex: Int): UniFile = withIOContext {
        val folder = chapter.asFolder(source)
        val pages = folder.imagePages()
        if (pageIndex !in 1..pages.lastIndex) {
            throw NotEditable("Split point is outside the chapter")
        }

        val parent = folder.parentFile ?: throw NotEditable("Chapter folder has no parent")
        val name = generateSequence(1) { it + 1 }
            .map { "${folder.name} (${it + 1})" }
            .first { parent.findFile(it) == null }
        val second = parent.createDirectory(name) ?: throw NotEditable("Unable to create the new folder")

        pages.drop(pageIndex).forEachIndexed { i, page ->
            page.moveInto(second, "%04d.%s".format(i + 1, page.extension))
        }
        second
    }

    /** Pages in [chapter], or 0 when it is not stored as a folder. */
    suspend fun pageCount(source: LocalSource, chapter: Chapter): Int = withIOContext {
        when (val format = source.getFormat(chapter)) {
            is LocalSource.Format.Directory -> format.file.imagePages().size
            else -> 0
        }
    }

    private fun Chapter.asFolder(source: LocalSource): UniFile =
        when (val format = source.getFormat(this)) {
            is LocalSource.Format.Directory -> format.file
            else -> throw NotEditable("Only chapters stored as a folder can be restructured")
        }

    private fun UniFile.imagePages(): List<UniFile> = listFiles().orEmpty()
        .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
        .sortedWith { a, b -> a.name.orEmpty().compareTo(b.name.orEmpty()) }

    /**
     * SAF has no move, so the bytes are copied across and the original dropped only once the copy
     * is on disk. A failure part way leaves the original where it was.
     */
    private fun UniFile.moveInto(target: UniFile, name: String) {
        val copy = target.createFile(name) ?: throw NotEditable("Unable to write $name")
        openInputStream().use { input ->
            copy.openOutputStream().use { output -> input.copyTo(output) }
        }
        delete()
    }

    private val UniFile.extension: String
        get() = name.orEmpty().substringAfterLast('.', "jpg")
}
