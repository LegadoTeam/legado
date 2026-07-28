package io.legado.app.ui.code

import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ConversionException
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ErrorReason
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlAnalyzeUrlConverterTest {

    @Test
    fun `curl converts request method headers cookies and JSON body`() {
        val converted = CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
            """
            curl 'https://example.com/api?q=a,b' \
              -H 'Accept: application/json' \
              --cookie='sid=a=b' \
              --data-raw '{"name":"reader"}'
            """.trimIndent()
        )
        val (url, option) = parseAnalyzeUrl(converted)

        assertEquals("https://example.com/api?q=a,b", url)
        assertEquals("POST", option.getMethod())
        assertEquals("application/json", option.getHeaderMap()?.get("Accept"))
        assertEquals("sid=a=b", option.getHeaderMap()?.get("Cookie"))
        assertEquals("""{"name":"reader"}""", option.getBody())
        assertFalse(option.getFollowRedirects() ?: true)
    }

    @Test
    fun `curl body remains a string including empty and JSON-looking values`() {
        val jsonBody = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -d '[1,2]' https://example.com"""
            )
        )
        val emptyBody = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --data-raw '' https://example.com"""
            )
        )

        assertEquals("[1,2]", jsonBody["body"])
        assertEquals("", emptyBody["body"])
    }

    @Test
    fun `analyze URL split follows the first comma before an option object`() {
        val curl = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com/a,b?x=1,{"method":"POST","body":{"x":1}}"""
        )

        assertTrue(curl.contains("'https://example.com/a,b?x=1'"))
        assertTrue(curl.contains("-L"))
        assertTrue(curl.contains("""-H 'Content-Type: application/json'"""))
        assertTrue(curl.contains("--data-raw '{"))
        assertTrue(curl.contains("\"x\": 1"))
    }

    @Test
    fun `GET and HEAD bodies are rejected because AnalyzeUrl does not send them`() {
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.analyzeUrlToCurl(
                """https://example.com,{"method":"GET","body":"q=1"}"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.analyzeUrlToCurl(
                """https://example.com,{"method":"HEAD","body":"q=1"}"""
            )
        }
    }

    @Test
    fun `equals signs survive long option forms and shell round trip`() {
        val analyzeUrl = CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
            """curl.exe --request=HEAD --header='X-Key: a=b' --url='https://example.com/a?x=1&y=2'"""
        )
        val curl = CurlAnalyzeUrlConverter.analyzeUrlToCurl(analyzeUrl)

        assertTrue(curl.contains("-I"))
        assertFalse(curl.contains("-X HEAD"))
        assertTrue(curl.contains("""-H 'X-Key: a=b'"""))
        assertTrue(curl.contains("'https://example.com/a?x=1&y=2'"))
    }

    @Test
    fun `unsupported request semantics fail instead of changing behavior`() {
        assertFailure(ErrorReason.UNSUPPORTED_METHOD) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -X PUT https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -F 'file=@book.txt' https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --proxy http://127.0.0.1:8080 https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl https://example.com https://example.org"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --cookie cookies.txt https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --json @payload.json https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -X GET -d 'q=1' https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --referer='https://example.com;auto' -L https://example.com"""
            )
        }
        assertFailure(ErrorReason.UNSUPPORTED_OPTION) {
            CurlAnalyzeUrlConverter.analyzeUrlToCurl(
                """https://example.com,{"method":"GET","timeout":1000}"""
            )
        }
    }

    @Test
    fun `user agent and referer options become headers`() {
        val options = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -A 'Reader/1.0' --referer=https://example.com/from -L https://example.com"""
            )
        )
        val headers = options["headers"] as Map<*, *>

        assertEquals("Reader/1.0", headers["User-Agent"])
        assertEquals("https://example.com/from", headers["Referer"])
        assertFalse(options.containsKey("followRedirects"))
    }

    @Test
    fun `explicit JSON headers win regardless of option order`() {
        val options = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl --json '{}' -H 'Content-Type: application/problem+json' https://example.com"""
            )
        )
        val headers = options["headers"] as Map<*, *>

        assertEquals("application/problem+json", headers["Content-Type"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun `ordinary data keeps curl form content type and canonical header names`() {
        val defaultOptions = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -d '{"x":1}' https://example.com"""
            )
        )
        val explicitOptions = parseRawOptions(
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl -d 'x=1' -H 'content-type: text/plain' https://example.com"""
            )
        )
        val defaultHeaders = defaultOptions["headers"] as Map<*, *>
        val explicitHeaders = explicitOptions["headers"] as Map<*, *>

        assertEquals("application/x-www-form-urlencoded", defaultHeaders["Content-Type"])
        assertEquals("text/plain", explicitHeaders["Content-Type"])
        assertFalse(explicitHeaders.containsKey("content-type"))
    }

    @Test
    fun `string encoded AnalyzeUrl headers remain compatible`() {
        val curl = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com,{"headers":"{\"X-Key\":\"a=b\"}"}"""
        )

        assertTrue(curl.contains("""-H 'X-Key: a=b'"""))
    }

    @Test
    fun `HEAD uses the curl head option and false redirects omit location`() {
        val head = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com,{"method":"HEAD"}"""
        )
        val noRedirect = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com,{"followRedirects":false}"""
        )

        assertTrue(head.contains("-I"))
        assertFalse(head.contains("-X HEAD"))
        assertFalse(noRedirect.contains(" -L "))
    }

    @Test
    fun `AnalyzeUrl form bodies use the same default encoding`() {
        val curl = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com,{"method":"POST","body":"q=a b","followRedirects":false}"""
        )
        val whitespace = CurlAnalyzeUrlConverter.analyzeUrlToCurl(
            """https://example.com,{"method":"POST","body":" "}"""
        )

        assertTrue(curl.contains("--data-raw q=a+b"))
        assertFalse(curl.contains("Content-Type: application/json"))
        assertTrue(whitespace.contains("--data-raw +"))
    }

    @Test
    fun `empty user agent keeps the app header removal convention`() {
        val analyzeUrl = CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
            """curl -A '' https://example.com"""
        )
        val curl = CurlAnalyzeUrlConverter.analyzeUrlToCurl(analyzeUrl)

        assertEquals("null", parseAnalyzeUrl(analyzeUrl).second.getHeaderMap()?.get("User-Agent"))
        assertTrue(curl.contains("-A ''"))
    }

    @Test
    fun `unclosed shell quotes are rejected`() {
        assertFailure(ErrorReason.INVALID_CURL) {
            CurlAnalyzeUrlConverter.curlToAnalyzeUrl(
                """curl 'https://example.com"""
            )
        }
    }

    private fun parseAnalyzeUrl(value: String): Pair<String, AnalyzeUrl.UrlOption> {
        val matcher = AnalyzeUrl.paramPattern.matcher(value)
        assertTrue(matcher.find())
        val url = value.substring(0, matcher.start())
        val option = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
            value.substring(matcher.end())
        ).getOrThrow()
        return url to option
    }

    private fun parseRawOptions(value: String): Map<*, *> {
        val matcher = AnalyzeUrl.paramPattern.matcher(value)
        assertTrue(matcher.find())
        return GSON.fromJsonObject<Map<String, Any?>>(value.substring(matcher.end()))
            .getOrThrow()
    }

    private fun assertFailure(reason: ErrorReason, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is ConversionException)
        error as ConversionException
        assertEquals(reason, error.reason)
    }
}
