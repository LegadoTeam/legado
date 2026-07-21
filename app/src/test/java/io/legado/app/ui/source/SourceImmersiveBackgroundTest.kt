package io.legado.app.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SourceImmersiveBackgroundTest {

    @Test
    fun sourceEditorsMatchTitleBarTransparency() {
        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt").readText()
        assertTrue(titleBar.contains("if (AppConfig.isEInkMode)"))
        assertTrue(titleBar.contains("else if (!opaque && context.transparentNavBar)"))

        listOf(
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
            "src/main/java/io/legado/app/ui/rss/source/edit/RssSourceEditActivity.kt",
        ).forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(source.contains("transparentNavBar && !AppConfig.isEInkMode"))
            assertTrue(source.contains("if (transparentBar) Color.TRANSPARENT else backgroundColor"))
            assertTrue(source.contains("if (transparentBar) binding.tabLayout.elevation = 0f"))
        }
    }

    @Test
    fun sourceDebugHelpPanelsHideLogsAndMatchTitleBarTransparency() {
        listOf(
            "src/main/java/io/legado/app/ui/book/source/debug/BookSourceDebugActivity.kt",
            "src/main/java/io/legado/app/ui/rss/source/debug/RssSourceDebugActivity.kt",
        ).forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(source.contains("transparentNavBar && !AppConfig.isEInkMode"))
            assertTrue(source.contains("Color.TRANSPARENT else backgroundColor"))
            assertTrue(source.contains("binding.recyclerView.visibility = View.GONE"))
            assertTrue(source.contains("binding.recyclerView.visibility = View.VISIBLE"))
            assertTrue(source.contains("loading = true"))
            assertTrue(source.contains("loading = false"))
            assertTrue(
                Regex("private fun startSearch\\(key: String\\) \\{\\s*openOrCloseHelp\\(false\\)")
                    .containsMatchIn(source)
            )
            assertTrue(
                source.contains(
                    "if (open || !loading) binding.rotateLoading.gone() " +
                        "else binding.rotateLoading.visible()"
                )
            )
        }
    }

    @Test
    fun debugHelpBackgroundBelongsToScrollablePanel() {
        listOf("activity_source_debug.xml", "activity_rss_source_debug.xml").forEach { layout ->
            val help = viewById(layout, "help")
            assertEquals("@color/background", help.getAttribute("android:background"))
            val content = (0 until help.childNodes.length)
                .map { help.childNodes.item(it) }
                .filterIsInstance<Element>()
                .single()
            assertFalse(content.hasAttribute("android:background"))
        }
    }

    private fun viewById(layout: String, id: String): Element {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(projectFile("src/main/res/layout/$layout"))
        return document.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("android:id") == "@+id/$id" }
        }
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
