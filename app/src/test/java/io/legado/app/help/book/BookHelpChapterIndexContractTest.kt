package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookHelpChapterIndexContractTest {

    @Test
    fun `chapter ratio maps old progress into the new list`() {
        val source = projectFile("src/main/java/io/legado/app/help/book/BookHelp.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(source.contains("if (oldChapterListSize == 0) oldDurChapterIndex"))
        assertTrue(
            source.contains(
                "oldDurChapterIndex.toLong() * newChapterSize / oldChapterListSize"
            )
        )
        assertFalse(source.contains("oldDurChapterIndex * oldChapterListSize / newChapterSize"))

        assertEquals(100, scaleIndex(50, oldSize = 100, newSize = 200))
        assertEquals(50, scaleIndex(100, oldSize = 200, newSize = 100))
        assertEquals(50, scaleIndex(50, oldSize = 100, newSize = 100))
        assertEquals(
            Int.MAX_VALUE,
            scaleIndex(Int.MAX_VALUE, oldSize = Int.MAX_VALUE, newSize = Int.MAX_VALUE)
        )
    }

    private fun scaleIndex(oldIndex: Int, oldSize: Int, newSize: Int): Int {
        return (oldIndex.toLong() * newSize / oldSize).toInt()
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
