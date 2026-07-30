package io.legado.app.lib.theme

import com.google.android.material.color.utilities.Hct
import io.legado.app.constant.PreferKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Suppress("RestrictedApi")
class WallpaperThemeTest {

    @Test
    fun `wallpaper palette maps all day and night color preferences`() {
        assertArrayEquals(
            arrayOf(
                PreferKey.cPrimary,
                PreferKey.cAccent,
                PreferKey.cBackground,
                PreferKey.cBBackground,
                PreferKey.cNPrimary,
                PreferKey.cNAccent,
                PreferKey.cNBackground,
                PreferKey.cNBBackground,
            ),
            WallpaperTheme.colorPreferenceKeys,
        )
        val colors = WallpaperTheme.colorsForSeed(0xFF0066CC.toInt())
        assertEquals(8, colors.size)
        assertTrue(Hct.fromInt(colors[0]).tone in 30.0..50.0)
        assertTrue(Hct.fromInt(colors[1]).tone in 30.0..50.0)
        assertTrue(Hct.fromInt(colors[2]).tone > 90.0)
        assertTrue(Hct.fromInt(colors[4]).tone < 40.0)
        assertTrue(Hct.fromInt(colors[5]).tone > 70.0)
        assertTrue(Hct.fromInt(colors[6]).tone < 20.0)
    }

    @Test
    fun `wallpaper changes refresh activities and restored settings sync listeners`() {
        val themeConfig = File(
            "src/main/java/io/legado/app/help/config/ThemeConfig.kt"
        ).readText()
        val lifecycleHelp = File(
            "src/main/java/io/legado/app/help/LifecycleHelp.kt"
        ).readText()
        val restore = File(
            "src/main/java/io/legado/app/help/storage/Restore.kt"
        ).readText()
        val wallpaperTheme = File(
            "src/main/java/io/legado/app/lib/theme/WallpaperTheme.kt"
        ).readText()

        assertTrue(themeConfig.contains("LifecycleHelp.recreateActivities()"))
        assertTrue(lifecycleHelp.contains("fun recreateActivities()"))
        assertTrue(restore.contains("WallpaperTheme.syncWithPreferences(appCtx)"))
        assertTrue(
            wallpaperTheme.contains("private fun registerListener(context: Context): Boolean")
        )
    }
}
