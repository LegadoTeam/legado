package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.utils.sendToClip

class SourceLoginJsExtensions(
    private val activity: AppCompatActivity, source: BaseSource?,
    private val callback: Callback? = null
) : RssJsExtensions(activity, source) {

    interface Callback {
        fun upUiData(data: Map<String, String>)
    }

    fun upLoginData(data: Map<String, String>) {
        callback?.upUiData(data)
    }

    fun copyText(text: String) {
        activity.sendToClip(text)
    }
}