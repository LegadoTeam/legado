package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CachePopupActionMigrationTest {

    @Test
    fun `cache long press uses the shared vertical popup action menu`() {
        val source = readProjectFile(CACHE_ACTIVITY)

        assertFalse(source.contains("import androidx.appcompat.widget.PopupMenu"))
        assertFalse(source.contains("PopupMenu.OnMenuItemClickListener"))
        assertFalse(source.contains("PopupMenu(this, it)"))
        assertFalse(source.contains("applyOpenTint"))
        assertFalse(source.contains("inflate(R.menu.book_cache_download)"))
        assertFalse(
            "Legacy cache menu resource should be removed",
            sequenceOf(File(LEGACY_MENU), File("app/$LEGACY_MENU")).any(File::isFile)
        )
        assertContains(source, "showDownloadMenu(it)")
        assertContains(source, "popupActionMenu(this)")
        assertOrdered(
            source,
            "item(getString(R.string.menu_download_after), \"download_after\")",
            "item(getString(R.string.menu_download_all), \"download_all\")"
        )
        assertContains(source, "\"download_after\" -> startDownloadAfterCurrent()")
        assertContains(source, "\"download_all\" -> startDownloadAll()")
    }

    @Test
    fun `cache download ranges and stop behavior remain unchanged`() {
        val source = readProjectFile(CACHE_ACTIVITY)
        val afterCurrent = section(
            source,
            "private fun startDownloadAfterCurrent()",
            "private fun startDownloadAll()"
        )
        val all = section(
            source,
            "private fun startDownloadAll()",
            "override fun onMenuOpened"
        )

        assertContains(source, "R.id.menu_download -> startDownloadAfterCurrent()")
        assertFalse(source.contains("R.id.menu_download_after"))
        assertFalse(source.contains("R.id.menu_download_all"))
        assertContains(afterCurrent, "sureCacheBook")
        assertContains(afterCurrent, "adapter.getItems().forEach")
        assertContains(afterCurrent, "book.durChapterIndex")
        assertContains(afterCurrent, "book.lastChapterIndex")
        assertContains(afterCurrent, "CacheBook.stop(this@CacheActivity)")
        assertContains(all, "sureCacheBook")
        assertContains(all, "adapter.getItems().forEach")
        assertTrue(Regex("""book,\s+0,\s+book\.lastChapterIndex""").containsMatchIn(all))
        assertContains(all, "CacheBook.stop(this@CacheActivity)")
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("CacheActivity.kt should contain $start before $end", startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun assertOrdered(source: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("CacheActivity.kt should contain $snippet after the previous action", current > previous)
            previous = current
        }
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("CacheActivity.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()

    private companion object {
        const val CACHE_ACTIVITY = "src/main/java/io/legado/app/ui/book/cache/CacheActivity.kt"
        const val LEGACY_MENU = "src/main/res/menu/book_cache_download.xml"
    }
}
