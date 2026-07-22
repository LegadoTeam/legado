package io.legado.app.api

import io.legado.app.api.controller.BookSourceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JsSourceWebApiContractTest {

    @Test
    fun `web route forwards the script body without a rename parameter`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")

        assertTrue(
            server.contains(
                "\"/saveJsSource\" -> BookSourceController.saveJsSource(postData)"
            )
        )
    }

    @Test
    fun `request boundary rejects unsafe bodies before parsing`() {
        val validHeaders = mapOf(
            "x-legado-token" to "secret",
            "content-type" to "text/plain; charset=utf-8",
            "content-length" to "128",
        )

        assertTrue(BookSourceController.validateJsSourceRequest(validHeaders, "secret") == null)
        assertTrue(BookSourceController.hasValidJsSourceApiToken(validHeaders, "secret"))
        assertEquals(
            "legado.token.5Luk54mM",
            BookSourceController.jsSourceWebSocketProtocol("令牌"),
        )
        val webSocketProtocol = BookSourceController.jsSourceWebSocketProtocol("secret")!!
        assertTrue(
            BookSourceController.hasValidJsSourceWebSocketProtocol(
                mapOf("sec-websocket-protocol" to "legado, $webSocketProtocol"),
                "secret",
            )
        )
        assertFalse(
            BookSourceController.hasValidJsSourceWebSocketProtocol(
                mapOf("sec-websocket-protocol" to "legado, $webSocketProtocol.invalid"),
                "secret",
            )
        )
        assertFalse(
            BookSourceController.hasValidJsSourceWebSocketProtocol(
                mapOf("sec-websocket-protocol" to webSocketProtocol),
                "secret",
            )
        )
        assertFalse(
            BookSourceController.hasValidJsSourceWebSocketProtocol(
                mapOf("sec-websocket-protocol" to "$webSocketProtocol, legado"),
                "secret",
            )
        )
        assertFalse(
            BookSourceController.validateJsSourceRequest(
                validHeaders - "x-legado-token",
                "secret",
            )!!.isSuccess
        )
        assertFalse(
            BookSourceController.validateJsSourceRequest(
                validHeaders + ("x-legado-token" to "wrong"),
                "secret",
            )!!.isSuccess
        )
        assertFalse(
            BookSourceController.validateJsSourceRequest(
                validHeaders,
                null,
            )!!.isSuccess
        )
        assertFalse(
            BookSourceController.validateJsSourceRequest(
                validHeaders + ("content-length" to "1048577"),
                "secret",
            )!!.isSuccess
        )
        assertFalse(
            BookSourceController.validateJsSourceRequest(
                validHeaders + ("transfer-encoding" to "chunked"),
                "secret",
            )!!.isSuccess
        )

        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val validationIndex = server.indexOf("validateJsSourceRequest(session.headers)")
        val parseBodyIndex = server.indexOf("session.parseBody(files)")
        assertTrue(validationIndex >= 0)
        assertTrue(parseBodyIndex >= 0)
        assertTrue(validationIndex < parseBodyIndex)
        val rejectionBranch = server.substringAfter("if (requestError != null)")
            .substringBefore("} else")
        assertTrue(rejectionBranch.contains("shouldCloseConnection = true"))
        assertTrue(server.contains("response.closeConnection(true)"))
        assertTrue(server.contains("PROTECTED_SOURCE_WRITE_ROUTES"))
        assertTrue(server.contains("/deleteBookSources"))
        assertTrue(server.contains("/saveRssSource"))
        assertTrue(server.contains("BookSourceController.hasValidJsSourceApiToken"))
        assertFalse(server.contains("WebOriginPolicy"))
        val protectedRouteCheck = server.indexOf("uri in PROTECTED_SOURCE_WRITE_ROUTES")
        assertTrue(protectedRouteCheck >= 0)
        assertTrue(protectedRouteCheck < parseBodyIndex)
    }

    @Test
    fun `controller limits payload timeout and preserves cancellation`() {
        val controller = readProjectFile(
            "app/src/main/java/io/legado/app/api/controller/BookSourceController.kt"
        )
        val timeoutCatch = controller.indexOf("catch (error: TimeoutCancellationException)")
        val cancellationCatch = controller.indexOf("catch (error: CancellationException)")
        val genericCatch = controller.indexOf("catch (error: Exception)")

        assertTrue(controller.contains("JsSourceUpsert.validatePayload(postData)"))
        assertTrue(controller.contains("JS_SOURCE_SAVE_TIMEOUT_MILLIS"))
        assertTrue(controller.contains("JsSourceUpsert.save("))
        assertTrue(timeoutCatch >= 0)
        assertTrue(cancellationCatch > timeoutCatch)
        assertTrue(genericCatch > cancellationCatch)
    }

    @Test
    fun `HTTP server rethrows cancellation before generic errors`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val cancellationCatch = server.indexOf("catch (e: CancellationException)")
        val genericCatch = server.indexOf("catch (e: Exception)")

        assertTrue(cancellationCatch >= 0)
        assertTrue(genericCatch > cancellationCatch)
        assertTrue(server.contains("x-legado-token"))
        val webSocketServer = readProjectFile(
            "app/src/main/java/io/legado/app/web/WebSocketServer.kt"
        )
        assertFalse(webSocketServer.contains("WebOriginPolicy"))
        assertFalse(webSocketServer.contains("tokenRequired"))
        assertTrue(webSocketServer.contains("hasValidJsSourceWebSocketProtocol"))
        assertTrue(webSocketServer.contains("override fun serve"))
        assertTrue(webSocketServer.contains("Response.Status.FORBIDDEN"))
        assertTrue(webSocketServer.contains("\"/searchBook\""))
        val bookSearchWebSocket = readProjectFile(
            "app/src/main/java/io/legado/app/web/socket/BookSearchWebSocket.kt"
        )
        val bookDebugWebSocket = readProjectFile(
            "app/src/main/java/io/legado/app/web/socket/BookSourceDebugWebSocket.kt"
        )
        val rssDebugWebSocket = readProjectFile(
            "app/src/main/java/io/legado/app/web/socket/RssSourceDebugWebSocket.kt"
        )
        assertFalse(bookSearchWebSocket.contains("matchesJsSourceApiToken"))
        assertFalse(bookSearchWebSocket.contains("searchMap[\"token\"]"))
        assertTrue(bookSearchWebSocket.contains("AUTH_TIMEOUT_MILLIS"))
        assertFalse(bookSearchWebSocket.contains("tokenRequired"))
        assertFalse(bookDebugWebSocket.contains("matchesJsSourceApiToken"))
        assertFalse(bookDebugWebSocket.contains("debugBean[\"token\"]"))
        assertTrue(bookDebugWebSocket.contains("Debug.tryAcquireCallback"))
        assertTrue(bookDebugWebSocket.contains("Debug.cancelDebug(this)"))
        assertTrue(bookDebugWebSocket.contains("cancelOwnedDebug()"))
        assertFalse(bookDebugWebSocket.contains("tokenRequired"))
        assertDebugSocketFailureReleasesOwner(bookDebugWebSocket)
        assertDebugSocketFailureReleasesOwner(rssDebugWebSocket)

        val bookDebugModel = readProjectFile(
            "app/src/main/java/io/legado/app/ui/book/source/debug/BookSourceDebugModel.kt"
        )
        val rssDebugModel = readProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/source/debug/RssSourceDebugModel.kt"
        )
        assertTrue(bookDebugModel.contains("state == -1 || state == 1000"))
        assertTrue(rssDebugModel.contains("state == -1 || state == 1000"))
        assertTrue(bookDebugModel.contains("Debug.cancelDebug(this)"))
        assertTrue(rssDebugModel.contains("Debug.cancelDebug(this)"))
        assertTrue(bookDebugModel.contains("error: ((Throwable) -> Unit)?"))
        assertTrue(rssDebugModel.contains("error: ((Throwable) -> Unit)?"))
        assertTrue(bookDebugModel.contains("error?.invoke(it)"))
        assertTrue(rssDebugModel.contains("error?.invoke(it)"))

        val bookDebugActivity = readProjectFile(
            "app/src/main/java/io/legado/app/ui/book/source/debug/BookSourceDebugActivity.kt"
        )
        val rssDebugActivity = readProjectFile(
            "app/src/main/java/io/legado/app/ui/rss/source/debug/RssSourceDebugActivity.kt"
        )
        assertTrue(bookDebugActivity.contains("error.localizedMessage ?: \"调试失败\""))
        assertTrue(rssDebugActivity.contains("error.localizedMessage ?: \"调试失败\""))

        val debugModel = readProjectFile("app/src/main/java/io/legado/app/model/Debug.kt")
        assertTrue(debugModel.contains("withActiveDebugSession"))
        assertTrue(debugModel.contains("trackDebugTask"))
        assertTrue(debugModel.contains("debugSessionId"))
        assertTrue(debugModel.contains("没有正文章节\", state = -1"))
        assertTrue(
            debugModel.substringAfter("private fun trackDebugTask")
                .contains("tasks.add(task)")
        )
        val sourceActivity = readProjectFile(
            "app/src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        )
        assertFalse(
            sourceActivity.contains(
                "CheckSource.stop(this)\n                        Debug.finishChecking()"
            )
        )
    }

    @Test
    fun `HTTP log read routes require the source API token`() {
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")
        val tokenCheck = server.indexOf("uri in PROTECTED_HTTP_LOG_READ_ROUTES")
        val logsRoute = server.indexOf("\"/getHttpLogs\" -> HttpLogController.getLogs")
        val detailRoute = server.indexOf("\"/getHttpLog\" -> HttpLogController.getLog")

        assertTrue(tokenCheck >= 0)
        assertTrue(logsRoute > tokenCheck)
        assertTrue(detailRoute > tokenCheck)
        assertTrue(server.contains("BookSourceController.hasValidJsSourceApiToken(session.headers)"))
        assertTrue(server.contains("PROTECTED_HTTP_LOG_READ_ROUTES"))
        val getBranch = server.substringAfter("Method.GET ->")
            .substringBefore("else -> Unit")
        val rejectionBranch = getBranch.substringAfter("if (requestError != null)")
            .substringBefore("} else")
        assertTrue(rejectionBranch.contains("shouldCloseConnection = true"))
        val optionsBranch = server.substringAfter("Method.OPTIONS ->")
            .substringBefore("Method.POST ->")
        assertTrue(
            optionsBranch.contains(
                "response.addHeader(\"Access-Control-Allow-Methods\", \"GET, POST\")"
            )
        )
        val webHeaders = server.substringAfter("private fun Response.addWebHeaders")
        assertTrue(webHeaders.contains("addHeader(\"Cache-Control\", \"no-store\")"))
        assertFalse(server.contains("/setHttpLogRecording"))
    }

    @Test
    fun `editor delegates persistence to shared upsert`() {
        val activity = readProjectFile(
            "app/src/main/java/io/legado/app/ui/book/source/edit/JsSourceEditActivity.kt"
        )

        assertTrue(activity.contains("JsSourceUpsert.save(text, openedSourceUrl)"))
        assertFalse(activity.contains("private fun preserveUserState"))
        assertFalse(activity.contains("private fun stampSource"))
    }

    @Test
    fun `documentation records endpoint limits and network boundary`() {
        val api = readProjectFile("api.md")
        val updateLog = readProjectFile("app/src/main/assets/updateLog.md")
        val backupConfig = readProjectFile(
            "app/src/main/java/io/legado/app/help/storage/BackupConfig.kt"
        )
        val manifest = readProjectFile("app/src/main/AndroidManifest.xml")
        val backupRules = readProjectFile("app/src/main/res/xml/backup_rules.xml")
        val extractionRules = readProjectFile(
            "app/src/main/res/xml/data_extraction_rules.xml"
        )
        val webAxios = readProjectFile("modules/web/src/api/axios.ts")
        val webApi = readProjectFile("modules/web/src/api/api.ts")
        val webShelf = readProjectFile("modules/web/src/views/BookShelf.vue")
        val sourceToken = readProjectFile("modules/web/src/api/sourceToken.ts")
        val sourceEditor = readProjectFile("modules/web/src/views/SourceEditor.vue")
        val chapterContent = readProjectFile("modules/web/src/components/ChapterContent.vue")
        val apiIndex = readProjectFile("modules/web/src/api/index.ts")
        val server = readProjectFile("app/src/main/java/io/legado/app/web/HttpServer.kt")

        assertTrue(api.contains("/saveJsSource"))
        assertTrue(api.contains("1 MiB"))
        assertTrue(api.contains("30 秒"))
        assertTrue(api.contains("text/plain; charset=utf-8"))
        assertTrue(api.contains("去除脚本文本首尾空白"))
        assertTrue(api.contains("X-Legado-Token"))
        assertTrue(api.contains("其他设置"))
        assertTrue(api.contains("Web 书源访问令牌"))
        assertTrue(api.contains("Sec-WebSocket-Protocol"))
        assertTrue(api.contains("读取任何 WebSocket 帧前"))
        assertTrue(api.contains("不会进入应用备份"))
        assertTrue(api.contains("浏览器同源状态不作为身份凭据"))
        assertTrue(api.contains("旧 JSON 书源写入接口"))
        assertTrue(api.contains("页面重载后需要重新输入"))
        assertTrue(api.contains("可信局域网"))
        assertTrue(api.contains("/getHttpLogs?limit=50"))
        assertTrue(api.contains("/getHttpLog?id=1"))
        assertTrue(api.contains("8 KiB"))
        assertTrue(api.contains("X-Legado-Token = 设置中配置的令牌"))
        assertTrue(updateLog.contains("Web API 新增带令牌保护的纯 JavaScript 单文件书源保存接口"))
        assertTrue(backupConfig.contains("PreferKey.jsSourceApiToken"))
        assertTrue(manifest.contains("@xml/backup_rules"))
        assertTrue(manifest.contains("@xml/data_extraction_rules"))
        assertTrue(backupRules.contains("js_source_api_credentials.xml"))
        assertTrue(backupRules.contains("js_source_api_credentials.xml.bak"))
        assertTrue(extractionRules.contains("js_source_api_credentials.xml"))
        assertTrue(extractionRules.contains("js_source_api_credentials.xml.bak"))
        assertTrue(webAxios.contains("X-Legado-Token"))
        assertTrue(webAxios.contains("requestSourceApiToken"))
        assertFalse(sourceToken.contains("localStorage.setItem"))
        assertFalse(sourceToken.contains("localStorage.getItem"))
        assertTrue(sourceToken.contains("localStorage.removeItem('apiToken')"))
        assertTrue(sourceToken.contains("sourceApiTokenWebSocketProtocol"))
        assertFalse(webApi.contains("apiToken_localStorage_key"))
        assertTrue(webApi.contains("token: string"))
        assertTrue(webApi.contains("sourceApiTokenWebSocketProtocol(token)"))
        assertTrue(webApi.contains("['legado', sourceApiTokenWebSocketProtocol(token)]"))
        assertTrue(sourceToken.contains("Web 书源访问令牌"))
        assertTrue(webShelf.contains("requestSourceApiToken({ remember: false })"))
        assertTrue(sourceEditor.contains("v-if=\"authorized\""))
        assertTrue(sourceEditor.contains("onUnmounted(clearSourceApiToken)"))
        assertTrue(chapterContent.contains("DOMPurify.sanitize"))
        assertTrue(apiIndex.contains("url.pathname += '/'"))
        assertTrue(server.contains("application/json; charset=utf-8"))
        assertTrue(server.contains("X-Content-Type-Options"))
        assertTrue(server.contains("Content-Security-Policy"))
    }

    private fun readProjectFile(path: String): String {
        val userDirectory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val repositoryRoot = generateSequence(userDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
        requireNotNull(repositoryRoot) { "Repository root not found from $userDirectory" }
        val file = File(repositoryRoot, path)
        require(file.isFile) { "Project file not found: $file" }
        return file.readText()
    }

    private fun assertDebugSocketFailureReleasesOwner(source: String) {
        val heartbeat = source.substringAfter("private fun startHeartbeat()")
            .substringBefore("override fun onClose")
        val printLog = source.substringAfter("override fun printLog")
            .substringBefore("private fun cancelOwnedDebug")

        listOf(heartbeat, printLog).forEach { section ->
            val failure = section.substringAfter("}.onFailure {")
            val releaseIndex = failure.indexOf("cancelOwnedDebug()")
            val cancellationIndex = failure.indexOf("if (it is CancellationException) throw it")
            val closeIndex = failure.indexOf("CloseCode.InternalServerError")
            assertTrue(releaseIndex >= 0)
            assertTrue(cancellationIndex > releaseIndex)
            assertTrue(closeIndex > cancellationIndex)
        }
    }
}
