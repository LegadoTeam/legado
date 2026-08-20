package io.legado.app.ui.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontSelectionStyleTest {

    @Test
    fun `selected font uses an accent stroke`() {
        val layout = readProjectFile("src/main/res/layout/item_font.xml")
        val adapter = readProjectFile("src/main/java/io/legado/app/ui/font/FontAdapter.kt")

        assertFalse(layout.contains("MaterialCardView"))
        assertTrue(layout.contains("<LinearLayout"))
        assertTrue(layout.contains("@+id/root_card"))
        assertTrue(layout.contains("android:foreground=\"?android:attr/selectableItemBackground\""))
        assertTrue(adapter.contains("rootCard.background = GradientDrawable().apply"))
        assertTrue(adapter.contains("setColor(Color.TRANSPARENT)"))
        assertTrue(adapter.contains("setStroke(2.dpToPx(), context.accentColor)"))
    }

    @Test
    fun `font preview falls back when loading a recycled row fails`() {
        val adapter = readProjectFile("src/main/java/io/legado/app/ui/font/FontAdapter.kt")
            .substringAfter("override fun convert")

        assertTrue(adapter.contains("tvFont.typeface = kotlin.runCatching"))
        assertTrue(adapter.contains("}.getOrNull() ?: Typeface.DEFAULT"))
    }

    @Test
    fun `private fonts load independently of the optional external folder`() {
        val dialog = readProjectFile("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
        val setup = dialog.substringAfter("val fontPath = getPrefString(PreferKey.fontFolder)")
            .substringBefore("override fun onMenuItemClick")
        val localLoaderMarker =
            "private fun loadLocalFonts(openFolderWhenEmpty: Boolean = false)"
        assertTrue(dialog.contains(localLoaderMarker))
        val localLoader = dialog.substringAfter(localLoaderMarker)
            .substringBefore("private fun getLocalFonts()")

        assertTrue(setup.contains("loadLocalFonts(openFolderWhenEmpty = true)"))
        assertTrue(setup.contains("loadFontFiles(FileDoc.fromDocumentFile(doc))"))
        val readableFolder = setup.substringAfter("if (doc?.canRead() == true)")
            .substringBefore("} else {")
        assertFalse(readableFolder.contains("loadLocalFonts"))
        assertTrue(localLoader.contains("getLocalFonts()"))
        assertTrue(localLoader.contains("if (it.isNotEmpty())"))
        assertTrue(localLoader.contains("adapter.setItems(it)"))
        assertTrue(localLoader.contains("else if (openFolderWhenEmpty)"))
        assertTrue(localLoader.contains("openFolder()"))

        val permissionLoader = dialog.substringAfter("private fun loadFontFilesByPermission")
            .substringBefore("private fun loadFontFiles(fileDoc")
        assertTrue(permissionLoader.contains(".onDenied"))
        assertTrue(permissionLoader.contains("loadLocalFonts()"))

        val externalLoader = dialog.substringAfter("private fun loadFontFiles(fileDoc")
            .substringBefore("private fun mergeFontItems")
        assertTrue(externalLoader.substringAfter(".onError").contains("loadLocalFonts()"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
