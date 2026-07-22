package io.legado.app.web

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.HttpLogController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.service.WebService
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        var returnData: ReturnData? = null
        var shouldCloseConnection = false
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG) {
            "${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)"
        }

        try {
            when (session.method) {
                Method.OPTIONS -> {
                    val response = newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain; charset=utf-8",
                        ""
                    )
                    response.addHeader("Access-Control-Allow-Methods", "GET, POST")
                    response.addHeader("Access-Control-Allow-Headers", "content-type, x-legado-token")
                    response.addWebHeaders(session.headers["origin"], uri)
                    //response.addHeader("Access-Control-Max-Age", "3600");
                    return response
                }

                Method.POST -> {
                    val requestError = when {
                        uri == "/saveJsSource" -> {
                            BookSourceController.validateJsSourceRequest(session.headers)
                        }

                        uri in PROTECTED_SOURCE_WRITE_ROUTES &&
                            !BookSourceController.hasValidJsSourceApiToken(session.headers) -> {
                            ReturnData().setErrorMsg("Web 书源访问令牌未配置或不正确")
                        }

                        else -> null
                    }
                    if (requestError != null) {
                        returnData = requestError
                        shouldCloseConnection = true
                    } else {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val postData = files["postData"]

                        returnData = runBlocking {
                            when (uri) {
                                "/saveBookSource" -> BookSourceController.saveSource(postData)
                                "/saveBookSources" -> BookSourceController.saveSources(postData)
                                "/saveJsSource" -> BookSourceController.saveJsSource(postData)
                                "/deleteBookSources" -> BookSourceController.deleteSources(postData)
                                "/saveBook" -> BookController.saveBook(postData)
                                "/deleteBook" -> BookController.deleteBook(postData)
                                "/saveBookProgress" -> BookController.saveBookProgress(postData)
                                "/addLocalBook" -> BookController.addLocalBook(
                                    session.parameters,
                                    files,
                                )
                                "/saveReadConfig" -> BookController.saveWebReadConfig(postData)
                                "/saveRssSource" -> RssSourceController.saveSource(postData)
                                "/saveRssSources" -> RssSourceController.saveSources(postData)
                                "/deleteRssSources" -> RssSourceController.deleteSources(postData)
                                "/saveReplaceRule" -> ReplaceRuleController.saveRule(postData)
                                "/deleteReplaceRule" -> ReplaceRuleController.delete(postData)
                                "/testReplaceRule" -> ReplaceRuleController.testRule(postData)
                                else -> null
                            }
                        }
                    }
                }

                Method.GET -> {
                    val parameters = session.parameters
                    val requestError = if (
                        uri in PROTECTED_HTTP_LOG_READ_ROUTES &&
                        !BookSourceController.hasValidJsSourceApiToken(session.headers)
                    ) {
                        ReturnData().setErrorMsg("Web 书源访问令牌未配置或不正确")
                    } else {
                        null
                    }
                    if (requestError != null) {
                        returnData = requestError
                        shouldCloseConnection = true
                    } else {
                        returnData = when (uri) {
                            "/getBookSource" -> BookSourceController.getSource(parameters)
                            "/getBookSources" -> BookSourceController.sources
                            "/getHttpLogs" -> HttpLogController.getLogs(parameters)
                            "/getHttpLog" -> HttpLogController.getLog(parameters)
                            "/getBookshelf" -> BookController.bookshelf
                            "/getChapterList" -> BookController.getChapterList(parameters)
                            "/refreshToc" -> BookController.refreshToc(parameters)
                            "/getBookContent" -> BookController.getBookContent(parameters)
                            "/cover" -> BookController.getCover(parameters)
                            "/image" -> BookController.getImg(parameters)
                            "/getReadConfig" -> BookController.getWebReadConfig()
                            "/getRssSource" -> RssSourceController.getSource(parameters)
                            "/getRssSources" -> RssSourceController.sources
                            "/getReplaceRules" -> ReplaceRuleController.allRules
                            else -> null
                        }
                    }
                }

                else -> Unit
            }

            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                return assetsWeb.getResponse(uri).apply {
                    addWebHeaders(session.headers["origin"], uri)
                }
            }

            val response = if (returnData.data is Bitmap) {
                val outputStream = ByteArrayOutputStream()
                (returnData.data as Bitmap).compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                outputStream.close()
                val inputStream = ByteArrayInputStream(byteArray)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    inputStream,
                    byteArray.size.toLong()
                )
            } else {
                val data = returnData.data
                if (data is List<*> && data.size > 3000) {
                    val pipe = Pipe(16 * 1024)
                    Coroutine.async {
                        pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                            GSON.toJson(returnData, it)
                        }
                    }
                    newChunkedResponse(
                        Response.Status.OK,
                        JSON_MIME,
                        pipe.source.buffer().inputStream()
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        JSON_MIME,
                        GSON.toJson(returnData)
                    )
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addWebHeaders(session.headers["origin"], uri)
            if (shouldCloseConnection) {
                response.closeConnection(true)
            }
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - End($startAt)"
            }
            return response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                e.message ?: "Internal server error"
            ).apply {
                addWebHeaders(session.headers["origin"], uri)
                closeConnection(true)
            }
        }

    }

    companion object {
        private const val TAG = "HttpServer"
        private const val JSON_MIME = "application/json; charset=utf-8"
        private const val VUE_CONTENT_SECURITY_POLICY =
            "default-src 'self' data: blob:; " +
                "script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src * data: blob:; font-src 'self' data: http: https:; " +
                "connect-src * ws: wss:; object-src 'none'; base-uri 'self'"
        private val PROTECTED_SOURCE_WRITE_ROUTES = setOf(
            "/saveBookSource",
            "/saveBookSources",
            "/deleteBookSources",
            "/saveRssSource",
            "/saveRssSources",
            "/deleteRssSources",
            "/saveReplaceRule",
            "/deleteReplaceRule",
            "/testReplaceRule",
        )
        private val PROTECTED_HTTP_LOG_READ_ROUTES = setOf(
            "/getHttpLogs",
            "/getHttpLog",
        )
    }

    private fun Response.addWebHeaders(origin: String?, uri: String) {
        addHeader("X-Content-Type-Options", "nosniff")
        origin?.let { addHeader("Access-Control-Allow-Origin", it) }
        if (uri in PROTECTED_HTTP_LOG_READ_ROUTES) {
            addHeader("Cache-Control", "no-store")
        }
        if (uri.startsWith("/vue/") && uri.endsWith(".html")) {
            addHeader("Content-Security-Policy", VUE_CONTENT_SECURITY_POLICY)
        }
    }

}
