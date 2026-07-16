package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

object SearchBookShelfHelp {

    data class AddResult(
        val total: Int,
        val addedBooks: List<Book>,
    ) {
        val added: Int
            get() = addedBooks.size

        val skipped: Int
            get() = total - added
    }

    internal interface Store {
        val minOrder: Int

        fun getBook(name: String, author: String): Book?

        fun getBook(bookUrl: String): Book?

        fun update(book: Book)

        fun insertIgnore(book: Book): Boolean
    }

    fun addLoadedBooksToShelf(books: List<SearchBook>): AddResult {
        var result = AddResult(books.size, emptyList())
        appDb.runInTransaction {
            result = addLoadedBooksToShelf(books, AppStore)
        }
        return result
    }

    internal fun addLoadedBooksToShelf(
        books: List<SearchBook>,
        store: Store,
    ): AddResult {
        if (books.isEmpty()) return AddResult(0, emptyList())

        val minOrder = store.minOrder
        val addedBooks = arrayListOf<Book>()
        val booksToOrder = arrayListOf<Book>()
        books.forEach { searchBook ->
            val existingBook = store.getBook(searchBook.name, searchBook.author)
                ?: store.getBook(searchBook.bookUrl)
            if (existingBook != null) {
                if (existingBook.isNotShelf) {
                    existingBook.removeType(BookType.notShelf)
                    if (existingBook.order == 0) {
                        booksToOrder.add(existingBook)
                    } else {
                        store.update(existingBook)
                    }
                    addedBooks.add(existingBook)
                }
                return@forEach
            }

            val newBook = searchBook.toBook().apply {
                removeType(BookType.notShelf)
            }
            if (store.insertIgnore(newBook)) {
                addedBooks.add(newBook)
                if (newBook.order == 0) booksToOrder.add(newBook)
            }
        }
        var nextOrder = minOrder - booksToOrder.size
        val lastOrder = nextOrder + booksToOrder.lastIndex
        if (nextOrder <= 0 && lastOrder >= 0) {
            nextOrder = -booksToOrder.size
        }
        booksToOrder.forEach { book ->
            book.order = nextOrder++
            store.update(book)
        }
        return AddResult(books.size, addedBooks)
    }

    private object AppStore : Store {
        override val minOrder: Int
            get() = appDb.bookDao.minOrder

        override fun getBook(name: String, author: String): Book? {
            return appDb.bookDao.getBook(name, author)
        }

        override fun getBook(bookUrl: String): Book? {
            return appDb.bookDao.getBook(bookUrl)
        }

        override fun update(book: Book) {
            appDb.bookDao.update(book)
        }

        override fun insertIgnore(book: Book): Boolean {
            return appDb.bookDao.insertIgnore(book) != -1L
        }
    }
}

internal fun Book.isSameShelfIdentity(other: Book): Boolean {
    return (bookUrl.isNotBlank() && bookUrl == other.bookUrl) || isSameNameAuthor(other)
}

internal fun mergeActiveShelfBook(activeBook: Book?, shelfBook: Book): Book? {
    activeBook ?: return null
    if (!activeBook.isSameShelfIdentity(shelfBook)) return null
    if (activeBook.bookUrl.isNotBlank() && activeBook.bookUrl == shelfBook.bookUrl) {
        activeBook.removeType(BookType.notShelf)
        activeBook.order = shelfBook.order
        return activeBook
    }
    return shelfBook
}
