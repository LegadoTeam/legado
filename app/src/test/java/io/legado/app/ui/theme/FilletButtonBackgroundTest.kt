package io.legado.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FilletButtonBackgroundTest {

    @Test
    fun `fillet button background uses a rounded ripple mask`() {
        val drawable = projectFile("src/main/res/drawable/bg_fillet_btn.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(drawable)
        val root = document.documentElement

        assertEquals("ripple", root.tagName)
        assertEquals(
            "?android:attr/colorControlHighlight",
            root.getAttributeNS(ANDROID_NS, "color")
        )

        val items = document.getElementsByTagName("item")
        val mask = (0 until items.length)
            .asSequence()
            .map { items.item(it) as Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "id") == "@android:id/mask" }
        assertTrue("Ripple should be clipped to the rounded button shape", mask != null)
    }

    @Test
    fun `layouts no longer reference the legacy fillet selector`() {
        val resourceRoot = projectFile("src/main/res")
        val legacyReferences = resourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { it.readText().contains("selector_fillet_btn_bg") }
            .map { it.relativeTo(resourceRoot).path }
            .toList()

        assertTrue("Legacy selector references remain: $legacyReferences", legacyReferences.isEmpty())
        assertFalse(projectFile("src/main/res/drawable/selector_fillet_btn_bg.xml").exists())
        assertFalse(projectFile("src/main/res/drawable/shape_fillet_btn.xml").exists())
    }

    private fun projectFile(relativePath: String): File {
        return listOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull { it.exists() || it.parentFile?.exists() == true }
            ?: error("Project path not found: $relativePath")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
