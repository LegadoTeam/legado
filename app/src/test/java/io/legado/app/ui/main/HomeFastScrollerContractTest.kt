package io.legado.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HomeFastScrollerContractTest {

    @Test
    fun `folder bookshelf and discovery use independent fast scrollers`() {
        assertFastScrollerLayout(
            layout = "fragment_bookshelf2.xml",
            recyclerId = "rv_bookshelf",
            constraintTarget = "refresh_layout",
        )
        assertFastScrollerLayout(
            layout = "fragment_explore.xml",
            recyclerId = "rv_find",
            constraintTarget = "rv_find",
        )

        val bookshelf = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/style2/BookshelfFragment2.kt",
        ).readText()
        assertInOrder(
            bookshelf.sourceSection(
                "private fun initRecyclerView() {",
                "private fun upFastScrollerBar() {",
            ),
            "fastScroller.attachRecyclerView(binding.rvBookshelf)",
            "upFastScrollerBar()",
        )
        assertFastScrollerToggle(
            bookshelf.sourceSection(
                "private fun upFastScrollerBar() {",
                "override fun upGroup(data: List<BookGroup>)",
            ),
            "AppConfig.showBookshelfFastScroller",
            "binding.rvBookshelf",
        )
        assertInOrder(
            bookshelf.sourceSection(
                "observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {",
                "override fun onDestroyView() {",
            ),
            "booksAdapter.notifyDataSetChanged()",
            "upFastScrollerBar()",
        )
        assertDetachBeforeSuper(
            bookshelf.sourceSection("override fun onDestroyView() {", "\n}"),
        )

        val explore = projectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt",
        ).readText()
        assertInOrder(
            explore.sourceSection(
                "private fun initRecyclerView() {",
                "private fun upFastScrollerBar() {",
            ),
            "fastScroller.attachRecyclerView(binding.rvFind)",
            "upFastScrollerBar()",
        )
        assertFastScrollerToggle(
            explore.sourceSection(
                "private fun upFastScrollerBar() {",
                "private fun initGroupData() {",
            ),
            "AppConfig.showDiscoveryFastScroller",
            "binding.rvFind",
        )
        assertInOrder(
            explore.sourceSection("override fun onResume() {", "override fun onPause() {"),
            "super.onResume()",
            "upFastScrollerBar()",
        )
        assertDetachBeforeSuper(
            explore.sourceSection(
                "override fun onDestroyView() {",
                "private fun upGroupsMenu(",
            ),
        )

        val config = projectFile(
            "src/main/java/io/legado/app/help/config/AppConfig.kt",
        ).readText()
        assertTrue(
            config.contains(
                "get() = appCtx.getPrefBoolean(PreferKey.showDiscoveryFastScroller, false)",
            ),
        )

        val preferences = parseProjectXml("src/main/res/xml/pref_config_other.xml")
        val discoverySwitch = preferences.findElementByAndroidKey("showDiscoveryFastScroller")
        assertEquals("false", discoverySwitch.androidAttribute("defaultValue"))
        assertEquals(
            "@string/show_discovery_fast_scroller",
            discoverySwitch.androidAttribute("title"),
        )
    }

    private fun assertFastScrollerLayout(
        layout: String,
        recyclerId: String,
        constraintTarget: String,
    ) {
        val document = parseProjectXml("src/main/res/layout/$layout")
        val recycler = document.findElementByAndroidId(recyclerId)
        val fastScroller = document.findElementByAndroidId("fast_scroller")

        assertEquals(
            "io.legado.app.ui.widget.recycler.RecyclerViewAtPager2",
            recycler.tagName,
        )
        assertEquals(
            "io.legado.app.ui.widget.recycler.scroller.FastScroller",
            fastScroller.tagName,
        )
        assertEquals("0dp", fastScroller.androidAttribute("layout_height"))
        assertEquals(
            "@dimen/fastscroll_scrollbar_margin_top",
            fastScroller.androidAttribute("layout_marginTop"),
        )
        assertEquals(
            "@dimen/fastscroll_scrollbar_margin_bottom",
            fastScroller.androidAttribute("layout_marginBottom"),
        )
        listOf(
            "layout_constraintTop_toTopOf",
            "layout_constraintBottom_toBottomOf",
            "layout_constraintEnd_toEndOf",
        ).forEach { attribute ->
            assertEquals("@id/$constraintTarget", fastScroller.appAttribute(attribute))
        }
    }

    private fun parseProjectXml(pathInApp: String): Document {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun String.sourceSection(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        assertTrue("Missing source marker: $startMarker", start >= 0)
        assertTrue("Missing source marker after $startMarker: $endMarker", end > start)
        return substring(start, end)
    }

    private fun assertFastScrollerToggle(
        source: String,
        config: String,
        recycler: String,
    ) {
        assertInOrder(
            source,
            "val show = $config",
            "binding.fastScroller.isEnabled = show",
            "$recycler.scrollBarSize = if (show) {",
            "0",
            "ViewConfiguration.get(requireContext()).scaledScrollBarSize",
        )
    }

    private fun assertDetachBeforeSuper(source: String) {
        assertInOrder(
            source,
            "fastScroller.detachRecyclerView()",
            "super.onDestroyView()",
        )
    }

    private fun assertInOrder(source: String, vararg values: String) {
        var previous = -1
        values.forEach { value ->
            val index = source.indexOf(value, previous + 1)
            assertTrue("Missing or out-of-order source: $value", index > previous)
            previous = index
        }
    }

    private fun Document.findElementByAndroidId(id: String): Element {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length)
            .asSequence()
            .map { nodes.item(it) as Element }
            .single { it.androidAttribute("id") == "@+id/$id" }
    }

    private fun Document.findElementByAndroidKey(key: String): Element {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length)
            .asSequence()
            .map { nodes.item(it) as Element }
            .single { it.androidAttribute("key") == key }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String =
        getAttributeNS(APP_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
