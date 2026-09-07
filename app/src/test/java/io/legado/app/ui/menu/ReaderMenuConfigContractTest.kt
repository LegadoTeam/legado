package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderMenuConfigContractTest {

    @Test
    fun readerOverflowUsesConfigAndKeepsHiddenActionsReachable() {
        val source = read("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        listOf(
            "override fun onShowActivityOverflowMenu(anchor: View, menu: Menu): Boolean",
            "loadReaderMenuConfig(this)",
            "showReaderOverflowMenu(anchor, menu)",
            "showReaderMoreMenu(anchor, menu)",
            "readerOverflowItemsByKey()",
            "ACTION_READER_MORE",
            "ACTION_READER_CONFIG",
            "menu_reader_all_features)?.isVisible = true",
            "refreshReaderMenu()",
            "invalidateOptionsMenu()"
        ).forEach { expected -> assertTrue("missing $expected", source.contains(expected)) }
        assertFalse(source.contains("androidx.appcompat.widget.PopupMenu"))
    }

    @Test
    fun readerConfigSupportsSelectAllSlideSelectionAndReorder() {
        val source = read(
            "src/main/java/io/legado/app/ui/book/read/config/ReaderMenuConfigDialog.kt"
        )
        listOf(
            "R.id.menu_reader_select_all",
            "R.id.menu_reader_select_none",
            "R.id.menu_reader_reset",
            "DragSelectTouchHelper",
            ".activeSlideSelect()",
            "ItemTouchHelper",
            "SimpleCallback",
            "swapWithinGroup",
            "swapItem(srcPosition, targetPosition)",
            "canDropOver",
            "source.primary == target.primary",
            "regroupAndPersist",
            "setOnUserCheckedChangeListener",
            "ivDrag",
            "startDrag(holder)",
            "event.actionMasked == MotionEvent.ACTION_DOWN",
            "saveReaderMenuConfig"
        ).forEach { expected -> assertTrue("missing $expected", source.contains(expected)) }
        assertTrue(source.contains("val orderedKeys = config.primary + config.more"))
        assertTrue(source.contains("orderedKeys.mapNotNull"))
    }

    @Test
    fun menuAndPreferenceExposeConfigurationEntry() {
        val menu = read("src/main/res/menu/book_read.xml")
        val preference = read("src/main/res/xml/pref_config_read.xml")
        val backupConfig = read("src/main/java/io/legado/app/help/storage/BackupConfig.kt")
        assertTrue(menu.contains("@+id/menu_reader_more"))
        assertTrue(menu.contains("@+id/menu_reader_all_features"))
        assertTrue(preference.contains("android:key=\"customReaderMenu\""))
        assertTrue(backupConfig.contains("PreferKey.readerMenuConfig"))
    }

    private fun read(path: String): String {
        return sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()
    }
}
