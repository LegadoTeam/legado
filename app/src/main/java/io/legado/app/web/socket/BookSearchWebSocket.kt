package io.legado.app.web.socket

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.IOException

class BookSearchWebSocket(handshakeRequest: NanoHTTPD.IHTTPSession) :
    NanoWSD.WebSocket(handshakeRequest),
    CoroutineScope by MainScope(),
    SearchModel.CallBack {

    private val normalClosure = NanoWSD.WebSocketFrame.CloseCode.NormalClosure
    private var searchModel: SearchModel? = null

    @Volatile
    private var requestReceived = false

    private val SEARCH_FINISH = "Search finish"

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
            }
        }
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        cancel()
        searchModel?.close()
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
                val searchMap =
                    GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()
                if (searchMap != null) {
                    val key = searchMap["key"]
                    if (key.isNullOrBlank()) {
                        send(appCtx.getString(R.string.cannot_empty))
                        close(normalClosure, SEARCH_FINISH, false)
                        return@launch
                    }
                    requestReceived = true
                    startHeartbeat()
                    SearchModel(this@BookSearchWebSocket, this@BookSearchWebSocket).also {
                        searchModel = it
                        it.search(System.currentTimeMillis(), key)
                    }
                } else {
                    close(
                        NanoWSD.WebSocketFrame.CloseCode.PolicyViolation,
                        "认证数据格式错误",
                        false
                    )
                }
            }
        }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {

    }

    override fun onException(exception: IOException) {
        searchModel?.close()
    }

    override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)

    override fun onSearchStart() {

    }

    override fun onSearchProgress(searched: Int, total: Int) {

    }

    override fun onSearchSuccess(searchBooks: List<SearchBook>) {
        send(GSON.toJson(searchBooks))
    }

    override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) = close(normalClosure, SEARCH_FINISH, false)

    override fun onSearchCancel(exception: Throwable?) = close(normalClosure, exception?.toString() ?: SEARCH_FINISH, false)

    companion object {
        private const val AUTH_TIMEOUT_MILLIS = 10_000L
    }

}
