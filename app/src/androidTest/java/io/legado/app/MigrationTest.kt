package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HighlightRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    private val ALL_MIGRATIONS = DatabaseMigrations.migrations

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
            .addMigrations(*ALL_MIGRATIONS)
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
            .addMigrations(*ALL_MIGRATIONS)
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
            .addMigrations(*ALL_MIGRATIONS)
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

    @Test
    @Throws(IOException::class)
    fun migrate97To98AddsHighlightRules() {
        val databaseName = "migration-highlight-rules"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 97).close()

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query("PRAGMA table_info(highlightRules)")
                    .use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                        val columns = buildMap<String, String?> {
                            while (cursor.moveToNext()) {
                                put(cursor.getString(nameIndex), cursor.getString(defaultIndex))
                            }
                        }
                        assertTrue(columns.keys.containsAll(
                            listOf(
                                "id",
                                "name",
                                "pattern",
                                "isRegex",
                                "scope",
                                "isEnabled",
                                "style",
                                "sortOrder",
                                "timeoutMillisecond",
                                "applyToTitle"
                            )
                        ))
                        assertEquals("0", columns["applyToTitle"])
                    }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate99To100DefaultsHighlightRulesToBody() {
        val databaseName = "migration-highlight-rule-body"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 99).apply {
            execSQL(
                """insert into highlightRules
                    (name, pattern, isRegex, scope, isEnabled, style, sortOrder,
                    timeoutMillisecond, applyToTitle)
                    values ('rule', 'text', 0, null, 1, '', 0, 3000, 0)"""
            )
            close()
        }

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query(
                    "select applyToBody from highlightRules where name = 'rule'"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate100To101BackfillsUniqueHighlightRuleUuids() {
        val databaseName = "migration-highlight-rule-uuid"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 100).apply {
            execSQL(
                """insert into highlightRules
                    (name, pattern, isRegex, scope, isEnabled, style, sortOrder,
                    timeoutMillisecond, applyToTitle, applyToBody)
                    values ('first', 'one', 0, null, 1, '', 3, 3000, 0, 1),
                    ('second', 'two', 0, null, 0, '', 7, 3000, 1, 0)"""
            )
            close()
        }

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query(
                    "select name, pattern, isEnabled, sortOrder, uuid " +
                        "from highlightRules order by id"
                ).use { cursor ->
                    val rows = buildList {
                        while (cursor.moveToNext()) {
                            add(
                                listOf(
                                    cursor.getString(0),
                                    cursor.getString(1),
                                    cursor.getInt(2).toString(),
                                    cursor.getInt(3).toString(),
                                    cursor.getString(4)
                                )
                            )
                        }
                    }
                    assertEquals(listOf("first", "one", "1", "3"), rows[0].take(4))
                    assertEquals(listOf("second", "two", "0", "7"), rows[1].take(4))
                    val uuids = rows.map { UUID.fromString(it[4]).toString() }
                    assertEquals(2, uuids.distinct().size)
                }
                openHelper.writableDatabase.query("PRAGMA index_list(highlightRules)")
                    .use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                        var foundUniqueUuidIndex = false
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameIndex) == "index_highlightRules_uuid") {
                                foundUniqueUuidIndex = cursor.getInt(uniqueIndex) == 1
                            }
                        }
                        assertTrue(foundUniqueUuidIndex)
                    }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate101To102SeparatesLegacyPersistedCovers() {
        val databaseName = "migration-persisted-book-covers"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val legacyName = "0123456789abcdef0123456789abcdef.cover"
        val legacyPath = "/data/user/0/io.legado.app/files/covers/$legacyName"
        val manualPath = "/data/user/0/io.legado.app/files/covers/manual.png"
        val networkPath = "https://images.example/covers/$legacyName"
        helper.createDatabase(databaseName, 101).apply {
            listOf(
                Triple("legacy", legacyPath, "author"),
                Triple("manual", manualPath, "author"),
                Triple("network", networkPath, "author"),
            ).forEach { (name, cover, author) ->
                execSQL(
                    "insert into books (bookUrl, name, author, customCoverUrl) values (?, ?, ?, ?)",
                    arrayOf(name, name, author, cover)
                )
            }
            close()
        }

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query(
                    "select bookUrl, customCoverUrl, persistedCoverUrl from books order by bookUrl"
                ).use { cursor ->
                    val rows = buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(0), cursor.getString(1) to cursor.getString(2))
                        }
                    }
                    assertEquals(manualPath to null, rows["manual"])
                    assertEquals(networkPath to null, rows["network"])
                    assertEquals(null to legacyPath, rows["legacy"])
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate102To103AddsDisabledSourceReplacementScope() {
        val databaseName = "migration-replace-rule-source-scope"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 102).apply {
            execSQL(
                """insert into replace_rules
                    (id, name, pattern, replacement, scopeTitle, scopeContent,
                     isEnabled, isRegex, timeoutMillisecond, sortOrder)
                    values (1, 'legacy', 'x', 'y', 1, 1, 1, 0, 3000, 0)"""
            )
            close()
        }

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query(
                    "select scopeTitle, scopeSource, scopeContent from replace_rules where id = 1"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(1, cursor.getInt(2))
                }
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate103To104AddsRssContentPaginationRule() {
        val databaseName = "migration-rss-content-pagination"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 103).close()

        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase.query("PRAGMA table_info(rssSources)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                    assertTrue(columns.contains("nextContentUrl"))
                }
                close()
            }
    }

    @Test
    fun bookCoverUpdatesRejectStaleStateAndSupportBothRestoreLevels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.bookDao
            val book = Book(
                bookUrl = "https://books.example/book",
                origin = "https://sources.example/a",
                name = "book",
                author = "author",
                coverUrl = "https://images.example/source.jpg",
                customCoverUrl = "https://images.example/custom.jpg",
                persistedCoverUrl = "/covers/old.cover",
            )
            dao.insert(book)

            assertEquals(
                1,
                dao.updatePersistedCoverUrlIfUnchanged(
                    book.bookUrl,
                    book.origin,
                    book.coverUrl,
                    book.customCoverUrl,
                    book.persistedCoverUrl,
                    "/covers/new.cover",
                )
            )
            assertEquals("/covers/new.cover", dao.getBook(book.bookUrl)?.persistedCoverUrl)

            val current = book.copy(persistedCoverUrl = "/covers/new.cover")
            listOf(
                current.copy(origin = "https://sources.example/stale"),
                current.copy(coverUrl = "https://images.example/stale-source.jpg"),
                current.copy(customCoverUrl = "https://images.example/stale-custom.jpg"),
                current.copy(persistedCoverUrl = "/covers/stale.cover"),
            ).forEach { stale ->
                assertEquals(
                    0,
                    dao.updatePersistedCoverUrlIfUnchanged(
                        stale.bookUrl,
                        stale.origin,
                        stale.coverUrl,
                        stale.customCoverUrl,
                        stale.persistedCoverUrl,
                        "/covers/rejected.cover",
                    )
                )
            }
            assertEquals("/covers/new.cover", dao.getBook(book.bookUrl)?.persistedCoverUrl)

            assertEquals(
                1,
                dao.clearPersistedCoverUrlIfUnchanged(book.bookUrl, "/covers/new.cover")
            )
            assertEquals(
                0,
                dao.clearCoverOverridesIfUnchanged(
                    book.bookUrl,
                    book.customCoverUrl,
                    "/covers/new.cover",
                )
            )
            assertEquals(
                1,
                dao.clearCoverOverridesIfUnchanged(book.bookUrl, book.customCoverUrl, null)
            )
            dao.getBook(book.bookUrl)?.let { restored ->
                assertEquals(null, restored.customCoverUrl)
                assertEquals(null, restored.persistedCoverUrl)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun highlightRuleDaoKeepsUuidIdentityAndRollsBackInvalidImports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.highlightRuleDao
            val uuidA = "11111111-1111-4111-8111-111111111111"
            val uuidB = "22222222-2222-4222-8222-222222222222"
            val uuidC = "33333333-3333-4333-8333-333333333333"
            dao.insert(
                HighlightRule(uuid = uuidA, pattern = "old", order = 3),
                HighlightRule(uuid = uuidB, pattern = "stay", order = 8)
            )

            dao.importRules(
                listOf(
                    HighlightRule(id = 99, uuid = uuidA, pattern = "updated"),
                    HighlightRule(id = 99, uuid = uuidC, pattern = "added")
                )
            )
            assertEquals(
                listOf(
                    Triple(uuidA, "updated", 0),
                    Triple(uuidB, "stay", 1),
                    Triple(uuidC, "added", 2)
                ),
                dao.all.map { Triple(it.uuid, it.pattern, it.order) }
            )

            dao.move(setOf(uuidB, uuidC), toTop = true)
            assertEquals(listOf(uuidB, uuidC, uuidA), dao.all.map { it.uuid })
            assertEquals(listOf(0, 1, 2), dao.all.map { it.order })

            dao.replaceAll(
                listOf(
                    HighlightRule(id = 1, uuid = uuidB, pattern = "restored-first"),
                    HighlightRule(id = 1, uuid = uuidC, pattern = "restored-second")
                )
            )
            assertEquals(
                listOf(
                    Triple(uuidB, "restored-first", 0),
                    Triple(uuidC, "restored-second", 1)
                ),
                dao.all.map { Triple(it.uuid, it.pattern, it.order) }
            )
            assertEquals(2, dao.all.map { it.id }.distinct().size)

            val beforeInvalidRestore = dao.all.map { Triple(it.uuid, it.pattern, it.order) }
            assertThrows(IllegalArgumentException::class.java) {
                dao.replaceAll(
                    listOf(
                        HighlightRule(id = 1, uuid = uuidA, pattern = "first"),
                        HighlightRule(id = 2, uuid = uuidA, pattern = "duplicate")
                    )
                )
            }
            assertEquals(
                beforeInvalidRestore,
                dao.all.map { Triple(it.uuid, it.pattern, it.order) }
            )
        } finally {
            database.close()
        }
    }
}
