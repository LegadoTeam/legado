package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SleepTimerDialogWiringTest {

    @Test
    fun presetsAndCustomInputKeepMainlineTimerContracts() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/SleepTimerDialog.kt"
        ).readText()
        val preferKey = projectFile(
            "src/main/java/io/legado/app/constant/PreferKey.kt"
        ).readText()

        assertTrue(source.contains("TIME_PRESETS = intArrayOf(15, 30, 45, 60)"))
        assertTrue(source.contains("CHAPTER_PRESETS = intArrayOf(1, 2, 3, 5)"))
        assertTrue(source.contains("value !in 1..max"))
        assertTrue(source.contains("MAX_CHAPTER_STOP_COUNT"))
        assertTrue(source.contains("private const val MAX_MINUTES = 180"))
        assertTrue(source.contains("if (chapter > 0) AppConfig.sleepTimerPreferChapter = true"))
        assertTrue(source.contains("if (minute > 0) AppConfig.sleepTimerPreferChapter = false"))
        assertTrue(source.contains("PreferKey.lastSleepTimer"))
        assertTrue(source.contains("PreferKey.lastSleepChapter"))
        assertTrue(source.contains("llCustomInput.isVisible && customChapterMode == chapterMode"))
        assertTrue(source.contains("arguments?.getInt(ARG_MINUTE)"))
        assertTrue(source.contains("arguments?.getInt(ARG_CHAPTER)"))
        assertTrue(source.contains("arguments?.getBoolean(ARG_EPISODES)"))
        assertFalse(source.contains("AudioPlayService"))
        assertFalse(source.contains("BaseReadAloudService"))
        assertTrue(preferKey.contains("const val lastSleepTimer = \"lastSleepTimer\""))
        assertTrue(preferKey.contains("const val lastSleepChapter = \"lastSleepChapter\""))
    }

    @Test
    fun layoutProvidesEqualAccessibleOptionsAndOneCustomInput() {
        val document = parseProjectXml("src/main/res/layout/dialog_sleep_timer.xml")
        val presetIds = listOf(
            "@+id/tv_time_p1",
            "@+id/tv_time_p2",
            "@+id/tv_time_p3",
            "@+id/tv_time_p4",
            "@+id/tv_chapter_p1",
            "@+id/tv_chapter_p2",
            "@+id/tv_chapter_p3",
            "@+id/tv_chapter_p4",
        )

        presetIds.forEach { id ->
            val option = findViewById(document, id)
            assertEquals(id, "@style/SleepTimerOption", option.getAttribute("style"))
        }

        listOf("@+id/tv_time_custom", "@+id/tv_chapter_custom").forEach { id ->
            val option = findViewById(document, id)
            assertEquals(id, "@style/SleepTimerOption", option.getAttribute("style"))
            assertEquals(id, "match_parent", option.getAttributeNS(androidNamespace, "layout_width"))
            assertEquals(id, "0", option.getAttributeNS(androidNamespace, "layout_weight"))
        }

        val styles = parseProjectXml("src/main/res/values/styles.xml")
        val optionStyle = findStyle(styles, "SleepTimerOption")
        assertEquals("0dp", styleItem(optionStyle, "android:layout_width"))
        assertEquals("1", styleItem(optionStyle, "android:layout_weight"))
        assertEquals("48dp", styleItem(optionStyle, "android:minHeight"))
        assertEquals("true", styleItem(optionStyle, "android:clickable"))
        assertEquals("true", styleItem(optionStyle, "android:focusable"))

        val customRow = findViewById(document, "@+id/ll_custom_input")
        val customInput = findViewById(document, "@+id/et_custom")
        val off = findViewById(document, "@+id/iv_off")
        assertEquals("gone", customRow.getAttributeNS(androidNamespace, "visibility"))
        assertEquals("number", customInput.getAttributeNS(androidNamespace, "inputType"))
        assertEquals("3", customInput.getAttributeNS(androidNamespace, "maxLength"))
        assertEquals(
            "@string/sleep_timer_off",
            off.getAttributeNS(androidNamespace, "contentDescription"),
        )
    }

    @Test
    fun chineseResourcesKeepChapterAndEpisodeUnitsSeparate() {
        assertEquals("%d章", chineseString("sleep_timer_chapter_short"))
        assertEquals("%d集", chineseString("sleep_timer_episode_short"))
    }

    private fun chineseString(name: String): String {
        val document = parseProjectXml("src/main/res/values-zh/strings.xml")
        return document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("name") == name }
                .textContent
        }
    }

    private fun findViewById(document: Document, id: String): Element {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttributeNS(androidNamespace, "id") == id }
    }

    private fun findStyle(document: Document, name: String): Element {
        val nodes = document.getElementsByTagName("style")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
    }

    private fun styleItem(style: Element, name: String): String {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
            .textContent
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

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
