package io.legado.app.data.dao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceDaoGroupFilterContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/data/dao/BookSourceDao.kt")
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    private val flowGroupSearchQuery by lazy {
        queryBefore("fun flowGroupSearch(sourceGroup: String)")
    }

    private val groupSearchQuery by lazy {
        queryBefore("fun groupSearch(sourceGroup: String)")
    }

    private val flowGroupExploreQuery by lazy {
        queryBefore("fun flowGroupExplore(sourceGroup: String)")
    }

    @Test
    fun `group filters use normalized membership contract`() {
        val queries = listOf(flowGroupSearchQuery, groupSearchQuery, flowGroupExploreQuery)

        queries.forEach { query ->
            assertTrue(query.contains("+ NON_EMPTY_SOURCE_GROUP_CONDITION +"))
            assertTrue(query.contains("+ SOURCE_GROUP_MEMBERSHIP_FILTER +"))
            assertTrue(query.contains(" as t2"))
            assertFalse(query.contains("bookSourceGroup ="))
            assertFalse(query.contains("bookSourceGroup like"))
        }
        assertTrue(flowGroupExploreQuery.contains("t2.enabledExplore = 1"))
        assertTrue(flowGroupExploreQuery.contains("t2.hasExploreUrl = 1"))
    }

    private fun queryBefore(functionMarker: String): String {
        val end = source.indexOf(functionMarker)
        val start = source.lastIndexOf("@Query(", end)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
