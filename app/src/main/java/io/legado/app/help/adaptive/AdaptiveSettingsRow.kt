package io.legado.app.help.adaptive

import android.content.Context
import android.widget.LinearLayout

/** Side-by-side settings panes contain scrolling content, not aligned text baselines. */
class AdaptiveSettingsRow(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        // Weighted children otherwise receive an UNSPECIFIED height during baseline probing.
        // Compose LazyColumn rejects that probe before the final bounded measurement runs.
        isBaselineAligned = false
    }
}
