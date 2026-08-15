package io.legado.app.web.mcp

import com.script.rhino.runScriptWithContext
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.HttpLogController
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.IntentData
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceResult
import io.legado.app.model.Debug
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.printOnDebug
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splitties.init.appCtx
import java.io.IOException
import java.time.Instant

object McpToolServer {

    private const val NOTIFICATION_TIMEOUT_MS = 500L
    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debugMutex = Mutex()
    private val localReadToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        openWorldHint = false,
    )
    private val localWriteToolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = true,
        openWorldHint = false,
    )
    private val openWorldWriteToolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = true,
        idempotentHint = false,
        openWorldHint = true,
    )

    fun create(): Server {
        return Server(
            serverInfo = Implementation(
                name = "legado",
                version = AppConst.appInfo.versionName,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(),
                    logging = ServerCapabilities.Logging,
                ),
            ),
        ).also {
            registerTools(it)
            registerResources(it)
        }
    }

    private fun registerResources(server: Server) {
        val assetDir = "web/help/md"
        val fileNames = try {
            appCtx.assets.list(assetDir).orEmpty().toList()
        } catch (error: IOException) {
            error.printOnDebug()
            emptyList()
        }
        fileNames
            .filter { it.endsWith(".md") }
            .sorted()
            .forEach { fileName ->
                val name = fileName.removeSuffix(".md")
                val uri = "legado://help/$name"
                server.addResource(
                    uri = uri,
                    name = name,
                    description = "Legado 应用内帮助文档：$name",
                    mimeType = "text/markdown",
                ) { _ ->
                    val text = appCtx.assets.open("$assetDir/$fileName")
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                text = text,
                                uri = uri,
                                mimeType = "text/markdown",
                            )
                        )
                    )
                }
            }
    }

    private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))

    private fun err(text: String) = CallToolResult(
        content = listOf(TextContent(text)),
        isError = true,
    )

    private fun ReturnData.dataOrThrow(): Any? {
        if (!isSuccess) throw IllegalArgumentException(errorMsg)
        return data
    }

    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.intOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        this?.get(key)?.jsonPrimitive?.booleanOrNull

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private suspend fun sendBestEffort(block: suspend () -> Unit) {
        try {
            withTimeoutOrNull(NOTIFICATION_TIMEOUT_MS) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Notifications do not affect the tool result.
        }
    }

    private suspend fun ClientConnection.sendProgressLine(
        line: String,
        progress: Int,
        progressToken: RequestId?,
        total: Int? = null,
        logger: String = "legado.debug_source",
    ) {
        sendBestEffort {
            sendLoggingMessage(
                LoggingMessageNotification(
                    LoggingMessageNotificationParams(
                        level = LoggingLevel.Info,
                        data = JsonPrimitive(line),
                        logger = logger,
                    )
                )
            )
        }
        if (progressToken != null) {
            sendBestEffort {
                notification(
                    ProgressNotification(
                        ProgressNotificationParams(
                            progressToken = progressToken,
                            progress = progress.toDouble(),
                            total = total?.toDouble(),
                            message = line,
                        )
                    )
                )
            }
        }
    }

    private suspend fun ClientConnection.sendCheckProgress(
        sourcesByUrl: Map<String, BookSourcePart>,
        results: Map<String, CheckSourceResult>,
        reportedUrls: MutableSet<String>,
        total: Int,
        progressToken: RequestId?,
    ) {
        results.forEach { (url, result) ->
            val source = sourcesByUrl[url] ?: return@forEach
            if (reportedUrls.add(url)) {
                val line = "[${reportedUrls.size}/$total] " +
                    McpFormat.renderCheckResult(source, result)
                sendProgressLine(
                    line,
                    reportedUrls.size,
                    progressToken,
                    total = total,
                    logger = "legado.check_source",
                )
            }
        }
    }

    private fun registerTools(server: Server) {
        server.addTool(
            name = "save_source",
            description = "保存单个书源。纯 JavaScript 单文件源传脚本原文；声明式源传 BookSource JSON 对象。" +
                "同 bookSourceUrl 重复保存时保留启用、排序和权重；传入分组为空时保留已有分组。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("source", stringProp("JS 脚本原文或 BookSource JSON 对象"))
                    put("format", stringProp("js|json；缺省时自动识别"))
                },
                required = listOf("source"),
            ),
            toolAnnotations = openWorldWriteToolAnnotations,
        ) { request ->
            try {
                val source = request.arguments.str("source")
                    ?: return@addTool err("参数 source 不能为空")
                val format = request.arguments.str("format") ?: McpFormat.detectFormat(source)
                when (format) {
                    "js" -> {
                        val saved = BookSourceController.saveJsSource(source).dataOrThrow() as BookSource
                        ok("已保存：${saved.bookSourceName}\nbookSourceUrl: ${saved.bookSourceUrl}")
                    }

                    "json" -> {
                        val saved = McpSourceStore.saveDeclarative(source)
                        ok("已保存：${saved.bookSourceName}\nbookSourceUrl: ${saved.bookSourceUrl}")
                    }

                    else -> err("参数 format 必须为 js 或 json")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "debug_source",
            description = "运行应用内书源调试并返回逐步日志。key 可为关键词、绝对 URL、::URL、++URL 或 --URL。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", stringProp("书源 bookSourceUrl"))
                    put("key", stringProp("调试关键词或入口 URL"))
                    putJsonObject("timeoutSec") {
                        put("type", "integer")
                        put("description", "超时秒数，默认 120，范围 10..600")
                    }
                },
                required = listOf("url", "key"),
            ),
            toolAnnotations = openWorldWriteToolAnnotations,
        ) { request ->
            try {
                val url = request.arguments.str("url")
                    ?: return@addTool err("参数 url 不能为空")
                val key = request.arguments.str("key")
                    ?: return@addTool err("参数 key 不能为空")
                val timeoutSec = (request.arguments.int("timeoutSec") ?: 120).coerceIn(10, 600)
                val source = appDb.bookSourceDao.getBookSource(url)
                    ?: return@addTool err("未找到书源，请检查书源地址")
                if (!debugMutex.tryLock()) {
                    return@addTool err("调试通道占用中，请稍后重试")
                }
                try {
                    if (Debug.callback != null || Debug.isChecking) {
                        return@addTool err("调试通道占用中，请稍后重试")
                    }
                    val progressToken = request.meta?.progressToken
                    val (log, timedOut) = coroutineScope {
                        // Keep only the newest unsent line; the complete bounded log is returned.
                        val lineChannel = Channel<String>(Channel.CONFLATED)
                        val notificationJob = launch {
                            var progress = 0
                            for (line in lineChannel) {
                                sendProgressLine(line, ++progress, progressToken)
                            }
                        }
                        try {
                            McpDebugCollector { lineChannel.trySend(it) }.collect(
                                debugScope,
                                source,
                                key,
                                timeoutSec * 1_000L,
                            )
                        } finally {
                            lineChannel.close()
                            notificationJob.join()
                        }
                    }
                    val body = McpFormat.truncate(log.ifEmpty { "（调试无输出）" })
                    ok(
                        if (timedOut) {
                            "$body\n\n[调试超时 ${timeoutSec}s，以上为已收到的部分日志]"
                        } else {
                            body
                        }
                    )
                } finally {
                    debugMutex.unlock()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "list_sources",
            description = "列出书源摘要，可按名称或 URL 子串过滤。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("search", stringProp("名称或 URL 子串，大小写不敏感"))
                },
                required = emptyList(),
            ),
            toolAnnotations = localReadToolAnnotations,
        ) { request ->
            try {
                val summaries = McpFormat.summarizeSources(
                    appDb.bookSourceDao.all,
                    request.arguments.str("search"),
                )
                ok("共 ${summaries.size} 条\n${McpFormat.truncate(McpFormat.toPrettyJson(summaries))}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "get_source",
            description = "按 bookSourceUrl 读取书源 JSON，超长内容最多返回 200000 字符。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", stringProp("书源 bookSourceUrl"))
                },
                required = listOf("url"),
            ),
            toolAnnotations = localReadToolAnnotations,
        ) { request ->
            try {
                val url = request.arguments.str("url")
                    ?: return@addTool err("参数 url 不能为空")
                val source = appDb.bookSourceDao.getBookSource(url)
                    ?: return@addTool err("未找到书源，请检查书源地址")
                ok(McpFormat.truncate(McpFormat.prettyJson(GSON.toJson(source)), 200_000))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "delete_sources",
            description = "按 bookSourceUrl 删除一个或多个书源。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("urls") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "bookSourceUrl 列表")
                    }
                },
                required = listOf("urls"),
            ),
            toolAnnotations = localWriteToolAnnotations,
        ) { request ->
            try {
                val urls = (request.arguments?.get("urls") as? JsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    .orEmpty()
                if (urls.isEmpty()) {
                    return@addTool err("参数 urls 不能为空")
                }
                JsSourceUpsert.withSaveLock {
                    val existing = urls.mapNotNull(appDb.bookSourceDao::getBookSource)
                    if (existing.isEmpty()) {
                        return@withSaveLock ok("未找到可删除的书源")
                    }
                    SourceHelp.deleteBookSources(existing)
                    ok("已删除 ${existing.size} 个书源")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "get_http_logs",
            description = "读取最新的已脱敏 HTTP 请求日志摘要；内存最多保留 50 条。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "条数，默认 50")
                    }
                },
                required = emptyList(),
            ),
            toolAnnotations = localReadToolAnnotations,
        ) { request ->
            try {
                val limit = request.arguments.int("limit") ?: 50
                val data = HttpLogController.getLogs(mapOf("limit" to listOf(limit.toString())))
                    .dataOrThrow() as Map<*, *>
                val recording = data["recording"] as Boolean
                val logs = data["logs"] as List<*>
                val lines = logs.map { item ->
                    val log = item as Map<*, *>
                    "#${log["id"]} ${Instant.ofEpochMilli(log["time"] as Long)} " +
                        "${log["method"]} ${log["url"]} -> ${log["statusCode"]} " +
                        "${log["duration"]}ms" +
                        (log["error"]?.let { " | $it" } ?: "")
                }
                val header = if (recording) {
                    "最新 ${lines.size} 条："
                } else {
                    "HTTP 日志记录未开启；以下为关闭前保留的记录："
                }
                ok("$header\n${lines.ifEmpty { listOf("（空）") }.joinToString("\n")}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "get_http_log",
            description = "按 id 读取单条已脱敏 HTTP 请求详情；请求和响应正文记录上限各为 8 KiB。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "get_http_logs 返回的记录 id")
                    }
                },
                required = listOf("id"),
            ),
            toolAnnotations = localReadToolAnnotations,
        ) { request ->
            try {
                val id = request.arguments.int("id")
                    ?: return@addTool err("参数 id 不能为空")
                val record = HttpLogController.getLog(mapOf("id" to listOf(id.toString())))
                    .dataOrThrow() as HttpLogRecord
                ok(McpFormat.truncate(record.detail, 200_000))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "set_http_log_recording",
            description = "开启或关闭应用内 HTTP 日志记录；设置会持久化，切换不会清空已有记录。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true 开启，false 关闭")
                    }
                },
                required = listOf("enabled"),
            ),
            toolAnnotations = localWriteToolAnnotations,
        ) { request ->
            try {
                val enabled = request.arguments.bool("enabled")
                    ?: return@addTool err("参数 enabled 必须为布尔值")
                HttpLogController.setRecording(enabled).dataOrThrow()
                ok("HTTP 日志记录已${if (enabled) "开启" else "关闭"}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "get_cookies",
            description = "非破坏性读取指定 URL 所属二级域名的 Cookie，返回持久层与会话层合并结果。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", stringProp("URL 或域名"))
                },
                required = listOf("url"),
            ),
            toolAnnotations = localReadToolAnnotations,
        ) { request ->
            try {
                val url = request.arguments.str("url")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@addTool err("参数 url 不能为空")
                val domain = NetworkUtils.getSubDomain(url)
                val cookie = CookieManager.mergeCookies(
                    CookieManager.getCookieNoSession(url),
                    CookieManager.getSessionCookie(domain),
                ).orEmpty()
                ok(McpFormat.truncate(cookie.ifEmpty { "（该域名没有 Cookie）" }))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "set_cookie",
            description = "合并写入指定 URL 所属二级域名的持久层 Cookie，不删除其他持久键；" +
                "同名会话 Cookie 在当前会话中仍优先。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", stringProp("URL 或域名"))
                    put("cookie", stringProp("分号分隔的 Cookie 键值对"))
                },
                required = listOf("url", "cookie"),
            ),
            toolAnnotations = localWriteToolAnnotations,
        ) { request ->
            try {
                val url = request.arguments.str("url")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@addTool err("参数 url 不能为空")
                val cookie = request.arguments.str("cookie")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@addTool err("参数 cookie 不能为空")
                val cookieMap = CookieStore.cookieToMap(cookie)
                if (cookieMap.isEmpty() || cookieMap.keys.any { it.isBlank() }) {
                    return@addTool err("参数 cookie 必须包含有效的 name=value")
                }
                CookieStore.replaceCookie(url, cookie)
                ok("Cookie 已写入持久层")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "clear_cookies",
            description = "清除指定 URL 所属二级域名的持久、会话和 WebView Cookie。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("url", stringProp("URL 或域名"))
                },
                required = listOf("url"),
            ),
            toolAnnotations = localWriteToolAnnotations,
        ) { request ->
            try {
                val url = request.arguments.str("url")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@addTool err("参数 url 不能为空")
                CookieStore.removeCookie(url)
                ok("Cookie 已清除")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "eval_js",
            description = "在应用内书源 JavaScript 环境执行脚本，返回求值结果和 java.log 输出。" +
                "可按 bookSourceUrl 绑定已保存书源的运行时身份，但不自动执行其 mainJs；" +
                "不传时使用空白书源。与书源调试共用通道。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("js", stringProp("要执行的 JavaScript 脚本"))
                    put("url", stringProp("可选的书源 bookSourceUrl"))
                    putJsonObject("timeoutSec") {
                        put("type", "integer")
                        put("description", "超时秒数，默认 60，范围 5..600")
                    }
                },
                required = listOf("js"),
            ),
            toolAnnotations = openWorldWriteToolAnnotations,
        ) { request ->
            try {
                val js = request.arguments.str("js")
                when (JsSourceUpsert.validatePayload(js)) {
                    JsSourceUpsert.PayloadIssue.EMPTY ->
                        return@addTool err("参数 js 不能为空")

                    JsSourceUpsert.PayloadIssue.TOO_LARGE ->
                        return@addTool err("脚本不能超过 1 MiB")

                    null -> Unit
                }
                requireNotNull(js)
                val url = request.arguments.str("url")
                val timeoutSec = (request.arguments.int("timeoutSec") ?: 60).coerceIn(5, 600)
                val source = if (url.isNullOrBlank()) {
                    BookSource()
                } else {
                    appDb.bookSourceDao.getBookSource(url)
                        ?: return@addTool err("未找到书源，请检查书源地址")
                }
                if (!debugMutex.tryLock()) {
                    return@addTool err("调试通道占用中，请稍后重试")
                }
                try {
                    val collector = McpDebugCollector()
                    if (!Debug.startSimpleDebug(collector, source.getKey())) {
                        return@addTool err("调试通道占用中，请稍后重试")
                    }
                    try {
                        val startedAt = System.currentTimeMillis()
                        val outcome: Result<String?>? = try {
                            withTimeoutOrNull(timeoutSec * 1_000L) {
                                Result.success(
                                    withContext(Dispatchers.IO) {
                                        val context = currentCoroutineContext()
                                        val raw = runScriptWithContext { source.evalJS(js) }
                                        JsSourceEngine.normalizeJsResult(raw, context)
                                    }
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                        val elapsedMs = System.currentTimeMillis() - startedAt
                        val logSuffix = collector.snapshot().trimEnd().takeIf { it.isNotEmpty() }
                            ?.let { "\n\n-- 日志 --\n$it" }
                            .orEmpty()
                        when {
                            outcome == null -> err(
                                McpFormat.truncate(
                                    "-- 错误 --\n求值超时 ${timeoutSec}s，脚本已取消$logSuffix"
                                )
                            )

                            outcome.isFailure -> {
                                val error = requireNotNull(outcome.exceptionOrNull())
                                err(
                                    McpFormat.truncate(
                                        "-- 错误 --\n" +
                                            (error.localizedMessage ?: error.toString()) +
                                            logSuffix
                                    )
                                )
                            }

                            else -> ok(
                                McpFormat.truncate(
                                    "-- 结果 --\n${outcome.getOrNull() ?: "null"}" +
                                        "\n\n耗时 ${elapsedMs}ms$logSuffix"
                                )
                            )
                        }
                    } finally {
                        Debug.cancelDebug(collector)
                    }
                } finally {
                    debugMutex.unlock()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                err(error.localizedMessage ?: error.toString())
            }
        }

        server.addTool(
            name = "check_source",
            description = "按应用当前校验配置批量校验书源并写回分组、错误备注和响应时间。" +
                "单批最多 50 个；校验期间书源调试不可用，客户端取消请求不会中止应用内校验。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("urls") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "要校验的 bookSourceUrl 列表，单批最多 50 个")
                    }
                },
                required = listOf("urls"),
            ),
            toolAnnotations = openWorldWriteToolAnnotations,
        ) { request ->
            val urls = (request.arguments?.get("urls") as? JsonArray)
                ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()
            if (urls.isEmpty()) return@addTool err("参数 urls 不能为空")
            if (urls.size > 50) return@addTool err("单批最多校验 50 个书源")
            val parts = urls.map { url ->
                appDb.bookSourceDao.getBookSourcePart(url)
                    ?: return@addTool err("未找到书源：$url")
            }
            if (!debugMutex.tryLock()) {
                return@addTool err("调试通道占用中，请稍后重试")
            }
            var serviceRequested = false
            var checkSessionId = 0L
            var selectedSourcesKey: String? = null
            try {
                checkSessionId = Debug.tryStartCheckSession()
                    ?: return@addTool err("调试通道占用中，请稍后重试")
                selectedSourcesKey = CheckSource.start(appCtx, parts, checkSessionId)
                serviceRequested = true
                val progressToken = request.meta?.progressToken
                val sourcesByUrl = parts.associateBy { it.bookSourceUrl }
                val reportedUrls = hashSetOf<String>()

                val started = withTimeoutOrNull(5_000L) {
                    while (!Debug.isCheckServiceStarted(checkSessionId) &&
                        Debug.isChecking(checkSessionId)
                    ) {
                        delay(100)
                    }
                    Debug.isCheckServiceStarted(checkSessionId)
                } ?: Debug.isCheckServiceStarted(checkSessionId)
                if (!started) {
                    IntentData.get<Any>(selectedSourcesKey)
                    if (Debug.isChecking(checkSessionId)) {
                        runCatching { CheckSource.stop(appCtx, checkSessionId) }
                        withTimeoutOrNull(5_000L) {
                            while (Debug.isChecking(checkSessionId)) delay(100)
                        }
                        if (!Debug.isCheckServiceStarted(checkSessionId)) {
                            Debug.finishChecking(checkSessionId)
                        }
                    }
                    return@addTool err("校验服务未能启动，请将应用置于前台后重试")
                }

                while (Debug.isChecking(checkSessionId)) {
                    sendCheckProgress(
                        sourcesByUrl,
                        Debug.getCheckSnapshot(checkSessionId, urls).results,
                        reportedUrls,
                        urls.size,
                        progressToken,
                    )
                    delay(250)
                }
                val snapshot = Debug.takeCheckSnapshot(checkSessionId, urls)
                sendCheckProgress(
                    sourcesByUrl,
                    snapshot.results,
                    reportedUrls,
                    urls.size,
                    progressToken,
                )
                val summary = McpFormat.renderCheckSummary(
                    parts,
                    snapshot.results,
                    snapshot.messages,
                )
                ok(McpFormat.truncate(summary))
            } catch (error: CancellationException) {
                if (!serviceRequested && checkSessionId > 0L) {
                    IntentData.get<Any>(selectedSourcesKey)
                    Debug.finishChecking(checkSessionId)
                }
                throw error
            } catch (error: Exception) {
                if (serviceRequested && checkSessionId > 0L &&
                    Debug.isChecking(checkSessionId)
                ) {
                    runCatching { CheckSource.stop(appCtx, checkSessionId) }
                } else if (checkSessionId > 0L) {
                    IntentData.get<Any>(selectedSourcesKey)
                    Debug.finishChecking(checkSessionId)
                }
                err(error.localizedMessage ?: error.toString())
            } finally {
                debugMutex.unlock()
            }
        }
    }
}
