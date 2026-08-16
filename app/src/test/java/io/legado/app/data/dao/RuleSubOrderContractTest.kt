package io.legado.app.data.dao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleSubOrderContractTest {

    @Test
    fun `new subscriptions append after the highest order`() {
        val dao = projectFile("src/main/java/io/legado/app/data/dao/RuleSubDao.kt").readText()
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/rss/subscription/RuleSubActivity.kt"
        ).readText()

        assertTrue(dao.contains("SELECT COALESCE(MAX(customOrder), 0) FROM ruleSubs"))
        assertFalse(dao.contains("order by customOrder limit"))
        assertTrue(activity.contains("appDb.ruleSubDao.maxOrder + 1"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).first { it.isFile }
}
