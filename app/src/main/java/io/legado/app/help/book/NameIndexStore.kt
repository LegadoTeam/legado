package io.legado.app.help.book

import io.legado.app.data.entities.Book

/** One shelf snapshot per batch add so later items see inserts/updates. */
internal class NameIndexStore(
    private val inner: SearchBookShelfHelp.Store,
) : SearchBookShelfHelp.Store {
    private val books = inner.allBooks().map { it.copy() }.toMutableList()

    override val minOrder: Int
        get() = inner.minOrder

    override fun getBook(name: String, author: String): Book? {
        return books.firstOrNull { it.name == name && it.author == author }
    }

    override fun getBook(bookUrl: String): Book? {
        return books.firstOrNull { it.bookUrl == bookUrl }
    }

    override fun getBooksByName(name: String): List<Book> {
        val key = BookAuthorIdentity.canonicalName(name)
        if (key.isEmpty()) return emptyList()
        return books.filter { BookAuthorIdentity.canonicalName(it.name) == key }
    }

    override fun allBooks(): List<Book> = books.toList()

    override fun update(book: Book) {
        inner.update(book)
        val index = books.indexOfFirst { it.bookUrl == book.bookUrl }
        if (index >= 0) books[index] = book.copy() else books.add(book.copy())
    }

    override fun insertIgnore(book: Book): Boolean {
        val ok = inner.insertIgnore(book)
        if (ok) books.add(book.copy())
        return ok
    }
}
