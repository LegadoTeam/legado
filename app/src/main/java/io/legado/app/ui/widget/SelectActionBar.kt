package io.legado.app.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import io.legado.app.R
import io.legado.app.databinding.ViewSelectActionBarBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.TintHelper
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryDisabledTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.visible


@Suppress("unused")
class SelectActionBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val bgIsLight = ColorUtils.isColorLight(context.bottomBackground)
    private val primaryTextColor = context.getPrimaryTextColor(bgIsLight)
    private val disabledColor = context.getSecondaryDisabledTextColor(bgIsLight)

    private var callBack: CallBack? = null
    private var selMenu: Menu? = null
    private var menuItemClickListener: PopupMenu.OnMenuItemClickListener? = null
    private val binding = ViewSelectActionBarBinding
        .inflate(LayoutInflater.from(context), this, true)

    init {
        if (!isInEditMode) {
            val transparentNavBar = context.transparentNavBar
            if (transparentNavBar) {
                setBackgroundColor(Color.TRANSPARENT)
            } else {
                setBackgroundColor(context.bottomBackground)
                elevation = context.elevation
            }
            binding.cbSelectedAll.setTextColor(primaryTextColor)
            TintHelper.setTint(binding.cbSelectedAll, context.accentColor, !bgIsLight)
            binding.ivMenuMore.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN)
            binding.cbSelectedAll.setOnUserCheckedChangeListener { isChecked ->
                callBack?.selectAll(isChecked)
            }
            binding.btnRevertSelection.setOnClickListener { callBack?.revertSelection() }
            binding.btnSelectActionMain.setOnClickListener { callBack?.onClickSelectBarMainAction() }
            binding.ivMenuMore.setOnClickListener { showMoreMenu() }
            applyNavigationBarPadding()
        }
    }

    fun setMainActionText(text: String) = binding.run {
        btnSelectActionMain.text = text
        btnSelectActionMain.visible()
    }

    fun setMainActionText(@StringRes id: Int) = binding.run {
        btnSelectActionMain.setText(id)
        btnSelectActionMain.visible()
    }

    @SuppressLint("RestrictedApi")
    fun inflateMenu(@MenuRes resId: Int): Menu? {
        selMenu = MenuBuilder(context).apply {
            SupportMenuInflater(context).inflate(resId, this)
        }
        binding.ivMenuMore.visible()
        return selMenu
    }

    fun setCallBack(callBack: CallBack) {
        this.callBack = callBack
    }

    fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener) {
        menuItemClickListener = listener
    }

    private fun showMoreMenu() {
        val menuItems = selMenu?.visibleItems().orEmpty()
        if (menuItems.isEmpty()) {
            return
        }
        val binding = this@SelectActionBar.binding
        PopupAction(context).apply {
            setVertical(true)
            setDangerValues(
                menuItems
                    .filter { it.itemId in dangerMenuItemIds }
                    .map { it.itemId.toString() }
                    .toSet()
            )
            setDisabledValues(
                menuItems
                    .filterNot { it.isEnabled }
                    .map { it.itemId.toString() }
                    .toSet()
            )
            setItems(
                menuItems.map { item ->
                    SelectItem(item.title.toString(), item.itemId.toString())
                }
            )
            onActionClick = { action ->
                dismiss()
                menuItems.firstOrNull {
                    it.isEnabled && it.itemId.toString() == action
                }?.let { menuItem ->
                    menuItemClickListener?.onMenuItemClick(menuItem)
                }
            }
            showAsDropDown(binding.ivMenuMore, 0, 4.dpToPx())
        }
    }

    private fun Menu.visibleItems(): List<MenuItem> {
        val result = arrayListOf<MenuItem>()
        for (index in 0 until size()) {
            val item = getItem(index)
            if (item.isVisible) {
                result.add(item)
            }
        }
        return result
    }

    fun upCountView(selectCount: Int, allCount: Int) = binding.run {
        if (selectCount == 0) {
            cbSelectedAll.isChecked = false
        } else {
            cbSelectedAll.isChecked = selectCount >= allCount
        }

        //重置全选的文字
        if (cbSelectedAll.isChecked) {
            cbSelectedAll.text = context.getString(
                R.string.select_cancel_count,
                selectCount,
                allCount
            )
        } else {
            cbSelectedAll.text = context.getString(
                R.string.select_all_count,
                selectCount,
                allCount
            )
        }
        setMenuClickable(selectCount > 0)
    }

    private fun setMenuClickable(isClickable: Boolean) = binding.run {
        btnRevertSelection.isEnabled = isClickable
        btnRevertSelection.isClickable = isClickable
        btnSelectActionMain.isEnabled = isClickable
        btnSelectActionMain.isClickable = isClickable
        if (isClickable) {
            ivMenuMore.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
        } else {
            ivMenuMore.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN)
        }
        ivMenuMore.isEnabled = isClickable
        ivMenuMore.isClickable = isClickable
    }

    companion object {
        private val dangerMenuItemIds = setOf(
            R.id.menu_del_selection,
            R.id.menu_del
        )
    }

    interface CallBack {

        fun selectAll(selectAll: Boolean)

        fun revertSelection()

        fun onClickSelectBarMainAction() {}
    }
}
