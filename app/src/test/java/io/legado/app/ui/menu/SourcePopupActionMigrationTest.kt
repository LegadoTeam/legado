package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcePopupActionMigrationTest {

    @Test
    fun `source related row menus use the shared vertical builder`() {
        sourceMenuFiles.forEach { path ->
            val source = readProjectFile(path)
            assertFalse("$path should not import platform PopupMenu", source.contains("import android.widget.PopupMenu"))
            assertFalse("$path should not import AppCompat PopupMenu", source.contains("import androidx.appcompat.widget.PopupMenu"))
            assertContains(path, source, "popupActionMenu(context)")
        }
        assertContains(BOOK_SOURCE, readProjectFile(BOOK_SOURCE), "danger(\"delete\")")
        assertContains(CHANGE_BOOK, readProjectFile(CHANGE_BOOK), "danger(\"deleteSource\")")
        assertContains(CHANGE_CHAPTER, readProjectFile(CHANGE_CHAPTER), "danger(\"deleteSource\")")
        assertContains(EXPLORE, readProjectFile(EXPLORE), "danger(\"delete\")")
        assertContains(RSS, readProjectFile(RSS), "danger(\"delete\")")
        legacyMenuFiles.forEach { path ->
            assertFalse(
                "$path should be removed",
                sequenceOf(File(path), File("app/$path")).any(File::isFile)
            )
        }
    }

    @Test
    fun `dynamic source menu entries keep their visibility and labels`() {
        val bookSource = readProjectFile(BOOK_SOURCE)
        listOf(
            "val defaultOrder = callBack.sort == BookSourceSort.Default",
            "item(context.getString(R.string.login), \"login\", source.hasLoginUrl)",
            "if (source.enabledExplore) R.string.disable_explore else R.string.enable_explore",
            "source.hasExploreUrl"
        ).forEach { assertContains(BOOK_SOURCE, bookSource, it) }

        assertContains(
            EXPLORE,
            readProjectFile(EXPLORE),
            "item(context.getString(R.string.login), \"login\", source.hasLoginUrl)"
        )
        assertContains(
            RSS,
            readProjectFile(RSS),
            "item(context.getString(R.string.login), \"login\", !rssSource.loginUrl.isNullOrBlank())"
        )
    }

    @Test
    fun `source menu labels keep their previous order`() {
        assertOrdered(
            BOOK_SOURCE,
            "R.string.to_top",
            "R.string.to_bottom",
            "R.string.login",
            "R.string.search",
            "R.string.debug",
            "R.string.delete",
            "R.string.disable_explore else R.string.enable_explore"
        )
        listOf(CHANGE_BOOK, CHANGE_CHAPTER).forEach { path ->
            assertOrdered(
                path,
                "R.string.to_top",
                "R.string.to_bottom",
                "R.string.edit_source",
                "R.string.disable_source",
                "R.string.delete_source"
            )
        }
        assertOrdered(
            EXPLORE,
            "R.string.edit",
            "R.string.to_top",
            "R.string.login",
            "R.string.search",
            "R.string.refresh",
            "R.string.delete"
        )
        assertOrdered(
            RSS,
            "R.string.edit",
            "R.string.to_top",
            "R.string.login",
            "R.string.disable_source",
            "R.string.delete"
        )
    }

    @Test
    fun `source menu callbacks and delete side effects are preserved`() {
        assertActions(
            BOOK_SOURCE,
            "\"top\" -> callBack.toTop(source)",
            "\"bottom\" -> callBack.toBottom(source)",
            "\"login\" -> context.startActivity<SourceLoginActivity>",
            "\"search\" -> callBack.searchBook(source)",
            "\"debug\" -> callBack.debug(source)",
            "\"delete\" -> {",
            "callBack.del(source)",
            "selected.remove(source)",
            "\"toggleExplore\" -> callBack.enableExplore(!source.enabledExplore, source)"
        )
        assertActions(
            CHANGE_BOOK,
            "\"topSource\" -> callBack.topSource(searchBook)",
            "\"bottomSource\" -> callBack.bottomSource(searchBook)",
            "\"editSource\" -> callBack.editSource(searchBook)",
            "\"disableSource\" -> callBack.disableSource(searchBook)",
            "\"deleteSource\" -> context.alert(R.string.draw)",
            "callBack.deleteSource(searchBook)",
            "updateItems(0, itemCount, listOf<Int>())"
        )
        assertActions(
            CHANGE_CHAPTER,
            "\"topSource\" -> callBack.topSource(searchBook)",
            "\"bottomSource\" -> callBack.bottomSource(searchBook)",
            "\"editSource\" -> callBack.editSource(searchBook)",
            "\"disableSource\" -> callBack.disableSource(searchBook)",
            "\"deleteSource\" -> {",
            "callBack.deleteSource(searchBook)",
            "updateItems(0, itemCount, listOf<Int>())"
        )
        assertActions(
            EXPLORE,
            "\"edit\" -> callBack.editSource(source.bookSourceUrl)",
            "\"top\" -> callBack.toTop(source)",
            "\"search\" -> callBack.searchBook(source)",
            "\"login\" -> context.startActivity<SourceLoginActivity>",
            "\"refresh\" -> refreshExplore(source, position, binding)",
            "\"delete\" -> callBack.deleteSource(source)"
        )
        assertActions(
            RSS,
            "\"edit\" -> callBack.edit(rssSource)",
            "\"top\" -> callBack.toTop(rssSource)",
            "\"login\" -> callBack.login(rssSource)",
            "\"disable\" -> callBack.disable(rssSource)",
            "\"delete\" -> callBack.del(rssSource)"
        )
    }

    private fun assertActions(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        expected.forEach { assertContains(path, source, it) }
    }

    private fun assertOrdered(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        var previous = -1
        expected.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("$path should contain $snippet after the previous item", current > previous)
            previous = current
        }
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

    private companion object {
        const val BOOK_SOURCE = "src/main/java/io/legado/app/ui/book/source/manage/BookSourceAdapter.kt"
        const val CHANGE_BOOK = "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceAdapter.kt"
        const val CHANGE_CHAPTER = "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceAdapter.kt"
        const val EXPLORE = "src/main/java/io/legado/app/ui/main/explore/ExploreAdapter.kt"
        const val RSS = "src/main/java/io/legado/app/ui/main/rss/RssAdapter.kt"
        val sourceMenuFiles = listOf(BOOK_SOURCE, CHANGE_BOOK, CHANGE_CHAPTER, EXPLORE, RSS)
        val legacyMenuFiles = listOf(
            "src/main/res/menu/book_source_item.xml",
            "src/main/res/menu/change_source_item.xml",
            "src/main/res/menu/explore_item.xml",
            "src/main/res/menu/rss_main_item.xml"
        )
    }
}
