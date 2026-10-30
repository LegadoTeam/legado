package io.legado.app.help.adaptive

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AdaptiveSettingsRowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class ScrollProbe(context: Context) : View(context) {
        var measurements = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            check(MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                "Scrollable content received infinite height"
            }
            measurements++
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun tree(row: LinearLayout, sidebar: Boolean): Pair<LinearLayout, ScrollProbe> {
        val probe = ScrollProbe(context)
        row.addView(View(context).apply { visibility = if (sidebar) View.VISIBLE else View.GONE },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        row.addView(FrameLayout(context).apply { addView(probe) },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 56))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } to probe
    }

    private fun measure(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    @Test(expected = IllegalStateException::class)
    fun originalBaselineProbeReproducesInfiniteHeight() {
        val (root, _) = tree(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }, false)
        measure(root, 1200, 900)
    }

    @Test fun singlePaneReceivesRemainingHeight() {
        val (root, probe) = tree(AdaptiveSettingsRow(context), false)
        measure(root, 600, 900)
        assertEquals(844, probe.measuredHeight)
        assertEquals(600, probe.measuredWidth)
        assertTrue(probe.measurements > 0)
    }

    @Test fun doublePaneRemainsBoundedAfterResize() {
        val (root, probe) = tree(AdaptiveSettingsRow(context), true)
        measure(root, 1200, 900)
        assertEquals(600, probe.measuredWidth)
        assertEquals(844, probe.measuredHeight)
        measure(root, 1600, 700)
        assertEquals(800, probe.measuredWidth)
        assertEquals(644, probe.measuredHeight)
    }
}
