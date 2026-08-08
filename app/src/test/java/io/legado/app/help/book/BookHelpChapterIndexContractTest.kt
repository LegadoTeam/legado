package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `chapter number lookup is not limited to the nearby index window`() {
        val dialogSource = projectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceDialog.kt"
        )
            .readText()
            .replace("\r\n", "\n")

        assertTrue(dialogSource.contains("searchAllChapterNumbers = true"))
        assertEquals(
            174,
            findNearestChapterNumberIndex(chapterNumbers(144 to 114, 174 to 144), 144, 144)
        )
        assertEquals(
            142,
            findNearestChapterNumberIndex(chapterNumbers(142 to 144, 174 to 144), 144, 144)
        )
        assertEquals(
            142,
            findNearestChapterNumberIndex(chapterNumbers(142 to 144, 146 to 144), 144, 144)
        )
        assertNull(findNearestChapterNumberIndex(chapterNumbers(144 to 114), 144, 144))
    }

    private fun scaleIndex(oldIndex: Int, oldSize: Int, newSize: Int): Int {
        return (oldIndex.toLong() * newSize / oldSize).toInt()
    }

    private fun chapterNumbers(vararg entries: Pair<Int, Int>): List<Int> {
        return MutableList(200) { -1 }.apply {
            entries.forEach { (index, number) -> this[index] = number }
        }
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
