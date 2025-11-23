package io.legado.app.help

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.escapeForJs
import io.legado.app.utils.fromJsonObject

class WebJsExtensions(private val source: BaseSource, private val activity: AppCompatActivity, private val webView: WebView): JsExtensions {
    override fun getSource(): BaseSource {
        return source
    }
    @JavascriptInterface
    fun put(key: String, value: String): String {
        getSource().put(key, value)
        return value
    }
    @JavascriptInterface
    fun get(key: String): String {
        return getSource().get(key)
    }
    /**
     * 由软件主动注入的js函数调用
     */
    @JavascriptInterface
    fun request(funName: String, jsParam: Array<String>, id: String) {
        Coroutine.async(activity.lifecycleScope) {
            when (funName) {
                "run" -> {
                    AnalyzeRule(null, source).run {
                        setCoroutineContext(coroutineContext)
                        evalJS(jsParam[0]).toString()
                    }
                }
                "ajaxAwait" -> {
                    ajax(jsParam[0], jsParam[1].toInt()).toString()
                }
                "connectAwait" -> {
                    connect(jsParam[0], jsParam[1], jsParam[2].toInt())
                }
                "getAwait" -> {
                    get(jsParam[0], jsParam[1], jsParam[2].toInt())
                }
                "headAwait" -> {
                    head(jsParam[0], jsParam[1], jsParam[2].toInt())
                }
                "postAwait" -> {
                    post(jsParam[0], jsParam[1], jsParam[2], jsParam[3].toInt())
                }
                "webViewAwait" -> {
                    webView(jsParam[0], jsParam[1], jsParam[2]).toString()
                }
                "decryptStrAwait" -> {
                    createSymmetricCrypto(jsParam[0], jsParam[1], jsParam[2]).decryptStr(jsParam[3])
                }
                "encryptBase64Await" -> {
                    createSymmetricCrypto(jsParam[0], jsParam[1], jsParam[2]).encryptBase64(jsParam[3])
                }
                "encryptHexAwait" -> {
                    createSymmetricCrypto(jsParam[0], jsParam[1], jsParam[2]).encryptHex(jsParam[3])
                }
                "createSignHexAwait" -> {
                    createSign(jsParam[0]).setPublicKey(jsParam[1]).setPrivateKey(jsParam[2]).signHex(jsParam[3])
                }
                "downloadFileAwait" -> {
                    downloadFile(jsParam[0])
                }
                "readTxtFileAwait" -> {
                    readTxtFile(jsParam[0])
                }
                "importScriptAwait" -> {
                    importScript(jsParam[0])
                }
                "getStringAwait" -> {
                    AnalyzeRule(source = source).getString(jsParam[0], jsParam[1])
                }
                else -> "error funName"
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
    fun log(msg: String?): String {
        return super.log(msg).toString()
    }
    @JavascriptInterface
    fun ajax(url: String): String? {
        return super.ajax(url, 9000)
    }
    @JavascriptInterface
    fun ajax(url: String, callTimeout: Int): String? {
        return super.ajax(url, callTimeout.toLong())
    }
    @JavascriptInterface
    fun connect(urlStr: String?): String {
        if (urlStr.isNullOrEmpty()) return "error empty url"
        return super.connect(urlStr, null, 9000).toString()
    }
    @JavascriptInterface
    fun connect(urlStr: String, header: String): String {
        return super.connect(urlStr, header, 9000).toString()
    }
    @JavascriptInterface
    fun connect(urlStr: String, header: String, callTimeout: Int): String {
        return super.connect(urlStr, header, callTimeout.toLong()).toString()
    }
    @JavascriptInterface
    fun get(urlStr: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.get(urlStr, headerMap, 9000).body()
    }
    @JavascriptInterface
    fun get(urlStr: String, headers: String, timeout: Int): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.get(urlStr, headerMap, timeout).body()
    }
    @JavascriptInterface
    fun post(urlStr: String, body: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.post(urlStr, body, headerMap, 9000).body()
    }
    @JavascriptInterface
    fun post(urlStr: String, body: String, headers: String, timeout: Int): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return super.post(urlStr, body, headerMap, timeout).body()
    }
    @JavascriptInterface
    fun head(urlStr: String, headers: String): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return GSON.toJson(super.head(urlStr, headerMap, 9000).headers())
    }
    @JavascriptInterface
    fun head(urlStr: String, headers: String, timeout: Int): String {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
        return GSON.toJson(super.head(urlStr, headerMap, timeout).headers())
    }

