package io.legado.app.ui.book.changesource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceToolbarLayoutTest {

    @Test
    fun `change source toolbars reserve end inset for expanded search actions`() {
        listOf(
            "dialog_book_change_source.xml",
            "dialog_chapter_change_source.xml",
        ).forEach { name ->
            val source = projectFile("src/main/res/layout/$name").readText()
            assertTrue(
                "$name must reserve the action end inset",
                source.contains("app:contentInsetEndWithActions=\"16dp\"")
            )
        }
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
