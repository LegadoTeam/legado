package io.legado.app.ui.book.source

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.Spinner
import androidx.appcompat.widget.SearchView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ItemBookSourceBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.source.manage.BookSourceAdapter
import io.legado.app.ui.widget.recycler.scroller.FastScrollRecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BookSourceCheckUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val group = "Check UI $id"
    private val sources = (0..2).map { BookSource(
        bookSourceUrl = "https://check.invalid/$id/$it", bookSourceName = "Check fixture $it",
        bookSourceGroup = if (it < 2) group else "Other $id", bookSourceComment = "Original comment",
    ) }
    private val savedHelp = LocalConfig.all["bookSourceHelpVersion"]
    private var scenario: ActivityScenario<BookSourceActivity>? = null
    private val savedFlags = listOf(CheckSource.checkDomain, CheckSource.checkSearch,
        CheckSource.checkDiscovery, CheckSource.checkInfo, CheckSource.checkCategory, CheckSource.checkContent)

    @Before fun setup() {
        LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
        appDb.bookSourceDao.insert(*sources.toTypedArray())
        scenario = ActivityScenario.launch(BookSourceActivity::class.java)
    }

    @After fun cleanup() {
        Debug.currentCheckSession()?.let { CheckSource.stop(context, it) }
        waitUntil { !Debug.isChecking }
        scenario?.close()
        appDb.bookSourceDao.delete(*sources.toTypedArray())
        LocalConfig.edit().apply {
            if (savedHelp is Int) putInt("bookSourceHelpVersion", savedHelp) else remove("bookSourceHelpVersion")
        }.commit()
        CheckSource.checkDomain = savedFlags[0]
        CheckSource.checkSearch = savedFlags[1]
        CheckSource.checkDiscovery = savedFlags[2]
        CheckSource.checkInfo = savedFlags[3]
        CheckSource.checkCategory = savedFlags[4]
        CheckSource.checkContent = savedFlags[5]
    }

    @Test fun statusFilterIntersectsGroupAndSurvivesRecreation() {
        val dao = appDb.bookSourceDao
        val queued = dao.beginCheck(sources.map { dao.getBookSourcePart(it.bookSourceUrl)!! })
        dao.completeCheck(queued[0], true, "", 1)
        dao.completeCheck(queued[2], true, "", 1)
        scenario!!.onActivity {
            it.findViewById<SearchView>(R.id.search_view).setQuery("group:$group", false)
            it.findViewById<Spinner>(R.id.check_status_filter).setSelection(2)
        }
        awaitItems(listOf(sources[0].bookSourceUrl))
        screenshot("source-check-filter")
        scenario!!.recreate()
        awaitItems(listOf(sources[0].bookSourceUrl))
        scenario!!.onActivity { it.findViewById<Spinner>(R.id.check_status_filter).setSelection(1) }
        awaitItems(listOf(sources[1].bookSourceUrl))
        screenshot("source-check-filter-needed")
    }

    @Test fun realServicePersistsFailureAndSuccessWithoutChangingGroups() = runBlocking {
        CheckSource.checkDomain = false
        CheckSource.checkDiscovery = false
        CheckSource.checkInfo = false
        CheckSource.checkCategory = false
        CheckSource.checkContent = false
        CheckSource.checkSearch = true // Missing search rule fails without a network request.
        val dao = appDb.bookSourceDao
        val url = sources[0].bookSourceUrl
        var session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("FAILED", dao.getCheckState(url)!!.status)
        assertEquals(group, dao.getBookSource(url)!!.bookSourceGroup)
        assertEquals("Original comment", dao.getBookSource(url)!!.bookSourceComment)
        CheckSource.checkSearch = false
        session = Debug.tryStartCheckSession()!!
        CheckSource.start(context, listOf(dao.getBookSourcePart(url)!!), session)
        waitUntil { !Debug.isChecking(session) }
        assertEquals("PASSED", dao.getCheckState(url)!!.status)
    }

    private fun awaitItems(expected: List<String>) = waitUntil {
        var actual = emptyList<String>()
        var rendered = false
        scenario!!.onActivity {
            val recycler = it.findViewById<FastScrollRecyclerView>(R.id.recycler_view)
            val adapter = recycler.adapter as BookSourceAdapter
            actual = adapter.getItems().map { source -> source.bookSourceUrl }
            rendered = !recycler.isComputingLayout && !recycler.hasPendingAdapterUpdates() &&
                !recycler.isLayoutRequested && recycler.itemAnimator?.isRunning != true &&
                recycler.childCount == expected.size && (0 until recycler.childCount).all { index ->
                    val child = recycler.getChildAt(index)
                    val position = recycler.getChildAdapterPosition(child)
                    val source = adapter.getItem(position)
                    source?.bookSourceUrl == expected.getOrNull(index) &&
                        ItemBookSourceBinding.bind(child).cbBookSource.text.toString() ==
                        source?.getDisPlayNameGroup()
                }
        }
        actual == expected && rendered
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        assertTrue("Condition did not become true", predicate())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val frameCommitted = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        scenario!!.onActivity { activity ->
            val decor = activity.window.decorView
            assertTrue("Screenshot requires the hardware-rendered window", decor.isHardwareAccelerated)
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            // A settled View hierarchy does not mean RenderThread has submitted its new frame.
            decor.viewTreeObserver.registerFrameCommitCallback { frameCommitted.countDown() }
            decor.postInvalidateOnAnimation()
        }
        assertTrue("Filtered frame was not committed", frameCommitted.await(5, TimeUnit.SECONDS))
        val copied = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN
        scenario!!.onActivity { activity ->
            // Read the submitted window buffer, not a possibly older compositor screenshot.
            PixelCopy.request(activity.window, bitmap, { result ->
                copyResult = result
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        val directory = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
        bitmap.useBitmap {
            assertTrue("Filtered frame was not copied", copied.await(5, TimeUnit.SECONDS))
            assertEquals("Unable to capture the rendered window", PixelCopy.SUCCESS, copyResult)
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
