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
            assertTrue(source.contains("listOf(binding.tabLayout, binding.fieldNav)"))
            assertTrue(source.contains("if (transparentBar) Color.TRANSPARENT else backgroundColor"))
            assertTrue(source.contains("if (transparentBar) tabs.elevation = 0f"))
        }
    }

    @Test
    fun transparentBottomBarsUseVisibleBackgroundForContrast() {
        mapOf(
            "src/main/java/io/legado/app/ui/widget/SelectActionBar.kt" to
                "if (context.transparentNavBar) context.backgroundColor else context.bottomBackground",
            "src/main/java/io/legado/app/ui/widget/text/AccentStrokeTextView.kt" to
                "if (context.transparentNavBar) context.backgroundColor else context.bottomBackground",
            "src/main/java/io/legado/app/lib/theme/view/ThemeBottomNavigationVIew.kt" to
                "if (transparentNavBar) context.backgroundColor else context.bottomBackground",
        ).forEach { (path, expression) ->
            assertTrue(projectFile(path).readText().contains(expression))
        }
    }

    @Test
    fun transparentTitleBarsUseVisibleBackgroundForContrast() {
        val theme = projectFile("src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt")
            .readText()
        assertTrue(
            theme.contains("toolbarBackgroundColor(transparentBar, primaryColor, backgroundColor)")
        )

        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt")
            .readText()
        assertTrue(titleBar.contains("automaticForeground = themeMode == 0 && !opaque"))
        assertTrue(titleBar.contains("!AppConfig.isEInkMode && background?.alpha == 0"))
        assertTrue(titleBar.contains("context.getToolbarTextColor(true)"))
        assertTrue(titleBar.contains("toolbar.navigationIcon?.colorFilter = colorFilter"))
        assertTrue(titleBar.contains("toolbar.overflowIcon?.colorFilter = colorFilter"))
        assertTrue(titleBar.contains("findViewById<SearchView>(R.id.search_view)?.applyTint(color)"))
        assertTrue(titleBar.contains("findViewById<TabLayout>(R.id.tab_layout)"))
        assertTrue(titleBar.contains("setTabTextColors(tabUnselectedColor, color)"))
        assertTrue(titleBar.contains("R.color.md_light_secondary"))
        assertTrue(titleBar.contains("R.color.md_dark_secondary"))
        assertTrue(titleBar.contains("toolbar.menu.forEach { item ->"))

        val menu = projectFile("src/main/java/io/legado/app/utils/MenuExtensions.kt").readText()
        assertTrue(menu.contains("(impl.actionView as? SearchView)?.applyTint(tintColor)"))

        val activity = projectFile("src/main/java/io/legado/app/base/BaseActivity.kt").readText()
        val fragment = projectFile("src/main/java/io/legado/app/base/BaseFragment.kt").readText()
        assertTrue(
            activity.contains("transparentBar = titleBar?.usesTransparentForeground == true")
        )
        assertTrue(fragment.contains("titleBar?.post { titleBar.applyForegroundColor() }"))
    }

    @Test
    fun navigationBarsDisablePlatformContrastScrimOnAndroidQ() {
        val source = projectFile("src/main/java/io/legado/app/utils/ActivityExtensions.kt")
            .readText()
            .substringAfter("fun Activity.setNavigationBarColorAuto")
            .substringBefore("\nfun ")
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)"))
        assertTrue(source.contains("window.isNavigationBarContrastEnforced = false"))
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
