package io.legado.app.base

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaseDialogAutofillTest {

    @Test
    fun `dialog windows disable autofill like activity windows`() {
        val source = projectFile("src/main/java/io/legado/app/base/BaseDialogFragment.kt")
            .readText()

        assertTrue(source.contains("dialog?.window?.decorView?.disableAutoFill()"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
