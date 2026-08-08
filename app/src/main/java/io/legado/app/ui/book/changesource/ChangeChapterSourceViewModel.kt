package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.ensureActive

@Suppress("MemberVisibilityCanBePrivate")
class ChangeChapterSourceViewModel(application: Application) :
    ChangeBookSourceViewModel(application) {

    var chapterIndex: Int = 0
    var chapterTitle: String = ""

    override fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        super.initData(arguments, book, fromReadBookActivity)
        arguments?.let { bundle ->
            bundle.getString("chapterTitle")?.let {
                chapterTitle = it
            }
            chapterIndex = bundle.getInt("chapterIndex")
        }
    }

    fun getContent(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
        success: (content: String) -> Unit,
        error: (msg: String) -> Unit
    ) {
        execute {
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            WebBook.getContentAwait(bookSource, book, chapter, nextChapterUrl, false)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            error.invoke(it.localizedMessage ?: "获取正文出错")
        }
    }

    fun getOriginalChapters(
        bookUrl: String,
        success: (chapters: List<BookChapter>) -> Unit,
        error: (msg: String) -> Unit,
    ) {
        execute {
            appDb.bookChapterDao.getChapterList(bookUrl)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            error.invoke(it.localizedMessage ?: "获取目录出错")
        }
    }

    fun cacheContents(
        sourceBook: Book,
        sourceChapters: List<Pair<BookChapter, String?>>,
        originalBook: Book,
        originalChapter: BookChapter,
        success: () -> Unit,
        error: (msg: String) -> Unit,
    ): Coroutine<Unit> {
        return execute {
            val bookSource = appDb.bookSourceDao.getBookSource(sourceBook.origin)
                ?: throw NoStackTraceException("书源不存在")
            val contents = sourceChapters.map { (chapter, nextChapterUrl) ->
                WebBook.getContentAwait(
                    bookSource,
                    sourceBook,
                    chapter,
                    nextChapterUrl,
                    false,
                )
            }
            val mergedContent = mergeChapterSourceContents(contents)
            if (ReadBook.book?.bookUrl == originalBook.bookUrl) {
                ReadBook.cancelContentLoading()
            }
            ensureActive()
            BookHelp.saveText(
                originalBook,
                originalChapter,
                mergedContent,
            )
        }.onSuccess {
            success.invoke()
        }.onError {
            error.invoke(it.localizedMessage ?: "获取正文出错")
        }
    }

}

internal fun mergeChapterSourceContents(contents: List<String>): String = buildString {
    contents.forEachIndexed { index, content ->
        if (index > 0) {
            val previous = contents[index - 1]
            val lastContentIndex = previous.indexOfLast { !it.isWhitespace() }
            if (lastContentIndex >= 0 && previous[lastContentIndex] in "。！？.!?" &&
                (lastContentIndex + 1 until previous.length)
                    .none { previous[it] in "\r\n" }
            ) {
                append('\n')
            }
        }
        append(content)
    }
}

internal fun nextChapterSourceOriginal(
    chapters: List<BookChapter>,
    currentIndex: Int,
): BookChapter? = chapters.firstOrNull { !it.isVolume && it.index > currentIndex }
