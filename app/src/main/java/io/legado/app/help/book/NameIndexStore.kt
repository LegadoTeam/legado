package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * One shelf snapshot per batch add so N results do not reload bookDao.all N times.
 * Inserts during the batch are visible to later items.
 */
internal class NameIndexStore(
    private val inner: SearchBookShelfHelp.Store,
) : SearchBookShelfHelp.Store {
    private val snapshot = inner.allBooks()
    private val inserted = arrayListOf<Book>()

    override val minOrder: Int
        get() = inner.minOrder

    override fun getBook(name: String, author: String): Book? = inner.getBook(name, author)

    override fun getBook(bookUrl: String): Book? = inner.getBook(bookUrl)

    override fun getBooksByName(name: String): List<Book> {
        val key = BookAuthorIdentity.canonicalName(name)
        if (key.isEmpty()) return emptyList()
        return (snapshot + inserted).filter { BookAuthorIdentity.canonicalName(it.name) == key }
    }

    override fun allBooks(): List<Book> = snapshot + inserted

    override fun update(book: Book) = inner.update(book)

    override fun insertIgnore(book: Book): Boolean {
        val ok = inner.insertIgnore(book)
        if (ok) inserted.add(book)
        return ok
    }
}
