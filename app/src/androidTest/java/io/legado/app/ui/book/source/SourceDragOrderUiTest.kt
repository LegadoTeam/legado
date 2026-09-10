package io.legado.app.ui.book.source

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ItemBookSourceBinding
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercise the real ItemTouchHelper gesture, database order and reopened management list. */
@RunWith(AndroidJUnit4::class)
class SourceDragOrderUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private enum class Kind { BOOK, RSS, REPLACE }

    @Test fun filteredBookDragPreservesHiddenOrder() = verifyDrag(Kind.BOOK)
    @Test fun descendingBookDragPreservesHiddenOrder() = verifyDrag(Kind.BOOK, true)
    @Test fun filteredRssDragPreservesHiddenOrder() = verifyDrag(Kind.RSS)
    @Test fun filteredReplaceDragPreservesHiddenOrder() = verifyDrag(Kind.REPLACE)
    @Test fun heldBookDragWithDeletedTargetDoesNotMoveAnotherSource() = verifyDrag(Kind.BOOK, removeTarget = true)

    private fun verifyDrag(kind: Kind, descending: Boolean = false, removeTarget: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val group = "Drag $id"
        val orders = listOf(100, 100, 400, 700, 900, 900)
        val oldBooks = if (kind == Kind.BOOK) appDb.bookSourceDao.allPart else emptyList()
        val oldRss = if (kind == Kind.RSS) appDb.rssSourceDao.all else emptyList()
        val oldRules = if (kind == Kind.REPLACE) appDb.replaceRuleDao.all else emptyList()
        val help = LocalConfig.all["bookSourceHelpVersion"]
        val books = orders.mapIndexed { i, order -> BookSource(
            bookSourceUrl = "https://drag.invalid/$id/$i", bookSourceName = "Drag source $i",
            bookSourceGroup = if (i % 2 == 0) group else "Hidden $id",
            bookSourceComment = "Metadata $i", customOrder = order,
        ) }
        val rss = books.map { RssSource(sourceUrl = it.bookSourceUrl, sourceName = it.bookSourceName,
            sourceGroup = it.bookSourceGroup, sourceComment = it.bookSourceComment,
            customOrder = it.customOrder, ruleContent = "body@text") }
        val firstRuleId = System.currentTimeMillis() * 1000
        val rules = books.mapIndexed { i, item -> ReplaceRule(id = firstRuleId + i,
            name = item.bookSourceName, group = item.bookSourceGroup, order = item.customOrder,
            pattern = "original $i", replacement = "replacement $i", scope = "scope $i") }
        val countBook = Book(bookUrl = "https://count-drag.invalid/$id", name = "Count drag $id",
            origin = books[if (descending) 4 else 0].bookSourceUrl)
        var scenario: ActivityScenario<out Activity>? = null
        try {
            LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
            when (kind) {
                Kind.BOOK -> appDb.bookSourceDao.insert(*books.toTypedArray())
                Kind.RSS -> appDb.rssSourceDao.insert(*rss.toTypedArray())
                Kind.REPLACE -> appDb.replaceRuleDao.insert(*rules.toTypedArray())
            }
            val fixtureKeys = when (kind) {
                Kind.BOOK -> books.map { it.bookSourceUrl }
                Kind.RSS -> rss.map { it.sourceUrl }
                Kind.REPLACE -> rules.map { it.id.toString() }
            }
            val visible = fixtureKeys.filterIndexed { index, _ -> index % 2 == 0 }
                .let { if (descending) it.reversed() else it }
            val before = databaseKeys(kind)
            var metadata = databaseMetadata(kind)
            val rawOrders = databaseOrders(kind)
            scenario = launch(kind)
            if (descending) scenario.onActivity { activity ->
                val item = activity.findViewById<TitleBar>(R.id.title_bar).menu
                    .findItem(R.id.menu_sort_desc)
                (activity as BookSourceActivity).onCompatOptionsItemSelected(item)
            }
            filter(scenario, "group:$group")
            awaitItems(scenario, visible)
            screenshot("drag-${kind.name}-$descending-before")

            // Move out and back while holding the same pointer: no persisted reorder or renumbering.
            drag(scenario, 0, 2, returnToStart = true) {
                if (kind == Kind.BOOK) {
                    appDb.bookDao.insert(countBook)
                    waitUntil("live count follows the held source row") {
                        var updated = false
                        scenario!!.onActivity { activity ->
                            val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
                            val adapter = list.adapter as RecyclerAdapter<*, *>
                            val row = list.findViewHolderForAdapterPosition(2)?.itemView
                            updated = (adapter.getItem(2) as? BookSourcePart)?.bookSourceUrl == countBook.origin &&
                                row != null && ItemBookSourceBinding.bind(row).tvBookshelfCount.text.toString() ==
                                context.getString(R.string.source_bookshelf_count, 1)
                        }
                        updated
                    }
                    assertEquals("Count changes do not persist a drag before release", before, databaseKeys(kind))
                    appDb.bookDao.delete(countBook)
                }
            }
            awaitItems(scenario, visible)
            assertEquals(before, databaseKeys(kind))
            assertEquals(rawOrders, databaseOrders(kind))

            val moved = visible.first()
            val target = visible.last()
            val dragScenario = checkNotNull(scenario)
            drag(scenario, 0, 2, whileHeld = {
                val version = listUpdateVersion(dragScenario)
                // A real Room invalidation reaches the manager while the pointer still owns A.
                updateComment(kind, visible[1], "Refreshed while dragging")
                if (removeTarget) appDb.bookSourceDao.delete(target)
                metadata = databaseMetadata(kind)
                waitUntil("Room publication during held $kind drag") {
                    listUpdateVersion(dragScenario) > version
                }
                awaitItems(dragScenario, visible.drop(1) + moved)
                assertEquals(if (removeTarget) before.filter { it != target } else before, databaseKeys(kind))
                screenshot("drag-${kind.name}-$descending-held-refresh-$removeTarget")
            })
            val expected = if (removeTarget) before.filter { it != target } else before.toMutableList().apply {
                remove(moved)
                add(indexOf(target) + if (descending) 0 else 1, moved)
            }
            waitUntil("persisted $kind drag order") { databaseKeys(kind) == expected }
            assertEquals(before.filter { it != moved && (!removeTarget || it != target) },
                databaseKeys(kind).filter { it != moved })
            if (removeTarget) assertEquals(rawOrders.filterKeys { it != target }, databaseOrders(kind))
            assertEquals(metadata, databaseMetadata(kind))
            awaitItems(scenario, if (removeTarget) visible.filter { it != target } else visible.drop(1) + moved)
            screenshot("drag-${kind.name}-$descending-after")
            filter(scenario, "")
            awaitItems(scenario, if (descending) expected.reversed() else expected)
            scenario.close()
            scenario = launch(kind)
            filter(scenario, "")
            awaitItems(scenario, expected)
            filter(scenario, "group:$group")
            awaitItems(scenario, expected.filter { it in visible })
            screenshot("drag-${kind.name}-$descending-reopened")
        } finally {
            scenario?.close()
            appDb.bookDao.delete(countBook)
            when (kind) {
                Kind.BOOK -> {
                    appDb.bookSourceDao.delete(*books.toTypedArray())
                    appDb.bookSourceDao.upOrder(oldBooks)
                }
                Kind.RSS -> {
                    appDb.rssSourceDao.delete(*rss.toTypedArray())
                    val saved = oldRss.associate { it.sourceUrl to it.customOrder }
                    appDb.rssSourceDao.update(*appDb.rssSourceDao.all.map {
                        it.copy(customOrder = saved[it.sourceUrl] ?: it.customOrder)
                    }.toTypedArray())
                }
                Kind.REPLACE -> {
                    appDb.replaceRuleDao.delete(*rules.toTypedArray())
                    val saved = oldRules.associate { it.id to it.order }
                    appDb.replaceRuleDao.update(*appDb.replaceRuleDao.all.map {
                        it.copy(order = saved[it.id] ?: it.order)
                    }.toTypedArray())
                }
            }
            LocalConfig.edit().apply {
                if (help is Int) putInt("bookSourceHelpVersion", help) else remove("bookSourceHelpVersion")
            }.commit()
        }
    }

    private fun launch(kind: Kind): ActivityScenario<out Activity> = when (kind) {
        Kind.BOOK -> ActivityScenario.launch(BookSourceActivity::class.java)
        Kind.RSS -> ActivityScenario.launch(RssSourceActivity::class.java)
        Kind.REPLACE -> ActivityScenario.launch(ReplaceRuleActivity::class.java)
    }

    private fun databaseKeys(kind: Kind): List<String> = when (kind) {
        Kind.BOOK -> appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
        Kind.RSS -> appDb.rssSourceDao.all.map { it.sourceUrl }
        Kind.REPLACE -> appDb.replaceRuleDao.all.map { it.id.toString() }
    }

    private fun databaseOrders(kind: Kind): Map<String, Int> = when (kind) {
        Kind.BOOK -> appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder }
        Kind.RSS -> appDb.rssSourceDao.all.associate { it.sourceUrl to it.customOrder }
        Kind.REPLACE -> appDb.replaceRuleDao.all.associate { it.id.toString() to it.order }
    }

    private fun updateComment(kind: Kind, key: String, comment: String) = when (kind) {
        Kind.BOOK -> appDb.bookSourceDao.update(checkNotNull(appDb.bookSourceDao.getBookSource(key))
            .copy(bookSourceComment = comment))
        Kind.RSS -> appDb.rssSourceDao.update(checkNotNull(appDb.rssSourceDao.getByKey(key))
            .copy(sourceComment = comment))
        Kind.REPLACE -> appDb.replaceRuleDao.update(checkNotNull(appDb.replaceRuleDao.findById(key.toLong()))
            .copy(replacement = comment))
    }

    private fun listUpdateVersion(scenario: ActivityScenario<out Activity>): Long {
        var version = -1L
        scenario.onActivity { activity ->
            version = (activity.findViewById<RecyclerView>(R.id.recycler_view).adapter as RecyclerAdapter<*, *>)
                .listUpdateVersion
        }
        return version
    }

    private fun databaseMetadata(kind: Kind): Map<String, String> = when (kind) {
        Kind.BOOK -> appDb.bookSourceDao.all.associate {
            it.bookSourceUrl to GSON.toJson(it.copy(customOrder = 0))
        }
        Kind.RSS -> appDb.rssSourceDao.all.associate {
            it.sourceUrl to GSON.toJson(it.copy(customOrder = 0))
        }
        Kind.REPLACE -> appDb.replaceRuleDao.all.associate {
            it.id.toString() to GSON.toJson(it.copy(order = 0))
        }
    }

    private fun filter(scenario: ActivityScenario<out Activity>, query: String) {
        scenario.onActivity { activity -> activity.findViewById<SearchView>(R.id.search_view).apply {
            setQuery(query, false)
            clearFocus()
        } }
    }

    private fun awaitItems(scenario: ActivityScenario<out Activity>, expected: List<String>) {
        waitUntil("visible order $expected") {
            var matches = false
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                val items = (recycler.adapter as RecyclerAdapter<*, *>).getItems().map {
                    when (it) {
                        is BookSourcePart -> it.bookSourceUrl
                        is RssSource -> it.sourceUrl
                        is ReplaceRule -> it.id.toString()
                        else -> error("Unexpected row $it")
                    }
                }
                matches = items == expected && !recycler.isComputingLayout &&
                    recycler.itemAnimator?.isRunning != true && !recycler.hasPendingAdapterUpdates()
            }
            matches
        }
    }

    private fun drag(scenario: ActivityScenario<out Activity>, from: Int, to: Int,
                     returnToStart: Boolean = false, whileHeld: () -> Unit = {}) {
        var x = 0f
        var startY = 0f
        var endY = 0f
        var returnY = 0f
        var movedItem: Any? = null
        scenario.onActivity { activity ->
            val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
            val source = checkNotNull(recycler.findViewHolderForAdapterPosition(from)).itemView
            val target = checkNotNull(recycler.findViewHolderForAdapterPosition(to)).itemView
            movedItem = (recycler.adapter as RecyclerAdapter<*, *>).getItem(from)
            val start = IntArray(2).also(source::getLocationOnScreen)
            val end = IntArray(2).also(target::getLocationOnScreen)
            // The left padding is outside the checkbox slide-selection area (16..50 dp).
            x = start[0] + 8 * context.resources.displayMetrics.density
            startY = start[1] + source.height / 2f
            val direction = if (to > from) 1 else -1
            // chooseDropTarget requires crossing the target edge, not merely matching it.
            endY = end[1] + target.height / 2f + direction * target.height / 4f
            returnY = startY - direction * source.height / 4f
        }
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun move(start: Float, end: Float) {
            for (step in 1..16) {
                event(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16f)
                SystemClock.sleep(30)
            }
            SystemClock.sleep(200)
        }
        event(MotionEvent.ACTION_DOWN, startY)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        try {
            move(startY, endY)
            waitUntil("held row reaching $to") {
                var atTarget = false
                scenario.onActivity { activity ->
                    val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                    atTarget = (recycler.adapter as RecyclerAdapter<*, *>).getItem(to) == movedItem
                }
                atTarget
            }
            whileHeld()
            if (returnToStart) move(endY, returnY)
        } catch (failure: Throwable) {
            screenshot("drag-held-failure-${SystemClock.uptimeMillis()}")
            scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(R.id.recycler_view)
                android.util.Log.e("SourceDragOrderUiTest",
                    "from=$from to=$to x=$x startY=$startY endY=$endY " +
                        "rows=${(recycler.adapter as RecyclerAdapter<*, *>).getItems()}")
            }
            throw failure
        } finally {
            event(MotionEvent.ACTION_UP, if (returnToStart) returnY else endY)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400) // ItemTouchHelper calls clearView after its recovery animation.
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.getExternalFilesDir(null), "ui-regression/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $description")
    }
}
