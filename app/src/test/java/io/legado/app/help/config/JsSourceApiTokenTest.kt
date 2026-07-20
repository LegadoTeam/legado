package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsSourceApiTokenTest {

    @Test
    fun `token storage trims outer whitespace and rejects blank values`() {
        assertNull(normalizeJsSourceApiToken(null))
        assertNull(normalizeJsSourceApiToken("  \t\n"))
        assertEquals("secret token", normalizeJsSourceApiToken("  secret token  "))
    }
}
