package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Issue1008MenuContractTest {

    @Test
    fun sourceIconsAndRssOverflowFollowReportedMenuContract() {
        assertItemIcon(
            "src/main/res/menu/source_login.xml",
            "menu_show_login_header",
            "ic_add_online"
        )
        assertItemIcon(
            "src/main/res/menu/book_source.xml",
            "menu_group_sources_by_domain",
            "ic_add_online"
        )

        val rssMenu = readProjectFile("src/main/res/menu/rss_read.xml")
        val aloud = itemBlock(rssMenu, "menu_aloud")
        assertFalse(aloud.contains("android:icon="))
        assertContains("rss_read.xml", aloud, """app:showAsAction="never"""")

        val rssActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt"
        )
        assertContains(
            "ReadRssActivity.kt",
            rssActivity,
            "class ReadRssActivity :"
        )
        assertContains(
            "ReadRssActivity.kt",
            rssActivity,
            "showOpenMenuIcon = false"
        )
    }

    private fun assertItemIcon(path: String, id: String, drawable: String) {
        val item = itemBlock(readProjectFile(path), id)
        assertContains(path, item, """android:icon="@drawable/$drawable"""")
    }

    private fun itemBlock(source: String, id: String): String {
        val marker = """android:id="@+id/$id""""
        val start = source.indexOf(marker)
        assertTrue("$id should exist", start >= 0)
        val end = source.indexOf("/>", start)
        assertTrue("$id should have a complete item", end > start)
        return source.substring(start, end)
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
