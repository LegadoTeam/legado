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

    private fun findPlayButton(document: Document): Element {
        return document.getElementsByTagName(
            "com.google.android.material.floatingactionbutton.FloatingActionButton"
        ).let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttributeNS(androidNamespace, "id") == "@+id/fab_play_stop" }
        }
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
