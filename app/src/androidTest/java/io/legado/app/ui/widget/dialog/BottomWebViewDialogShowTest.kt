package io.legado.app.ui.widget.dialog

import android.os.SystemClock
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.R as MaterialR
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
import kotlin.math.abs

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

    @Test
    fun bottomSheetWindowAlignsToBottom() {
        scenario!!.onActivity { activity ->
            val dialog = newDialog()
            dialog.show(activity.supportFragmentManager, "bottom-alignment")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { fragment -> fragment.dialog?.isShowing == true }
            val window = checkNotNull(dialog.dialog?.window)
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            assertTrue(abs(window.decorView.height - sheet.bottom) <= 2)

            dialog.upConfig("{\"dialogHeight\":240}")
            dialog.upConfig("{\"dialogHeight\":480}")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario!!.onActivity {
            val dialog = it.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { fragment -> fragment.dialog?.isShowing == true }
            val window = checkNotNull(dialog.dialog?.window)
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            assertTrue(abs(window.decorView.height - sheet.bottom) <= 2)
        }
    }

    @Test
    fun configuredHeightsStayAnchoredToBottom() {
        scenario!!.onActivity { activity ->
            newDialog().show(activity.supportFragmentManager, "configured-height")
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val initial = sheetGeometry()
        applyConfig("{\"dialogHeight\":480}")
        val short = sheetGeometry()
        applyConfig("{\"dialogHeight\":720}")
        val tall = sheetGeometry()

        assertTrue(abs(initial.bottomGap) <= 2)
        assertTrue(abs(short.bottomGap) <= 2)
        assertTrue(abs(tall.bottomGap) <= 2)
        assertTrue(tall.top < short.top)
    }

    @Test
    fun percentageHeightWithoutFitToContentsStaysAtBottom() {
        scenario!!.onActivity { activity ->
            // Relevant configuration from the source attached to issue #1135.
            newDialog(config = """{"heightPercentage":0.75,"setFitToContents":false,
                "isGestureInsetBottomIgnored":true,"isHideable":true}""")
                .show(activity.supportFragmentManager, "source-height")
        }
        val initial = awaitGeometry {
            !it.fitToContents && it.state == BottomSheetBehavior.STATE_EXPANDED &&
                it.height > 0 && it.height < it.parentHeight
        }
        assertTrue(initial.toString(), abs(initial.bottomGap) <= 2)

        applyConfig("""{"heightPercentage":0.5,"setFitToContents":false}""")
        val resized = awaitGeometry { !it.fitToContents && it.height < initial.height }
        assertTrue(resized.toString(), abs(resized.bottomGap) <= 2)
        assertTrue(resized.toString(), resized.top > initial.top)
    }

    private fun newDialog(page: String = "comments", config: String? = null) = BottomWebViewDialog(
        source.bookSourceUrl,
        0,
        "${source.bookSourceUrl}/$page",
        "<html><body>$page</body></html>",
        config = config,
    )

    private fun applyConfig(config: String) {
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { it.dialog?.isShowing == true }
                .upConfig(config)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun sheetGeometry(): SheetGeometry {
        var geometry: SheetGeometry? = null
        scenario!!.onActivity { activity ->
            val dialog = activity.supportFragmentManager.fragments
                .filterIsInstance<BottomWebViewDialog>()
                .single { it.dialog?.isShowing == true }
            val sheet = checkNotNull(dialog.dialog?.findViewById<View>(MaterialR.id.design_bottom_sheet))
            val parent = sheet.parent as View
            val behavior = BottomSheetBehavior.from(sheet)
            geometry = SheetGeometry(
                top = sheet.top,
                bottomGap = parent.height - sheet.bottom,
                height = sheet.height,
                parentHeight = parent.height,
                fitToContents = behavior.isFitToContents,
                state = behavior.state,
            )
        }
        return checkNotNull(geometry)
    }

    private fun awaitGeometry(condition: (SheetGeometry) -> Boolean): SheetGeometry {
        val deadline = SystemClock.uptimeMillis() + 5000
        var geometry: SheetGeometry
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            geometry = sheetGeometry()
            if (condition(geometry)) return geometry
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Sheet did not reach the expected geometry: $geometry")
    }

    private data class SheetGeometry(
        val top: Int,
        val bottomGap: Int,
        val height: Int,
        val parentHeight: Int,
        val fitToContents: Boolean,
        val state: Int,
    )

    private fun visibleDialogs(manager: FragmentManager) = manager.fragments.count {
        it is BottomWebViewDialog && it.dialog?.isShowing == true
    }
}
