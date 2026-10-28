package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReadRecordAuthorLayoutTest {

    @Test
    fun `read record row reserves an optional author line before reading time`() {
        val layout = File("app/src/main/res/layout/item_read_record.xml")
            .takeIf(File::isFile)
            ?: File("src/main/res/layout/item_read_record.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(layout)
        val textViews = document.getElementsByTagName("TextView")
        val author = (0 until textViews.length)
            .map { textViews.item(it).attributes.getNamedItem("android:id")?.nodeValue }
            .firstOrNull { it == "@+id/tv_author" }
        assertEquals("@+id/tv_author", author)

        val source = layout.readText()
        assertTrue(source.contains("app:layout_constraintTop_toBottomOf=\"@id/tv_author\""))
    }
}
