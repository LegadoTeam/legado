package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Search/explore bulk add. Does not delete or rewrite official bookshelf rows.
 * A new empty/佚名 hit is skipped when that title already has exactly one
 * visible web real author. Badge keys are URL and exact name-author; bare
 * title is added only when the shelf row itself has a weak author.
 */
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

        fun getBooksByName(name: String): List<Book>

        fun allBooks(): List<Book>

        fun update(book: Book)

        fun insertIgnore(book: Book): Boolean
    }

    fun shelfBadgeKeys(books: Iterable<Book>): Set<String> {
        val keys = linkedSetOf<String>()
        for (book in books) {
            if (book.isNotShelf) continue
            keys.add(book.bookUrl)
            keys.add("${book.name}-${book.author}")
            // Bare title only for weak authors. Real-author rows must not make
            // empty-author search hits look already-on-shelf.
            if (BookAuthorIdentity.isWeakAuthor(book.author)) {
                keys.add(book.name)
            }
        }
        return keys
    }

    fun isInShelfBadgeIndex(book: SearchBook, index: Set<String>): Boolean {
        if (index.contains(book.bookUrl)) return true
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return index.contains(key)
    }

    fun shouldSkipWeakInsert(name: String, author: String, bookUrl: String): Boolean {
        return shouldSkipWeakInsert(SearchBook(bookUrl = bookUrl, name = name, author = author), AppStore)
    }

    internal fun shouldSkipWeakInsert(searchBook: SearchBook, store: Store): Boolean {
        return shouldSkipWeakInsert(searchBook, store, store.getBooksByName(searchBook.name))
    }

    private fun shouldSkipWeakInsert(
        searchBook: SearchBook,
        store: Store,
        sameName: List<Book>,
    ): Boolean {
        if (!BookAuthorIdentity.isWeakAuthor(searchBook.author)) return false
        store.getBook(searchBook.bookUrl)?.takeUnless { it.isNotShelf }?.let { return false }
        val visible = sameName.filter { !it.isLocal && !it.isNotShelf }
        return BookAuthorIdentity.soleRealAuthor(visible.map { it.author }) != null
    }

    fun isOfficialUrlOnShelf(bookUrl: String): Boolean {
        if (bookUrl.isBlank()) return false
        return appDb.bookDao.getBook(bookUrl)?.isNotShelf == false
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
        val shelf = store.allBooks().toMutableList()
        books.forEach { searchBook ->
            if (shouldSkipWeakInsert(
                    searchBook,
                    store,
                    shelf.filter { BookAuthorIdentity.equalName(it.name, searchBook.name) },
                )
            ) return@forEach
            val byUrl = store.getBook(searchBook.bookUrl)
            if (byUrl != null) {
                if (byUrl.isNotShelf) {
                    byUrl.removeType(BookType.notShelf)
                    store.update(byUrl)
                    addedBooks.add(byUrl)
                    val index = shelf.indexOfFirst { it.bookUrl == byUrl.bookUrl }
                    if (index >= 0) shelf[index] = byUrl else shelf.add(byUrl)
                    if (byUrl.order == 0) booksToOrder.add(byUrl)
                }
                return@forEach
            }
            val newBook = searchBook.toBook().apply {
                removeType(BookType.notShelf)
            }
            val persisted = persistNewBook(store, newBook) ?: return@forEach
            if (persisted.bookUrl == newBook.bookUrl) {
                addedBooks.add(newBook)
                shelf.add(newBook)
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

    /** Insert without REPLACE. Unique-key conflicts are not deleted. */
    internal fun persistNewBook(store: Store, book: Book): Book? {
        store.getBook(book.bookUrl)?.let {
            store.update(book)
            return book
        }
        if (store.insertIgnore(book)) return book
        val owner = store.getBook(book.name, book.author) ?: return null
        return owner.takeUnless { it.isLocal || it.isNotShelf }
    }

    fun persistIncomingBook(book: Book): Book? {
        var persisted: Book? = null
        appDb.runInTransaction {
            persisted = persistNewBook(AppStore, book)
        }
        return persisted
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

        override fun getBooksByName(name: String): List<Book> {
            val key = BookAuthorIdentity.canonicalName(name)
            if (key.isEmpty()) return emptyList()
            return allBooks().filter { BookAuthorIdentity.canonicalName(it.name) == key }
        }

        override fun allBooks(): List<Book> = appDb.bookDao.all

        override fun update(book: Book) {
            book.update()
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
