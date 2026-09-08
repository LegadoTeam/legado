package io.legado.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.HighlightRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleGroupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "highlight-groups-${UUID.randomUUID()}"
    private var database: AppDatabase? = null

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*DatabaseMigrations.migrations).allowMainThreadQueries().build()
        .also { database = it }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun groupOperationsAreScoped() {
        val db = open()
        val first = HighlightRule(uuid = "11111111-1111-1111-1111-111111111111", name = "one", pattern = "one", group = "characters")
        val second = HighlightRule(uuid = "22222222-2222-2222-2222-222222222222", name = "two", pattern = "two", group = "quotes")
        db.highlightRuleDao.insert(first, second)
        assertEquals(listOf("characters", "quotes"), runBlocking {
            db.highlightRuleDao.flowGroups().first()
        })
        db.highlightRuleDao.moveGroup("characters", "quotes")
        assertTrue(db.highlightRuleDao.all.all { it.group == "quotes" })
        db.highlightRuleDao.deleteGroup("quotes")
        assertTrue(db.highlightRuleDao.all.isEmpty())
    }
}
