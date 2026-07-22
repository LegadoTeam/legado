package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    private val themeIconTint: ColorStateList

    /** menu id -> 默认矢量图标; applySkin(null) 时据此还原 */
    private val defaultIcons = mapOf(
        R.id.menu_bookshelf to R.drawable.ic_bottom_books,
        R.id.menu_discovery to R.drawable.ic_bottom_explore,
        R.id.menu_rss to R.drawable.ic_bottom_rss_feed,
        R.id.menu_my_config to R.drawable.ic_bottom_person,
    )

    init {
        val transparentNavBar = context.transparentNavBar
        val bgColor = context.bottomBackground
        if (transparentNavBar) {
            setBackgroundColor(Color.TRANSPARENT)
        } else {
            setBackgroundColor(bgColor)
            elevation = context.elevation
        }
        val textIsDark = ColorUtils.isColorLight(bgColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context))
            .create()
        themeIconTint = colorStateList
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        if (AppConfig.isEInkMode || transparentNavBar) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = Color.TRANSPARENT.toDrawable()
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    /**
     * 应用/取消底栏图集。
     * @param iconMap menu id -> StateListDrawable; 传 null 表示恢复默认主题图标
     * @param iconSizePx 有皮肤时的图标尺寸(像素)
     */
    fun applySkin(iconMap: Map<Int, StateListDrawable>?, iconSizePx: Int) {
        if (iconMap == null) {
            itemIconTintList = themeIconTint
            itemIconSize = 24.dpToPx()
            defaultIcons.forEach { (id, res) ->
                menu.findItem(id)?.icon = defaultIcon(res, tinted = false)
            }
        } else {
            itemIconTintList = null
            itemIconSize = iconSizePx
            defaultIcons.keys.forEach { id ->
                menu.findItem(id)?.icon =
                    iconMap[id] ?: defaultIcon(defaultIcons.getValue(id), tinted = true)
            }
        }
    }

    private fun defaultIcon(resId: Int, tinted: Boolean) =
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            if (tinted) DrawableCompat.setTintList(this, themeIconTint)
        }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}
