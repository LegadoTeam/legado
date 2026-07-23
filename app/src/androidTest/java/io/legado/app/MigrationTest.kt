package io.legado.app

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    private val ALL_MIGRATIONS = arrayOf<Migration>(

    )

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database.
        helper.createDatabase(TEST_DB, 50).apply {
            close()
        }

        // Open latest version of the database. Room will validate the schema
        // once all migrations execute.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate93To94AddsAutomaticTasks() {
        val databaseName = "migration-auto-task"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 93).close()

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .build().apply {
                openHelper.writableDatabase.query("PRAGMA table_info(auto_task_rules)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                    assertTrue(columns.contains("id"))
                    assertTrue(columns.contains("customOrder"))
                    assertTrue(columns.contains("lastLog"))
                }
                close()
            }
    }
}
