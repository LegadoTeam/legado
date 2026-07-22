package io.legado.app.help.http

import io.legado.app.constant.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class HttpLogRecord(
    val id: Long,
    val time: Long,
    val method: String,
    val path: String,
    val url: String,
    val statusCode: Int,
    val duration: Long,
    val requestHeaders: String,
    val requestBody: String,
    val responseHeaders: String,
    val responseBody: String,
    val error: String?,
) {

    val summary: String
        get() = buildString {
            append("[HTTP#$id] $method ${path.ifBlank { "/" }} -> $statusCode ${duration}ms")
            if (!error.isNullOrBlank()) append(" | $error")
        }

    val detail: String
        get() = buildString {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            appendLine("HTTP request")
            appendLine("Time: ${dateFormat.format(Date(time))}")
            appendLine("Duration: ${duration}ms")
            appendLine()
            appendLine("Request")
            appendLine("$method $url")
            if (requestHeaders.isNotBlank()) appendLine(requestHeaders)
            if (requestBody.isNotBlank()) {
                appendLine()
                appendLine(requestBody)
            }
            appendLine()
            appendLine("Response")
            appendLine("Status: $statusCode")
            if (responseHeaders.isNotBlank()) appendLine(responseHeaders)
            if (responseBody.isNotBlank()) {
                appendLine()
                appendLine(responseBody)
            }
            if (!error.isNullOrBlank()) {
                appendLine()
                appendLine("Error")
                appendLine(error)
            }
        }

    companion object {
        private val idPattern = Regex("^\\[HTTP#(\\d+)]")

        fun parseId(message: String): Long? {
            return idPattern.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
    }
}

object HttpLogStore {

    internal const val MAX_RECORDS = 50
    private val idGenerator = AtomicLong()
    private val records = ArrayDeque<HttpLogRecord>(MAX_RECORDS)

    fun nextId(): Long = idGenerator.incrementAndGet()

    @Synchronized
    fun add(record: HttpLogRecord) {
        records.addFirst(record)
        while (records.size > MAX_RECORDS) {
            records.removeLast()
        }
        AppLog.put(record.summary)
    }

    @Synchronized
    fun get(id: Long): HttpLogRecord? {
        return records.firstOrNull { it.id == id }
    }

    @Synchronized
    fun latest(limit: Int): List<HttpLogRecord> {
        return records.take(limit.coerceIn(1, MAX_RECORDS))
    }

    @Synchronized
    fun clear() {
        records.clear()
    }
}
