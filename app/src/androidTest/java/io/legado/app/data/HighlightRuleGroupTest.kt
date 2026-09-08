package io.legado.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*DatabaseMigrations.migrations).allowMainThreadQueries().build()
        .also { database = it }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationPreservesExistingRuleContentAndIdentity() {
        helper.createDatabase(name, 108).use {
            it.execSQL("""INSERT INTO highlightRules
                (id, uuid, name, pattern, isRegex, scope, isEnabled, style, sortOrder,
                 timeoutMillisecond, applyToTitle, applyToBody)
                VALUES (42, '11111111-1111-1111-1111-111111111111', 'saved', 'a.*',
                        1, 'book', 0, '{"color":123}', 7, 900, 1, 0)""")
        }
        helper.runMigrationsAndValidate(name, 109, true, *DatabaseMigrations.migrations).close()
        val rule = open().highlightRuleDao.all.single()
        // Entity equality compares only IDs, so inspect every preserved field.
        assertEquals(42L, rule.id)
        assertEquals("11111111-1111-1111-1111-111111111111", rule.uuid)
        assertEquals("saved", rule.name)
        assertEquals("a.*", rule.pattern)
        assertTrue(rule.isRegex)
        assertEquals("book", rule.scope)
        assertEquals(false, rule.isEnabled)
        assertEquals("{\"color\":123}", rule.style)
        assertEquals(7, rule.order)
        assertEquals(900L, rule.timeoutMillisecond)
        assertTrue(rule.applyToTitle)
        assertEquals(false, rule.applyToBody)
        assertTrue(rule.group.isNullOrBlank())
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
