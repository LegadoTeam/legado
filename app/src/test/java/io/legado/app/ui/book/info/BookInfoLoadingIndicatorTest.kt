package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookInfoLoadingIndicatorTest {

    @Test
    fun `overlapping network loads stay active until the last one finishes`() {
        val changes = mutableListOf<Boolean>()
        val counter = BookInfoNetworkLoadingCounter(changes::add)

        counter.begin()
        counter.begin()
        assertEquals(listOf(true), changes)

        counter.end()
        assertEquals(listOf(true), changes)

        counter.end()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `loading indicator overlays the content in both orientations`() {
        LAYOUTS.forEach { layoutPath ->
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(projectFile(layoutPath))
            val indicators = document.getElementsByTagName(PROGRESS_TAG)

            assertEquals(layoutPath, 1, indicators.length)
            val indicator = indicators.item(0) as Element
            assertEquals("@+id/refresh_progress_bar", indicator.androidAttribute("id"))
            assertEquals("match_parent", indicator.androidAttribute("layout_width"))
            assertEquals("2dp", indicator.androidAttribute("layout_height"))
            assertEquals("top", indicator.androidAttribute("layout_gravity"))

            val parent = indicator.parentNode as Element
            assertEquals("FrameLayout", parent.tagName)
            assertEquals("0dp", parent.androidAttribute("layout_height"))
            assertEquals("1", parent.androidAttribute("layout_weight"))
            assertSame(indicator, parent.elementChildren().last())
        }
    }

    @Test
    fun `activity uses the auto loading color and both web requests are tracked`() {
        val activity = projectFile(ACTIVITY_PATH).readText()
        val viewModel = projectFile(VIEW_MODEL_PATH).readText()

        assertTrue(activity.contains("refreshProgressBar.secondColor = accentColor"))
        assertTrue(activity.contains("refreshProgressBar.isAutoLoading = isLoading"))
        assertTrue(activity.contains("upLoading(viewModel.loadingData.value == true, it)"))
        assertTrue(
            activity.contains(
                "viewModel.chapterListData.value?.let { upLoading(false, it) }",
            ),
        )
        assertEquals(
            2,
            viewModel.lineSequence().count { it.trim() == ".trackNetworkLoading()" },
        )
    }

    @Test
    fun `refresh failures preserve the last successful chapter list`() {
        val viewModel = projectFile(VIEW_MODEL_PATH).readText()
        val refreshSection = viewModel.substringBefore("private fun loadWebFile")
        val webFileSection = viewModel
            .substringAfter("private fun loadWebFile")
            .substringBefore("fun <T> importOrDownloadWebFile")
        val preserveCurrent =
            "chapterListData.postValue(chapterListData.value.orEmpty())"
        val clearChapters = "chapterListData.postValue(emptyList())"

        assertEquals(
            5,
            refreshSection.lineSequence().count { it.trim() == preserveCurrent },
        )
        assertFalse(refreshSection.contains(clearChapters))
        assertEquals(
            2,
            webFileSection.lineSequence().count { it.trim() == clearChapters },
        )
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Element.elementChildren(): List<Element> {
        return (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val PROGRESS_TAG =
            "io.legado.app.ui.widget.anima.RefreshProgressBar"
        private const val ACTIVITY_PATH =
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        private const val VIEW_MODEL_PATH =
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        private val LAYOUTS = listOf(
            "src/main/res/layout/activity_book_info.xml",
            "src/main/res/layout-land/activity_book_info.xml",
        )
    }
}
