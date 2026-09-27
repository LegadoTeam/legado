package io.legado.app.ui.main.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExploreGroupMenuStateTest {

    @Test
    fun `group query parsing preserves exact group names`() {
        assertNull(exploreGroupFromQuery(null))
        assertNull(exploreGroupFromQuery("keyword"))
        assertEquals("", exploreGroupFromQuery("group:"))
        assertEquals("all", exploreGroupFromQuery("group:all"))
        assertEquals("manage", exploreGroupFromQuery("group:manage"))

        assertTrue(isExploreAllQuery(null))
        assertTrue(isExploreAllQuery("keyword"))
        assertFalse(isExploreAllQuery("group:"))
        assertFalse(isExploreAllQuery("group:missing"))
    }

    @Test
    fun `only existing groups remain selected`() {
        val groups = linkedSetOf("all", "manage", "novel")

        assertNull(selectedExploreGroup("keyword", groups))
        assertNull(selectedExploreGroup("group:missing", groups))
        assertEquals("all", selectedExploreGroup("group:all", groups))
        assertEquals("manage", selectedExploreGroup("group:manage", groups))
        assertEquals("novel", selectedExploreGroup("group:novel", groups))
    }

    @Test
    fun `fragment wires all checked and missing group fallback states`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt"
        )
        val log = readProjectFile("src/main/assets/updateLog.md")

        listOf(
            "upGroupsMenu(resetMissingGroup = true)",
            "R.id.menu_group_all",
            "R.string.all_source",
            "menu.setGroupCheckable(R.id.menu_group_text, true, true)",
            "isExploreAllQuery(query)",
            "searchView.setQuery(\"\", false)",
            "item.isChecked = true"
        ).forEach { expected ->
            assertTrue("ExploreFragment should contain $expected", source.contains(expected))
        }
        val updateChecks = source.substringAfter("private fun updateGroupsMenuChecks")
            .substringBefore("override val scope")
        val nonExclusive = updateChecks.indexOf(
            "menu.setGroupCheckable(R.id.menu_group_text, true, false)"
        )
        val checkedAssignment = updateChecks.indexOf("item.isChecked =")
        val exclusive = updateChecks.indexOf(
            "menu.setGroupCheckable(R.id.menu_group_text, true, true)"
        )
        assertTrue(nonExclusive in 0 until checkedAssignment)
        assertTrue(exclusive > checkedAssignment)
        assertTrue(log.contains("**2026/07/25**"))
        assertTrue(log.contains("发现页书源分组菜单增加全部书源选项和当前分组勾选"))
    }

    @Test
    fun `discover toolbar opens book source management`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/main/explore/ExploreFragment.kt"
        )
        val menu = readProjectFile("src/main/res/menu/main_explore.xml")

        assertTrue(source.contains("item.itemId == R.id.menu_source_manage"))
        assertTrue(source.contains("startActivity<BookSourceActivity>()"))
        assertTrue(menu.contains("android:id=\"@+id/menu_source_manage\""))
        assertTrue(menu.contains("android:title=\"@string/book_source_manage\""))
        assertTrue(menu.contains("android:icon=\"@drawable/ic_settings\""))
        val sourceManageItem = menu.substringAfter("android:id=\"@+id/menu_source_manage\"")
            .substringBefore("/>")
        assertTrue(sourceManageItem.contains("app:showAsAction=\"always\""))
    }

    private fun readProjectFile(path: String): String =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
