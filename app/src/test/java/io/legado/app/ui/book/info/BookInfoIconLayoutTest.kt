package io.legado.app.ui.book.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BookInfoIconLayoutTest {

    @Test
    fun metadataIconsUseFixedSizeAndLogicalSpacing() {
        LAYOUTS.forEach { path ->
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(projectFile(path))
            val icons = document.getElementsByTagName("ImageView").asElements()
                .filter { it.androidAttribute("src") in ICON_SOURCES }
            val labels = document.getElementsByTagName("TextView").asElements()
                .filter { it.androidAttribute("id") in LABEL_IDS }

            assertEquals(ICON_SOURCES.size, icons.size)
            assertEquals(ICON_SOURCES, icons.map { it.androidAttribute("src") }.toSet())
            icons.forEach { icon ->
                assertEquals("18dp", icon.androidAttribute("layout_width"))
                assertEquals("18dp", icon.androidAttribute("layout_height"))
                assertEquals("6dp", icon.androidAttribute("layout_marginEnd"))
                assertFalse(icon.hasAttributeNS(ANDROID_NS, "paddingRight"))
            }
            assertEquals(LABEL_IDS.size, labels.size)
            assertEquals(LABEL_IDS, labels.map { it.androidAttribute("id") }.toSet())
            labels.forEach { label ->
                assertFalse(label.hasAttributeNS(ANDROID_NS, "paddingStart"))
                assertFalse(label.hasAttributeNS(ANDROID_NS, "paddingRight"))
                if (path.contains("/layout/")) {
                    assertEquals("6dp", label.androidAttribute("paddingEnd"))
                }
            }
        }
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).map { item(it) as Element }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NS, name)

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private val LAYOUTS = listOf(
            "src/main/res/layout/activity_book_info.xml",
            "src/main/res/layout-land/activity_book_info.xml",
        )
        private val ICON_SOURCES = setOf(
            "@drawable/ic_author",
            "@drawable/ic_web_outline",
            "@drawable/ic_book_last",
            "@drawable/ic_groups",
            "@drawable/ic_folder_open",
        )
        private val LABEL_IDS = setOf(
            "@+id/tv_author",
            "@+id/tv_origin",
            "@+id/tv_lasted",
            "@+id/tv_group",
            "@+id/tv_toc",
        )
    }
}
