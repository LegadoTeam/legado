package io.legado.app.lib.theme

import com.google.android.material.color.utilities.Hct
import io.legado.app.constant.PreferKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(Hct.fromInt(colors[2]).tone > 90.0)
        assertTrue(Hct.fromInt(colors[6]).tone < 20.0)
    }
}
