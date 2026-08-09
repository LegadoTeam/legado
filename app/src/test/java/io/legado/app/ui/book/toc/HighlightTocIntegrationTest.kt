package io.legado.app.ui.book.toc

import io.legado.app.data.entities.BookHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HighlightTocIntegrationTest {

    @Test
    fun `current chapter url wins after toc reorder`() {
        val highlight = BookHighlight(
            chapterUrl = "chapter-url",
            chapterIndex = 2
        )

        assertEquals(
            8,
            resolveHighlightChapterIndex(highlight, mapOf("chapter-url" to 8))
        )
        assertNull(resolveHighlightChapterIndex(highlight, emptyMap()))
        assertEquals(
            2,
            resolveHighlightChapterIndex(highlight.copy(chapterUrl = ""), emptyMap())
        )
    }

    @Test
    fun `highlight order uses body positions`() {
        val withTitle = BookHighlight(chapterPos = 14, layoutTitleLength = 10)
        val withoutTitle = BookHighlight(chapterPos = 8, layoutTitleLength = 0)

        assertEquals(4, highlightBodyPosition(withTitle))
        assertEquals(8, highlightBodyPosition(withoutTitle))
        assertTrue(highlightBodyPosition(withTitle) < highlightBodyPosition(withoutTitle))
    }

    @Test
    fun `toc hosts and searches the highlight page`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocActivity.kt"
        ).readText()
        val viewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt"
        ).readText()
        val activityLayout = parseLayout("activity_chapter_list.xml")

        assertEquals(
            1,
            activityLayout.getElementsByTagName("androidx.viewpager.widget.ViewPager").length
        )
        assertTrue(activity.contains("FragmentPagerAdapter"))
        assertTrue(activity.contains("tabLayout.setupWithViewPager(binding.viewPager)"))
        assertTrue(activity.contains("2 -> HighlightFragment()"))
        assertTrue(activity.contains("return 3"))
        assertTrue(activity.contains("viewModel.startHighlightSearch(searchKey)"))
        assertTrue(viewModel.contains("interface HighlightCallBack"))
    }

    @Test
    fun `highlight flow belongs to the current view`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/HighlightFragment.kt"
        ).readText()

        assertTrue(fragment.contains("bookData.observe(viewLifecycleOwner)"))
        assertTrue(fragment.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(fragment.countMatches("highlightJob?.cancel()") >= 2)
        assertTrue(fragment.contains("binding.recyclerView.adapter = null"))
        assertTrue(fragment.contains("clearCallbackIfOwned"))
        assertTrue(fragment.contains("upHighlight(viewModel.searchKey)"))
        assertTrue(fragment.contains("if (!supportsHighlightPosition(book))"))
    }

    @Test
    fun `tab searches do not stack bookmark collectors`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/BookmarkFragment.kt"
        ).readText()

        assertTrue(fragment.contains("bookData.observe(viewLifecycleOwner)"))
        assertTrue(fragment.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(fragment.countMatches("bookmarkJob?.cancel()") >= 2)
        assertTrue(fragment.contains("binding.recyclerView.adapter = null"))
        assertTrue(fragment.contains("upBookmark(viewModel.searchKey)"))
    }

    @Test
    fun `recreated chapter page restores the shared search`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt"
        ).readText()

        assertTrue(fragment.contains("viewModel.searchKey?.takeIf { it.isNotBlank() }"))
        assertTrue(fragment.contains("queryChapterIndexes(book, normalizedSearchKey)"))
    }

    @Test
    fun `recreated chapter page does not stack observers`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt"
        ).readText()
        val eventBusExtensions = projectFile(
            "src/main/java/io/legado/app/utils/EventBusExtensions.kt"
        ).readText()
        val fragmentObserver = eventBusExtensions
            .substringAfter("inline fun <reified EVENT> Fragment.observeEvent(")
            .substringBefore("inline fun <reified EVENT> Fragment.observeEventSticky(")
        val fragmentStickyObserver = eventBusExtensions
            .substringAfter("inline fun <reified EVENT> Fragment.observeEventSticky(")
            .substringBefore("inline fun <reified EVENT> LifecycleService.observeEvent(")

        assertTrue(fragment.contains("bookData.observe(viewLifecycleOwner)"))
        assertTrue(!fragment.contains("observe(this@ChapterListFragment)"))
        assertTrue(fragmentObserver.contains("observe(viewLifecycleOwner, o)"))
        assertTrue(fragmentStickyObserver.contains("observeSticky(this, o)"))
    }

    @Test
    fun `highlight jump waits for current layout coordinates`() {
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/HighlightFragment.kt"
        ).readText()
        val readBook = projectFile(
            "src/main/java/io/legado/app/model/ReadBook.kt"
        ).readText()
        val bookInfo = projectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        ).readText()
        val readViewModel = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt"
        ).readText()

        assertTrue(fragment.contains("EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH"))
        assertTrue(fragment.contains("EXTRA_HIGHLIGHT_ANCHOR_TEXT"))
        assertTrue(fragment.contains("highlight.chapterPosEnd - highlight.chapterPos == it.length"))
        assertTrue(readBook.contains("if (hasPendingHighlightJump()) return"))
        assertTrue(readBook.countMatches("positionReady && !available") >= 2)
        assertTrue(readBook.contains("if (curTextChapter !== textChapter) return false"))
        assertEquals(2, readBook.countMatches("resolvePendingHighlightAnchor(book, textChapter)"))
        assertTrue(readBook.contains("if (!chapter.isCompleted)"))
        assertTrue(readBook.contains("manualHighlightAnchorsVersion"))
        assertTrue(readBook.contains("val cacheResult = textChapter.isCompleted"))
        assertTrue(bookInfo.contains("highlightLayoutTitleLength.takeIf { deferHighlightPosition }"))
        assertTrue(bookInfo.contains("highlightAnchorText.takeIf { deferHighlightPosition }"))
        assertTrue(readViewModel.contains("|| hasHighlightTarget"))
        assertTrue(
            readViewModel.contains(
                "intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH)"
            )
        )
        assertTrue(
            readViewModel.contains(
                "intent.removeExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT)"
            )
        )
        assertTrue(readViewModel.contains("highlightAnchorText = highlightAnchorText"))
        assertTrue(readViewModel.contains("intent.removeExtra(\"index\")"))
        assertTrue(readViewModel.contains("intent.removeExtra(\"chapterPos\")"))
    }

    @Test
    fun `highlight queries use the stable book owner`() {
        val dao = projectFile(
            "src/main/java/io/legado/app/data/dao/BookHighlightDao.kt"
        ).readText()
        val fragment = projectFile(
            "src/main/java/io/legado/app/ui/book/toc/HighlightFragment.kt"
        ).readText()

        assertTrue(dao.contains("where bookUrl = :bookUrl"))
        assertTrue(dao.contains("fun flowByBook(bookUrl: String)"))
        assertTrue(dao.contains("fun flowSearch(bookUrl: String, key: String)"))
        assertTrue(fragment.contains("flowByBook(book.bookUrl)"))
        assertTrue(fragment.contains("flowSearch(book.bookUrl, searchKey)"))
        assertTrue(fragment.contains("getChapterList(book.bookUrl)"))
        assertTrue(fragment.contains("resolveHighlightChapterIndex"))
        assertTrue(!fragment.contains("book.name, book.author"))
    }

    @Test
    fun `highlight item keeps card styling and bindings`() {
        val root = parseLayout("item_highlight.xml")

        assertEquals("androidx.cardview.widget.CardView", root.tagName)
        assertEquals("8dp", root.appAttribute("cardCornerRadius"))
        val fields = root.getElementsByTagName("TextView")
        val ids = (0 until fields.length)
            .map { fields.item(it) as Element }
            .map { it.androidAttribute("id") }
            .toSet()
        assertEquals(
            setOf("@+id/tv_chapter_name", "@+id/tv_book_text", "@+id/tv_note"),
            ids
        )
    }

    private fun parseLayout(name: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/$name"))
            .documentElement

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(appNamespace, name)

    private fun String.countMatches(value: String): Int =
        windowed(value.length).count { it == value }

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"
    }
}
