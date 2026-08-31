package io.legado.app.lib.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import org.json.JSONArray

object WallpaperTheme {

    internal val colorPreferenceKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
    )

    private var listener: Any? = null
    private var applyingColors = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    internal val isApplyingColors: Boolean
        get() = applyingColors

    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @MainThread
    fun setFollow(
        context: Context,
        enabled: Boolean,
        autoUpdate: Boolean,
        restoreColors: Boolean = true,
    ): Boolean {
        if (!enabled) {
            unregisterListener(context)
            if (restoreColors) {
                //主动关闭跟随,恢复开启前备份的颜色
                restoreBackedUpColors(context)
            } else {
                //手动改色导致退出跟随,以手动颜色为准,丢弃备份
                discardBackedUpColors(context)
            }
            context.defaultSharedPreferences.edit {
                putBoolean(PreferKey.wallpaperColorFollow, false)
            }
            return true
        }
        if (!isAvailable()) return false
        val seed = readWallpaperSeed(context) ?: return false
        if (autoUpdate && !registerListener(context)) return false
        if (!autoUpdate) unregisterListener(context)
        backupColors(context)
        context.defaultSharedPreferences.edit {
            putBoolean(PreferKey.wallpaperColorFollow, true)
            putBoolean(PreferKey.wallpaperColorAutoUpdate, autoUpdate)
        }
        applyColors(context, colorsForSeed(seed), recreate = true)
        return true
    }

    @MainThread
    fun onColorPreferenceChanged(context: Context) {
        if (applyingColors || !context.getPrefBoolean(PreferKey.wallpaperColorFollow)) return
        setFollow(context, enabled = false, autoUpdate = false, restoreColors = false)
    }

    @MainThread
    fun syncWithPreferences(context: Context) {
        unregisterListener(context)
        if (!isAvailable()) return
        if (!context.getPrefBoolean(PreferKey.wallpaperColorFollow)) return
        if (!context.getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)) return
        applyCurrentColors(context, recreate = false)
        if (!registerListener(context)) {
            context.defaultSharedPreferences.edit {
                putBoolean(PreferKey.wallpaperColorAutoUpdate, false)
            }
        }
    }

    @Suppress("RestrictedApi")
    internal fun colorsForSeed(seed: Int): IntArray {
        val dynamicColors = MaterialDynamicColors()
        val source = Hct.fromInt(seed)
        val day = SchemeContent(Hct.fromInt(seed), false, 0.0)
        val night = SchemeContent(Hct.fromInt(seed), true, 0.0)
        return intArrayOf(
            dynamicColors.primary().getArgb(day),
            dynamicColors.secondary().getArgb(day),
            dynamicColors.background().getArgb(day),
            dynamicColors.surfaceVariant().getArgb(day),
            Hct.from(source.hue, source.chroma, 30.0).toInt(),
            dynamicColors.primary().getArgb(night),
            dynamicColors.background().getArgb(night),
            dynamicColors.surfaceVariant().getArgb(night),
        )
    }

    private fun applyCurrentColors(context: Context, recreate: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val colors = readWallpaperSeed(context)?.let(::colorsForSeed) ?: return false
        applyColors(context, colors, recreate)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readWallpaperSeed(context: Context): Int? = runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.toArgb()
    }.getOrNull()

    //首次开启跟随时备份当前颜色,备份存在则不覆盖
    private fun backupColors(context: Context) {
        val preferences = context.defaultSharedPreferences
        if (preferences.getString(PreferKey.wallpaperColorBackup, null) != null) return
        val colors = JSONArray()
        colorPreferenceKeys.forEach { key ->
            colors.put(preferences.getInt(key, Int.MIN_VALUE))
        }
        preferences.edit {
            putString(PreferKey.wallpaperColorBackup, colors.toString())
        }
    }

    //恢复备份的颜色并清除备份,未备份过的颜色键移除以回落默认值
    private fun restoreBackedUpColors(context: Context) {
        val preferences = context.defaultSharedPreferences
        val backup = preferences.getString(PreferKey.wallpaperColorBackup, null) ?: return
        val colors = runCatching { JSONArray(backup) }.getOrNull() ?: return
        applyingColors = true
        try {
            preferences.edit {
                colorPreferenceKeys.indices.forEach { index ->
                    val color = colors.optInt(index, Int.MIN_VALUE)
                    if (color == Int.MIN_VALUE) {
                        remove(colorPreferenceKeys[index])
                    } else {
                        putInt(colorPreferenceKeys[index], color)
                    }
                }
                remove(PreferKey.wallpaperColorBackup)
            }
        } finally {
            applyingColors = false
        }
        ThemeConfig.applyDayNight(context, recreateAllActivities = true)
    }

    private fun discardBackedUpColors(context: Context) {
        context.defaultSharedPreferences.edit {
            remove(PreferKey.wallpaperColorBackup)
        }
    }

    private fun applyColors(context: Context, colors: IntArray, recreate: Boolean) {
        val preferences = context.defaultSharedPreferences
        val colorsUnchanged = colorPreferenceKeys.indices.all { index ->
            preferences.getInt(colorPreferenceKeys[index], Int.MIN_VALUE) == colors[index]
        }
        if (colorsUnchanged) return
        applyingColors = true
        try {
            preferences.edit {
                colorPreferenceKeys.indices.forEach { index ->
                    putInt(colorPreferenceKeys[index], colors[index])
                }
            }
        } finally {
            applyingColors = false
        }
        if (recreate) {
            ThemeConfig.applyDayNight(context, recreateAllActivities = true)
        }
    }

    @MainThread
    private fun registerListener(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (listener != null) return true
        val appContext = context.applicationContext
        val colorsChangedListener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
            val seed = colors?.primaryColor?.toArgb() ?: return@OnColorsChangedListener
            if (!appContext.getPrefBoolean(PreferKey.wallpaperColorFollow)) {
                return@OnColorsChangedListener
            }
            if (!appContext.getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)) {
                return@OnColorsChangedListener
            }
            applyColors(appContext, colorsForSeed(seed), recreate = true)
        }
        return runCatching {
            WallpaperManager.getInstance(appContext)
                .addOnColorsChangedListener(colorsChangedListener, mainHandler)
            listener = colorsChangedListener
        }.isSuccess
    }

    @MainThread
    private fun unregisterListener(context: Context) {
        val registeredListener = listener ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            listener = null
            return
        }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            WallpaperManager.getInstance(context.applicationContext)
                .removeOnColorsChangedListener(
                    registeredListener as WallpaperManager.OnColorsChangedListener
                )
        }.onSuccess {
            listener = null
        }
    }
}
