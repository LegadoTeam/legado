package io.legado.app.ui.book.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AudioPlayLayoutTest {

    @Test
    fun playButtonsUseCompatTintForTheirWhiteIcons() {
        val layoutPaths = listOf(
            "src/main/res/layout/activity_audio_play.xml",
            "src/main/res/layout-land/activity_audio_play.xml",
        )

        layoutPaths.forEach { path ->
            val fab = findPlayButton(parseProjectXml(path))

            assertEquals(
                path,
                "@color/md_black_1000",
                fab.getAttributeNS(appNamespace, "tint")
            )
            assertFalse(path, fab.hasAttributeNS(androidNamespace, "tint"))
            assertEquals(
                path,
                "@color/md_white_1000",
                fab.getAttributeNS(appNamespace, "backgroundTint")
            )
        }

        assertEquals("#FFFFFFFF", vectorFillColor("ic_play_24dp.xml"))
        assertEquals("#FFFFFFFF", vectorFillColor("ic_pause_24dp.xml"))
    }

    @Test
    fun chapterInfoIsBoundedAndPresentInBothOrientations() {
        val portraitPath = "src/main/res/layout/activity_audio_play.xml"
        val landscapePath = "src/main/res/layout-land/activity_audio_play.xml"
        val layouts = listOf(portraitPath, landscapePath).associateWith(::parseProjectXml)

        layouts.forEach { (path, document) ->
            val title = findViewById(document, "@+id/tv_sub_title")
            val chapterIndex = findViewById(document, "@+id/tv_chapter_index")

            assertEquals(path, "end", title.getAttributeNS(androidNamespace, "ellipsize"))
            assertEquals(path, "2", title.getAttributeNS(androidNamespace, "maxLines"))
            assertEquals(path, "bold", title.getAttributeNS(androidNamespace, "textStyle"))
            assertEquals(path, "gone", chapterIndex.getAttributeNS(androidNamespace, "visibility"))
            assertEquals(path, "12sp", chapterIndex.getAttributeNS(androidNamespace, "textSize"))
            assertEquals(
                path,
                "@color/md_dark_secondary",
                chapterIndex.getAttributeNS(androidNamespace, "textColor"),
            )
        }

        val portraitTitle = findViewById(layouts.getValue(portraitPath), "@+id/tv_sub_title")
        val portraitIndex = findViewById(layouts.getValue(portraitPath), "@+id/tv_chapter_index")
        assertEquals(
            "@+id/tv_chapter_index",
            portraitTitle.getAttributeNS(appNamespace, "layout_constraintBottom_toTopOf"),
        )
        assertEquals(
            "@+id/tv_sub_title",
            portraitIndex.getAttributeNS(appNamespace, "layout_constraintTop_toBottomOf"),
        )
        assertEquals(
            "@+id/ll_player_progress",
            portraitIndex.getAttributeNS(appNamespace, "layout_constraintBottom_toTopOf"),
        )

        val landscapeTitle = findViewById(layouts.getValue(landscapePath), "@+id/tv_sub_title")
        val landscapeIndex = findViewById(layouts.getValue(landscapePath), "@+id/tv_chapter_index")
        assertEquals(
            "@+id/ll_player_progress",
            landscapeTitle.getAttributeNS(appNamespace, "layout_constraintBottom_toTopOf"),
        )
        assertEquals(
            "@+id/tv_chapter_index",
            landscapeTitle.getAttributeNS(appNamespace, "layout_constraintEnd_toStartOf"),
        )
        assertEquals(
            "@+id/tv_sub_title",
            landscapeIndex.getAttributeNS(appNamespace, "layout_constraintBaseline_toBaselineOf"),
        )
        assertEquals(
            "parent",
            landscapeIndex.getAttributeNS(appNamespace, "layout_constraintEnd_toEndOf"),
        )
    }

    private fun findPlayButton(document: Document): Element {
        return document.getElementsByTagName(
            "com.google.android.material.floatingactionbutton.FloatingActionButton"
        ).let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttributeNS(androidNamespace, "id") == "@+id/fab_play_stop" }
        }
    }

    private fun findViewById(document: Document, id: String): Element {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .single { it.getAttributeNS(androidNamespace, "id") == id }
    }

    private fun vectorFillColor(fileName: String): String {
        val document = parseProjectXml("src/main/res/drawable/$fileName")
        val path = document.getElementsByTagName("path").item(0) as Element
        return path.getAttributeNS(androidNamespace, "fillColor")
    }

    private fun parseProjectXml(pathInApp: String): Document {
        val file = listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
    }

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        const val appNamespace = "http://schemas.android.com/apk/res-auto"
    }
}
