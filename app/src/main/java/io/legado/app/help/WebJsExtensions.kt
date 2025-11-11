package io.legado.app.help

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.escapeForJs

class WebJsExtensions(private val source: BaseSource, private val activity: AppCompatActivity, private val webView: WebView): JsExtensions {
    override fun getSource(): BaseSource {
        return source
    }
    @JavascriptInterface
    fun request(jsCode: String, id: String) {
        Coroutine.async(activity.lifecycleScope) {
            AnalyzeRule(null, source).run {
                setCoroutineContext(coroutineContext)
                evalJS(jsCode).toString()
            }
        }.onSuccess { data ->
            webView.evaluateJavascript("window.JSBridgeResult('$id', '${data.escapeForJs()}', null);", null)
        }.onError {
            webView.evaluateJavascript("window.JSBridgeResult('$id', null, '${it.localizedMessage?.escapeForJs()}');", null)
        }
    }
    @JavascriptInterface
    fun toast(msg: String?) {
        super.toast(msg)
    }
    @JavascriptInterface
    fun longToast(msg: String?) {
        super.longToast(msg)
    }
    @JavascriptInterface
    fun log(msg: String?) {
        super.log(msg)
    }
    @JavascriptInterface
    fun ajax(url: String): String? {
        return super.ajax(url)
    }


    companion object{
        const val JS_INJECTION = """
            window.run = function(jsCode) {
                return new Promise((resolve, reject) => {
                    const requestId = 'req_' + Date.now() + '_' + Math.random().toString(36).substring(2, 5);
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[requestId] = { resolve, reject };
                    window.java?.request(String(jsCode), requestId);
                });
            };
            window.JSBridgeResult = function(requestId, result, error) {
                if (window.JSBridgeCallbacks?.[requestId]) {
                    if (error) {
                        window.JSBridgeCallbacks[requestId].reject(error);
                    } else {
                        window.JSBridgeCallbacks[requestId].resolve(result);
                    }
                    delete window.JSBridgeCallbacks[requestId];
                }
            };"""
    }
}