package io.legado.app.ui.widget.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JsPatternKeywordTest {

    @Test
    fun `matches javascript keywords only at word boundaries`() {
        val code = "function test(value) { if (value instanceof String) return true }"
        val matcher = jsPattern.matcher(code)
        val matches = buildList {
            while (matcher.find()) add(matcher.group())
        }

        assertEquals(listOf("function", "if", "instanceof", "return", "true"), matches)
        assertFalse(jsPattern.matcher("format variable defaultValue").find())
    }
}
