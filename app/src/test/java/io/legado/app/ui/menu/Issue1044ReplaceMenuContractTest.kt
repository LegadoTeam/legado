package io.legado.app.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Issue1044ReplaceMenuContractTest {

    @Test
    fun `replace rule menu puts manual replacement before help with its icon`() {
        val menu = readProjectFile("src/main/res/menu/replace_rule.xml")
        val order = listOf(
            "menu_add_replace_rule",
            "menu_import_local",
            "menu_import_onLine",
            "menu_import_qr",
            "menu_manual_replace_rule",
            "menu_help"
        ).map { id ->
            val index = menu.indexOf("android:id=\"@+id/$id\"")
            assertTrue("$id should exist in replace_rule.xml", index >= 0)
            index
        }
        assertTrue("replace rule actions should follow the requested order", order.zipWithNext().all { (a, b) -> a < b })

        val manual = menu.substringAfter("android:id=\"@+id/menu_manual_replace_rule\"")
            .substringBefore("/>")
        assertTrue(manual.contains("android:icon=\"@drawable/ic_find_replace\""))
        assertTrue(!menu.contains("@drawable/ic_manual_replace"))

        val zhStrings = readProjectFile("src/main/res/values-zh/strings.xml")
        assertTrue(zhStrings.contains("<string name=\"manual_replace_rule\">手动替换</string>"))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
