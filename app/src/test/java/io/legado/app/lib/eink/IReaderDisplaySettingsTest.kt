package io.legado.app.lib.eink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class IReaderDisplaySettingsTest {
    @Test
    fun `documented waveforms select the corresponding stock post modes`() {
        val expected5Bit = mapOf(
            4097 to 98,
            4098 to 99,
            4099 to 99,
            4100 to 135268,
            4101 to 69732,
            4102 to 4197,
            4103 to 99,
            4104 to 4196,
        )
        for ((pattern, mode) in expected5Bit) {
            assertEquals(mode, iReaderRefreshMode(pattern, "0x500"))
            assertEquals(if (pattern == 4097) 98 else 99, iReaderRefreshMode(pattern, "0x400"))
        }
    }

    @Test
    fun `missing or unknown waveform never guesses an accelerated post mode`() {
        for (waveform in listOf(null, "", "bad-value", "0x600")) {
            for (pattern in listOf(null, 4099, 4100, 4101, 4102, 4104, Int.MAX_VALUE)) {
                assertEquals(99, iReaderRefreshMode(pattern, waveform))
            }
            assertEquals(98, iReaderRefreshMode(4097, waveform))
        }
        assertEquals(135268, iReaderRefreshMode(4100, " 0X500\n"))
        assertEquals(99, iReaderRefreshMode(0, "0x500"))
    }

    @Test
    fun `post mode values and package profile indexes are kept distinct`() {
        assertEquals(135268, IReaderDisplaySettings.Snapshot(
            readRefreshPattern = 4100, waveform = "0x500", packageRefreshMode = 2,
        ).refreshMode)
        assertEquals(false, IReaderDisplaySettings.Snapshot(packageRefreshMode = 2)
            .effectSupportedBySystemMode)
        for (mode in listOf(0, 1, 5)) {
            assertEquals(true, IReaderDisplaySettings.Snapshot(packageRefreshMode = mode)
                .effectSupportedBySystemMode)
        }
        for (mode in listOf(2, 4, 6)) {
            assertEquals(false, IReaderDisplaySettings.Snapshot(packageRefreshMode = mode)
                .effectSupportedBySystemMode)
        }
        for (mode in listOf(null, -1, 3, 99)) {
            // A default package profile may come from dpilist, not the global mode.
            assertNull(IReaderDisplaySettings.Snapshot(packageRefreshMode = mode, systemRefreshMode = 2)
                .effectSupportedBySystemMode)
        }
    }

    @Test
    fun `system full refresh ownership preserves the stock hardware and feature distinction`() {
        assertEquals(true, iReaderFullRefreshControlledBySystem("mt8786", true))
        assertEquals(false, iReaderFullRefreshControlledBySystem("mt8786", false))
        assertNull(iReaderFullRefreshControlledBySystem("mt8786", null))
        assertEquals(false, iReaderFullRefreshControlledBySystem("rk356x", true))
        assertEquals(false, iReaderFullRefreshControlledBySystem("RK3568", null))
        assertEquals(true, iReaderFullRefreshControlledBySystem("mt6771", null))
        assertNull(iReaderFullRefreshControlledBySystem(null, true))
    }

    @Test
    fun `optional settings API resolves lazily and reads only exact static int getters`() {
        var resolutions = 0
        val api = IReaderReadOnlyApi {
            resolutions++
            ReadOnlyFirmware::class.java
        }
        assertEquals(0, resolutions)
        assertEquals(5, api.int("getSystemRefreshFreq"))
        assertEquals(1, api.int("getPackageMode", "com.legado.app.debug"))
        assertEquals(1, resolutions)
        assertThrows(NoSuchMethodException::class.java) { api.int("getPackageMode") }
        assertThrows(IllegalArgumentException::class.java) { api.int("getSystemRefreshMode") }
        assertThrows(IllegalArgumentException::class.java) { api.int("getInstanceGetter") }
    }

    @Test
    fun `unavailable optional settings API is cached without affecting other bridges`() {
        var resolutions = 0
        val api = IReaderReadOnlyApi {
            resolutions++
            throw ClassNotFoundException("android.eink.api.EinkAPI")
        }
        repeat(5) { assertThrows(IllegalStateException::class.java) { api.int("getSystemRefreshFreq") } }
        assertEquals(1, resolutions)
        val controller = IReaderPageTurnController { object : IReaderPageTurnBridge {
            override fun postEffect(effectCode: Int) = Unit
            override fun forceNextPost(refreshMode: Int) = Unit
        } }
        assertTrue(controller.prepare(true, 0, IReaderPageTurnEffect.Effect.RIPPLE))
    }

    @Test
    fun `a denied optional getter does not poison other diagnostic reads`() {
        val api = IReaderReadOnlyApi { ReadOnlyFirmware::class.java }
        val failure = assertThrows(InvocationTargetException::class.java) { api.int("deniedGetter") }
        assertEquals("SecurityException: permission denied", describeIReaderFailure(failure))
        assertEquals(5, api.int("getSystemRefreshFreq"))
        assertThrows(OutOfMemoryError::class.java) {
            describeIReaderFailure(InvocationTargetException(OutOfMemoryError("test")))
        }
    }

    @Test
    fun `only known stock refresh modes may reach the controller`() {
        for (mode in listOf(98, 99, 4196, 4197, 135268, 69732)) assertTrue(IReaderRefreshModes.isKnown(mode))
        for (mode in listOf(-1, 0, 4097, 4099, Int.MAX_VALUE)) assertFalse(IReaderRefreshModes.isKnown(mode))
    }

    class ReadOnlyFirmware {
        fun getInstanceGetter(): Int = 1

        companion object {
            @JvmStatic fun getSystemRefreshFreq(): Int = 5
            @JvmStatic fun getPackageMode(packageName: String): Int {
                require(packageName == "com.legado.app.debug")
                return 1
            }
            @JvmStatic fun getSystemRefreshMode(): String = "1"
            @JvmStatic fun deniedGetter(): Int = throw SecurityException("permission denied")
        }
    }
}
