package io.legado.app.model.jsSource

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceMarshallerTest {

    private val textSource = BookSource(
        bookSourceUrl = "https://source.example",
        bookSourceName = "文本源",
        bookSourceType = 0,
        customOrder = 7,
    )

    @Test
    fun `accepts only known book type bits`() {
        assertEquals(BookType.text, JsSourceMarshaller.validateBookType(BookType.text))
        assertEquals(BookType.audio, JsSourceMarshaller.validateBookType(BookType.audio))
        assertNull(JsSourceMarshaller.validateBookType(0))
        assertNull(JsSourceMarshaller.validateBookType(1))
        assertNull(JsSourceMarshaller.validateBookType(BookType.updateError))
    }

    @Test
    fun `injects source identity into search results`() {
        val books = JsSourceMarshaller.parseSearchBooks(
            """[{"name":"书名","bookUrl":"https://source.example/book","origin":"ignored"}]""",
            textSource,
        )

        assertEquals(1, books.size)
        assertEquals(textSource.bookSourceUrl, books[0].origin)
        assertEquals(textSource.bookSourceName, books[0].originName)
        assertEquals(textSource.customOrder, books[0].originOrder)
        assertEquals(BookType.text, books[0].type)
    }

    @Test
    fun `drops search entries missing required fields`() {
        val books = JsSourceMarshaller.parseSearchBooks(
            """[{"name":"缺地址"},{"bookUrl":"缺名称"},{"name":"有效","bookUrl":"u"}]""",
            textSource,
        )

        assertEquals(1, books.size)
        assertEquals("有效", books[0].name)
    }

    @Test
    fun `merges only allowed detail fields`() {
        val book = Book(bookUrl = "keep", name = "原名", author = "原作者")
        book.durChapterIndex = 5

        JsSourceMarshaller.mergeBookInfo(
            book,
            """{"name":"新名","intro":"简介","bookUrl":"ignored","durChapterIndex":0}""",
            textSource,
            canReName = false,
        )

        assertEquals("原名", book.name)
        assertEquals("简介", book.intro)
        assertEquals("keep", book.bookUrl)
        assertEquals(5, book.durChapterIndex)
        assertNull(book.customTag)
    }

    @Test
    fun `accepts object variable in book info`() {
        val book = Book(bookUrl = "https://source.example/book")

        JsSourceMarshaller.mergeBookInfo(
            book,
            """{"variable":{"token":"abc"}}""",
            textSource,
            canReName = true,
        )

        assertEquals("abc", book.variableMap["token"])
    }

    @Test
    fun `merges and resolves download urls`() {
        val book = Book(bookUrl = "https://source.example/book/1")

        JsSourceMarshaller.mergeBookInfo(
            book,
            """{"downloadUrls":["/download/1.txt","https://cdn.example/2.epub","/download/1.txt","javascript:bad",""]}""",
            textSource,
            canReName = true,
        )

        assertEquals(
            listOf("https://source.example/download/1.txt", "https://cdn.example/2.epub"),
            book.downloadUrls,
        )
    }

    @Test
    fun `ignores invalid download urls`() {
        val book = Book(bookUrl = "https://source.example/book/1")

        JsSourceMarshaller.mergeBookInfo(
            book,
            """{"downloadUrls":"not-array"}""",
            textSource,
            canReName = true,
        )
        assertNull(book.downloadUrls)

        JsSourceMarshaller.mergeBookInfo(
            book,
            """{"downloadUrls":[{"bad":true}]}""",
            textSource,
            canReName = true,
        )
        assertNull(book.downloadUrls)
    }

    @Test
    fun `injects chapter identity and resolves relative urls`() {
        val book = Book(bookUrl = "https://source.example/book")
        book.tocUrl = "https://source.example/toc/index"

        val chapters = JsSourceMarshaller.parseChapters(
            """[
                {"title":"第1章","url":"/read/1"},
                {"title":"第2章","url":"https://other.example/read/2","isVip":true},
                {"url":"缺标题"}
            ]""".trimIndent(),
            book,
            textSource,
        )

        assertEquals(2, chapters.size)
        assertEquals("https://source.example/read/1", chapters[0].url)
        assertEquals(book.bookUrl, chapters[0].bookUrl)
        assertEquals(book.tocUrl, chapters[0].baseUrl)
        assertEquals(0, chapters[0].index)
        assertTrue(chapters[1].isVip)
    }
}
