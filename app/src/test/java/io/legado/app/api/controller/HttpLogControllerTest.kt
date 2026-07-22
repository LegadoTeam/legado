package io.legado.app.api.controller

import io.legado.app.constant.AppLog
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpLogControllerTest {

    @Before
    fun setUp() {
        HttpLogStore.clear()
        HttpLogStore.add(
            HttpLogRecord(
                id = 7,
                time = 123,
                method = "GET",
                path = "/books",
                url = "https://example.test/books",
                statusCode = 200,
                duration = 12,
                requestHeaders = "Authorization: [redacted]",
                requestBody = "",
                responseHeaders = "",
                responseBody = "ok",
                error = null,
            )
        )
    }

    @After
    fun tearDown() {
        HttpLogStore.clear()
        AppLog.clear()
    }

    @Test
    fun `list returns recording state and summary fields`() {
        val result = HttpLogController.getLogs(emptyMap(), recording = true)

        assertTrue(result.isSuccess)
        val data = result.data as Map<*, *>
        assertEquals(true, data["recording"])
        val logs = data["logs"] as List<*>
        val summary = logs.single() as Map<*, *>
        assertEquals(7L, summary["id"])
        assertEquals("GET", summary["method"])
        assertEquals("https://example.test/books", summary["url"])
        assertFalse(summary.containsKey("responseBody"))
        val limited = HttpLogController.getLogs(
            mapOf("limit" to listOf("0")),
            recording = false,
        ).data as Map<*, *>
        assertEquals(false, limited["recording"])
        assertEquals(1, (limited["logs"] as List<*>).size)
    }

    @Test
    fun `detail validates and resolves ids`() {
        assertEquals(
            "参数id不能为空",
            HttpLogController.getLog(emptyMap()).errorMsg,
        )
        assertEquals(
            "未找到 HTTP 记录 #8",
            HttpLogController.getLog(mapOf("id" to listOf("8"))).errorMsg,
        )
        val result = HttpLogController.getLog(mapOf("id" to listOf("7")))
        assertTrue(result.isSuccess)
        assertEquals(7L, (result.data as HttpLogRecord).id)
    }

    @Test
    fun `recording toggle updates persistence and runtime`() {
        var persisted: Boolean? = null
        var runtime: Boolean? = null

        val result = HttpLogController.setRecording(
            enabled = true,
            persist = { persisted = it },
            updateRuntime = { runtime = it },
        )

        assertTrue(result.isSuccess)
        assertEquals(true, persisted)
        assertEquals(true, runtime)
        assertEquals(true, (result.data as Map<*, *>)["recording"])
    }
}
