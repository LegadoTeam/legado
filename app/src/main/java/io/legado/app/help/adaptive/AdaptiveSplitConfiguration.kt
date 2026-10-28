package io.legado.app.help.adaptive

import androidx.window.embedding.DividerAttributes
import androidx.window.embedding.EmbeddingAspectRatio
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitPairRule
import androidx.window.embedding.SplitRule

/** Initial rules only. The window extension owns the live ratio after a divider drag. */
internal object AdaptiveSplitConfiguration {
    const val MIN_DRAG_RATIO = 0.30f
    const val MAX_DRAG_RATIO = 0.70f

    fun attributes(extension: Int): SplitAttributes {
        val builder = SplitAttributes.Builder()
            .setSplitType(SplitAttributes.SplitType.SPLIT_TYPE_EQUAL)
            .setLayoutDirection(SplitAttributes.LayoutDirection.LEFT_TO_RIGHT)
        if (AdaptivePanePolicy.allowDraggableDivider(extension)) {
            builder.setDividerAttributes(DividerAttributes.DraggableDividerAttributes.Builder()
                .setWidthDp(4).setColor(0xff888888.toInt())
                .setDragRange(DividerAttributes.DragRange.SplitRatioDragRange(MIN_DRAG_RATIO, MAX_DRAG_RATIO))
                .setDraggingToFullscreenAllowed(false).build())
        }
        return builder.build()
    }

    fun rule(pairs: Set<SplitPairFilter>, extension: Int): SplitPairRule = SplitPairRule.Builder(pairs)
        .setTag("adaptive_navigation")
        .setMinWidthDp(AdaptivePanePolicy.MIN_WIDTH_DP)
        .setMinHeightDp(AdaptivePanePolicy.MIN_HEIGHT_DP)
        .setMinSmallestWidthDp(0)
        .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
        .setMaxAspectRatioInLandscape(EmbeddingAspectRatio.ALWAYS_ALLOW)
        .setDefaultSplitAttributes(attributes(extension))
        .setFinishPrimaryWithSecondary(SplitRule.FinishBehavior.NEVER)
        .setFinishSecondaryWithPrimary(SplitRule.FinishBehavior.ALWAYS)
        .setClearTop(false).build()
}
