package io.legado.app.help

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils

object AppFreezeMonitor {

    private const val TAG = "AppFreezeMonitor"
    private const val CHECK_INTERVAL = 3000L

    val handler by lazy {
        Handler(HandlerThread("AppFreezeMonitor").apply { start() }.looper)
    }

    val screenStatusReceiver by lazy {
        ScreenStatusReceiver()
    }

    private var registeredReceiver = false
    private var monitorRunnable: Runnable? = null
    private var previous = 0L

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized
    fun init(context: Context) {
        if (!AppConfig.recordLog) {
            monitorRunnable?.let { handler.removeCallbacks(it) }
            if (registeredReceiver) {
                context.unregisterReceiver(screenStatusReceiver)
                registeredReceiver = false
            }
            return
        }

        if (!registeredReceiver) {
            context.registerReceiver(screenStatusReceiver, screenStatusReceiver.filter)
            registeredReceiver = true
        }

        previous = SystemClock.uptimeMillis()
        val runnable = monitorRunnable ?: object : Runnable {
            override fun run() {
                val current = SystemClock.uptimeMillis()
                val elapsed = current - previous
                val extra = elapsed - CHECK_INTERVAL

                if (extra > 300) {
                    LogUtils.d(TAG, "检测到应用被系统冻结，时长：$extra 毫秒")
                }

                previous = current

                if (AppConfig.recordLog) {
                    handler.removeCallbacks(this)
                    handler.postDelayed(this, CHECK_INTERVAL)
                }
            }
        }
        monitorRunnable = runnable
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, CHECK_INTERVAL)
    }

    class ScreenStatusReceiver : BroadcastReceiver() {

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> LogUtils.d(TAG, "SCREEN_ON")
                Intent.ACTION_SCREEN_OFF -> LogUtils.d(TAG, "SCREEN_OFF")
            }
        }
    }

}
