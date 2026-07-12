package io.legado.app.help

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.ChineseUtils

@Keep
@Suppress("unused")
class RegexJsExtensions(private val name: String): JsEncodeUtils {
    private val ruleData by lazy { RuleData() }

    /**
     * 输出调试日志
     */
    fun log(msg: Any?): Any? {
        AppLog.putDebug("替换净化规则 $name 输出: $msg")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    fun t2s(text: String): String {
        return ChineseUtils.t2s(text)
    }

    fun s2t(text: String): String {
        return ChineseUtils.s2t(text)
    }

    fun get(key: String): String {
        return ruleData.getVariable(key).takeIf { it.isNotEmpty() } ?: ""
    }

    fun put(key: String, value: String): String {
        ruleData.putVariable(key, value)
        return value
    }
}