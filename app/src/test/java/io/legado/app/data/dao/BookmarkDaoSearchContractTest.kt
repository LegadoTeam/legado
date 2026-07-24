package io.legado.app.data.dao

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BookmarkDaoSearchContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/data/dao/BookmarkDao.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `search predicates keep content matches within the selected book`() {
        val normalized = source.replace(Regex("\\s+"), " ")
        val scopedPredicate =
            "where bookName = :bookName and bookAuthor = :bookAuthor " +
                "and ( chapterName like '%'||:key||'%' " +
                "or content like '%'||:key||'%' ) order by chapterIndex"

        assertEquals(2, normalized.countOccurrences(scopedPredicate))
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var index = indexOf(value)
        while (index >= 0) {
            count++
            index = indexOf(value, index + value.length)
        }
        return count
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
