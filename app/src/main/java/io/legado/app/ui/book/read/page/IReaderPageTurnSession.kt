package io.legado.app.ui.book.read.page

import android.view.View
import android.view.ViewTreeObserver
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.eink.IReaderDisplaySettings
import io.legado.app.lib.eink.IReaderPageTurnEffect
import io.legado.app.lib.eink.IReaderRefreshModes

/** Coordinates one committed page with the optional firmware bridge and cleaning cycle. */
internal class IReaderPageTurnSession(
    private val view: View,
    private val position: () -> NativePageTurnGate.Position?,
    private val frame: () -> NativePageTurnGate.Frame?,
    private val enabled: () -> Boolean,
    private val positionVersion: () -> Long,
) {
    private val gate = NativePageTurnGate()
    private val refresh = NativePageTurnRefreshSession()
    private var observer: ViewTreeObserver? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null

    fun warmUp() {
        if (IReaderPageTurnEffect.isAvailable()) IReaderDisplaySettings.warmUp()
    }

    /** Capture the ready baseline before navigation mutates the current page binding. */
    fun beforeTurn(): Int? {
        if (!enabled()) return null
        gate.seedIfNeeded(frame())
        return view.display?.rotation ?: 0
    }

    fun request(forward: Boolean, rotation: Int) {
        val target = position() ?: return cancel()
        gate.request(target, forward, rotation, positionVersion())
        if (listener != null) return
        val tree = view.viewTreeObserver.takeIf { it.isAlive } ?: return cancel()
        val onPreDraw = ViewTreeObserver.OnPreDrawListener {
            prepareFrame()
            true
        }
        observer = tree
        listener = onPreDraw
        tree.addOnPreDrawListener(onPreDraw)
    }

    fun frameDrawn() {
        val current = frame()
        gate.frameDrawn(current)
        refresh.frameDrawn(current)
    }

    fun cancel() {
        gate.clear()
        refresh.cancelPending()
        removeListener()
    }

    fun reset() {
        cancel()
        gate.reset()
    }

    private fun prepareFrame() {
        val current = frame()
        val forward = gate.consume(
            position(), current, view.display?.rotation ?: 0, positionVersion(), enabled(),
        )
        if (!gate.hasPending) removeListener()
        if (forward == null || current == null) return
        val interval = AppConfig.iReaderPageTurnRefreshInterval
        val mode = if (refresh.isFullRefreshDue(current.bookUrl, interval)) {
            IReaderRefreshModes.FULL
        } else {
            IReaderDisplaySettings.readRefreshMode(view.context)
        }
        val prepared = IReaderPageTurnEffect.prepare(
            forward = forward,
            rotation = view.display?.rotation ?: 0,
            effect = if (AppConfig.iReaderPageTurnEffect == 2) {
                IReaderPageTurnEffect.Effect.WATERFALL
            } else {
                IReaderPageTurnEffect.Effect.RIPPLE
            },
            speed = IReaderPageTurnEffect.Speed.entries[AppConfig.iReaderPageTurnSpeed],
            reverse = AppConfig.iReaderPageTurnReverse,
            reverseLandscape = AppConfig.iReaderPageTurnReverseLandscape,
            refreshMode = mode,
        )
        if (prepared && interval > 0) {
            refresh.arm(current, mode == IReaderRefreshModes.FULL)
        }
        if (!prepared && IReaderPageTurnEffect.capability() == IReaderPageTurnEffect.Capability.FAILED) {
            // The failed bridge remains disabled, so subsequent frames cannot flood the log.
            AppLog.put("掌阅原生翻页调用失败，已回退无动画：${IReaderPageTurnEffect.failureReason()}")
        }
    }

    private fun removeListener() {
        listener?.let { current ->
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(current)
        }
        listener = null
        observer = null
    }
}
