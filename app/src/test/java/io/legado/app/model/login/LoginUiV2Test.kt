package io.legado.app.model.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUiV2Test {

    @Test
    fun `detects v2 marker`() {
        assertTrue(LoginUiV2.isV2(LoginUiV2.MARKER))
        assertTrue(LoginUiV2.isV2("""  {"version": 2}  """))
        assertFalse(LoginUiV2.isV2(null))
        assertFalse(LoginUiV2.isV2("""[{"name":"账号","type":"text"}]"""))
        assertFalse(LoginUiV2.isV2("""{"version":1}"""))
    }

    @Test
    fun `parses valid rows`() {
        val rows = LoginUiV2.parseRender(
            """{"rows":[
                {"key":"phone","name":"手机号","type":"text","hint":"11位","value":"138"},
                {"name":"说明","type":"label"},
                {"key":"line","name":"线路","type":"select","options":["电信","联通"]},
                {"key":"remember","name":"记住登录","type":"toggle","value":"true"},
                {"name":"发码","type":"button","action":"sendCode","countdown":60}
            ]}"""
        )

        assertEquals(5, rows!!.size)
        assertEquals("11位", rows[0].hint)
        assertEquals(listOf("电信", "联通"), rows[2].options)
        assertEquals("true", rows[3].value)
        assertEquals(60, rows[4].countdown)
    }

    @Test
    fun `rejects malformed or ambiguous rows`() {
        listOf(
            null,
            "",
            "not json",
            """{"noRows":true}""",
            """{"rows":[]}""",
            """{"rows":[{"name":"缺少键","type":"text"}]}""",
            """{"rows":[{"key":"x","name":"空选项","type":"select","options":[]}]}""",
            """{"rows":[{"name":"缺少键","type":"toggle"}]}""",
            """{"rows":[{"key":"x","name":"非法值","type":"toggle","value":"yes"}]}""",
            """{"rows":[{"key":"x","name":"空动作","type":"toggle","action":""}]}""",
            """{"rows":[{"name":"缺少动作","type":"button"}]}""",
            """{"rows":[{"key":"x","name":"甲","type":"text"},{"key":"x","name":"乙","type":"text"}]}""",
            """{"rows":[{"key":"x","name":"甲","type":"text"},{"key":"x","name":"乙","type":"toggle"}]}""",
            """{"rows":[{"name":"甲","type":"button","action":"same"},{"name":"乙","type":"button","action":"same"}]}""",
            """{"rows":[{"key":"x","name":"甲","type":"toggle","action":"same"},{"name":"乙","type":"button","action":"same"}]}""",
        ).forEach { assertNull(LoginUiV2.parseRender(it)) }
    }

    @Test
    fun `parses commands and reports malformed results`() {
        val result = LoginUiV2.parseActionResult(
            """{"state":{"step":"code"},"error":{"phone":"格式不对"},
                "login":{"token":"t1"},"close":true,"typo":1}"""
        )
        assertEquals("""{"step":"code"}""", result.stateJson)
        assertEquals("格式不对", result.error!!["phone"])
        assertEquals("""{"token":"t1"}""", result.loginJson)
        assertTrue(result.close)
        assertEquals(listOf("typo"), result.unknownKeys)
        assertFalse(result.malformed)

        assertFalse(LoginUiV2.parseActionResult(null).malformed)
        assertTrue(LoginUiV2.parseActionResult("not json").malformed)
        assertTrue(LoginUiV2.parseActionResult("[]").malformed)
        assertTrue(LoginUiV2.parseActionResult("""{"state":1}""").malformed)
        assertTrue(LoginUiV2.parseActionResult("""{"close":"false"}""").malformed)
        assertFalse(
            LoginUiV2.parseActionResult(
                """{"state":null,"error":null,"login":null,"close":null}"""
            ).malformed
        )
    }

    @Test
    fun `field values prefer render then session then storage`() {
        assertEquals("render", LoginUiV2.resolveFieldValue("render", "typed", "stored"))
        assertEquals("", LoginUiV2.resolveFieldValue("", "typed", "stored"))
        assertEquals("typed", LoginUiV2.resolveFieldValue(null, "typed", "stored"))
        assertEquals("stored", LoginUiV2.resolveFieldValue(null, null, "stored"))
        assertNull(LoginUiV2.resolveFieldValue(null, null, null))
    }
}
