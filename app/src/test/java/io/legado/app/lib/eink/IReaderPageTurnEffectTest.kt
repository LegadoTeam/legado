package io.legado.app.lib.eink

import io.legado.app.lib.eink.IReaderPageTurnEffect.Capability
import io.legado.app.lib.eink.IReaderPageTurnEffect.Effect
import io.legado.app.lib.eink.IReaderPageTurnEffect.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IReaderPageTurnEffectTest {

    @Before
    fun resetFirmware() {
        IntFirmware.calls.clear()
        VoidFirmware.calls.clear()
        CommandOnlyFirmware.calls.clear()
        FailingForceFirmware.calls.clear()
    }

    @Test
    fun `protocol matches the Ocean 6 Pro direction and speed tables`() {
        val next = mapOf(
            Effect.RIPPLE to listOf(1, 4, 2, 3),
            Effect.WATERFALL to listOf(4, 2, 3, 1),
        )
        val previous = mapOf(
            Effect.RIPPLE to listOf(2, 3, 1, 4),
            Effect.WATERFALL to listOf(3, 1, 4, 2),
        )
        val speeds = mapOf(Speed.SLOW to 128, Speed.NORMAL to 64, Speed.FAST to 0)
        for (effect in Effect.entries) {
            for ((speed, flag) in speeds) {
                for (rotation in 0..3) {
                    assertEquals(next.getValue(effect)[rotation] or flag,
                        iReaderPageTurnCode(true, rotation, effect, speed))
                    assertEquals(previous.getValue(effect)[rotation] or flag,
                        iReaderPageTurnCode(false, rotation, effect, speed))
                }
            }
        }
    }

    @Test
    fun `reverse applies to both effects while landscape calibration preserves portrait`() {
        for (effect in Effect.entries) {
            for (rotation in 0..3) {
                for (forward in listOf(true, false)) {
                    val normal = iReaderPageTurnCode(forward, rotation, effect, Speed.NORMAL)
                    val opposite = iReaderPageTurnCode(!forward, rotation, effect, Speed.NORMAL)
                    assertEquals(opposite, iReaderPageTurnCode(
                        forward, rotation, effect, Speed.NORMAL, reverse = true))
                    assertEquals(if (rotation % 2 == 1) opposite else normal,
                        iReaderPageTurnCode(forward, rotation, effect, Speed.NORMAL,
                            reverseLandscape = true))
                    assertEquals(if (rotation % 2 == 1) normal else opposite,
                        iReaderPageTurnCode(forward, rotation, effect, Speed.NORMAL,
                            reverse = true, reverseLandscape = true))
                }
            }
        }
    }

    @Test
    fun `capability resolves once without submitting any display commands`() {
        var resolutions = 0
        val controller = IReaderPageTurnController {
            resolutions++
            IReaderReflectionBridge.create(IntFirmware::class.java)
        }
        repeat(5) { assertEquals(Capability.AVAILABLE, controller.capability()) }
        assertNull(controller.failureReason())
        assertEquals(1, resolutions)
        assertTrue(IntFirmware.calls.isEmpty())
    }

    @Test
    fun `absent interface is cached and never invokes a bridge`() {
        var resolutions = 0
        val controller = IReaderPageTurnController {
            resolutions++
            null
        }
        repeat(5) {
            assertFalse(controller.prepare(true, 0, Effect.RIPPLE))
            assertEquals(Capability.UNAVAILABLE, controller.capability())
        }
        assertEquals(1, resolutions)
        assertNotNull(controller.failureReason())
    }

    @Test
    fun `missing vendor class or inaccessible signatures leave the interface unavailable`() {
        val controller = IReaderPageTurnController {
            throw ClassNotFoundException("android.eink.EPDCDevice")
        }
        assertEquals(Capability.UNAVAILABLE, controller.capability())
        assertFalse(controller.prepare(true, 0, Effect.RIPPLE))
        assertEquals("ClassNotFoundException: android.eink.EPDCDevice", controller.failureReason())
        assertNull(IReaderReflectionBridge.create(InstanceFirmware::class.java))
        assertNull(IReaderReflectionBridge.create(UnrelatedFirmware::class.java))
    }

    @Test
    fun `method channel supports integer return signatures and command ordering`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(IntFirmware::class.java)
        }
        assertTrue(controller.prepare(true, 0, Effect.RIPPLE))
        assertTrue(controller.prepare(false, 0, Effect.WATERFALL, Speed.FAST))
        assertEquals(listOf(
            "next-effect-type 65", "force:16777315",
            "next-effect-type 3", "force:16777315",
        ), IntFirmware.calls)
    }

    @Test
    fun `method channel also supports void return signatures`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(VoidFirmware::class.java)
        }
        assertTrue(controller.prepare(true, 0, Effect.WATERFALL, Speed.SLOW))
        assertEquals(listOf("next-effect-type 132", "force:16777315"), VoidFirmware.calls)
    }

    @Test
    fun `firmware without force method uses the known command with its original numeric format`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(CommandOnlyFirmware::class.java)
        }
        assertTrue(controller.prepare(true, 0, Effect.RIPPLE))
        assertEquals(listOf("next-effect-type 65", "epdc-force-next-post-mode 1000063"),
            CommandOnlyFirmware.calls)
    }

    @Test
    fun `failed effect submission skips force and permanently disables the bridge`() {
        var resolutions = 0
        var commands = 0
        var forced = 0
        val controller = IReaderPageTurnController {
            resolutions++
            object : IReaderPageTurnBridge {
                override fun postEffect(effectCode: Int) {
                    commands++
                    throw UnsatisfiedLinkError("native display method is absent")
                }

                override fun forceNextPost(refreshMode: Int) { forced++ }
            }
        }
        repeat(10) { assertFalse(controller.prepare(true, 0, Effect.RIPPLE)) }
        assertEquals(Capability.FAILED, controller.capability())
        assertEquals(1, resolutions)
        assertEquals(1, commands)
        assertEquals(0, forced)
        assertEquals("UnsatisfiedLinkError: native display method is absent", controller.failureReason())
    }

    @Test
    fun `a throwing force method is never retried using the command channel`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(FailingForceFirmware::class.java)
        }
        repeat(5) { assertFalse(controller.prepare(true, 0, Effect.RIPPLE)) }
        assertEquals(listOf("next-effect-type 65", "force:16777315"), FailingForceFirmware.calls)
        assertEquals(Capability.FAILED, controller.capability())
        assertEquals("IllegalStateException: force failed after invocation", controller.failureReason())
    }

    @Test
    fun `reflection-wrapped virtual machine errors are not downgraded to display failures`() {
        val controller = IReaderPageTurnController {
            object : IReaderPageTurnBridge {
                override fun postEffect(effectCode: Int) {
                    throw java.lang.reflect.InvocationTargetException(OutOfMemoryError("test failure"))
                }

                override fun forceNextPost(refreshMode: Int) = Unit
            }
        }
        assertThrows(OutOfMemoryError::class.java) {
            controller.prepare(true, 0, Effect.RIPPLE)
        }
    }

    @Test
    fun `invalid rotation neither resolves the bridge nor submits an effect`() {
        var resolutions = 0
        val controller = IReaderPageTurnController {
            resolutions++
            IReaderReflectionBridge.create(IntFirmware::class.java)
        }
        for (rotation in listOf(-1, 4, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(controller.prepare(true, rotation, Effect.RIPPLE))
        }
        assertEquals(0, resolutions)
        assertTrue(IntFirmware.calls.isEmpty())
        assertTrue(controller.prepare(true, 0, Effect.RIPPLE))
    }

    @Test
    fun `continuous turns preserve one command pair per page and only one resolution`() {
        var resolutions = 0
        val controller = IReaderPageTurnController {
            resolutions++
            IReaderReflectionBridge.create(CommandOnlyFirmware::class.java)
        }
        repeat(200) { page ->
            assertTrue(controller.prepare(page % 2 == 0, page % 4,
                if (page % 3 == 0) Effect.WATERFALL else Effect.RIPPLE))
        }
        assertEquals(1, resolutions)
        assertEquals(400, CommandOnlyFirmware.calls.size)
        assertEquals(200, CommandOnlyFirmware.calls.count {
            it == "epdc-force-next-post-mode 1000063"
        })
        assertEquals(Capability.AVAILABLE, controller.capability())
    }

    @Test
    fun `stock full and fast refresh modes preserve effect then force ordering`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(IntFirmware::class.java)
        }
        for (mode in listOf(98, 99, 4196, 4197, 135268, 69732)) {
            assertTrue(controller.prepare(true, 0, Effect.RIPPLE, refreshMode = mode))
        }
        assertEquals(listOf(
            "next-effect-type 65", "force:16777314",
            "next-effect-type 65", "force:16777315",
            "next-effect-type 65", "force:16781412",
            "next-effect-type 65", "force:16781413",
            "next-effect-type 65", "force:16912484",
            "next-effect-type 65", "force:16846948",
        ), IntFirmware.calls)
    }

    @Test
    fun `command channel formats stock refresh values using the original hexadecimal notation`() {
        val controller = IReaderPageTurnController {
            IReaderReflectionBridge.create(CommandOnlyFirmware::class.java)
        }
        assertTrue(controller.prepare(true, 0, Effect.RIPPLE, refreshMode = 98))
        assertTrue(controller.prepare(false, 0, Effect.WATERFALL, refreshMode = 135268))
        assertEquals(listOf(
            "next-effect-type 65", "epdc-force-next-post-mode 1000062",
            "next-effect-type 67", "epdc-force-next-post-mode 1021064",
        ), CommandOnlyFirmware.calls)
    }

    @Test
    fun `unknown refresh modes do not resolve or arm the firmware`() {
        var resolutions = 0
        val controller = IReaderPageTurnController {
            resolutions++
            IReaderReflectionBridge.create(IntFirmware::class.java)
        }
        for (mode in listOf(-1, 0, 1, 4099, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(controller.prepare(true, 0, Effect.RIPPLE, refreshMode = mode))
        }
        assertEquals(0, resolutions)
        assertTrue(IntFirmware.calls.isEmpty())
        assertTrue(controller.prepare(true, 0, Effect.RIPPLE))
    }

    class IntFirmware {
        companion object {
            val calls = mutableListOf<String>()
            @JvmStatic fun nativePostCommand(command: String): Int { calls += command; return 7 }
            @JvmStatic fun setForceNextPostMode(mode: Int): Int { calls += "force:$mode"; return 7 }
        }
    }

    class VoidFirmware {
        companion object {
            val calls = mutableListOf<String>()
            @JvmStatic fun nativePostCommand(command: String) { calls += command }
            @JvmStatic fun setForceNextPostMode(mode: Int) { calls += "force:$mode" }
        }
    }

    class CommandOnlyFirmware {
        companion object {
            val calls = mutableListOf<String>()
            @JvmStatic fun nativePostCommand(command: String) { calls += command }
        }
    }

    class FailingForceFirmware {
        companion object {
            val calls = mutableListOf<String>()
            @JvmStatic fun nativePostCommand(command: String) { calls += command }
            @JvmStatic fun setForceNextPostMode(mode: Int) {
                calls += "force:$mode"
                error("force failed after invocation")
            }
        }
    }

    class InstanceFirmware {
        @Suppress("UNUSED_PARAMETER")
        fun nativePostCommand(command: String) = Unit
    }

    class UnrelatedFirmware {
        companion object {
            @JvmStatic @Suppress("UNUSED_PARAMETER")
            fun post(command: String) = Unit
        }
    }
}
