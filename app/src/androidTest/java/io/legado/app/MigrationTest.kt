package io.legado.app

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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

    @Test
    @Throws(IOException::class)
    fun migrate95To97AddsHighlights() {
        val databaseName = "migration-book-highlight"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 95).close()

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .build().apply {
                openHelper.writableDatabase.query("PRAGMA table_info(highlights)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                    var primaryKey: String? = null
                    val columns = buildMap {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex)
                            put(name, cursor.getString(defaultIndex))
                            if (cursor.getInt(primaryKeyIndex) > 0) primaryKey = name
                        }
                    }
                    assertEquals(
                        setOf(
                            "time",
                            "bookUrl",
                            "chapterUrl",
                            "bookName",
                            "bookAuthor",
                            "chapterIndex",
                            "chapterPos",
                            "chapterPosEnd",
                            "layoutTitleLength",
                            "chapterName",
                            "bookText",
                            "style",
                            "note"
                        ),
                        columns.keys
                    )
                    assertEquals("-1", columns["layoutTitleLength"])
                    assertEquals("''", columns["bookUrl"])
                    assertEquals("''", columns["chapterUrl"])
                    assertEquals("time", primaryKey)
                }
                openHelper.writableDatabase.query("PRAGMA index_list(highlights)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val indices = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                    assertTrue(indices.contains("index_highlights_bookUrl"))
                }
                openHelper.writableDatabase.query(
                    "PRAGMA index_info(index_highlights_bookUrl)"
                ).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val columns = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                    assertEquals(listOf("bookUrl"), columns)
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate96To97BackfillsHighlightOwners() {
        val databaseName = "migration-book-highlight-owner"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 96).apply {
            execSQL(
                """insert into books (bookUrl, name, author)
                    values ('book-url', 'book', 'author')"""
            )
            execSQL(
                """insert into chapters
                    (url, title, isVolume, baseUrl, bookUrl, `index`, isVip, isPay)
                    values ('chapter-url', 'chapter', 0, '', 'book-url', 2, 0, 0)"""
            )
            execSQL(
                """insert into highlights
                    (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterPosEnd,
                    chapterName, bookText, style, note)
                    values (1, 'book', 'author', 2, 0, 4, 'chapter', 'text', '', '')"""
            )
            close()
        }

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .build().apply {
                openHelper.writableDatabase.query(
                    "select bookUrl, chapterUrl from highlights where time = 1"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("book-url", cursor.getString(0))
                    assertEquals("chapter-url", cursor.getString(1))
                }
                close()
            }
    }
}
