package io.legado.app.data.entities

import com.script.ScriptBindings
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginInfoMapInitializationTest {

    @Test
    fun `login ui script can read login info during default initialization`() {
        val source = ReentrantLoginSource()

        val loginInfo = source.getLoginInfoMap()

        assertEquals(1, source.evaluationCount)
        assertTrue(source.nestedLoginInfo?.isEmpty() == true)
        assertEquals("default-token", loginInfo["token"])
        assertNotNull(source.savedLoginInfo)
        val savedLoginInfo = GSON.fromJsonObject<Map<String, String>>(source.savedLoginInfo)
            .getOrThrow()
        assertEquals("default-token", savedLoginInfo["token"])
    }

    @Test
    fun `rhino bridge can read login info during default initialization`() {
        val source = RhinoReentrantLoginSource()

        val loginInfo = source.getLoginInfoMap()

        assertEquals("rhino-default", loginInfo["token"])
        val savedLoginInfo = GSON.fromJsonObject<Map<String, String>>(source.savedLoginInfo)
            .getOrThrow()
        assertEquals("rhino-default", savedLoginInfo["token"])
    }

    @Test
    fun `same source reentry uses fallback`() {
        var nestedInitializerRan = false

        val result = LoginInfoMapInitialization.run(
            sourceKey = "https://example.com",
            onReentry = { error("outer call must initialize") },
        ) {
            val nested = LoginInfoMapInitialization.run(
                sourceKey = "https://example.com",
                onReentry = { mutableMapOf<String, String>() },
            ) {
                nestedInitializerRan = true
                mutableMapOf("unexpected" to "value")
            }

            assertTrue(nested.isEmpty())
            mutableMapOf("token" to "default")
        }

        assertFalse(nestedInitializerRan)
        assertEquals("default", result["token"])
    }

    @Test
    fun `different source can initialize during nested script call`() {
        val nested = LoginInfoMapInitialization.run(
            sourceKey = "https://first.example",
            onReentry = { error("outer call must initialize") },
        ) {
            LoginInfoMapInitialization.run(
                sourceKey = "https://second.example",
                onReentry = { error("different source must not use fallback") },
            ) {
                "initialized"
            }
        }

        assertEquals("initialized", nested)
    }

    @Test
    fun `failed initialization releases guard`() {
        assertThrows(IllegalStateException::class.java) {
            LoginInfoMapInitialization.run(
                sourceKey = "https://example.com",
                onReentry = { error("outer call must initialize") },
            ) {
                error("script failed")
            }
        }

        val retried = LoginInfoMapInitialization.run(
            sourceKey = "https://example.com",
            onReentry = { false },
        ) {
            true
        }

        assertTrue(retried)
    }

    private class ReentrantLoginSource : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = null
        override var loginUi: String? = "@js:buildLoginUi()"
        override var header: String? = null
        override var enabledCookieJar: Boolean? = true
        override var jsLib: String? = null

        var evaluationCount = 0
        var nestedLoginInfo: MutableMap<String, String>? = null
        var savedLoginInfo: String? = null

        override fun getTag() = "test"

        override fun getKey() = "https://example.com"

        override fun getLoginInfo(): String? = null

        override fun putLoginInfo(info: String): Boolean {
            savedLoginInfo = info
            return true
        }

        override fun evalJS(
            jsStr: String,
            bindingsConfig: ScriptBindings.() -> Unit,
        ): Any? {
            evaluationCount++
            nestedLoginInfo = getLoginInfoMap()
            return """[{"name":"token","type":"text","default":"default-token"}]"""
        }
    }

    private class RhinoReentrantLoginSource : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = null
        override var loginUi: String? = """@js:
            var nestedLoginInfo = source.getLoginInfoMap();
            var suffix = "default";
            [{
                name: "token",
                type: "text",
                default: nestedLoginInfo.get("token") || "rhino-" + suffix
            }];
        """.trimIndent()
        override var header: String? = null
        override var enabledCookieJar: Boolean? = true
        override var jsLib: String? = null

        var savedLoginInfo: String? = null

        override fun getTag() = "rhino-test"

        override fun getKey() = "https://rhino.example"

        override fun getLoginInfo(): String? = null

        override fun putLoginInfo(info: String): Boolean {
            savedLoginInfo = info
            return true
        }
    }
}
