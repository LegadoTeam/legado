package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Issue1046ReadTitleAdditionTest {

    @Test
    fun `title only preference is persisted with a disabled legacy default`() {
        val preferKey = source("app/src/main/java/io/legado/app/constant/PreferKey.kt")
        val appConfig = source("app/src/main/java/io/legado/app/help/config/AppConfig.kt")
        val preferences = source("app/src/main/res/xml/pref_config_read.xml")
        val backupConfig = source("app/src/main/java/io/legado/app/help/storage/BackupConfig.kt")
        val restore = source("app/src/main/java/io/legado/app/help/storage/Restore.kt")

        assertTrue(preferKey.contains("const val showReadTitleChapterNameOnly"))
        assertTrue(
            appConfig.contains(
                "getPrefBoolean(PreferKey.showReadTitleChapterNameOnly, false)"
            )
        )
        assertTrue(preferences.contains("android:key=\"showReadTitleChapterNameOnly\""))
        val titleOnlyPreference = Regex(
            "(?s)<io\\.legado\\.app\\.lib\\.prefs\\.SwitchPreference\\b.*?" +
                "android:key=\"showReadTitleChapterNameOnly\".*?/>"
        ).find(preferences)?.value.orEmpty()
        assertTrue(titleOnlyPreference.contains("android:defaultValue=\"false\""))
        assertTrue(backupConfig.contains("PreferKey.showReadTitleChapterNameOnly"))
        assertTrue(restore.contains("PreferKey.showReadTitleChapterNameOnly !in map"))
        assertTrue(restore.contains("edit.putBoolean(PreferKey.showReadTitleChapterNameOnly, false)"))
    }

    @Test
    fun `changing the preference refreshes both title menus without losing URL actions`() {
        val moreConfig = source(
            "app/src/main/java/io/legado/app/ui/book/read/config/MoreConfigDialog.kt"
        )
        val readMenu = source("app/src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")

        assertTrue(moreConfig.contains("PreferKey.showReadTitleChapterNameOnly"))
        assertTrue(readMenu.contains("tvChapterUrl.alpha = if (chapterNameOnly && hasChapterUrl) 0f else 1f"))
        assertTrue(readMenu.contains("titleBarAddition.doOnLayout"))
        assertTrue(readMenu.contains("tvChapterName.translationY"))
        assertTrue(readMenu.contains("R.id.tv_source_action, ConstraintSet.BOTTOM"))
        assertTrue(readMenu.contains("val bottomTarget = if (tvChapterUrl.isGone)"))
        assertTrue(readMenu.contains("tvChapterName.setOnClickListener(chapterViewClickListener)"))
    }

    @Test
    fun `title only label is available in the supported base locales`() {
        assertTrue(
            source("app/src/main/res/values/strings.xml")
                .contains("<string name=\"show_read_title_chapter_name_only\">")
        )
        assertTrue(
            source("app/src/main/res/values-zh/strings.xml")
                .contains("<string name=\"show_read_title_chapter_name_only\">附加区域仅显示章节名</string>")
        )
        assertTrue(
            source("app/src/main/res/values-zh-rTW/strings.xml")
                .contains("<string name=\"show_read_title_chapter_name_only\">附加區域僅顯示章節名</string>")
        )
    }

    private fun source(relativePath: String): String {
        var current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Project file not found: $relativePath")
    }
}
