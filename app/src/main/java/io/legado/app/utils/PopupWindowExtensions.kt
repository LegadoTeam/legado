package io.legado.app.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import io.legado.app.lib.theme.ThemeStore

fun PopupWindow.applyMd3PopupStyle() {
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    elevation = 8f.dpToPx()
    contentView?.let { it.background = ColorDrawable(ThemeStore.backgroundColor(it.context)) }
    isClippingEnabled = true
}
