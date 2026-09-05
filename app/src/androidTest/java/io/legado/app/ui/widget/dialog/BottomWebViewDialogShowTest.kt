package io.legado.app.ui.widget.dialog

import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.about.AboutActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class BottomWebViewDialogShowTest {

    private val source = BookSource(
        bookSourceUrl = "https://example.invalid/dialog-test/${UUID.randomUUID()}",
        bookSourceName = "Dialog regression fixture",
        enabled = false,
        enabledExplore = false,
    )
    private var scenario: ActivityScenario<AboutActivity>? = null

    @Before
    fun setUp() {
        appDb.bookSourceDao.insert(source)
        scenario = ActivityScenario.launch(AboutActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
        }
    }

    @Test
    fun repeatedRequestsDoNotStackAndDismissAllowsImmediateReopen() {
        scenario!!.onActivity { activity ->
            val manager = activity.supportFragmentManager
            val first = newDialog()
            first.show(manager, "first")
            newDialog("other").show(manager, "other")
            newDialog().show(manager, "duplicate-with-different-tag")
            assertEquals(2, visibleDialogs(manager))

            first.dismiss()
            val reopened = newDialog()
            reopened.show(manager, "reopened")
            assertFalse(first.dialog?.isShowing == true)
            assertTrue(reopened.dialog?.isShowing == true)
            assertEquals(2, visibleDialogs(manager))
        }
    }

    @Test
    fun queuedBackgroundRequestsOpenOnlyOneWindow() {
        lateinit var manager: FragmentManager
        scenario!!.onActivity { manager = it.supportFragmentManager }
        val workers = List(3) {
            thread { newDialog().show(manager, "background") }
        }
        workers.forEach { it.join() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario!!.onActivity {
            assertEquals(1, visibleDialogs(manager))
        }
    }

    @Test
    fun stoppedAndDestroyedHostsRejectNewWindows() {
        lateinit var manager: FragmentManager
        scenario!!.moveToState(Lifecycle.State.CREATED).onActivity {
            manager = it.supportFragmentManager
            assertTrue(manager.isStateSaved)
            newDialog().show(manager, "stopped")
            assertEquals(0, visibleDialogs(manager))
        }
        scenario!!.close()
        scenario = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(manager.isDestroyed)
            newDialog().show(manager, "destroyed")
            assertEquals(0, visibleDialogs(manager))
        }
    }

    private fun newDialog(page: String = "comments") = BottomWebViewDialog(
        source.bookSourceUrl,
        0,
        "${source.bookSourceUrl}/$page",
        "<html><body>$page</body></html>",
    )

    private fun visibleDialogs(manager: FragmentManager) = manager.fragments.count {
        it is BottomWebViewDialog && it.dialog?.isShowing == true
    }
}
