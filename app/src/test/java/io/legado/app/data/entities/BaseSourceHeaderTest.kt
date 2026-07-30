package io.legado.app.data.entities

import org.junit.Assert.assertEquals
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

    private class TestSource : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = null
        override var loginUi: String? = null
        override var header: String? = "{Referer:'https://example.com/', 'User-Agent':'test'}"
        override var enabledCookieJar: Boolean? = true
        override var jsLib: String? = null

        val logs = mutableListOf<String>()

        override fun getTag() = "test"

        override fun getKey() = "https://example.com"

        override fun getLoginInfo(): String? = null

        override fun putLoginInfo(info: String) = true

        override fun log(msg: Any?): Any? {
            logs.add(msg.toString())
            return msg
        }
    }
}
