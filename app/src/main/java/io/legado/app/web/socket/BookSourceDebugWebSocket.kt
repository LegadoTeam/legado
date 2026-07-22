package io.legado.app.web.socket


import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.model.Debug
import io.legado.app.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import splitties.init.appCtx

import java.io.IOException

/**
 * web端书源调试
 */
class BookSourceDebugWebSocket(handshakeRequest: NanoHTTPD.IHTTPSession) :
    NanoWSD.WebSocket(handshakeRequest),
    CoroutineScope by MainScope(),
    Debug.Callback {

    private val notPrintState = arrayOf(10, 20, 30, 40)

    @Volatile
    private var requestReceived = false

    override fun onOpen() {
        launch(IO) {
            delay(AUTH_TIMEOUT_MILLIS)
            if (!requestReceived && isOpen) {
                close(
                    NanoWSD.WebSocketFrame.CloseCode.PolicyViolation,
                    "认证超时",
                    false
                )
            }
        }
    }

    private fun startHeartbeat() {
        launch(IO) {
            kotlin.runCatching {
                while (requestReceived && isOpen) {
                    delay(30_000)
                    if (isOpen) {
                        ping("ping".toByteArray())
                    }
                }
            }.onFailure {
                cancelOwnedDebug()
                if (it is CancellationException) throw it
                it.printOnDebug()
                runCatching {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.InternalServerError,
                        "调试连接异常",
                        false
                    )
                }
            }
        }
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        cancel()
        cancelOwnedDebug()
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        launch(IO) {
            kotlin.runCatching {
                if (requestReceived) return@launch
                if (!message.textPayload.isJson()) {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.PolicyViolation,
                        "认证数据格式错误",
                        false
                    )
                    return@launch
                }
                val debugBean =
                    GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()
                if (debugBean != null) {
                    val tag = debugBean["tag"]
                    val key = debugBean["key"]
                    if (tag.isNullOrBlank() || key.isNullOrBlank()) {
                        send(appCtx.getString(R.string.cannot_empty))
                        close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                        return@launch
                    }
                    val source = appDb.bookSourceDao.getBookSource(tag)
                    if (source == null) {
                        send("书源不存在")
                        close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                        return@launch
                    }
                    if (!Debug.tryAcquireCallback(this@BookSourceDebugWebSocket)) {
                        send("调试通道占用中，请稍后重试")
                        close(
                            NanoWSD.WebSocketFrame.CloseCode.NormalClosure,
                            "调试结束",
                            false
                        )
                        return@launch
                    }
                    requestReceived = true
                    startHeartbeat()
                    Debug.startDebug(this, source, key)
                } else {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.PolicyViolation,
                        "认证数据格式错误",
                        false
                    )
                    return@launch
                }
            }.onFailure {
                if (it is CancellationException) throw it
                cancelOwnedDebug()
                it.printOnDebug()
                runCatching {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.InternalServerError,
                        "调试失败",
                        false
                    )
                }
            }
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {

    }

    override fun onException(exception: IOException) {
        cancelOwnedDebug()
    }

    override fun printLog(state: Int, msg: String) {
        if (state in notPrintState) {
            return
        }
        runOnIO {
            runCatching {
                send(msg)
                if (state == -1 || state == 1000) {
                    cancelOwnedDebug()
                    close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                }
            }.onFailure {
                cancelOwnedDebug()
                if (it is CancellationException) throw it
                it.printOnDebug()
                runCatching {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.InternalServerError,
                        "调试连接异常",
                        false
                    )
                }
            }
        }
    }

    private fun cancelOwnedDebug() {
        Debug.cancelDebug(this)
    }

    companion object {
        private const val AUTH_TIMEOUT_MILLIS = 10_000L
    }

}
