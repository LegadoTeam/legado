package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceMainJsTest {

    @Test
    fun `js source uses main script for login`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            loginUrl = "https://example.com/login",
        )
        assertEquals("https://example.com/login", source.getLoginJs())

        val script = "var source = {}; function login() {}"
        source.mainJs = script

        assertEquals(script, source.getLoginJs())
    }

    @Test
    fun `declarative source keeps inline login extraction`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            loginUrl = "<js>function login(){}</js>",
        )

        assertEquals("function login(){}", source.getLoginJs())
    }

    @Test
    fun `inline login scripts allow surrounding whitespace and mixed case`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            loginUrl = "\n <JS>function login(){}</JS>\t",
            loginUi = "  <jS>return []; </JS>\n",
        )

        assertEquals("function login(){}", source.getLoginJs())
        assertEquals("return [];", source.getLoginUiJs())
    }

    @Test
    fun `login capability accepts url or form`() {
        val source = BookSource(bookSourceUrl = "https://example.com")
        assertFalse(source.hasLogin())
        assertFalse(source.hasLoginForm())

        source.loginUi = "[]"
        assertFalse(source.hasLogin())
        assertFalse(source.hasLoginForm())

        source.loginUi = "[ \n ]"
        assertFalse(source.hasLogin())
        assertFalse(source.hasLoginForm())

        source.loginUi = "[{\"name\":\"账号\",\"type\":\"text\"}]"
        assertTrue(source.hasLogin())
        assertTrue(source.hasLoginForm())

        source.loginUrl = "https://example.com/login"
        assertTrue(source.hasLogin())

        source.loginUi = "[]"
        assertTrue(source.hasLogin())
        assertFalse(source.hasLoginForm())
    }
}
