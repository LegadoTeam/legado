package io.legado.app.ui.widget

import android.content.Context
import android.view.View
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.dpToPx

class PopupActionMenuBuilder(private val context: Context) {

    private val items = arrayListOf<SelectItem<String>>()
    private val dangerValues = linkedSetOf<String>()

    fun item(title: String, value: String, visible: Boolean = true): PopupActionMenuBuilder {
        if (visible) items.add(SelectItem(title, value))
        return this
    }

    fun danger(vararg values: String): PopupActionMenuBuilder {
        dangerValues.addAll(values)
        return this
    }

    fun show(anchor: View, onClick: (String) -> Unit) {
        PopupAction(context).apply {
            setVertical(true)
            setDangerValues(dangerValues)
            setItems(items)
            onActionClick = { action ->
                dismiss()
                onClick(action)
            }
            showAsDropDown(anchor, 0, 4.dpToPx())
        }
    }
}

fun popupActionMenu(
    context: Context,
    block: PopupActionMenuBuilder.() -> Unit
): PopupActionMenuBuilder = PopupActionMenuBuilder(context).apply(block)
