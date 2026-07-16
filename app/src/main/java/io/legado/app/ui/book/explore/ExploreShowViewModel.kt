package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.SearchBookShelfHelp
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.mergeActiveShelfBook
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap


@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {
    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val addBooksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()
    val errorTopLiveData = MutableLiveData<String>()
    val pageLiveData = MutableLiveData<Int>()
    val addBooksBusy = MutableLiveData(false)
    private var bookSource: BookSource? = null
    private var exploreUrl: String? = null
    private var page = 1
    private val booksLock = Any()
    private var books = linkedSetOf<SearchBook>()
    private val addBooksLock = Any()
    private var addBooksCoroutine: Coroutine<*>? = null

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    fun initData(intent: Intent) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            exploreUrl = intent.getStringExtra("exploreUrl")
            if (bookSource == null && sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            explore()
        }
    }

    /**
     * 上滑触发的增量更新
     */
    fun explore(page: Int) {
        val source = bookSource
        val url = exploreUrl
        if (source == null || url == null) return
        WebBook.exploreBook(viewModelScope, source, url, page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                synchronized(booksLock) {
                    val newBooks = linkedSetOf<SearchBook>()
                    newBooks.addAll(searchBooks)
                    newBooks.addAll(books)
                    books = newBooks
                }
                addBooksData.postValue(searchBooks)
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                pageLiveData.postValue(page)
            }.onError {
                it.printOnDebug()
                errorTopLiveData.postValue(it.stackTraceStr)
            }
    }
    fun skipPage(page: Int) {
        if (page > 0) {
            synchronized(booksLock) {
                books.clear()
            }
            this.page = page
        }
    }

    fun explore() {
        val source = bookSource
        val url = exploreUrl
        if (source == null || url == null) return
        WebBook.exploreBook(viewModelScope, source, url, page)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                val loadedBooks = synchronized(booksLock) {
                    books.addAll(searchBooks)
                    books.toList()
                }
                booksData.postValue(loadedBooks)
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                pageLiveData.postValue(page)
                page++
            }.onError {
                it.printOnDebug()
                errorLiveData.postValue(it.stackTraceStr)
            }
    }

    fun getLoadedBooks(): List<SearchBook> {
        return synchronized(booksLock) { books.toList() }
    }

    fun addLoadedBooksToShelf(
        loadedBooks: List<SearchBook>,
    ): Boolean {
        val snapshot = loadedBooks.toList()
        if (snapshot.isEmpty()) return false
        val task = synchronized(addBooksLock) {
            if (addBooksCoroutine != null) return false
            executeLazy {
                val result = SearchBookShelfHelp.addLoadedBooksToShelf(snapshot)
                val callBackBooks = result.addedBooks.map { book ->
                    kotlin.runCatching {
                        appDb.bookSourceDao.getBookSource(book.origin)
                    }.getOrNull() to book
                }
                withContext(NonCancellable + Main.immediate) {
                    val activeBooks = result.addedBooks
                        .flatMap(::syncActiveBook)
                        .distinctBy { it.bookUrl }
                    val shelfStates = activeBooks.map {
                        ShelfState(it.bookUrl, it.type, it.order)
                    }
                    if (shelfStates.isNotEmpty()) {
                        withContext(IO) {
                            shelfStates.forEach {
                                appDb.bookDao.updateShelfState(it.bookUrl, it.type, it.order)
                            }
                        }
                    }
                    val activeBooksByUrl = activeBooks.associateBy { it.bookUrl }
                    val mergedCallBackBooks = callBackBooks.map { (source, book) ->
                        source to (activeBooksByUrl[book.bookUrl] ?: book)
                    }
                    SourceCallBack.callBackBooks(
                        SourceCallBack.ADD_BOOK_SHELF,
                        mergedCallBackBooks,
                    )
                }
                result
            }.also {
                addBooksCoroutine = it
            }
        }
        addBooksBusy.value = true
        task.onSuccess { result ->
            context.toastOnUi(
                context.getString(
                    R.string.add_loaded_books_to_bookshelf_result,
                    result.added,
                    result.skipped,
                )
            )
        }.onError {
            AppLog.put("发现列表批量加入书架失败\n${it.localizedMessage}", it)
            val message = it.localizedMessage
            if (message.isNullOrBlank()) {
                context.toastOnUi(R.string.add_loaded_books_to_bookshelf_failed)
            } else {
                context.toastOnUi(
                    "${context.getString(R.string.add_loaded_books_to_bookshelf_failed)}\n$message"
                )
            }
        }
        task.invokeOnCompletion {
            synchronized(addBooksLock) {
                if (addBooksCoroutine === task) {
                    addBooksCoroutine = null
                }
            }
            addBooksBusy.postValue(false)
        }
        task.start()
        return true
    }

    private fun syncActiveBook(book: Book): List<Book> {
        val activeBooks = arrayListOf<Book>()
        mergeActiveShelfBook(ReadBook.book, book)?.let {
            ReadBook.book = it
            ReadBook.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        mergeActiveShelfBook(AudioPlay.book, book)?.let {
            AudioPlay.book = it
            AudioPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        mergeActiveShelfBook(ReadManga.book, book)?.let {
            ReadManga.book = it
            ReadManga.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        mergeActiveShelfBook(VideoPlay.book, book)?.let {
            VideoPlay.book = it
            VideoPlay.inBookshelf = true
            if (it !== book && it.bookUrl == book.bookUrl) activeBooks.add(it)
        }
        return activeBooks
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    private data class ShelfState(
        val bookUrl: String,
        val type: Int,
        val order: Int,
    )

}
