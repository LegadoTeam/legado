package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx

object HttpLogController {

    fun getLogs(parameters: Map<String, List<String>>): ReturnData {
        return getLogs(parameters, AppConfig.recordHttpLog)
    }

    internal fun getLogs(
        parameters: Map<String, List<String>>,
        recording: Boolean,
    ): ReturnData {
        val limit = parameters["limit"]?.firstOrNull()?.toIntOrNull()
            ?.coerceIn(1, HttpLogStore.MAX_RECORDS)
            ?: HttpLogStore.MAX_RECORDS
        val summaries = HttpLogStore.latest(limit).map(::summary)
        return ReturnData().setData(
            mapOf(
                "recording" to recording,
                "logs" to summaries,
            )
        )
    }

    fun getLog(parameters: Map<String, List<String>>): ReturnData {
        val id = parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return ReturnData().setErrorMsg("参数id不能为空")
        val record = HttpLogStore.get(id)
            ?: return ReturnData().setErrorMsg("未找到 HTTP 记录 #$id")
        return ReturnData().setData(record)
    }

    internal fun setRecording(enabled: Boolean): ReturnData {
        return setRecording(
            enabled = enabled,
            persist = { appCtx.putPrefBoolean(PreferKey.recordHttpLog, it) },
            updateRuntime = { AppConfig.recordHttpLog = it },
        )
    }

    internal fun setRecording(
        enabled: Boolean,
        persist: (Boolean) -> Unit,
        updateRuntime: (Boolean) -> Unit,
    ): ReturnData {
        persist(enabled)
        updateRuntime(enabled)
        return ReturnData().setData(mapOf("recording" to enabled))
    }

    private fun summary(record: HttpLogRecord): Map<String, Any?> = mapOf(
        "id" to record.id,
        "time" to record.time,
        "method" to record.method,
        "url" to record.url,
        "statusCode" to record.statusCode,
        "duration" to record.duration,
        "error" to record.error,
    )
}
