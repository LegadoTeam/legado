package io.legado.app.data.dao

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightRuleDaoContractTest {

    @Test
    fun `empty book fields cannot match every scoped rule`() {
        val source = projectFile(
            "src/main/java/io/legado/app/data/dao/HighlightRuleDao.kt"
        ).readText()

        assertTrue(source.contains("(:name != '' AND instr(scope, :name) > 0)"))
        assertTrue(source.contains("(:origin != '' AND instr(scope, :origin) > 0)"))
    }

    @Test
    fun `restored rules replace one complete rule set`() {
        val source = projectFile(
            "src/main/java/io/legado/app/data/dao/HighlightRuleDao.kt"
        ).readText()

        assertTrue(source.contains("@Transaction"))
        assertTrue(source.contains("fun replaceAll(rules: List<HighlightRule>)"))
        assertTrue(source.contains("deleteAll()"))
    }

    @Test
    fun `reader uses stable order when positions match`() {
        val source = projectFile(
            "src/main/java/io/legado/app/data/dao/HighlightRuleDao.kt"
        ).readText()
        val query = source.substringBefore("fun findEnabledByBook")
            .substringAfterLast("@Query(")

        assertTrue(query.contains("ORDER BY sortOrder ASC, id ASC"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .first(File::isFile)
}
