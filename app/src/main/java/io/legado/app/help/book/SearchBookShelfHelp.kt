package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Search/explore bulk add. Does not delete or rewrite official bookshelf rows.
 * A new empty/佚名 hit is skipped when that title already has exactly one
 * visible web real author. Badge is for the displayed row only (URL / this
 * book's name-author), not a cross-page identity.
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
            keys.add(roomIdentityKey(book.name, book.author))
            val n = BookAuthorIdentity.canonicalName(book.name)
            if (n.isEmpty() || book.isLocal) continue
            keys.add("$n-${book.author}")
            val eff = BookAuthorIdentity.effectiveAuthor(book.author)
            if (eff.isNotEmpty()) {
                keys.add("$n-$eff")
            } else {
                keys.add(n)
            }
        }
        return keys
    }

    fun isInShelfBadgeIndex(book: SearchBook, index: Set<String>): Boolean {
        if (index.contains(book.bookUrl)) return true
        val persistKey = roomIdentityKey(book.name, BookAuthorIdentity.persistAuthor(book.author))
        if (index.contains(persistKey)) return true
        val name = BookAuthorIdentity.canonicalName(book.name)
        val eff = BookAuthorIdentity.effectiveAuthor(book.author)
        if (eff.isNotEmpty()) {
            return index.contains("$name-$eff") || index.contains("$name-${book.author}")
        }
        return index.contains(name) || index.contains("$name-${book.author}")
    }

    fun shouldSkipWeakInsert(name: String, author: String, bookUrl: String): Boolean {
        return shouldSkipWeakInsert(SearchBook(bookUrl = bookUrl, name = name, author = author), AppStore)
    }

    internal fun shouldSkipWeakInsert(searchBook: SearchBook, store: Store): Boolean {
        if (!BookAuthorIdentity.isWeakAuthor(searchBook.author)) return false
        store.getBook(searchBook.bookUrl)?.takeUnless { it.isNotShelf }?.let { return false }
        val sameName = store.getBooksByName(searchBook.name)
            .filter { !it.isLocal && !it.isNotShelf }
        return BookAuthorIdentity.soleRealAuthor(sameName.map { it.author }) != null
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
        return addLoadedBooksToIndexedStore(books, NameIndexStore(store))
    }

    private fun addLoadedBooksToIndexedStore(
        books: List<SearchBook>,
        store: Store,
    ): AddResult {
        val minOrder = store.minOrder
        val addedBooks = arrayListOf<Book>()
        val booksToOrder = arrayListOf<Book>()
        books.forEach { searchBook ->
            if (shouldSkipWeakInsert(searchBook, store)) return@forEach
            val byUrl = store.getBook(searchBook.bookUrl)
            if (byUrl != null) {
                if (byUrl.isNotShelf) {
                    byUrl.removeType(BookType.notShelf)
                    store.update(byUrl)
                    addedBooks.add(byUrl)
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

    /** Insert without REPLACE. Weak authors persist as empty. Unique-key conflicts are not deleted. */
    internal fun persistNewBook(store: Store, book: Book): Book? {
        store.getBook(book.bookUrl)?.let {
            store.update(book)
            return book
        }
        if (BookAuthorIdentity.isWeakAuthor(book.author)) {
            book.author = BookAuthorIdentity.persistAuthor(book.author)
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

private fun roomIdentityKey(name: String, author: String): String = "rk\u0000$name\u0000$author"

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
