package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook

/**
 * Shelf add + 「在架」badge. Does not delete or rewrite existing bookshelf rows.
 *
 * Search/explore bulk add: a new empty/佚名 hit is skipped when that title
 * already has exactly one web real author on the shelf. A new real author is
 * always inserted; existing 佚名 rows stay put. BookInfo add persists the
 * current book's URL only and does not retarget another shelf row.
 * Local rows are never reused or REPLACE-deleted. If a local book already owns
 * the unique (name, author) key, the web copy is not inserted.
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

    /**
     * One shelf-identity answer for badge, skip-insert, and BookInfo.
     * [identityOnShelf] is list/UI/skip-insert. [urlOnShelf] is the only
     * flag that may persist this page's URL (replace/save/chapters).
     */
    data class ShelfPresence(
        val existing: Book?,
        val identityOnShelf: Boolean,
        val urlOnShelf: Boolean,
    )

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
        val visible = books.filter { !it.isNotShelf }
        val keys = linkedSetOf<String>()
        val byName = visible.groupBy { BookAuthorIdentity.canonicalName(it.name) }
        for ((n, peers) in byName) {
            if (n.isEmpty()) continue
            val webPeers = peers.filter { !it.isLocal }
            val sole = BookAuthorIdentity.soleRealAuthor(webPeers.map { it.author })
            for (book in peers) {
                keys.add(book.bookUrl)
                // Exact Room (name, author) identity. Do not trim: padded local
                // names do not block a trimmed web insert.
                keys.add(roomIdentityKey(book.name, book.author))
                if (book.isLocal) continue
                keys.add("$n-${book.author}")
                val eff = BookAuthorIdentity.effectiveAuthor(book.author)
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
        val persistKey = roomIdentityKey(book.name, BookAuthorIdentity.persistAuthor(book.author))
        if (index.contains(persistKey)) return true
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
        val byUrl = store.getBook(searchBook.bookUrl)
        if (byUrl != null && !byUrl.isNotShelf) return byUrl
        val want = BookAuthorIdentity.effectiveAuthor(searchBook.author)
        val sameName = store.getBooksByName(searchBook.name)
            .filter { !it.isLocal && !it.isNotShelf }
        val visible = if (want.isNotEmpty()) {
            reusable(store.getBook(searchBook.name, searchBook.author))
                ?: reusable(store.getBook(searchBook.name, want))
                ?: sameName.firstOrNull {
                    BookAuthorIdentity.effectiveAuthor(it.author) == want
                }
        } else if (sameName.isEmpty()) {
            null
        } else {
            val sole = BookAuthorIdentity.soleRealAuthor(sameName.map { it.author })
            if (sole != null) {
                sameName.firstOrNull {
                    BookAuthorIdentity.effectiveAuthor(it.author) == sole
                }
            } else {
                sameName.firstOrNull { BookAuthorIdentity.isWeakAuthor(it.author) }
            }
        }
        return visible ?: byUrl
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
            book.author = BookAuthorIdentity.persistAuthor(book.author)
        }
        if (store.insertIgnore(book)) return book
        return reusable(store.getBook(book.name, book.author))
    }

    fun persistIncomingBook(book: Book): Book? = persistNewBook(AppStore, book)

    internal fun resolveOnShelf(searchBook: SearchBook): Book? {
        return resolveExisting(AppStore, searchBook)
    }

    fun presence(name: String, author: String, bookUrl: String): ShelfPresence {
        return presence(SearchBook(bookUrl = bookUrl, name = name, author = author), AppStore)
    }

    fun isIncomingOnVisibleShelf(name: String, author: String, bookUrl: String): Boolean {
        return presence(name, author, bookUrl).identityOnShelf
    }

    internal fun presence(searchBook: SearchBook, store: Store): ShelfPresence {
        val byUrl = store.getBook(searchBook.bookUrl)
        if (byUrl != null && !byUrl.isNotShelf) {
            return ShelfPresence(byUrl, identityOnShelf = true, urlOnShelf = true)
        }
        val existing = resolveExisting(store, searchBook)?.takeUnless { it.isNotShelf }
        if (existing != null) {
            return ShelfPresence(existing, identityOnShelf = true, urlOnShelf = false)
        }
        val owner = store.getBook(
            searchBook.name,
            BookAuthorIdentity.persistAuthor(searchBook.author),
        )?.takeUnless { it.isNotShelf }
        return ShelfPresence(owner, identityOnShelf = owner != null, urlOnShelf = false)
    }

    internal fun isIncomingOnVisibleShelf(searchBook: SearchBook, store: Store): Boolean {
        return presence(searchBook, store).identityOnShelf
    }

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

/** Exact Room (name, author) badge key. NUL separators cannot collide with canonical `$name-$author`. */
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
