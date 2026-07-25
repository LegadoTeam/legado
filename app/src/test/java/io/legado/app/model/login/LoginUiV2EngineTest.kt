package io.legado.app.model.login

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUiV2EngineTest {

    private val source = BookSource(
        bookSourceUrl = "https://example.com",
        bookSourceName = "登录测试",
        loginUi = LoginUiV2.MARKER,
        mainJs = """
            function loginUi(state) {
                if (!state.step) return { rows: [
                    { key: "phone", name: "手机号", type: "text" },
                    { name: "发送验证码", type: "button", action: "sendCode" }
                ] };
                return { rows: [
                    { key: "code", name: "验证码", type: "text" },
                    { name: "重新发码", type: "button", action: "sendCode", countdown: 60 }
                ] };
            }
            function loginAction(action, state, form) {
                if (action == "sendCode") {
                    if (!form.phone) return { error: { phone: "手机号必填" } };
                    return { state: { step: "code", phone: `+86${'$'}{form.phone}` } };
                }
                if (action == "noop") return;
                return { login: { token: state.phone + "-tk" }, close: true };
            }
        """.trimIndent(),
    )

    @Test
    fun `renders from explicit state`() {
        val first = LoginUiV2.parseRender(source.evalLoginUiV2("{}"))
        assertEquals("phone", first!![0].key)

        val second = LoginUiV2.parseRender(source.evalLoginUiV2("""{"step":"code"}"""))
        assertEquals("code", second!![0].key)
        assertEquals(60, second[1].countdown)
    }

    @Test
    fun `dispatches actions across json boundary`() {
        val state = LoginUiV2.parseActionResult(
            source.evalLoginActionV2(
                "sendCode",
                "{}",
                """{"phone":"13800000000"}""",
            )
        )
        assertTrue(state.stateJson!!.contains("+8613800000000"))
        assertNull(state.error)

        val error = LoginUiV2.parseActionResult(
            source.evalLoginActionV2("sendCode", "{}", "{}")
        )
        assertEquals("手机号必填", error.error!!["phone"])

        val neutral = LoginUiV2.parseActionResult(
            source.evalLoginActionV2("noop", "{}", "{}")
        )
        assertNull(neutral.stateJson)
        assertFalse(neutral.close)
    }

    @Test
    fun `declarative login script uses loginUrl`() {
        val declarative = BookSource(
            bookSourceUrl = "https://declarative.example.com",
            bookSourceName = "声明式登录测试",
            loginUi = LoginUiV2.MARKER,
            loginUrl = """
                function loginUi(state) {
                    return { rows: [{ key: "account", name: "账号", type: "text" }] };
                }
                function loginAction(action, state, form) {
                    return { login: { account: form.account }, close: true };
                }
            """.trimIndent(),
        )

        val rows = LoginUiV2.parseRender(declarative.evalLoginUiV2("{}"))
        assertEquals("account", rows!![0].key)
        val result = LoginUiV2.parseActionResult(
            declarative.evalLoginActionV2("submit", "{}", """{"account":"reader"}""")
        )
        assertEquals("""{"account":"reader"}""", result.loginJson)
        assertTrue(result.close)
    }
}
