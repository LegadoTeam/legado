package io.legado.app.data.entities

import com.script.ScriptBindings
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseSourceHeaderTest {

    @Test
    fun `legacy header JSON is accepted without a format warning`() {
        val source = TestSource()

        val headers = source.getHeaderMap()

        assertEquals("https://example.com/", headers["Referer"])
        assertEquals("test", headers["User-Agent"])
        assertTrue(source.logs.isEmpty())
    }

    @Test
    fun `wrapped header scripts allow surrounding whitespace and mixed case`() {
        val source = TestSource()

        source.header = "\n <JS>({ 'X-Test': 'tag' })</JS>\t"
        assertEquals("tag", source.getHeaderMap()["X-Test"])

        source.header = " \t@JS:({ 'X-Test': 'prefix' })\n"
        assertEquals("prefix", source.getHeaderMap()["X-Test"])
        assertEquals(
            listOf("({ 'X-Test': 'tag' })", "({ 'X-Test': 'prefix' })"),
            source.evaluatedScripts,
        )
        assertTrue(source.logs.isEmpty())
    }

    @Test
    fun `header cancellation is propagated without logging a rule error`() {
        val source = TestSource().apply {
            evaluationError = CancellationException("reader closed")
            header = "@js:({ 'X-Test': 'cancelled' })"
        }

        assertThrows(CancellationException::class.java) {
            source.getHeaderMap()
        }
        assertTrue(source.logs.isEmpty())
    }

    private class TestSource : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = null
        override var loginUi: String? = null
        override var header: String? = "{Referer:'https://example.com/', 'User-Agent':'test'}"
        override var enabledCookieJar: Boolean? = true
        override var jsLib: String? = null

        val logs = mutableListOf<String>()
        val evaluatedScripts = mutableListOf<String>()
        var evaluationError: Throwable? = null

        override fun getTag() = "test"

        override fun getKey() = "https://example.com"

        override fun getLoginInfo(): String? = null

        override fun putLoginInfo(info: String) = true

        override fun evalJS(
            jsStr: String,
            bindingsConfig: ScriptBindings.() -> Unit,
        ): Any? {
            evaluatedScripts.add(jsStr)
            evaluationError?.let { throw it }
            val value = if ("tag" in jsStr) "tag" else "prefix"
            return """{"X-Test":"$value","User-Agent":"test"}"""
        }

        override fun log(msg: Any?): Any? {
            logs.add(msg.toString())
            return msg
        }
    }
}
