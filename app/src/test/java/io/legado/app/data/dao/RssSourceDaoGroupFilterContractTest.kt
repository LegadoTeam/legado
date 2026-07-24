package io.legado.app.data.dao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RssSourceDaoGroupFilterContractTest {

    private val daoSource by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/RssSourceDao.kt")
    }

    private val activitySource by lazy {
        normalizedSource(
            "src/main/java/io/legado/app/ui/rss/source/manage/RssSourceActivity.kt"
        )
    }

    private val fragmentSource by lazy {
        normalizedSource("src/main/java/io/legado/app/ui/main/rss/RssFragment.kt")
    }

    private val groupQuery by lazy {
        queryBefore("fun flowGroupSearch(sourceGroup: String)")
    }

    private val enabledGroupQuery by lazy {
        queryBefore("fun flowEnabledByGroup(sourceGroup: String)")
    }

    private val groupFilter by lazy {
        val start = daoSource.indexOf("private const val RSS_SOURCE_GROUP_FILTER")
        val end = daoSource.indexOf("@Dao", start)
        require(start >= 0 && end > start)
        daoSource.substring(start, end)
    }

    @Test
    fun `rss group filters match complete normalized members`() {
        listOf(groupQuery, enabledGroupQuery).forEach { query ->
            assertTrue(query.contains("rssSources AS t2"))
            assertTrue(query.contains("+ RSS_SOURCE_GROUP_FILTER +"))
            assertFalse(query.contains("sourceGroup like", ignoreCase = true))
        }
        assertTrue(enabledGroupQuery.contains("t2.enabled = 1"))

        assertTrue(groupFilter.contains("trim(:sourceGroup, \$GROUP_TRIM_CHARACTERS) <> ''"))
        assertTrue(
            groupFilter.contains("with recursive rss_source_groups(group_name, rest) as (")
        )
        assertTrue(
            groupFilter.contains(
                "replace(replace(replace(coalesce(t2.sourceGroup, ''), ';', ','), " +
                    "'\uFF0C', ','), '\uFF1B', ',') || ','"
            )
        )
        assertTrue(
            groupFilter.contains(
                "trim(substr(rest, 1, instr(rest, ',') - 1), \$GROUP_TRIM_CHARACTERS)"
            )
        )
        assertTrue(groupFilter.contains("where rest <> ''"))
        assertTrue(
            groupFilter.contains(
                "group_name = trim(:sourceGroup, \$GROUP_TRIM_CHARACTERS)"
            )
        )
        assertFalse(groupFilter.contains(" like ", ignoreCase = true))

        assertTrue(activitySource.contains("val key = searchKey.substringAfter(\"group:\")"))
        assertTrue(activitySource.contains("rssSourceDao.flowGroupSearch(key)"))
        assertFalse(activitySource.contains("flowGroupSearch(\"%"))
        assertTrue(
            activitySource.contains("searchView.setQuery(\"group:\${item.title}\", true)")
        )

        assertTrue(fragmentSource.contains("val key = searchKey.substringAfter(\"group:\")"))
        assertTrue(fragmentSource.contains("rssSourceDao.flowEnabledByGroup(key)"))
        assertFalse(fragmentSource.contains("flowEnabledByGroup(\"%"))
    }

    private fun queryBefore(functionMarker: String): String {
        val end = daoSource.indexOf(functionMarker)
        val start = daoSource.lastIndexOf("@Query(", end)
        require(start >= 0 && end > start)
        return daoSource.substring(start, end)
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
