package io.legado.app.ui.code

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuleExpressionFormatterTest {

    @Test
    fun `whole rule expression keeps its double-brace wrapper`() = runBlocking {
        val formatted = formatRuleExpression(
            " {{ function demo(){return 1;} }} "
        ) { body ->
            assertEquals("function demo(){return 1;}", body)
            "function demo() {\n    return 1;\n}"
        }

        assertEquals(
            "{{function demo() {\n    return 1;\n}}}",
            formatted
        )
        assertEquals("{{value}}", formatRuleExpression("{{ value }}") { null })
    }

    @Test
    fun `embedded expression stays on the normal formatting path`() = runBlocking {
        var formatterCalled = false

        val formatted = formatRuleExpression("prefix {{value}}") {
            formatterCalled = true
            it
        }

        assertNull(formatted)
        assertFalse(formatterCalled)
    }
}
