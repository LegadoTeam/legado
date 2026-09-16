package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.AudioPlay
import io.legado.app.help.book.BookHelp

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null
    val bookData = MutableLiveData<Book>()
    val saved = MutableLiveData(false)
    private var saving = false

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                bookData.postValue(it)
            }
        }
    }

    fun saveBook(oldBook: Book, book: Book, synchronizeMetadata: Boolean) {
        if (saving) return
        saving = true
        execute {
            appDb.runInTransaction {
                appDb.bookDao.update(book)
                appDb.bookHighlightDao.updateBookMetadata(book.bookUrl, book.name, book.author)
                if (synchronizeMetadata && (oldBook.name != book.name || oldBook.author != book.author)) {
                    appDb.bookmarkDao.renameBook(oldBook.name, oldBook.author, book.name, book.author)
                    appDb.readRecordDao.renameBook(oldBook.name, oldBook.author, book.name, book.author)
                }
            }
            BookHelp.updateCacheFolder(oldBook, book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
                ReadBook.loadHighlights(book)
            }
            if (ReadManga.book?.bookUrl == book.bookUrl) ReadManga.book = book
            if (AudioPlay.book?.bookUrl == book.bookUrl) AudioPlay.book = book
            this@BookInfoEditViewModel.book = book
        }.onSuccess {
            saved.value = true
        }.onError {
            saving = false
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }
}
