package io.legado.app.model

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.String
import kotlin.onFailure

object SourceCallBack {
    private data class CustomButtonRequest(
        val activity: AppCompatActivity,
        val sourceKey: String,
        val bookUrl: String,
        val chapterIndex: Int?,
        val bookType: Int,
        val event: String,
    )

    private val pendingCustomButtons = Collections.synchronizedSet(mutableSetOf<CustomButtonRequest>())

    const val CLICK_AUTHOR = "clickAuthor"
    const val LONG_CLICK_AUTHOR = "longClickAuthor"
    const val CLICK_BOOK_NAME = "clickBookName"
    const val LONG_CLICK_BOOK_NAME = "longClickBookName"
    const val CLICK_CUSTOM_BUTTON = "clickCustomButton"
    const val LONG_CLICK_CUSTOM_BUTTON = "longClickCustomButton"
    const val CLICK_SHARE_BOOK = "clickShareBook"
    const val CLICK_CLEAR_CACHE = "clickClearCache"
    const val CLICK_COPY_BOOK_URL = "clickCopyBookUrl"
    const val CLICK_COPY_TOC_URL = "clickCopyTocUrl"
    const val CLICK_COPY_PLAY_URL = "clickCopyPlayUrl"
    const val CLICK_BOOK_LABEL = "clickBookLabel"
    const val LONG_CLICK_BOOK_LABEL = "longClickBookLabel"

    const val ADD_BOOK_SHELF = "addBookShelf"
    const val DEL_BOOK_SHELF = "delBookShelf"
    const val SAVE_READ = "saveRead"
    const val START_READ = "startRead"
    const val END_READ = "endRead"
    const val START_SHELF_REFRESH = "startShelfRefresh"
    const val END_SHELF_REFRESH = "endShelfRefresh"
    fun callBackBtn(
        activity: AppCompatActivity,
        event: String,
        source: BookSource?,
        book: Book,
        chapter: BookChapter?,
        bookType: Int = 0,
        result: String? = null,
        noCall: (() -> Unit)? = null
    ) {
        if (source == null || !source.eventListener) {
            noCall?.invoke()
            return
        }
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) {
            noCall?.invoke()
            return
        }
        val request = if (event == CLICK_CUSTOM_BUTTON || event == LONG_CLICK_CUSTOM_BUTTON) {
            CustomButtonRequest(activity, source.getKey(), book.bookUrl, chapter?.index, bookType, event)
        } else null
        if (request != null && !pendingCustomButtons.add(request)) return
        // Finish on Main after any showBrowser work posted by the script.
        activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            withContext(IO) {
                val java = SourceLoginJsExtensions(activity, source,  bookType)
                kotlin.runCatching {
                    val result = runScriptWithContext {
                        source.evalJS(jsStr) {
                            put("event", event)
                            put("java", java)
                            put("result", result)
                            put("book", book)
                            put("chapter", chapter)
                        }.toString()
                    }
                    if (!result.isTrue()) {
                        withContext(Dispatchers.Main) {
                            noCall?.invoke()
                        }
                    }
                }.onFailure {
                    AppLog.put("${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}", it, true)
                }
            }
        }.apply {
            // Also releases the claim when a destroyed host cancels before the lazy block starts.
            invokeOnCompletion { if (request != null) pendingCustomButtons.remove(request) }
            start()
        }
    }

    fun callBackBook(
        event: String,
        source: BookSource?,
        book: Book?,
        chapter: BookChapter? = null,
        result: String? = null
    ) {
        Coroutine.async {
            callBackBookInternal(event, source, book, chapter, result)
        }
    }

    fun callBackBooks(
        event: String,
        books: List<Pair<BookSource?, Book>>,
    ) {
        if (books.isEmpty()) return
        Coroutine.async {
            books.forEach { (source, book) ->
                callBackBookInternal(event, source, book)
            }
        }
    }

    private suspend fun callBackBookInternal(
        event: String,
        source: BookSource?,
        book: Book?,
        chapter: BookChapter? = null,
        result: String? = null,
    ) {
        if (source == null || book == null || !source.eventListener) return
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        kotlin.runCatching {
            withTimeout(60000L) {
                runScriptWithContext(kotlin.coroutines.coroutineContext) {
                    source.evalJS(jsStr) {
                        put("event", event)
                        put("result", result)
                        put("book", book)
                        put("chapter", chapter)
                    }
                }
            }
        }.onFailure {
            AppLog.put("${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}", it, true)
        }
    }

    fun callBackSource(scope: CoroutineScope, event: String, source: BookSource) {
        val jsStr = source.getContentRule().callBackJs
        if (jsStr.isNullOrEmpty()) return
        scope.launch(IO) {
            kotlin.runCatching {
                withTimeout(30000L) {
                    runScriptWithContext {
                        source.evalJS(jsStr) {
                            put("event", event)
                            put("result", null)
                            put("book", null)
                            put("chapter", null)
                        }
                    }
                }
            }.onFailure {
                AppLog.put("${source.bookSourceName}\n书源执行回调事件${event}出错\n${it.localizedMessage}", it, true)
            }
        }
    }

}
