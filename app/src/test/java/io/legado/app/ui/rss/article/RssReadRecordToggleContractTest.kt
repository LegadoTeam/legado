package io.legado.app.ui.rss.article

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RssReadRecordToggleContractTest {

    @Test
    fun `rss record setting is global and guards every insert path`() {
        val source = projectFile("src/main/java/io/legado/app/data/entities/RssSource.kt").normalized()
        val database = projectFile("src/main/java/io/legado/app/data/AppDatabase.kt").normalized()
        val dialog = projectFile("src/main/java/io/legado/app/ui/rss/article/ReadRecordDialog.kt").normalized()
        val readRss = projectFile("src/main/java/io/legado/app/ui/rss/read/ReadRss.kt").normalized()
        val jsExtensions = projectFile("src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt").normalized()
        val menu = projectFile("src/main/res/menu/rss_read_record.xml").normalized()
        val schema = projectFile("schemas/io.legado.app.data.AppDatabase/112.json").normalized()
        val appConfig = projectFile("src/main/java/io/legado/app/help/config/AppConfig.kt").normalized()
        val preferKey = projectFile("src/main/java/io/legado/app/constant/PreferKey.kt").normalized()

        assertTrue(source.contains("@ColumnInfo(defaultValue = \"1\") var enableReadRecord: Boolean = true"))
        assertTrue(Regex("version = (\\d+)").find(database)!!.groupValues[1].toInt() >= 112)
        assertTrue(database.contains("AutoMigration(from = 111, to = 112)"))
        assertTrue(schema.contains("\"version\": 112"))
        assertTrue(schema.contains("\"columnName\": \"enableReadRecord\""))
        assertTrue(appConfig.contains("enableRssReadRecord"))
        assertTrue(preferKey.contains("const val enableRssReadRecord"))
        assertTrue(dialog.contains("item.isChecked = !item.isChecked AppConfig.enableRssReadRecord = item.isChecked"))
        assertTrue(readRss.contains("if (AppConfig.enableRssReadRecord)"))
        assertTrue(jsExtensions.contains("if (AppConfig.enableRssReadRecord)"))
        assertTrue(!dialog.contains("setRecordEnabled"))
        assertTrue(!readRss.contains("source?.enableReadRecord"))
        assertTrue(!jsExtensions.contains("toSource.enableReadRecord"))
        assertTrue(menu.contains("@+id/menu_more"))
        assertTrue(menu.contains("@+id/menu_enable_record"))
    }

    private fun File.normalized() = readText().replace(Regex("\\s+"), " ")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
