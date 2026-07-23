package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSharePassphraseTest {

    private val mappingHeavyUrl = "https://example.com/path/file.json?key=4%2F5"
    private val fixedTime = 1_789_344_000_000L

    @Test
    fun decodesLegadoTFixturesForAllSupportedTypes() {
        val expiresAt = 1_800_000_000_000L
        val fixtures = mapOf(
            SourceSharePassphrase.Type.BOOK_SOURCE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！sy©1800000¥Sigma^",
            SourceSharePassphrase.Type.RSS_SOURCE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！dy©1800000¥Sigma^",
            SourceSharePassphrase.Type.DICT_RULE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！zd©1800000¥Sigma^",
            SourceSharePassphrase.Type.REPLACE_RULE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！jh©1800000¥Sigma^",
            SourceSharePassphrase.Type.TOC_RULE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！ml©1800000¥Sigma^",
            SourceSharePassphrase.Type.TTS_RULE to
                "复制口令到阅读导入#L:example店🛜1刚path钢file店串?key=🕓拜2F五！ld©1800000¥Sigma^",
        )

        fixtures.forEach { (type, fixture) ->
            assertEquals(
                fixture.replace("©1800000¥Sigma^", "©0¥Legado^"),
                SourceSharePassphrase.encode(mappingHeavyUrl, type, 0, fixedTime),
            )
            assertEquals(
                SourceSharePassphrase.DecodeResult.Success(
                    SourceSharePassphrase.Value(mappingHeavyUrl, type, expiresAt, "Sigma")
                ),
                SourceSharePassphrase.decode(fixture, fixedTime),
            )
        }
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

    @Test
    fun canEncodeRequiresSupportedSchemeAndHost() {
        assertTrue(SourceSharePassphrase.canEncode("https://example.com/path"))
        assertTrue(SourceSharePassphrase.canEncode("http://example.com/path"))
        assertFalse(SourceSharePassphrase.canEncode("https:///missing-host"))
        assertFalse(SourceSharePassphrase.canEncode("http:///missing-host"))
        assertFalse(SourceSharePassphrase.canEncode("https://example.com:bad/path"))
        assertFalse(SourceSharePassphrase.canEncode("HTTPS://example.com/path"))
        assertFalse(SourceSharePassphrase.canEncode("not a url"))
    }

    @Test
    fun canEncodeAndRoundTripInternationalizedHost() {
        val url = "https://例子.测试/path"

        assertTrue(SourceSharePassphrase.canEncode(url))
        assertEquals(
            SourceSharePassphrase.DecodeResult.Success(
                SourceSharePassphrase.Value(
                    url,
                    SourceSharePassphrase.Type.BOOK_SOURCE,
                    0,
                    "Legado",
                )
            ),
            SourceSharePassphrase.decode(
                SourceSharePassphrase.encode(
                    url,
                    SourceSharePassphrase.Type.BOOK_SOURCE,
                    0,
                    fixedTime,
                ),
                fixedTime,
            ),
        )
    }

    @Test
    fun canEncodeRejectsAmbiguousMappingTokens() {
        listOf(
            "https://example.com/path/电",
            "https://example.com/path/🛜1",
            "https://example.com/path#L:",
        ).forEach { url ->
            assertFalse(SourceSharePassphrase.canEncode(url))
        }
    }

    @Test
    fun canEncodeRejectsStructuralDelimiters() {
        listOf("！", "©", "¥", "^").forEach { delimiter ->
            assertFalse(SourceSharePassphrase.canEncode("https://example.com/path$delimiter"))
        }
    }

    @Test
    fun decodeRequiresFullPrefix() {
        assertEquals(
            SourceSharePassphrase.DecodeResult.NotFound,
            SourceSharePassphrase.decode("#L:example电🛜1！sy©0¥Legado^"),
        )
        assertEquals(
            SourceSharePassphrase.DecodeResult.NotFound,
            SourceSharePassphrase.decode("口令到阅读导入#L:example电🛜1！sy©0¥Legado^"),
        )
    }

    @Test
    fun decodeRejectsInvalidExpiryTokens() {
        listOf("", "00", "123456", "12345678", "abc", "١٢٣٤٥٦٧").forEach { expiry ->
            assertEquals(
                SourceSharePassphrase.DecodeResult.Invalid,
                SourceSharePassphrase.decode(
                    "复制口令到阅读导入#L:example电🛜1！sy©$expiry¥Legado^"
                ),
            )
        }
    }
}
