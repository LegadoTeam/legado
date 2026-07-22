package io.legado.app.ui.rss.source.debug

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.model.Debug

class RssSourceDebugModel(application: Application) : BaseViewModel(application),
    Debug.Callback {
    var rssSource: RssSource? = null
    private var callback: ((Int, String) -> Unit)? = null
    var listSrc: String? = null
    var contentSrc: String? = null

    fun initData(sourceUrl: String?, finally: () -> Unit) {
        sourceUrl?.let {
            execute {
                rssSource = appDb.rssSourceDao.getByKey(sourceUrl)
            }.onFinally {
                finally()
            }
        }
    }

    fun observe(callback: (Int, String) -> Unit) {
        this.callback = callback
    }

    fun startDebug(
        key: String,
        start: (() -> Unit)? = null,
        error: ((Throwable) -> Unit)? = null
    ) {
        execute {
            check(Debug.tryAcquireCallback(this@RssSourceDebugModel)) {
                "调试通道占用中，请稍后重试"
            }
            try {
                Debug.startDebug(this, rssSource!!, key)
            } catch (throwable: Throwable) {
                Debug.cancelDebug(this@RssSourceDebugModel)
                throw throwable
            }
        }.onStart {
            start?.invoke()
        }.onError {
            error?.invoke(it)
        }
    }

    override fun printLog(state: Int, msg: String) {
        when (state) {
            10 -> listSrc = msg
            20 -> contentSrc = msg
            else -> {
                callback?.invoke(state, msg)
                if (state == -1 || state == 1000) {
                    Debug.cancelDebug(this)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Debug.cancelDebug(this)
    }

}
