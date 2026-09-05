package io.legado.app.ui.book.explore

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.utils.defaultSharedPreferences
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
    private val context = instrumentation.targetContext
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
            val html = (1..20).joinToString("") {
                "<a href='/book/$category/$page/$it'>Category $category page $page book $it</a>"
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
        awaitActivity { it.viewModel.getLoadedBooks().size == 20 }
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
            assertFalse(it.binding.categoriesContainer.isVisible)
            it.binding.titleBar.menu.performIdentifierAction(R.id.menu_show_explore_categories, 0)
        }
        awaitActivity { it.binding.categoriesContainer.childCount == 3 }
        scenario!!.onActivity {
            assertTrue(AppConfig.showExploreCategories)
            assertEquals(listOf(8, 7, 7), rows(it).map { tabs -> tabs.tabCount })
            assertTrue(rows(it).all { tabs -> tabs.tabMode == TabLayout.MODE_SCROLLABLE })
            val categoriesLocation = IntArray(2)
            val booksLocation = IntArray(2)
            it.binding.categoriesContainer.getLocationOnScreen(categoriesLocation)
            it.binding.recyclerView.getLocationOnScreen(booksLocation)
            assertTrue(categoriesLocation[1] + it.binding.categoriesContainer.height <= booksLocation[1])
        }
        screenshot("explore-categories")
        scenario!!.close()
        launch()
        awaitActivity { it.binding.categoriesContainer.isVisible }
        scenario!!.onActivity {
            assertTrue(it.binding.titleBar.menu.findItem(R.id.menu_show_explore_categories).isChecked)
            it.binding.titleBar.menu.performIdentifierAction(R.id.menu_show_explore_categories, 0)
            assertFalse(it.binding.categoriesContainer.isVisible)
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
    fun categorySwitchRejectsLateResponseAndRetainsPageAfterRecreation() {
        scenario!!.onActivity {
            it.binding.titleBar.menu.performIdentifierAction(R.id.menu_show_explore_categories, 0)
        }
        awaitActivity { it.binding.categoriesContainer.childCount == 3 }
        scenario!!.onActivity { rows(it)[0].getTabAt(1)!!.select() }
        assertTrue("Slow category request started", slowStarted.await(15, TimeUnit.SECONDS))
        scenario!!.onActivity { rows(it)[1].getTabAt(0)!!.select() }
        awaitActivity { it.viewModel.getLoadedBooks().firstOrNull()?.name?.startsWith("Category 8") == true }
        releaseSlow.countDown()
        assertTrue(slowFinished.await(15, TimeUnit.SECONDS))
        scenario!!.onActivity { it.viewModel.explore() }
        awaitActivity { it.viewModel.pageLiveData.value == 2 }
        scenario!!.onActivity {
            assertEquals(40, it.viewModel.getLoadedBooks().size)
            assertTrue(it.viewModel.getLoadedBooks().all { book -> book.name.startsWith("Category 8") })
        }
        scenario!!.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
        scenario!!.recreate()
        awaitActivity { it.binding.categoriesContainer.childCount == 3 }
        scenario!!.onActivity {
            assertEquals("Category 8", it.binding.titleBar.title.toString())
            assertEquals(2, it.viewModel.pageLiveData.value)
            assertEquals(40, it.viewModel.getLoadedBooks().size)
            assertEquals(40, (it.binding.recyclerView.adapter as ExploreShowAdapter).getActualItemCount())
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
        activity.binding.categoriesContainer.children.map { it as TabLayout }.toList()

    private fun awaitActivity(condition: (ExploreShowActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        do {
            var satisfied = false
            scenario!!.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        scenario!!.onActivity {
            assertTrue("Timed out: ${it.viewModel.errorLiveData.value}", condition(it))
        }
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
