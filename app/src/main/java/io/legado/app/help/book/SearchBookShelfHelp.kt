package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Shelf add + 「在架」badge. Does not delete or rewrite existing bookshelf rows.
 *
 * Future adds: a new empty/佚名 hit is skipped when that title already has exactly
 * one web real author on the shelf. A new real author is always inserted; existing
 * 佚名 rows stay put (avoids irreversible guess if a second author appears later).
 * Local rows are never reused or REPLACE-deleted. If a local book already owns the
 * unique (name, author) key, the web copy is not inserted.
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

        fun update(book: Book)

        fun insertIgnore(book: Book): Boolean
    }

    /**
     * Existing row to reuse when adding [name]/[author].
     * Never returns a weak row for an incoming real author.
     */
    fun findExistingToReuseOnAdd(name: String, author: String, bookUrl: String = ""): Book? {
        return resolveExisting(
            AppStore,
            SearchBook(bookUrl = bookUrl, name = name, author = author),
        )
    }

    fun shelfBadgeKeys(books: Iterable<Book>): Set<String> {
        val visible = books.filter { !it.isNotShelf }
        val keys = linkedSetOf<String>()
        val byName = visible.groupBy { BookAuthorIdentity.canonicalName(it.name) }
        for ((n, peers) in byName) {
            if (n.isEmpty()) continue
            val webPeers = peers.filter { !it.isLocal }
            val sole = BookAuthorIdentity.soleRealAuthor(webPeers.map { it.author })
            for (book in peers) {
                keys.add(book.bookUrl)
                val eff = BookAuthorIdentity.effectiveAuthor(book.author)
                if (book.isLocal) {
                    if (eff.isNotEmpty()) {
                        keys.add("$n-${book.author}")
                        keys.add("$n-$eff")
                    }
                    continue
                }
                keys.add("$n-${book.author}")
                if (eff.isNotEmpty()) {
                    keys.add("$n-$eff")
                } else {
                    keys.add(n)
                }
            }
            if (sole != null) {
                keys.add("$n-$sole")
                keys.add("weak:$n")
            }
        }
        return keys
    }

    fun isInShelfBadgeIndex(book: SearchBook, index: Set<String>): Boolean {
        if (index.contains(book.bookUrl)) return true
        val name = BookAuthorIdentity.canonicalName(book.name)
        val eff = BookAuthorIdentity.effectiveAuthor(book.author)
        if (eff.isNotEmpty()) {
            if (index.contains("$name-$eff")) return true
            if (index.contains("$name-${book.author}")) return true
        } else {
            if (index.contains(name)) return true
            if (index.contains("weak:$name")) return true
            if (index.contains("$name-${book.author}")) return true
        }
        return false
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
            val existingBook = resolveExisting(store, searchBook)
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

    /**
     * URL match, or same effective author, or incoming weak + sole web real already on shelf.
     * Incoming real never reuses an existing weak row. Local rows are never reused
     * for a different URL (web add must still insert).
     */
    internal fun resolveExisting(store: Store, searchBook: SearchBook): Book? {
        store.getBook(searchBook.bookUrl)?.let { return it }
        val want = BookAuthorIdentity.effectiveAuthor(searchBook.author)
        val sameName = store.getBooksByName(searchBook.name).filter { !it.isLocal }
        if (want.isNotEmpty()) {
            reusable(store.getBook(searchBook.name, searchBook.author))?.let { return it }
            reusable(store.getBook(searchBook.name, want))?.let { return it }
            return sameName.firstOrNull {
                BookAuthorIdentity.effectiveAuthor(it.author) == want
            }
        }
        if (sameName.isEmpty()) return null
        val sole = BookAuthorIdentity.soleRealAuthor(sameName.map { it.author })
        if (sole != null) {
            return sameName.firstOrNull {
                BookAuthorIdentity.effectiveAuthor(it.author) == sole
            }
        }
        return sameName.firstOrNull { BookAuthorIdentity.isWeakAuthor(it.author) }
    }

    /**
     * Insert [book] without REPLACE-deleting another row.
     * Placeholder authors are stored as empty so they do not collide with a local 「佚名」.
     * @return the inserted book, an existing same-key web row, or null when a local unique key blocks insert.
     */
    internal fun persistNewBook(store: Store, book: Book): Book? {
        store.getBook(book.bookUrl)?.let {
            store.update(book)
            return book
        }
        if (BookAuthorIdentity.isWeakAuthor(book.author)) {
            book.author = ""
        }
        if (store.insertIgnore(book)) return book
        return reusable(store.getBook(book.name, book.author))
    }

    fun persistIncomingBook(book: Book): Book? = persistNewBook(AppStore, book)

    private fun reusable(book: Book?): Book? = book?.takeUnless { it.isLocal }

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
            // Trim-equivalent in memory so SQL can keep the name index (no trim(name)).
            return appDb.bookDao.all.filter { BookAuthorIdentity.canonicalName(it.name) == key }
        }

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
