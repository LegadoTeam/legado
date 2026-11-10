package io.legado.app.lib.eink

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Optional bridge to the page-turn effects supplied by iReader's firmware.
 *
 * Protocol references: GymMickey/legadoM-Ink (IReaderPageH) and
 * DragonAura/iReader-ripple (RippleEngine). Nothing from android.eink is linked
 * or bundled: ordinary Android devices can load this
 * class without the vendor framework. Resolve the bridge once, without submitting
 * commands; only [prepare] touches the display controller.
 */
internal object IReaderPageTurnEffect {

    enum class Effect {
        RIPPLE, WATERFALL
    }

    enum class Speed(internal val commandFlag: Int) {
        SLOW(128), NORMAL(64), FAST(0)
    }

    enum class Capability {
        UNAVAILABLE, AVAILABLE, FAILED
    }

    private val controller by lazy {
        IReaderPageTurnController {
            // Probing must not run a vendor static initializer or load its native
            // library. Initialization is deferred until the first actual command.
            val epdcClass = Class.forName(
                "android.eink.EPDCDevice", false, IReaderPageTurnEffect::class.java.classLoader
            )
            IReaderReflectionBridge.create(epdcClass)
        }
    }

    /** AVAILABLE means the known method signatures exist, not a hardware test. */
    fun capability(): Capability = controller.capability()

    fun isAvailable(): Boolean = capability() == Capability.AVAILABLE

    fun failureReason(): String? = controller.failureReason()

    /**
     * Arm the next submitted frame, immediately before drawing the committed page.
     * A successful return only confirms that both calls completed without throwing.
     * Any invocation failure disables the bridge for the remainder of this process.
     */
    fun prepare(
        forward: Boolean,
        rotation: Int,
        effect: Effect,
        speed: Speed = Speed.NORMAL,
        reverse: Boolean = false,
        reverseLandscape: Boolean = false,
        refreshMode: Int = IReaderRefreshModes.DEFAULT,
    ): Boolean = controller.prepare(
        forward, rotation, effect, speed, reverse, reverseLandscape, refreshMode
    )
}

internal interface IReaderPageTurnBridge {
    fun postEffect(effectCode: Int)
    fun forceNextPost(refreshMode: Int)
}

/** The protocol and failure state are independent of Android views and settings. */
internal class IReaderPageTurnController(
    private val bridgeFactory: () -> IReaderPageTurnBridge?,
) {
    private var resolved = false
    private var bridge: IReaderPageTurnBridge? = null
    private var state = IReaderPageTurnEffect.Capability.UNAVAILABLE
    private var failure: String? = null

    @Synchronized
    fun capability(): IReaderPageTurnEffect.Capability {
        if (!resolved) {
            resolved = true
            try {
                bridge = bridgeFactory()
                if (bridge != null) {
                    state = IReaderPageTurnEffect.Capability.AVAILABLE
                } else {
                    failure = "The iReader display interface is unavailable"
                }
            } catch (error: Throwable) {
                failure = describeIReaderFailure(error)
            }
        }
        return state
    }

    @Synchronized
    fun failureReason(): String? {
        capability()
        return failure
    }

    @Synchronized
    fun prepare(
        forward: Boolean,
        rotation: Int,
        effect: IReaderPageTurnEffect.Effect,
        speed: IReaderPageTurnEffect.Speed = IReaderPageTurnEffect.Speed.NORMAL,
        reverse: Boolean = false,
        reverseLandscape: Boolean = false,
        refreshMode: Int = IReaderRefreshModes.DEFAULT,
    ): Boolean {
        // Reject an unknown orientation without arming an unrelated future frame.
        if (rotation !in 0..3 || !IReaderRefreshModes.isKnown(refreshMode)) return false
        if (capability() != IReaderPageTurnEffect.Capability.AVAILABLE) return false
        val activeBridge = bridge ?: return false
        val code = iReaderPageTurnCode(
            forward, rotation, effect, speed, reverse, reverseLandscape
        )
        return try {
            activeBridge.postEffect(code)
            activeBridge.forceNextPost(refreshMode)
            true
        } catch (error: Throwable) {
            failure = describeIReaderFailure(error)
            bridge = null
            state = IReaderPageTurnEffect.Capability.FAILED
            false
        }
    }
}

/**
 * Rotation uses Surface.ROTATION_* values (0..3), without depending on Android.
 * The Ocean 6 Pro community implementation is the default orientation mapping.
 */
internal fun iReaderPageTurnCode(
    forward: Boolean,
    rotation: Int,
    effect: IReaderPageTurnEffect.Effect,
    speed: IReaderPageTurnEffect.Speed,
    reverse: Boolean = false,
    reverseLandscape: Boolean = false,
): Int {
    require(rotation in 0..3)
    val physicalForward = forward xor reverse xor (reverseLandscape && rotation % 2 == 1)
    val direction = when (effect) {
        IReaderPageTurnEffect.Effect.RIPPLE -> when (rotation) {
            0 -> if (physicalForward) 1 else 2
            1 -> if (physicalForward) 4 else 3
            2 -> if (physicalForward) 2 else 1
            else -> if (physicalForward) 3 else 4
        }

        IReaderPageTurnEffect.Effect.WATERFALL -> when (rotation) {
            0 -> if (physicalForward) 4 else 3
            1 -> if (physicalForward) 2 else 1
            2 -> if (physicalForward) 3 else 4
            else -> if (physicalForward) 1 else 2
        }
    }
    return direction or speed.commandFlag
}

internal class IReaderReflectionBridge private constructor(
    private val postCommand: Method,
    private val forceNextPostMode: Method?,
) : IReaderPageTurnBridge {

    override fun postEffect(effectCode: Int) {
        // Method.invoke safely boxes either int or void vendor return signatures.
        // Return-code semantics are undocumented, so do not invent success codes.
        postCommand.invoke(null, "next-effect-type $effectCode")
    }

    override fun forceNextPost(refreshMode: Int) {
        require(IReaderRefreshModes.isKnown(refreshMode))
        val postMode = FORCE_NEXT_FLAG or refreshMode
        if (forceNextPostMode != null) {
            forceNextPostMode.invoke(null, postMode)
        } else {
            // The community command represents the post mode in hexadecimal without
            // a prefix. Keep that representation for every stock-verified mode.
            postCommand.invoke(null, "epdc-force-next-post-mode ${postMode.toString(16)}")
        }
        // Never retry with the other channel after an invocation: a throwing
        // vendor method may already have changed state before reporting failure.
    }

    companion object {
        internal const val FORCE_NEXT_FLAG = 0x01000000

        fun create(epdcClass: Class<*>): IReaderPageTurnBridge? {
            val post = epdcClass.staticMethod("nativePostCommand", String::class.java)
                ?: return null
            val force = epdcClass.staticMethod("setForceNextPostMode", Int::class.javaPrimitiveType!!)
            return IReaderReflectionBridge(post, force)
        }

        private fun Class<*>.staticMethod(name: String, vararg arguments: Class<*>): Method? {
            val method = try {
                getMethod(name, *arguments)
            } catch (_: NoSuchMethodException) {
                return null
            }
            return method.takeIf { Modifier.isStatic(it.modifiers) }
        }
    }
}

internal fun describeIReaderFailure(error: Throwable): String {
    var cause = error
    while (cause is InvocationTargetException) {
        cause = cause.targetException ?: break
    }
    // A compatibility fallback must not hide process-level failures such as OOM.
    if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
    return buildString {
        append(cause.javaClass.simpleName)
        cause.message?.takeIf { it.isNotBlank() }?.let {
            append(": ")
            append(it.take(240))
        }
    }
}
