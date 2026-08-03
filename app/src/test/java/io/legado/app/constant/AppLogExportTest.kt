package io.legado.app.constant

import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogExportTest {

    @Test
    fun `export text is chronological and includes throwable details`() {
        val text = AppLog.exportText(
            listOf(
                Triple(2_000L, "newer", IllegalStateException("boom")),
                Triple(1_000L, "older", null),
            )
        )

        assertTrue(text.indexOf("older") < text.indexOf("newer"))
        assertTrue(text.contains("IllegalStateException: boom"))
    }
}
