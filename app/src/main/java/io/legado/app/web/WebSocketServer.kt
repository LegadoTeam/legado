package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.api.controller.BookSourceController
import io.legado.app.service.WebService
import io.legado.app.web.socket.*

class WebSocketServer(serverPort: Int) : NanoWSD(serverPort) {

    override fun serve(session: IHTTPSession): Response {
        if (isWebsocketRequested(session)) {
            if (session.uri !in WEBSOCKET_ROUTES) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "WebSocket route not found"
                ).apply { addHeader("X-Content-Type-Options", "nosniff") }
            }
            if (!BookSourceController.hasValidJsSourceWebSocketProtocol(session.headers)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain; charset=utf-8",
                    "Web 书源访问令牌未配置或不正确"
                ).apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("X-Content-Type-Options", "nosniff")
                }
            }
        }
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        WebService.serve()
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                BookSourceDebugWebSocket(handshake)
            }
            "/rssSourceDebug" -> {
                RssSourceDebugWebSocket(handshake)
            }
            "/searchBook" -> {
                BookSearchWebSocket(handshake)
            }
            else -> null
        }
    }

    companion object {
        private val WEBSOCKET_ROUTES = setOf(
            "/bookSourceDebug",
            "/rssSourceDebug",
            "/searchBook",
        )
    }

}
