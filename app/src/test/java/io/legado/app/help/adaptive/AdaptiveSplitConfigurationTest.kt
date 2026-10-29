package io.legado.app.help.adaptive

import android.app.Application
import android.content.ComponentName
import android.graphics.Rect
import androidx.window.embedding.DividerAttributes
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitRule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AdaptiveSplitConfigurationTest {
    @Test fun supportedExtensionsUseExplicitInteriorRangeWithoutFullscreen() {
        for (extension in listOf(6, 10)) {
            val divider = AdaptiveSplitConfiguration.attributes(extension).dividerAttributes
                as DividerAttributes.DraggableDividerAttributes
            val range = divider.dragRange as DividerAttributes.DragRange.SplitRatioDragRange
            assertEquals(0.30f, range.minRatio, 0f)
            assertEquals(0.70f, range.maxRatio, 0f)
            assertFalse(divider.isDraggingToFullscreenAllowed)
            assertTrue(range.minRatio > 0f && range.maxRatio < 1f)
        }
    }

    @Test fun legacyExtensionHasNoNonfunctionalDragHandle() {
        assertEquals(DividerAttributes.NO_DIVIDER,
            AdaptiveSplitConfiguration.attributes(5).dividerAttributes)
    }

    private fun rule() = AdaptiveSplitConfiguration.rule(setOf(SplitPairFilter(
        ComponentName("io.legado.app.debug", AdaptivePanePolicy.MAIN_ACTIVITY),
        ComponentName("io.legado.app.debug", "io.legado.app.ui.config.ConfigActivity"), null)), 6)

    @Test fun nativeRuleKeepsRootAndAllowsSecondaryBackStack() {
        val rule = rule()
        assertEquals(SplitRule.FinishBehavior.NEVER, rule.finishPrimaryWithSecondary)
        assertEquals(SplitRule.FinishBehavior.ALWAYS, rule.finishSecondaryWithPrimary)
        assertFalse(rule.clearTop)
        assertEquals(SplitAttributes.SplitType.SPLIT_TYPE_EQUAL, rule.defaultSplitAttributes.splitType)
    }

    @Test fun nativeWindowRuleAcceptsFoldableBoundsAndRejectsCompactWindows() {
        val rule = rule()
        // Exercise WindowManager's actual task-bound constraints, not a duplicate policy.
        val check = SplitRule::class.java.declaredMethods.first { it.name.startsWith("checkParentBounds") }
        fun accepts(width: Int, height: Int) = check.invoke(rule, 2f, Rect(0, 0, width, height)) as Boolean
        assertTrue(accepts(1320, 1440)) // 660x720dp inner display
        assertTrue(accepts(1200, 960)) // exact 600x480dp boundary
        assertFalse(accepts(1198, 1440))
        assertFalse(accepts(1320, 958))
    }
}
