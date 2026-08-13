package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga

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

        fun delete(book: Book)

        fun insertIgnore(book: Book): Boolean

        /** Satellite retarget before delete (highlights/bookmarks). Default no-op for tests. */
        fun onRetire(retired: Book, canonical: Book) {}

        /** Remap (name, author) keys when author string changes in place. */
        fun onAuthorKeyChange(name: String, oldAuthor: String, book: Book) {}
    }

    /**
     * Resolve an on-shelf row for name/author[/url] (RFC-003 §4.8).
     * Used by BookInfo and other getBook(name, author) call sites.
     */
    fun findExistingOnShelf(name: String, author: String, bookUrl: String = ""): Book? {
        return resolveExisting(
            AppStore,
            SearchBook(bookUrl = bookUrl, name = name, author = author),
        )
    }

    /** Same-name cleanup for title T (RFC-003 §4.10). */
    fun cleanupSameName(name: String) {
        appDb.runInTransaction {
            coalesceSameName(AppStore, name)
        }
    }

    /**
     * Retire [incoming] into [canonical] with satellite retarget (RFC-003 §4.9).
     * Does not insert chapters for the retired URL.
     */
    fun retireIncomingIntoCanonical(incoming: Book, canonical: Book) {
        if (incoming.bookUrl == canonical.bookUrl) {
            AppStore.update(canonical)
            return
        }
        if (!BookAuthorIdentity.mayRetireShelfBook(incoming, canonical)) {
            AppStore.update(canonical)
            return
        }
        appDb.runInTransaction {
            if (BookAuthorIdentity.preferProgress(incoming, canonical)) {
                BookAuthorIdentity.copyProgress(incoming, canonical)
            }
            BookAuthorIdentity.fillBlanksFrom(canonical, incoming)
            retargetActiveReading(incoming.bookUrl, canonical)
            AppStore.onRetire(incoming, canonical)
            if (AppStore.getBook(incoming.bookUrl) != null) {
                AppStore.delete(incoming)
            }
            AppStore.update(canonical)
            coalesceSameName(AppStore, canonical.name)
        }
    }

    /**
     * Keys for search/explore 「在架」徽章 (RFC-003 §4.8 / §4.11).
     * Includes sole-real aliases so weak↔sole matches without treating multi-author titles as one.
     */
    fun shelfBadgeKeys(books: Iterable<Book>): Set<String> {
        val visible = books.filter { !it.isNotShelf }
        val keys = linkedSetOf<String>()
        val byName = visible.groupBy { it.name.trim() }
        for ((name, peers) in byName) {
            if (name.isEmpty()) continue
            val sole = BookAuthorIdentity.soleRealAuthor(peers.map { it.author })
            for (book in peers) {
                keys.add(book.bookUrl)
                keys.add("$name-${book.author}")
                val eff = BookAuthorIdentity.effectiveAuthor(book.author)
                if (eff.isNotEmpty()) {
                    keys.add("$name-$eff")
                } else {
                    keys.add(name)
                }
            }
            if (sole != null) {
                keys.add("$name-$sole")
                keys.add("weak:$name")
            }
        }
        return keys
    }

    fun isInShelfBadgeIndex(book: SearchBook, index: Set<String>): Boolean {
        if (index.contains(book.bookUrl)) return true
        val name = book.name.trim()
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
            coalesceSameName(store, searchBook.name)
            val existingBook = resolveExisting(store, searchBook)
            if (existingBook != null) {
                applyIncomingToExisting(existingBook, searchBook, store)
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
                author = if (BookAuthorIdentity.isWeakAuthor(author)) "" else author.trim()
            }
            // If sole real exists after normalizing, resolve again (should not insert).
            coalesceSameName(store, newBook.name)
            val again = resolveExisting(store, searchBook)
            if (again != null) {
                applyIncomingToExisting(again, searchBook, store)
                if (again.isNotShelf) {
                    again.removeType(BookType.notShelf)
                    if (again.order == 0) booksToOrder.add(again) else store.update(again)
                    addedBooks.add(again)
                }
                return@forEach
            }
            if (store.insertIgnore(newBook)) {
                addedBooks.add(newBook)
                if (newBook.order == 0) booksToOrder.add(newBook)
            } else {
                val conflict = resolveExisting(store, searchBook)
                    ?: store.getBook(newBook.bookUrl)
                if (conflict != null) {
                    applyIncomingToExisting(conflict, searchBook, store)
                    if (conflict.isNotShelf) {
                        conflict.removeType(BookType.notShelf)
                        if (conflict.order == 0) booksToOrder.add(conflict) else store.update(conflict)
                        addedBooks.add(conflict)
                    }
                }
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
     * Resolve shelf book for [searchBook] (RFC-003 §4.8).
     */
    internal fun resolveExisting(store: Store, searchBook: SearchBook): Book? {
        store.getBook(searchBook.bookUrl)?.let { return it }
        val sameName = store.getBooksByName(searchBook.name)
        if (sameName.isEmpty()) return null
        val peerAuthors = sameName.map { it.author } + searchBook.author
        // Exact effective match
        val want = BookAuthorIdentity.effectiveAuthor(searchBook.author)
        sameName.firstOrNull {
            BookAuthorIdentity.effectiveAuthor(it.author) == want
        }?.let { return it }
        // Sole-real weak↔real
        val sole = BookAuthorIdentity.soleRealAuthor(peerAuthors) ?: return null
        if (want.isEmpty() || want == sole) {
            return sameName.firstOrNull {
                BookAuthorIdentity.effectiveAuthor(it.author) == sole
            } ?: sameName.firstOrNull { BookAuthorIdentity.isWeakAuthor(it.author) }
        }
        return null
    }

    private fun applyIncomingToExisting(
        existing: Book,
        searchBook: SearchBook,
        store: Store,
    ) {
        val incomingReal = BookAuthorIdentity.effectiveAuthor(searchBook.author)
        val existingReal = BookAuthorIdentity.effectiveAuthor(existing.author)
        val oldAuthor = existing.author
        if (existingReal.isEmpty() && incomingReal.isNotEmpty()) {
            // §4.9.1: never write a colliding author key; never collide-fill local.
            val conflict = store.getBook(existing.name, incomingReal)
            val canFill = (conflict == null || conflict.bookUrl == existing.bookUrl) &&
                !existing.isLocal
            if (canFill) {
                existing.author = incomingReal
            }
        }
        BookAuthorIdentity.fillBlanksFrom(
            existing,
            searchBook.toBook(),
        )
        store.update(existing)
        if (oldAuthor != existing.author) {
            store.onAuthorKeyChange(existing.name, oldAuthor, existing)
        }
        coalesceSameName(store, existing.name)
    }

    /**
     * Collapse same-name weak+sole-real duplicates (RFC-003 §4.9–4.10).
     * Never retires local books.
     */
    internal fun coalesceSameName(store: Store, name: String) {
        val rows = store.getBooksByName(name)
        if (rows.size < 2) return
        val sole = BookAuthorIdentity.soleRealAuthor(rows.map { it.author })
        if (sole == null) {
            // |S|≥2: leave weaks alone (RFC-003 §4.4.4 / §4.8). |S|==0: collapse weaks.
            if (BookAuthorIdentity.distinctRealAuthors(rows.map { it.author }).isNotEmpty()) {
                return
            }
            val weaks = rows.filter { BookAuthorIdentity.isWeakAuthor(it.author) }
            if (weaks.size < 2) return
            val canonical = BookAuthorIdentity.pickCanonicalShelfBook(weaks) ?: return
            retireOthers(store, weaks, canonical)
            return
        }
        val canonical = BookAuthorIdentity.pickCanonicalShelfBook(rows) ?: return
        // Ensure canonical holds sole author when safe
        if (BookAuthorIdentity.effectiveAuthor(canonical.author) != sole) {
            val holder = rows.firstOrNull {
                BookAuthorIdentity.effectiveAuthor(it.author) == sole
            }
            if (holder != null && holder.bookUrl != canonical.bookUrl) {
                // Prefer real-author row as canonical
                retireOthers(store, rows, holder)
                return
            }
            val conflict = store.getBook(canonical.name, sole)
            if (conflict == null || conflict.bookUrl == canonical.bookUrl) {
                if (!canonical.isLocal) {
                    canonical.author = sole
                    store.update(canonical)
                }
            }
        }
        retireOthers(store, rows, BookAuthorIdentity.pickCanonicalShelfBook(store.getBooksByName(name)) ?: return)
    }

    private fun retireOthers(store: Store, rows: List<Book>, canonical: Book) {
        var canon = canonical
        for (other in rows) {
            if (!BookAuthorIdentity.mayRetireShelfBook(other, canon)) continue
            if (BookAuthorIdentity.preferProgress(other, canon)) {
                BookAuthorIdentity.copyProgress(other, canon)
            }
            BookAuthorIdentity.fillBlanksFrom(canon, other)
            retargetActiveReading(other.bookUrl, canon)
            store.onRetire(other, canon)
            store.delete(other)
        }
        if (canon.isNotShelf) {
            canon.removeType(BookType.notShelf)
        }
        store.update(canon)
    }

    private fun retargetActiveReading(fromUrl: String, to: Book) {
        if (ReadBook.book?.bookUrl == fromUrl) {
            ReadBook.book = to
        }
        if (AudioPlay.book?.bookUrl == fromUrl) {
            AudioPlay.book = to
        }
        if (ReadManga.book?.bookUrl == fromUrl) {
            ReadManga.book = to
        }
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
            return appDb.bookDao.getBooksByName(name)
        }

        override fun update(book: Book) {
            book.update()
        }

        override fun delete(book: Book) {
            appDb.bookDao.delete(book)
        }

        override fun insertIgnore(book: Book): Boolean {
            return appDb.bookDao.insertIgnore(book) != -1L
        }

        override fun onRetire(retired: Book, canonical: Book) {
            try {
                appDb.bookHighlightDao.retargetBook(
                    fromUrl = retired.bookUrl,
                    toUrl = canonical.bookUrl,
                    bookName = canonical.name,
                    bookAuthor = canonical.author,
                )
                if (retired.name != canonical.name || retired.author != canonical.author) {
                    appDb.bookmarkDao.remapBook(
                        oldName = retired.name,
                        oldAuthor = retired.author,
                        newName = canonical.name,
                        newAuthor = canonical.author,
                    )
                }
            } catch (e: Exception) {
                AppLog.put("RFC-003 retire retarget failed\n${e.localizedMessage}", e)
                throw e
            }
        }

        override fun onAuthorKeyChange(name: String, oldAuthor: String, book: Book) {
            if (oldAuthor == book.author) return
            try {
                appDb.bookmarkDao.remapBook(
                    oldName = name,
                    oldAuthor = oldAuthor,
                    newName = book.name,
                    newAuthor = book.author,
                )
                appDb.bookHighlightDao.updateBookMetadata(
                    bookUrl = book.bookUrl,
                    bookName = book.name,
                    bookAuthor = book.author,
                )
            } catch (e: Exception) {
                AppLog.put("RFC-003 author key remap failed\n${e.localizedMessage}", e)
                throw e
            }
        }
    }
}

internal fun Book.isSameShelfIdentity(other: Book): Boolean {
    if (bookUrl.isNotBlank() && bookUrl == other.bookUrl) return true
    if (!BookAuthorIdentity.equalName(name, other.name)) return false
    // Without full same-name peers, only effective-author equality is safe.
    return BookAuthorIdentity.effectiveAuthor(author) ==
        BookAuthorIdentity.effectiveAuthor(other.author)
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
