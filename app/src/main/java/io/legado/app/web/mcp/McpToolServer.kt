package io.legado.app.web.mcp

import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.HttpLogController
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.Debug
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

object McpToolServer {

    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val debugMutex = Mutex()

    fun create(): Server {
        return Server(
            serverInfo = Implementation(
                name = "legado",
                version = AppConst.appInfo.versionName,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ).also(::registerTools)
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
                    val (log, timedOut) = McpDebugCollector().collect(
                        debugScope,
                        source,
                        key,
                        timeoutSec * 1_000L,
                    )
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
    }
}
