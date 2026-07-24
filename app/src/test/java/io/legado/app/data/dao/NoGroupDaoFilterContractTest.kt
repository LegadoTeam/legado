package io.legado.app.data.dao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoGroupDaoFilterContractTest {

    private val bookSourceDao by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/BookSourceDao.kt")
    }

    private val rssSourceDao by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/RssSourceDao.kt")
    }

    private val replaceRuleDao by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/ReplaceRuleDao.kt")
    }

    @Test
    fun `no group filters only accept blank values or the complete legacy label`() {
        listOf(
            queryBefore(bookSourceDao, "fun flowNoGroup(): Flow<List<BookSourcePart>>"),
            queryBefore(bookSourceDao, "val allNoGroup: List<BookSource>")
        ).forEach { query ->
            assertTrue(query.contains("+ BOOK_SOURCE_NO_GROUP_FILTER"))
            assertFalse(query.contains("like '%未分组%'", ignoreCase = true))
        }
        assertNoGroupFilter(
            constantBefore(bookSourceDao, "BOOK_SOURCE_NO_GROUP_FILTER"),
            "bookSourceGroup"
        )

        val rssQuery = queryBefore(rssSourceDao, "fun flowNoGroup(): Flow<List<RssSource>>")
        assertTrue(rssQuery.contains("+ RSS_SOURCE_NO_GROUP_FILTER"))
        assertFalse(rssQuery.contains("like '%未分组%'", ignoreCase = true))
        assertNoGroupFilter(
            constantBefore(rssSourceDao, "RSS_SOURCE_NO_GROUP_FILTER"),
            "sourceGroup"
        )

        val replaceQuery = queryBefore(
            replaceRuleDao,
            "fun flowNoGroup(): Flow<List<ReplaceRule>>"
        )
        assertTrue(replaceQuery.contains("+ REPLACE_RULE_NO_GROUP_FILTER"))
        assertFalse(replaceQuery.contains("like '%未分组%'", ignoreCase = true))
        assertNoGroupFilter(
            constantBefore(replaceRuleDao, "REPLACE_RULE_NO_GROUP_FILTER"),
            "`group`"
        )
    }

    private fun assertNoGroupFilter(filter: String, column: String) {
        assertTrue(
            filter.contains(
                "trim(coalesce($column, ''), \$GROUP_TRIM_CHARACTERS) in ('', '未分组')"
            )
        )
        assertFalse(filter.contains(" like ", ignoreCase = true))
        assertFalse(filter.contains("with recursive", ignoreCase = true))
    }

    private fun queryBefore(source: String, marker: String): String {
        val end = source.indexOf(marker)
        val start = source.lastIndexOf("Query(", end)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun constantBefore(source: String, name: String): String {
        val start = source.indexOf("private const val $name")
        val end = source.indexOf("@Dao", start)
        require(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun normalizedSource(pathInApp: String): String {
        return projectFile(pathInApp)
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
