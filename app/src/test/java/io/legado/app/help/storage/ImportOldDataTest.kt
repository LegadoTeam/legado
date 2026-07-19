package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ImportOldDataTest {

    @Test
    fun `blank newline rule is omitted from multiple explore urls`() {
        val migrated = ImportOldData.toNewUrls(
            "https://example.com/a\n   \nhttps://example.com/b"
        )

        assertEquals(
            "https://example.com/a\nhttps://example.com/b",
            migrated,
        )
        assertFalse(migrated.orEmpty().lineSequence().any { it == "null" })
    }

    @Test
    fun `blank ampersand rule is omitted from multiple explore urls`() {
        assertEquals(
            "https://example.com/{{key}}\nhttps://example.net/{{page}}",
            ImportOldData.toNewUrls(
                "https://example.com/searchKey&&   &&https://example.net/searchPage"
            ),
        )
    }

    @Test
    fun `only blank multiple explore urls migrate to null`() {
        assertNull(ImportOldData.toNewUrls("  && \n &&  "))
    }

    @Test
    fun `single explore url remains unchanged`() {
        val url = "https://example.com/books"

        assertEquals(url, ImportOldData.toNewUrls(url))
    }

    @Test
    fun `script explore url remains unchanged`() {
        val script = "<js>return 'https://example.com'"

        assertEquals(script, ImportOldData.toNewUrls(script))
    }

    @Test
    fun `at js explore url remains unchanged`() {
        val script = "@js:return 'https://example.com'"

        assertEquals(script, ImportOldData.toNewUrls(script))
    }
}
