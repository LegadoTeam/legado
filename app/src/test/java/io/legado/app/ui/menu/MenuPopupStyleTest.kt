package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MenuPopupStyleTest {

    @Test
    fun `popup background follows the custom theme surface`() {
        val materialValues = readProjectFile(
            "src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt"
        )

        assertContains(materialValues, "val Context.popupBackground: GradientDrawable")
        assertContains(materialValues, "background.cornerRadius = 12f.dpToPx()")
        assertContains(materialValues, "background.setColor(bottomBackground)")
    }

    @Test
    fun `custom popup windows apply the shared runtime background`() {
        val extension = readProjectFile(
            "src/main/java/io/legado/app/utils/PopupWindowExtensions.kt"
        )

        assertContains(extension, "fun PopupWindow.applyMd3PopupStyle()")
        assertContains(extension, "setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))")
        assertContains(extension, "elevation = 8f.dpToPx()")
        assertContains(extension, "contentView?.let { it.background = it.context.popupBackground }")
        assertFalse(extension.contains("contentView?.background == null"))

        listOf(
            "src/main/java/io/legado/app/ui/widget/PopupAction.kt",
            "src/main/java/io/legado/app/ui/widget/keyboard/KeyboardToolPop.kt",
            "src/main/java/io/legado/app/ui/book/audio/SliderPopup.kt",
        ).forEach { path ->
            assertContains(readProjectFile(path), "applyMd3PopupStyle()")
        }
    }

    @Test
    fun `autocomplete uses the runtime popup background`() {
        val autoComplete = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/text/AutoCompleteTextView.kt"
        )

        assertContains(autoComplete, "setDropDownBackgroundDrawable(context.popupBackground)")
        assertFalse(autoComplete.contains("setDropDownBackgroundResource"))
    }

    @Test
    fun `system popup keeps the static fallback`() {
        val background = readProjectFile("src/main/res/drawable/bg_popup_menu.xml")
        val styles = readProjectFile("src/main/res/values/styles.xml")

        assertContains(background, "<solid android:color=\"@color/background_menu\"")
        assertContains(styles, "<item name=\"android:popupBackground\">@drawable/bg_popup_menu</item>")
    }

    private fun assertContains(text: String, expected: String) {
        assertTrue("Expected to contain $expected", text.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
