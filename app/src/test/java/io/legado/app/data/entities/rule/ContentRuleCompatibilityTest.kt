package io.legado.app.data.entities.rule

import com.google.gson.JsonParser
import com.script.ScriptBindings
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.data.entities.BookSource
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRuleCompatibilityTest {

    @Test
    fun legacyRulesKeepTheirJavascriptJsonRepresentation() {
        val result = evaluate("JSON.stringify({ruleContent: source.ruleContent})", legacyRule)

        assertEquals(
            GSON.toJsonTree(mapOf("ruleContent" to legacyText)),
            JsonParser.parseString(result),
        )
    }

    @Test
    fun configuredBatchRulesKeepBothFieldsInTheirStringRepresentation() {
        for ((batch, size) in batchSettings) {
            val rule = legacyRule.copy(contentBatch = batch, maxBatchSize = size)
            val expected = legacyText.dropLast(1) +
                ", contentBatch=$batch, maxBatchSize=$size)"

            assertEquals(expected, rule.toString())
            assertEquals(
                GSON.toJsonTree(mapOf("ruleContent" to expected)),
                JsonParser.parseString(evaluate("JSON.stringify({ruleContent: source.ruleContent})", rule)),
            )
        }
    }

    @Test
    fun batchFieldsSurviveGsonAndRemainReadableInJavascript() {
        val converters = BookSource.Converters()
        for ((batch, size) in listOf(null to null) + batchSettings) {
            val original = legacyRule.copy(contentBatch = batch, maxBatchSize = size)
            val restored = converters.stringToContentRule(converters.contentRuleToString(original))!!
            assertEquals(original, restored)

            val result = evaluate(
                "JSON.stringify([source.ruleContent.content, " +
                    "source.ruleContent.contentBatch, source.ruleContent.maxBatchSize])",
                restored,
            )
            assertEquals(
                GSON.toJsonTree(listOf(legacyRule.content, batch, size)),
                JsonParser.parseString(result),
            )
        }
    }

    private fun evaluate(script: String, rule: ContentRule): String {
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        return RhinoScriptEngine.eval(script, ScriptBindings().apply {
            this["source"] = BookSource(
                bookSourceUrl = "https://content-rule-compatibility.invalid/",
                ruleContent = rule,
            )
        }).toString()
    }

    private val legacyRule = ContentRule(
        content = "body@text",
        subContent = "\"quoted\"\n中文",
        title = "",
        nextContentUrl = "a.next@href",
        sourceRegex = "正文",
        replaceRegex = "##广告",
        imageStyle = "FULL",
        payAction = "purchase()",
        callBackJs = "callback()",
    )

    private val legacyText =
        "ContentRule(content=body@text, subContent=\"quoted\"\n中文, title=, " +
            "nextContentUrl=a.next@href, webJs=null, sourceRegex=正文, " +
            "replaceRegex=##广告, imageStyle=FULL, imageDecode=null, " +
            "payAction=purchase(), callBackJs=callback())"

    private val batchSettings = listOf(
        "" to null,
        null to 0,
        "@js:batch()" to null,
        null to 4,
        "@js:batch()" to 4,
    )
}
