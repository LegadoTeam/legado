package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {

    private val mLogs = arrayListOf<Triple<Long, String, Throwable?>>()

    val logs
        @Synchronized get() = mLogs.toList()

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size >= 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        runCatching { postEvent(EventBus.APP_LOG_UPDATED, true) }
        if (BuildConfig.DEBUG) {
            runCatching {
                val stackTrace = Thread.currentThread().stackTrace
                Log.e(stackTrace[3].className, message, throwable)
            }
        }
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size >= 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        runCatching { postEvent(EventBus.APP_LOG_UPDATED, true) }
        if (BuildConfig.DEBUG) {
            runCatching {
                val stackTrace = Thread.currentThread().stackTrace
                Log.e(stackTrace[3].className, message, throwable)
            }
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    fun exportText(entries: List<Triple<Long, String, Throwable?>>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return buildString {
            entries.asReversed().forEach { (time, message, throwable) ->
                append(dateFormat.format(Date(time)))
                append(' ')
                appendLine(message)
                throwable?.let {
                    appendLine(it.stackTraceToString().trimEnd().prependIndent("    "))
                }
            }
        }
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            put(message, throwable)
        }
    }

}
