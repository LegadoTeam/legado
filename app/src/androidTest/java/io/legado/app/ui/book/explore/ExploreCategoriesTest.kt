package io.legado.app.ui.book.explore

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExploreCategoriesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val originalCategories = preferences.all[PreferKey.showExploreCategories]
    private val originalCronet = preferences.all[PreferKey.cronet]
    private val slowStarted = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)
    private val slowFinished = CountDownLatch(1)
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/category/1") {
                slowStarted.countDown()
                releaseSlow.await(20, TimeUnit.SECONDS)
            }
            val category = session.uri.substringAfterLast('/')
            val page = session.parameters["page"]?.firstOrNull() ?: "1"
            val ids = when {
                category == "2" && page == "3" -> (50..69).toList()
                category == "2" && page == "2" -> listOf(51) + (30..48)
                else -> (1..20).toList()
            }
            val html = ids.joinToString("") {
                val bookPath = if (category == "2") "/book/$category/$it" else "/book/$category/$page/$it"
                "<a href='$bookPath'>Category $category page $page book $it</a>"
            }
            if (category == "1") slowFinished.countDown()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
    private lateinit var source: BookSource
    private var scenario: ActivityScenario<ExploreShowActivity>? = null

    private fun categoryUrl(index: Int) =
        "http://127.0.0.1:${server.listeningPort}/category/$index?page={{page}}"

    @Before
    fun setUp() {
        preferences.edit().remove(PreferKey.showExploreCategories)
            .putBoolean(PreferKey.cronet, false).commit()
        server.start()
        source = BookSource(
            bookSourceUrl = "http://127.0.0.1:${server.listeningPort}/${UUID.randomUUID()}",
            bookSourceName = "Explore categories fixture",
            exploreUrl = (0 until 22).joinToString("&&") { "Category $it::${categoryUrl(it)}" },
            ruleExplore = ExploreRule(bookList = "tag.a", name = "text", bookUrl = "href"),
        )
        appDb.bookSourceDao.insert(source)
        launch()
        awaitActivity { it.model.getLoadedBooks().size == 20 }
    }

    @After
    fun tearDown() {
        releaseSlow.countDown()
        scenario?.close()
        server.stop()
        if (::source.isInitialized) {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
            appDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM searchBooks WHERE origin = ?", arrayOf(source.bookSourceUrl),
            )
        }
        preferences.edit().apply {
            if (originalCategories == null) remove(PreferKey.showExploreCategories)
            else putBoolean(PreferKey.showExploreCategories, originalCategories as Boolean)
            if (originalCronet == null) remove(PreferKey.cronet)
            else putBoolean(PreferKey.cronet, originalCronet as Boolean)
        }.commit()
    }

    @Test
    fun globalToggleRendersBalancedRowsAndSurvivesReopening() {
        scenario!!.onActivity {
            assertFalse(AppConfig.showExploreCategories)
            assertFalse(it.ui.categoriesContainer.isVisible)
            toggleCategories(it)
        }
        awaitActivity { it.ui.categoriesContainer.childCount == 3 }
        scenario!!.onActivity {
            assertTrue(AppConfig.showExploreCategories)
            assertEquals(listOf(8, 7, 7), rows(it).map { tabs -> tabs.tabCount })
            assertTrue(rows(it).all { tabs -> tabs.tabMode == TabLayout.MODE_SCROLLABLE })
            assertTrue(rows(it).all { tabs -> tabs.minimumHeight <= 40.dpToPx() })
            assertTrue(rows(it).all { tabs ->
                (tabs.getChildAt(0) as ViewGroup).children.all { tab ->
                    tab.paddingLeft == 12.dpToPx() && tab.paddingRight == 12.dpToPx()
                }
            })
            val categoriesLocation = IntArray(2)
            val booksLocation = IntArray(2)
            it.ui.categoriesContainer.getLocationOnScreen(categoriesLocation)
            it.ui.recyclerView.getLocationOnScreen(booksLocation)
            assertTrue(categoriesLocation[1] + it.ui.categoriesContainer.height <= booksLocation[1])
        }
        screenshot("explore-categories")
        scenario!!.close()
        launch()
        awaitActivity { it.ui.categoriesContainer.isVisible }
        scenario!!.onActivity {
            assertTrue(it.ui.titleBar.menu.findItem(R.id.menu_show_explore_categories).isChecked)
            toggleCategories(it)
            assertFalse(it.ui.categoriesContainer.isVisible)
        }
    }

    @Test
    fun backupRestoresToggleAndLegacyBackupResetsIt() {
        scenario!!.close()
        scenario = null
        val directory = File(context.cacheDir, "explore-backup-${UUID.randomUUID()}")
        val oldTitlePreference = preferences.all[PreferKey.showReadTitleChapterNameOnly]
        try {
            writePreferenceSnapshot(context, directory.path, "config") {
                putBoolean(PreferKey.showExploreCategories, true)
            }
            AppConfig.showExploreCategories = false
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertTrue(AppConfig.showExploreCategories)
            writePreferenceSnapshot(context, directory.path, "config") { }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.path) }
            assertFalse(AppConfig.showExploreCategories)
        } finally {
            preferences.edit().apply {
                if (oldTitlePreference == null) remove(PreferKey.showReadTitleChapterNameOnly)
                else putBoolean(PreferKey.showReadTitleChapterNameOnly, oldTitlePreference as Boolean)
            }.commit()
            directory.deleteRecursively()
        }
    }

    @Test
    fun prependingOverlappingPageRetainsVisibleBook() {
        scenario!!.onActivity {
            it.model.switchCategory(ExploreCategory("Category 2", categoryUrl(2)))
            it.model.skipPage(3)
            it.model.explore()
        }
        awaitActivity { it.model.pageLiveData.value == 3 && it.model.getLoadedBooks().size == 20 }
        scenario!!.onActivity {
            val manager = it.ui.recyclerView.layoutManager as LinearLayoutManager
            manager.scrollToPositionWithOffset(1, 0)
        }
        instrumentation.waitForIdleSync()
        var anchor: String? = null
        var top = 0
        scenario!!.onActivity {
            val manager = it.ui.recyclerView.layoutManager as LinearLayoutManager
            val position = manager.findFirstVisibleItemPosition()
            val adapter = it.ui.recyclerView.adapter as ExploreShowAdapter
            anchor = adapter.getItemByLayoutPosition(position)?.bookUrl
            assertTrue(anchor!!.endsWith("/50"))
            top = manager.findViewByPosition(position)!!.top
            it.model.explore(2)
        }
        awaitActivity { it.model.pageLiveData.value == 2 && it.model.getLoadedBooks().size == 39 }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            val manager = it.ui.recyclerView.layoutManager as LinearLayoutManager
            val position = manager.findFirstVisibleItemPosition()
            val adapter = it.ui.recyclerView.adapter as ExploreShowAdapter
            assertEquals(anchor, adapter.getItemByLayoutPosition(position)?.bookUrl)
            assertEquals(top, manager.findViewByPosition(position)!!.top)
        }
    }


    @Test
    fun categorySwitchRejectsLateResponseAndRetainsPageAfterRecreation() {
        scenario!!.onActivity {
            toggleCategories(it)
        }
        awaitActivity { it.ui.categoriesContainer.childCount == 3 }
        scenario!!.onActivity { rows(it)[0].getTabAt(1)!!.select() }
        assertTrue("Slow category request started", slowStarted.await(15, TimeUnit.SECONDS))
        scenario!!.onActivity { rows(it)[1].getTabAt(0)!!.select() }
        awaitActivity { it.model.getLoadedBooks().firstOrNull()?.name?.startsWith("Category 8") == true }
        releaseSlow.countDown()
        assertTrue(slowFinished.await(15, TimeUnit.SECONDS))
        scenario!!.onActivity { it.model.explore() }
        awaitActivity { it.model.pageLiveData.value == 2 }
        scenario!!.onActivity {
            assertEquals(40, it.model.getLoadedBooks().size)
            assertTrue(it.model.getLoadedBooks().all { book -> book.name.startsWith("Category 8") })
        }
        scenario!!.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
        scenario!!.recreate()
        awaitActivity { it.ui.categoriesContainer.childCount == 3 }
        scenario!!.onActivity {
            assertEquals("Category 8", it.ui.titleBar.title.toString())
            assertEquals(2, it.model.pageLiveData.value)
            assertEquals(40, it.model.getLoadedBooks().size)
            assertEquals(40, (it.ui.recyclerView.adapter as ExploreShowAdapter).getActualItemCount())
            assertEquals(listOf(-1, 0, -1), rows(it).map { tabs -> tabs.selectedTabPosition })
        }
        screenshot("explore-category-restored")
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(context, ExploreShowActivity::class.java).apply {
            putExtra("sourceUrl", source.bookSourceUrl)
            putExtra("exploreName", "Category 0")
            putExtra("exploreUrl", categoryUrl(0))
        })
    }

    private fun rows(activity: ExploreShowActivity) =
        activity.ui.categoriesContainer.children.map { it as TabLayout }.toList()

    private fun toggleCategories(activity: ExploreShowActivity) {
        assertTrue("Category menu action is installed", activity.ui.titleBar.menu
            .performIdentifierAction(R.id.menu_show_explore_categories, 0))
    }

    private val ExploreShowActivity.ui: ActivityExploreShowBinding
        get() = ActivityExploreShowBinding.bind(findViewById<View>(R.id.title_bar).parent as View)

    private val ExploreShowActivity.model: ExploreShowViewModel
        get() = ViewModelProvider(this)[ExploreShowViewModel::class.java]

    private fun awaitActivity(condition: (ExploreShowActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var satisfied = false
            scenario!!.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        var state = ""
        scenario!!.onActivity {
            state = "error=${it.model.errorLiveData.value}, books=${it.model.getLoadedBooks().size}, " +
                "page=${it.model.pageLiveData.value}, categories=${it.model.categoriesData.value?.size}, " +
                "rows=${it.ui.categoriesContainer.childCount}, enabled=${AppConfig.showExploreCategories}"
        }
        throw AssertionError("Timed out: $state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
