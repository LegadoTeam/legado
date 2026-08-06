package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量正文上下文的章节身份约定。
 *
 * 目录允许多章共用同一 url(靠序号/标题/tag 区分),缓存文件名按 index + 标题生成,
 * 所以身份判定必须落在 index 上。按 url 认章会把正文写进错误章节,
 * 并让同 url 的其余章节被误判为已完成。
 */
class BatchContentContextTest {

    private val bookSource = BookSource(
        bookSourceUrl = "https://example.com",
        bookSourceName = "测试源"
    )

    private val book = Book(
        bookUrl = "https://example.com/book/1",
        tocUrl = "https://example.com/book/1/toc",
        origin = "https://example.com"
    )

    private fun chapter(index: Int, url: String, title: String) = BookChapter(
        url = url,
        title = title,
        index = index,
        bookUrl = book.bookUrl,
        baseUrl = book.tocUrl
    )

    private fun contextOf(vararg chapters: BookChapter) =
        BatchContentContext(bookSource, book, chapters.toList())

    @Test
    fun `same url chapters resolve to distinct chapters in order`() {
        val first = chapter(1, "/read/1.html", "第一章")
        val second = chapter(2, "/read/1.html", "第二章")
        val context = contextOf(first, second)

        val resolvedFirst = context.resolveChapter("/read/1.html")
        assertSame(first, resolvedFirst)

        //第一章回存后,同一 url 必须让位给还没回存的第二章
        context.markSaved(resolvedFirst!!)
        assertSame(second, context.resolveChapter("/read/1.html"))
    }

    @Test
    fun `saving one chapter never marks its url twin as done`() {
        val first = chapter(1, "/read/1.html", "第一章")
        val second = chapter(2, "/read/1.html", "第二章")
        val context = contextOf(first, second)

        context.markSaved(first)

        assertTrue(context.isSaved(first))
        assertFalse("同 url 的另一章不能被判为已完成", context.isSaved(second))
        assertEquals(1, context.savedCount())
        assertEquals(listOf(second), context.missingChapters())
    }

    @Test
    fun `all url twins saved then further resolve fails instead of overwriting`() {
        val first = chapter(1, "/read/1.html", "第一章")
        val second = chapter(2, "/read/1.html", "第二章")
        val context = contextOf(first, second)

        context.markSaved(context.resolveChapter("/read/1.html")!!)
        context.markSaved(context.resolveChapter("/read/1.html")!!)

        assertEquals(2, context.savedCount())
        assertTrue(context.missingChapters().isEmpty())
        assertNull("多回存一次应报错而不是覆盖", context.resolveChapter("/read/1.html"))
    }

    @Test
    fun `chapter object resolves exactly even when urls collide`() {
        val first = chapter(1, "/read/1.html", "第一章")
        val second = chapter(2, "/read/1.html", "第二章")
        val context = contextOf(first, second)

        //先回存第二章,再传对象取第二章仍应命中它自己
        assertSame(second, context.resolveChapter(second))
        context.markSaved(second)
        assertSame(second, context.resolveChapter(second))
        assertSame(first, context.resolveChapter(first))
    }

    @Test
    fun `unique url stays overwritable`() {
        val only = chapter(1, "/read/1.html", "第一章")
        val other = chapter(2, "/read/2.html", "第二章")
        val context = contextOf(only, other)

        assertSame(only, context.resolveChapter("/read/1.html"))
        context.markSaved(only)
        //url 唯一时重复回存视为覆盖更新,不能因为已保存就解析失败
        assertSame(only, context.resolveChapter("/read/1.html"))
    }

    @Test
    fun `absolute url resolves to the relative chapter`() {
        val relative = chapter(1, "/read/1.html", "第一章")
        val context = contextOf(relative)

        assertSame(relative, context.resolveChapter("https://example.com/read/1.html"))
    }

    @Test
    fun `numbers are rejected so array positions cannot silently pick a chapter`() {
        val first = chapter(7, "/read/7.html", "第七章")
        val context = contextOf(first)

        //书源循环里传数组下标和传 chapter.index 无法区分,一律拒绝
        assertNull(context.resolveChapter(0))
        assertNull(context.resolveChapter(7))
        assertNull(context.resolveChapter(null))
    }

    @Test
    fun `unknown url resolves to null`() {
        val context = contextOf(chapter(1, "/read/1.html", "第一章"))

        assertNull(context.resolveChapter("/read/999.html"))
        assertNull(context.resolveChapter(""))
    }

    @Test
    fun `missing chapters keep batch order and drop only saved ones`() {
        val first = chapter(1, "/read/1.html", "第一章")
        val second = chapter(2, "/read/2.html", "第二章")
        val third = chapter(3, "/read/3.html", "第三章")
        val context = contextOf(first, second, third)

        context.markSaved(second)

        assertEquals(listOf(first, third), context.missingChapters())
    }

    @Test
    fun `content replace is a no-op without a replace rule`() {
        val only = chapter(1, "/read/1.html", "第一章")
        val context = contextOf(only)

        val content = "正文内容\n第二段"
        assertSame(content, context.applyContentReplace(only, content))
    }
}
