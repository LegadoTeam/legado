package io.legado.app.data.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchBookDaoGroupContractTest {

    private val searchBookDaoSource by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/SearchBookDao.kt")
    }

    private val enabledPartQuery by lazy {
        val source = normalizedSource("src/main/java/io/legado/app/data/dao/BookSourceDao.kt")
        val end = source.indexOf("fun getEnabledPartByGroup")
        val start = source.lastIndexOf("@Query(", end)
        require(start >= 0 && end > start)
        source.substring(start, end)
    }

    private val membershipFilter by lazy {
        val start = searchBookDaoSource.indexOf(
            "internal const val SOURCE_GROUP_MEMBERSHIP_FILTER"
        )
        val end = searchBookDaoSource.indexOf("@Dao", start)
        require(start >= 0 && end > start)
        searchBookDaoSource.substring(start, end)
    }

    @Test
    fun `change source queries match complete normalized group members`() {
        assertEquals(
            2,
            searchBookDaoSource.countOccurrences("+ SOURCE_GROUP_MEMBERSHIP_FILTER +")
        )
        assertEquals(1, enabledPartQuery.countOccurrences("+ SOURCE_GROUP_MEMBERSHIP_FILTER +"))
        assertTrue(enabledPartQuery.contains("from book_sources_part as t2"))

        assertTrue(searchBookDaoSource.contains("internal const val GROUP_TRIM_CHARACTERS"))
        assertTrue(
            searchBookDaoSource.contains(
                "internal const val NON_EMPTY_SOURCE_GROUP_CONDITION = " +
                    "\"trim(:sourceGroup, \$GROUP_TRIM_CHARACTERS) <> ''\""
            )
        )
        assertTrue(
            searchBookDaoSource.contains(
                "char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,"
            )
        )
        assertTrue(
            searchBookDaoSource.contains(
                "8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)"
            )
        )
        assertTrue(
            membershipFilter.contains(
                "trim(:sourceGroup, \$GROUP_TRIM_CHARACTERS) = ''"
            )
        )
        assertTrue(
            membershipFilter.contains("with recursive source_groups(group_name, rest) as (")
        )
        assertTrue(
            membershipFilter.contains(
                "replace(replace(replace(coalesce(t2.bookSourceGroup, ''), ';', ','), " +
                    "'\uFF0C', ','), '\uFF1B', ',') || ','"
            )
        )
        assertTrue(
            membershipFilter.contains(
                "trim(substr(rest, 1, instr(rest, ',') - 1), \$GROUP_TRIM_CHARACTERS)"
            )
        )
        assertTrue(membershipFilter.contains("substr(rest, instr(rest, ',') + 1)"))
        assertTrue(membershipFilter.contains("where rest <> ''"))
        assertTrue(
            membershipFilter.contains(
                "group_name = trim(:sourceGroup, \$GROUP_TRIM_CHARACTERS)"
            )
        )
        assertFalse(
            searchBookDaoSource.contains("t2.bookSourceGroup like '%'||:sourceGroup||'%'")
        )
        assertFalse(enabledPartQuery.contains("bookSourceGroup like :group || ',%'"))
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
