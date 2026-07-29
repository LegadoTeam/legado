package io.legado.app.ui.code

import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeObjectCompletionLanguageTest {

    @Test
    fun `runtime object members keep methods properties and global scope distinct`() {
        val crypto = RuntimeObjectCompletionLanguage.complete("java.cre")!!
            .suggestions.first { it.label == "createSymmetricCrypto" }
        val androidId = RuntimeObjectCompletionLanguage.complete("java.android")!!
            .suggestions.single { it.label == "androidId()" }
        val bookName = RuntimeObjectCompletionLanguage.complete("book.na")!!
            .suggestions.single { it.label == "name" }

        assertEquals("createSymmetricCrypto()", crypto.commitText)
        assertEquals(-1, crypto.cursorOffset)
        assertEquals("androidId()", androidId.commitText)
        assertEquals(0, androidId.cursorOffset)
        assertEquals(CompletionItemKind.Property, bookName.kind)
        assertTrue(
            RuntimeObjectCompletionLanguage.complete("chapter.ti")!!
                .suggestions.any { it.label == "title" }
        )
        assertTrue(
            RuntimeObjectCompletionLanguage.complete("config.bookS")!!
                .suggestions.any { it.label == "bookSourceName" }
        )
        assertTrue(
            RuntimeObjectCompletionLanguage.complete("book.getV")!!
                .suggestions.any { it.label == "getVariable" }
        )
        assertTrue(
            RuntimeObjectCompletionLanguage.complete("chapter.getV")!!
                .suggestions.any { it.label == "getVariable" }
        )
        assertNull(RuntimeObjectCompletionLanguage.complete("unknown.cre"))
        assertNull(RuntimeObjectCompletionLanguage.complete("holder.java.cre"))

        listOf("java", "source", "config", "cookie", "cache", "book", "chapter")
            .forEach { objectName ->
                val labels = RuntimeObjectCompletionLanguage.complete("$objectName.")!!
                    .suggestions.map { it.label }
                assertEquals(labels.distinct().size, labels.size)
            }

        val longLine = "x".repeat(10_000) + " java.cre"
        assertEquals(
            "java.cre",
            RuntimeObjectCompletionLanguage.contextBeforeCursor(longLine, longLine.length),
        )
        val longChain = "x".repeat(128) + ".java.cre"
        assertNull(
            RuntimeObjectCompletionLanguage.contextBeforeCursor(longChain, longChain.length)
        )
    }
}