    companion object{
        const val JS_INJECTION = """
            const requestId = n => 'req_' + n + '_' + Date.now() + '_' + Math.random().toString(36).slice(-3);
            window.run = function(jsCode) {
                return new Promise((resolve, reject) => {
                    const id = requestId("run");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("run", [String(jsCode)], id);
                });
            };
            window.ajaxAwait = function(url, callTimeout) {
                return new Promise((resolve, reject) => {
                    const id = requestId("ajaxAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("ajaxAwait", [String(url), String(callTimeout)], id);
                });
            };
            window.connectAwait = function(url, header, callTimeout) {
                return new Promise((resolve, reject) => {
                    const id = requestId("connectAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("connectAwait", [String(url), String(header), String(callTimeout)], id);
                });
            };
            window.getAwait = function(url, header, callTimeout) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("getAwait", [String(url), String(header), String(callTimeout)], id);
                });
            };
            window.headAwait = function(url, header, callTimeout) {
                return new Promise((resolve, reject) => {
                    const id = requestId("headAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("headAwait", [String(url), String(header), String(callTimeout)], id);
                });
            };
            window.postAwait = function(url, body, header, callTimeout) {
                return new Promise((resolve, reject) => {
                    const id = requestId("postAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("postAwait", [String(url), String(body), String(header), String(callTimeout)], id);
                });
            };
            window.webViewAwait = function(html, url, js) {
                return new Promise((resolve, reject) => {
                    const id = requestId("webViewAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("webViewAwait", [String(html), String(url), String(js)], id);
                });
            };
            window.decryptStrAwait = function(transformation, key, iv, data) {
                return new Promise((resolve, reject) => {
                    const id = requestId("decryptStrAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("decryptStrAwait", [String(transformation), String(key), String(iv), String(data)], id);
                });
            };
            window.encryptBase64Await = function(transformation, key, iv, data) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptBase64Await");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("encryptBase64Await", [String(transformation), String(key), String(iv), String(data)], id);
                });
            };
            window.encryptHexAwait = function(transformation, key, iv, data) {
                return new Promise((resolve, reject) => {
                    const id = requestId("encryptHexAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("encryptHexAwait", [String(transformation), String(key), String(iv), String(data)], id);
                });
            };
            window.createSignHexAwait = function(algorithm, publicKey, privateKey, data) {
                return new Promise((resolve, reject) => {
                    const id = requestId("createSignHexAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("createSignHexAwait", [String(algorithm), String(publicKey), String(privateKey), String(data)], id);
                });
            };
            window.downloadFileAwait = function(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("downloadFileAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("downloadFileAwait", [String(url)], id);
                });
            };
            window.readTxtFileAwait = function(path) {
                return new Promise((resolve, reject) => {
                    const id = requestId("readTxtFileAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("readTxtFileAwait", [String(path)], id);
                });
            };
            window.importScriptAwait = function(url) {
                return new Promise((resolve, reject) => {
                    const id = requestId("importScriptAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("importScriptAwait", [String(url)], id);
                });
            };
            window.getStringAwait = function(ruleStr, mContent) {
                return new Promise((resolve, reject) => {
                    const id = requestId("getStringAwait");
                    window.JSBridgeCallbacks = window.JSBridgeCallbacks || {};
                    window.JSBridgeCallbacks[id] = { resolve, reject };
                    window.java?.request("getStringAwait", [String(ruleStr), String(mContent)], id);
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