package io.legado.app.utils

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import io.legado.app.R
import io.legado.app.ui.widget.PopupAction

private class OverflowMenuState {
    var showIcons = true
    var onPrepareMenu: (Menu) -> Unit = {}
    var onOpenCustomMenu: (Menu) -> Unit = {}
    var onShowCustomMenu: ((View, Menu) -> Boolean)? = null
}

fun Toolbar.installMd3OverflowMenu(
    showIcons: Boolean = true,
    onPrepareMenu: (Menu) -> Unit = {},
    onOpenCustomMenu: (Menu) -> Unit = {},
    onShowCustomMenu: ((View, Menu) -> Boolean)? = null
) {
    val state = (getTag(R.id.toolbar_overflow_menu_state) as? OverflowMenuState)
        ?: OverflowMenuState().also { newState ->
            setTag(R.id.toolbar_overflow_menu_state, newState)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateMd3OverflowMenu(newState)
            }
        }
    state.showIcons = showIcons
    state.onPrepareMenu = onPrepareMenu
    state.onOpenCustomMenu = onOpenCustomMenu
    state.onShowCustomMenu = onShowCustomMenu
    updateMd3OverflowMenu(state)
}

private fun Toolbar.updateMd3OverflowMenu(state: OverflowMenuState) {
    post {
        val overflowDescription = runCatching {
            context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
        }.getOrNull()
        findOverflowAnchor(overflowDescription)?.setOnClickListener { anchor ->
            showMd3OverflowMenu(anchor, state)
        }
    }
}

@SuppressLint("RestrictedApi")
private fun Toolbar.showMd3OverflowMenu(anchor: View, state: OverflowMenuState) {
    state.onPrepareMenu(menu)
    val menuBuilder = menu as? MenuBuilder
    var actionItems = menu.visibleOverflowItems()
    if (menuBuilder == null || actionItems.isEmpty() || actionItems.hasUnsupportedItems()) {
        showOverflowMenu()
        return
    }

    state.onOpenCustomMenu(menu)
    if (state.onShowCustomMenu?.invoke(anchor, menu) == true) {
        return
    }
    actionItems = menu.visibleOverflowItems()
    if (actionItems.isEmpty()) return
    if (actionItems.hasUnsupportedItems()) {
        showOverflowMenu()
        return
    }

    PopupAction(context).apply {
        setVertical(true)
        setActionItems(
            actionItems.mapIndexed { index, item ->
                PopupAction.PopupActionItem(
                    title = item.title?.toString().orEmpty(),
                    value = index.toString(),
                    icon = if (state.showIcons) item.icon.copyForPopup() else null,
                    enabled = item.isEnabled,
                    checkable = item.isCheckable,
                    checked = item.isChecked
                )
            }
        )
        onActionClick = { action ->
            dismiss()
            action.toIntOrNull()
                ?.let(actionItems::getOrNull)
                ?.let { menuItem -> menuBuilder.performItemAction(menuItem, 0) }
        }
        showAsDropDown(anchor, 0, 4.dpToPx())
    }
}

private fun List<MenuItem>.hasUnsupportedItems(): Boolean {
    return any { item -> item.subMenu != null || item.actionView != null }
}

@SuppressLint("RestrictedApi")
private fun Menu.visibleOverflowItems(): List<MenuItem> {
    val result = arrayListOf<MenuItem>()
    for (index in 0 until size()) {
        val item = getItem(index)
        if (!item.isVisible) continue
        val impl = item as? MenuItemImpl ?: continue
        if (impl.requiresOverflow() || impl.requestsActionButton() && !impl.isActionButton) {
            result.add(item)
        }
    }
    return result
}

private fun View.findOverflowAnchor(overflowDescription: String?): View? {
    if (this is ImageView) {
        val params = layoutParams
        if (params is ActionMenuView.LayoutParams && params.isOverflowButton) return this
        if (overflowDescription != null && contentDescription == overflowDescription) return this
    }
    if (this is ViewGroup) {
        for (child in children) {
            child.findOverflowAnchor(overflowDescription)?.let { return it }
        }
    }
    return null
}

private fun android.graphics.drawable.Drawable?.copyForPopup(): android.graphics.drawable.Drawable? {
    return this?.constantState?.newDrawable()?.mutate() ?: this
}
