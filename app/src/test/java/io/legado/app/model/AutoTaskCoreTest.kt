package io.legado.app.model

import com.script.rhino.RhinoInterruptError
import com.script.rhino.RhinoScriptEngine
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.ConsString
import org.htmlunit.corejs.javascript.Scriptable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskCoreTest {

    @Test
    fun normalizesSupportedScriptWrappers() {
        assertEquals("return 1", AutoTask.normalizeScript(" @js: return 1 "))
        assertEquals("return 2", AutoTask.normalizeScript("<js> return 2 </js>"))
        assertEquals("return 3", AutoTask.normalizeScript(" return 3 "))
    }

    @Test
    fun movesUsingTheAdjacentVisibleTask() {
        val rules = listOf(
            AutoTaskRule(id = "a", customOrder = 0),
            AutoTaskRule(id = "hidden", customOrder = 1),
            AutoTaskRule(id = "c", customOrder = 2)
        )
        val expectedVisibleOrder = listOf("c", "a")

        val reordered = mergeAutoTaskOrder(rules, expectedVisibleOrder)
        val repeated = mergeAutoTaskOrder(reordered, expectedVisibleOrder)

        assertEquals(listOf("c", "hidden", "a"), reordered.map { it.id })
        assertEquals(reordered, repeated)
    }

    @Test
    fun buildsEscapedBookUpdateTask() {
        val bookUrl = "https://example.com/book?value=\");throw new Error('bad');//"
        val book = Book(bookUrl = bookUrl, name = "Test", author = "Author")
        val task = AutoTask.buildBookUpdateTask(book, "Update Test")

        assertEquals("book_update:c67024bf54021613", task.id)
        assertEquals("Update Test", task.name)
        assertEquals(AutoTask.DEFAULT_CRON, task.cron)

        val action = AutoTaskProtocol.parseActions(RhinoScriptEngine.eval(task.script))?.single()
        assertEquals("refreshToc", action?.get("type"))
        assertEquals(bookUrl, action?.get("bookUrl"))
        assertEquals(book.name, action?.get("bookName"))
        assertEquals(book.author, action?.get("bookAuthor"))
        assertEquals(AutoTask.BOOK_UPDATE_GENERATOR, action?.get("generatedBy"))
        assertEquals(true, action?.get("respectCanUpdate"))
        val notify = action?.get("notify") as Map<*, *>
        assertEquals(true, notify["enable"])
        assertEquals(1, (notify["minCount"] as Number).toInt())
        val cache = action?.get("cache") as Map<*, *>
        assertEquals(false, cache["enable"])
    }

    @Test
    fun findsGeneratedBookUpdateTaskAfterSourceChange() {
        val oldBook = Book(bookUrl = "old", name = "Test", author = "Author")
        val oldTask = AutoTask.buildBookUpdateTask(oldBook, "Update Test")
        val otherTask = AutoTask.buildBookUpdateTask(
            Book(bookUrl = "other", name = "Test", author = "Other"),
            "Update Other"
        )

        assertEquals(
            oldTask,
            AutoTask.findBookUpdateTask(
                listOf(oldTask, otherTask),
                Book(bookUrl = "new", name = "Test", author = "Author")
            )
        )
        assertNull(
            AutoTask.findBookUpdateTask(
                listOf(oldTask),
                Book(bookUrl = "new", name = "Test", author = "Changed")
            )
        )
    }

    @Test
    fun buildsBatchBookUpdateTasksWithoutStealingExactMatches() {
        val movedBook = Book(bookUrl = "new", name = "Test", author = "Author")
        val exactBook = Book(bookUrl = "exact", name = "Test", author = "Author")
        val movedTask = AutoTask.buildBookUpdateTask(
            Book(bookUrl = "old", name = "Test", author = "Author"),
            "Old"
        )
        val exactTask = AutoTask.buildBookUpdateTask(exactBook, "Exact").copy(enable = false)
        val cron = "0 */2 * * *"

        val tasks = AutoTask.buildBookUpdateTasks(
            books = listOf(movedBook, exactBook),
            existingTasks = listOf(exactTask, movedTask),
            cron = cron,
            nameOf = { "Update ${it.name}" }
        )

        assertEquals(listOf(movedTask.id, exactTask.id), tasks.map { it.id })
        assertEquals(listOf(true, false), tasks.map { it.enable })
        assertEquals(listOf(cron, cron), tasks.map { it.cron })
        assertEquals(
            listOf(movedBook.bookUrl, exactBook.bookUrl),
            tasks.map {
                AutoTaskProtocol.parseActions(RhinoScriptEngine.eval(it.script))
                    ?.single()?.get("bookUrl")
            }
        )
    }

    @Test
    fun generatedBookUpdateTaskRespectsStoppedUpdates() {
        assertTrue(AutoTaskProtocol.canRefreshBookToc(true, true))
        assertFalse(AutoTaskProtocol.canRefreshBookToc(false, true))
        assertTrue(AutoTaskProtocol.canRefreshBookToc(false, false))
    }

    @Test
    fun parsesProtocolArrayObjectAndWrapper() {
        assertEquals(1, AutoTaskProtocol.parseActions("{\"type\":\"notify\"}")?.size)
        assertEquals(2, AutoTaskProtocol.parseActions("[{\"type\":\"notify\"},{\"type\":\"refreshToc\"}]")?.size)
        assertEquals(1, AutoTaskProtocol.parseActions("{\"actions\":[{\"type\":\"notify\"}]}")?.size)
    }

    @Test
    fun parsesRhinoProtocolWithLazyStrings() {
        val result = RhinoScriptEngine.eval("var n = 1; [{type: 'notify', title: 'Task ' + n}]")
        val array = result as Scriptable
        val action = array.get(0, array) as Scriptable

        assertTrue(action.get("title", action) is ConsString)
        assertEquals("Task 1", AutoTaskProtocol.parseActions(result)?.single()?.get("title"))
    }

    @Test
    fun preservesCancellationWhileParsingRhinoProtocol() {
        val result = RhinoScriptEngine.eval(
            "[{type: 'notify', toJSON: function() { return this; }}]"
        )
        val job = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            AutoTaskProtocol.parseActions(result, job)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownProtocolAction() {
        AutoTaskProtocol.actionType(mapOf("type" to "unknown"))
    }

    @Test
    fun boundsStoredLogLength() {
        assertEquals(AutoTaskLogFormatter.MAX_LENGTH, AutoTaskLogFormatter.trim("x".repeat(8_000)).length)
        assertEquals(
            AutoTaskLogFormatter.MAX_ERROR_LENGTH,
            AutoTaskLogFormatter.trimError("x".repeat(8_000)).length
        )
    }

    @Test
    fun protocolSummaryIsNotRepeatedInStoredLog() {
        val action = "Notification: Done"
        val log = AutoTaskLogFormatter.success(0L, 25L, listOf(action), action)

        assertEquals(1, log.split(action).size - 1)
        assertFalse(log.contains("Result:"))
    }

    @Test
    fun cancellationIsNeverConvertedToFailure() {
        val cancellation = CancellationException("stop")
        assertEquals(cancellation, cancellation.autoTaskCancellation())
    }

    @Test
    fun ordinaryFailureIsNotCancellation() {
        assertNull(IllegalStateException("failed").autoTaskCancellation())
    }

    @Test
    fun rhinoWrappedCancellationIsNeverConvertedToFailure() {
        val cancellation = CancellationException("stop Rhino")
        assertEquals(
            cancellation,
            RhinoInterruptError(cancellation).autoTaskCancellation()
        )
    }

    @Test
    fun legacyRulesMigrateOnlyOnce() {
        var legacyJson: String? = "[{\"id\":\"one\",\"name\":\"Task\",\"script\":\"1\"}]"
        var persistCount = 0
        var clearCount = 0
        val migrate = {
            migrateLegacyAutoTaskRules(
                read = { legacyJson },
                persist = { persistCount++ },
                clear = {
                    clearCount++
                    legacyJson = null
                }
            )
        }

        assertEquals(1, migrate().size)
        assertEquals(0, migrate().size)
        assertEquals(1, persistCount)
        assertEquals(1, clearCount)
    }

    @Test
    fun autoTaskJsonFieldsHaveStableSerializedNames() {
        val expected = setOf(
            "id",
            "name",
            "enable",
            "cron",
            "loginUrl",
            "loginUi",
            "loginCheckJs",
            "comment",
            "script",
            "header",
            "jsLib",
            "concurrentRate",
            "enabledCookieJar",
            "customOrder",
            "lastRunAt",
            "lastResult",
            "lastError",
            "lastLog"
        )
        val serializedNames = AutoTaskRule::class.java.declaredFields.mapNotNull { field ->
            field.getAnnotation(SerializedName::class.java)?.value
        }.toSet()

        assertEquals(expected, serializedNames)
    }

    @Test
    fun exportedAutoTaskJsonContainsOnlyReusableConfiguration() {
        assertEquals("[]", AutoTask.exportJson(emptyList()))

        val rule = AutoTaskRule(
            id = "task-id",
            name = "task-name",
            enable = false,
            cron = "1 2 3 4 5",
            loginUrl = "https://example.com/login",
            loginUi = "login-ui",
            loginCheckJs = "login-check",
            comment = "comment",
            script = "script",
            header = "header",
            jsLib = "library",
            concurrentRate = "2/1000",
            enabledCookieJar = false,
            customOrder = 7,
            lastRunAt = 8L,
            lastResult = "result",
            lastError = "error",
            lastLog = "log",
        )

        val json = AutoTask.exportJson(listOf(rule))
        val exported = JsonParser.parseString(json).asJsonArray.single().asJsonObject
        val imported = GSON.fromJsonArray<AutoTaskRule>(json).getOrThrow().single()

        assertEquals(
            setOf(
                "id",
                "name",
                "enable",
                "cron",
                "loginUrl",
                "loginUi",
                "loginCheckJs",
                "comment",
                "script",
                "header",
                "jsLib",
                "concurrentRate",
                "enabledCookieJar"
            ),
            exported.keySet()
        )
        assertEquals(
            rule.copy(
                customOrder = 0,
                lastRunAt = 0L,
                lastResult = null,
                lastError = null,
                lastLog = null,
            ),
            imported
        )
    }

    @Test
    fun existingRoomRulesClearLegacyCacheOnceAndNeverReviveIt() {
        val loader = LegacyAutoTaskRulesLoader()
        val existing = listOf(AutoTaskRule(id = "current", name = "Current", script = "1"))
        var legacyJson: String? = "[{\"id\":\"old\",\"name\":\"Old\",\"script\":\"1\"}]"
        var persistCount = 0
        var clearCount = 0
        val load: (List<AutoTaskRule>) -> List<AutoTaskRule> = { roomRules ->
            loader.load(
                existingRules = roomRules,
                read = { legacyJson },
                persist = { persistCount++ },
                clear = {
                    clearCount++
                    legacyJson = null
                }
            )
        }

        assertEquals(existing, load(existing))
        assertEquals(emptyList<AutoTaskRule>(), load(emptyList()))
        assertEquals(0, persistCount)
        assertEquals(1, clearCount)
    }

    @Test
    fun failedLegacyCleanupCanRetry() {
        val loader = LegacyAutoTaskRulesLoader()
        val existing = listOf(AutoTaskRule(id = "current", name = "Current", script = "1"))
        var clearAttempts = 0

        runCatching {
            loader.load(existing, read = { null }, persist = {}, clear = {
                clearAttempts++
                error("cleanup failed")
            })
        }
        val result = loader.load(existing, read = { null }, persist = {}, clear = {
            clearAttempts++
        })

        assertEquals(existing, result)
        assertEquals(2, clearAttempts)
    }

    @Test
    fun notificationIdRangesNeverOverlap() {
        val taskIds = listOf(Int.MIN_VALUE, -1, 0, 9_999, Int.MAX_VALUE).map {
            AutoTaskProtocol.taskNotificationId(it, "ignored")
        } + AutoTaskProtocol.taskNotificationId(null, "task")
        val bookIds = listOf("", "book", "another").map {
            AutoTaskProtocol.bookUpdateNotificationId(it)
        }

        assertTrue(taskIds.all { it in 30_000..39_999 })
        assertTrue(bookIds.all { it in 50_000..59_999 })
        assertTrue(taskIds.toSet().intersect(bookIds.toSet()).isEmpty())
    }

    @Test
    fun notificationTextIsBoundedBeforePosting() {
        assertEquals(
            AutoTaskProtocol.MAX_NOTIFICATION_TITLE_LENGTH,
            AutoTaskProtocol.trimNotificationTitle("x".repeat(1_000)).length
        )
        assertEquals(
            AutoTaskProtocol.MAX_NOTIFICATION_CONTENT_LENGTH,
            AutoTaskProtocol.trimNotificationContent("x".repeat(8_000)).length
        )
    }

    @Test
    fun volumeRowsDoNotAffectNewChapterCount() {
        val volume = BookChapter(title = "Volume", isVolume = true)
        val chapter1 = BookChapter(title = "Chapter 1")
        val chapter2 = BookChapter(title = "Chapter 2")

        assertEquals(
            0,
            AutoTaskProtocol.countNewChapters(
                before = listOf(chapter1),
                after = listOf(volume, chapter1)
            )
        )
        assertEquals(
            1,
            AutoTaskProtocol.countNewChapters(
                before = listOf(volume, chapter1),
                after = listOf(chapter1, chapter2)
            )
        )
    }

    @Test
    fun findsNewContentChaptersByUrlAndSkipsVolumes() {
        val old = listOf(
            BookChapter(url = "volume", title = "Volume", isVolume = true),
            BookChapter(url = "one", title = "Chapter 1"),
            BookChapter(url = "two", title = "Chapter 2"),
        )
        val after = listOf(
            BookChapter(url = "one", title = "Chapter 1"),
            BookChapter(url = "new-volume", title = "New volume", isVolume = true),
            BookChapter(url = "new", title = "New chapter"),
            BookChapter(url = "two", title = "Chapter 2"),
        )

        assertEquals(1, AutoTaskProtocol.countNewChapters(old, after))
        assertEquals(
            listOf("new"),
            AutoTaskProtocol.newContentChapters(old, after).map { it.url }
        )
        assertTrue(
            AutoTaskProtocol.newContentChapters(
                before = old,
                after = listOf(
                    BookChapter(url = "rotated-one", title = "Chapter 1"),
                    BookChapter(url = "rotated-two", title = "Chapter 2"),
                )
            ).isEmpty()
        )
    }

    @Test
    fun cacheRetriesEachChapterAndReportsTheFirstFinalFailure() {
        val first = BookChapter(url = "first")
        val second = BookChapter(url = "second")
        val attempts = mutableMapOf<String, Int>()

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AutoTaskProtocol.cacheChaptersWithRetry(
                    chapters = listOf(first, second),
                    retryDelayMillis = 0,
                ) { chapter ->
                    val attempt = attempts.getOrDefault(chapter.url, 0) + 1
                    attempts[chapter.url] = attempt
                    if (chapter == first || attempt < 3) error(chapter.url)
                }
            }
        }

        assertEquals("first", failure.message)
        assertEquals(3, attempts["first"])
        assertEquals(3, attempts["second"])
    }

    @Test
    fun finalCancellationTakesPriorityOverCacheFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val context = currentCoroutineContext()
                AutoTaskProtocol.cacheChaptersWithRetry(
                    chapters = listOf(BookChapter(url = "first")),
                    retryDelayMillis = 0,
                    onFailure = { _, _ -> context.cancel() },
                ) {
                    error("cache failed")
                }
            }
        }
    }
}
