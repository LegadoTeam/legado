package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * One in-memory shelf index per batch add. Inserts and updates are visible
 * to later items without reloading Room or scanning the whole shelf.
 */
internal class NameIndexStore(
    private val inner: SearchBookShelfHelp.Store,
) : SearchBookShelfHelp.Store {
    private val byUrl = linkedMapOf<String, Book>()
    private val byName = hashMapOf<String, MutableList<Book>>()

    init {
        inner.allBooks().forEach(::remember)
    }

    override val minOrder: Int
        get() = inner.minOrder

    override fun getBook(name: String, author: String): Book? {
        return byUrl.values.firstOrNull { it.name == name && it.author == author }
    }

    override fun getBook(bookUrl: String): Book? {
        return byUrl[bookUrl]
    }

    override fun getBooksByName(name: String): List<Book> {
        val key = BookAuthorIdentity.canonicalName(name)
        if (key.isEmpty()) return emptyList()
        return byName[key].orEmpty().toList()
    }

    override fun allBooks(): List<Book> = byUrl.values.toList()

    override fun update(book: Book) {
        inner.update(book)
        remember(book)
    }

    override fun insertIgnore(book: Book): Boolean {
        val ok = inner.insertIgnore(book)
        if (ok) remember(book)
        return ok
    }

    override fun delete(book: Book) {
        inner.delete(book)
        forget(book)
    }

    private fun remember(book: Book) {
        val previous = byUrl.put(book.bookUrl, book)
        if (previous != null && previous !== book) {
            forgetName(previous)
        }
        rememberName(book)
    }

    private fun forget(book: Book) {
        val removed = byUrl.remove(book.bookUrl)
        forgetName(removed ?: book)
    }

    private fun rememberName(book: Book) {
        val key = BookAuthorIdentity.canonicalName(book.name)
        if (key.isEmpty()) return
        val list = byName.getOrPut(key) { arrayListOf() }
        list.removeAll { it.bookUrl == book.bookUrl }
        list.add(book)
    }

    private fun forgetName(book: Book) {
        val key = BookAuthorIdentity.canonicalName(book.name)
        val list = byName[key] ?: return
        list.removeAll { it.bookUrl == book.bookUrl }
        if (list.isEmpty()) byName.remove(key)
    }
}
