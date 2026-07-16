package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSharePassphraseTest {

    @Test
    fun decodesExistingHttpsPassphraseFormat() {
        val result = SourceSharePassphrase.decode(
            "复制口令到阅读导入#L:example电🛜1杠rules电串！sy©0¥Legado^"
        )

        assertTrue(result is SourceSharePassphrase.DecodeResult.Success)
        val value = (result as SourceSharePassphrase.DecodeResult.Success).value
        assertEquals("https://example.com/rules.json", value.url)
        assertEquals(SourceSharePassphrase.Type.BOOK_SOURCE, value.type)
        assertEquals(0L, value.expiresAt)
        assertEquals("Legado", value.customWord)
    }

    @Test
    fun roundTripPreservesTypeAndExpiry() {
        val now = 1_800_000_000_000L
        val passphrase = SourceSharePassphrase.encode(
            url = "https://example.com/bookSource.json",
            type = SourceSharePassphrase.Type.BOOK_SOURCE,
            expiryDays = 30,
            time = now,
        )

        val result = SourceSharePassphrase.decode(passphrase, time = now)

        assertTrue(result is SourceSharePassphrase.DecodeResult.Success)
        val value = (result as SourceSharePassphrase.DecodeResult.Success).value
        val expectedExpiry = (now + 30L * 24 * 60 * 60 * 1000)
            .toString()
            .take(7)
            .toLong() * 1_000_000L
        assertEquals("https://example.com/bookSource.json", value.url)
        assertEquals(SourceSharePassphrase.Type.BOOK_SOURCE, value.type)
        assertEquals(expectedExpiry, value.expiresAt)
    }

    @Test
    fun httpLinksUseARecognizableMarker() {
        val passphrase = SourceSharePassphrase.encode(
            url = "http://example.com/rules.json",
            type = SourceSharePassphrase.Type.REPLACE_RULE,
            expiryDays = 0,
            time = 1_800_000_000_000L,
        )

        assertTrue(passphrase.contains("#L0:"))
        val result = SourceSharePassphrase.decode(passphrase)
        assertTrue(result is SourceSharePassphrase.DecodeResult.Success)
        assertEquals(
            "http://example.com/rules.json",
            (result as SourceSharePassphrase.DecodeResult.Success).value.url,
        )
    }

    @Test
    fun expiredPassphraseIsRejected() {
        val createdAt = 1_700_000_000_000L
        val passphrase = SourceSharePassphrase.encode(
            url = "https://example.com/rss.json",
            type = SourceSharePassphrase.Type.RSS_SOURCE,
            expiryDays = 1,
            time = createdAt,
        )

        assertEquals(
            SourceSharePassphrase.DecodeResult.Expired,
            SourceSharePassphrase.decode(
                passphrase,
                time = createdAt + 2L * 24 * 60 * 60 * 1000,
            ),
        )
    }

    @Test
    fun malformedOrUnknownPassphraseIsRejected() {
        assertEquals(
            SourceSharePassphrase.DecodeResult.Invalid,
            SourceSharePassphrase.decode(
                "复制口令到阅读导入#L:example电🛜1！sy0¥Legado^"
            ),
        )
        assertEquals(
            SourceSharePassphrase.DecodeResult.Invalid,
            SourceSharePassphrase.decode(
                "复制口令到阅读导入#L:example电🛜1！xx©0¥Legado^"
            ),
        )
    }
}
