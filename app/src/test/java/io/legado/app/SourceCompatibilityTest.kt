package io.legado.app

import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.parseJsRequestHeaders
import io.legado.app.help.http.TRANSPARENT_ACCEPT_ENCODING
import io.legado.app.help.http.canUseTransparentDecompression
import io.legado.app.help.http.decompressResponse
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.ui.book.read.config.hasLoginCapability
import io.legado.app.ui.book.read.config.shouldOpenLoginOnSelection
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.htmlunit.corejs.javascript.NativeObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class SourceCompatibilityTest {

    @Test
    fun nativeObjectUsesJsonPathRules() {
        val content = RhinoScriptEngine.eval(
            "({book:{title:'Nested'},items:[{name:'First'},{name:'Second'}]})"
        )
        assertTrue(content is NativeObject)
        val analyzeRule = AnalyzeRule().setContent(content)

        assertEquals(
            "Nested",
            analyzeRule.getString(analyzeRule.splitSourceRule("$.book.title"))
        )
        assertEquals(
            listOf("First", "Second"),
            analyzeRule.getStringList(analyzeRule.splitSourceRule("$.items[*].name"))
        )
        assertEquals(
            emptyList<String>(),
            analyzeRule.getStringList(analyzeRule.splitSourceRule("$.missing[*]"))
        )
    }

    @Test
    fun escapedJsonPathLikeKeyKeepsDirectAccess() {
        val content = RhinoScriptEngine.eval("({'$.literal':'plain'})")
        val analyzeRule = AnalyzeRule().setContent(content)

        assertEquals(
            "plain",
            analyzeRule.getString(analyzeRule.splitSourceRule("@@$.literal"))
        )
    }

    @Test
    fun jsoupElementsKeepLegacyAttributeAccessFromJavaBindings() {
        val value = RhinoScriptEngine.eval(
            """
            const elements = java.getElements('#video-artist-name');
            const summary = [
                elements.attr('href'),
                elements.text(),
                elements.html(),
                elements.length,
                elements[0].tagName()
            ];
            elements[0] = 'replacement';
            summary.push(String(elements[0]));
            summary.join('|');
            """.trimIndent(),
            com.script.ScriptBindings().apply {
                this["java"] = JsoupElementsBridge()
            },
        )

        assertEquals("/artist/1|Artist|Artist|1|a|replacement", value)
    }

    @Test
    fun ordinaryListSubclassesKeepExistingRuntimeMethods() {
        val value = RhinoScriptEngine.eval(
            "java.getValues().legacyValue()",
            com.script.ScriptBindings().apply {
                this["java"] = DeclaredListBridge()
            },
        )

        assertEquals("legacy", value)
    }

    @Test
    fun jsEncodeOverloadsRemainCallableInsideWithAndEvalScopes() {
        val value = RhinoScriptEngine.eval(
            """
            const directType = typeof java.createSymmetricCrypto;
            const directCall = java.createSymmetricCrypto(
                'AES/CBC/PKCS5Padding',
                '0123456789abcdef',
                'abcdef0123456789'
            ) != null;
            const evalType = (function() {
                with (java) {
                    return eval('typeof createSymmetricCrypto');
                }
            })();
            const evalCall = (function() {
                with (java) {
                    return eval("createSymmetricCrypto('AES/CBC/PKCS5Padding', " +
                        "'0123456789abcdef', 'abcdef0123456789') != null");
                }
            })();
            [directType, directCall, evalType, evalCall].join(':');
            """.trimIndent(),
            com.script.ScriptBindings().apply {
                this["java"] = AnalyzeRule()
            },
        )

        assertEquals("function:true:function:true", value)
    }

    @Test
    fun rssSourceCryptoMethodsRemainCallableThroughNestedEval() {
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        val source = RssSource(
            sourceUrl = "https://example.com",
            sourceName = "compatibility-test",
        )
        val value = RhinoScriptEngine.eval(
            """
            const nested = (function() {
                with (java) {
                    return eval("eval(\"var crypto = createSymmetricCrypto; " +
                        "[typeof createSymmetricCrypto, typeof crypto, " +
                        "crypto('AES/CBC/PKCS5Padding', '0123456789abcdef', " +
                        "'abcdef0123456789') != null].join(':')\")");
                }
            })();
            const method = java['create' + 'SymmetricCrypto'];
            const dynamic = [typeof method, method.call(
                java,
                'AES/CBC/PKCS5Padding',
                '0123456789abcdef',
                'abcdef0123456789'
            ) != null].join(':');
            nested + '|' + dynamic;
            """.trimIndent(),
            com.script.ScriptBindings().apply {
                this["java"] = source
            },
        )

        assertEquals("function:function:true|function:true", value)
    }

    @Test
    fun jsRequestHeadersAcceptMapsAndJsonStrings() {
        assertEquals(
            mapOf("Authorization" to "Bearer token", "X-Mode" to "test"),
            parseJsRequestHeaders(
                """{"Authorization":"Bearer token","X-Mode":"test"}"""
            )
        )
        assertEquals(
            mapOf("X-Map" to "value"),
            parseJsRequestHeaders(mapOf("X-Map" to "value"))
        )
        assertEquals(
            mapOf("X-Rhino" to "value"),
            parseJsRequestHeaders(RhinoScriptEngine.eval("({'X-Rhino':'value'})"))
        )
        assertTrue(parseJsRequestHeaders(null).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            parseJsRequestHeaders("{invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseJsRequestHeaders(42)
        }
    }

    @Test
    fun httpTtsRecognizesScriptAndFormLoginCapabilities() {
        assertTrue(HttpTTS(loginUrl = "<js>login()</js>").hasLoginCapability())
        assertTrue(HttpTTS(loginUi = "[]").hasLoginCapability())
        assertFalse(HttpTTS(loginUrl = " ", loginUi = "\n").hasLoginCapability())
        assertFalse(HttpTTS().hasLoginCapability())
    }

    @Test
    fun httpTtsSelectionAlwaysOffersAvailableLoginAgain() {
        assertTrue(HttpTTS(loginUrl = "<js>login()</js>").shouldOpenLoginOnSelection())
        assertTrue(HttpTTS(loginUi = "[]").shouldOpenLoginOnSelection())
        assertFalse(HttpTTS().shouldOpenLoginOnSelection())

        val source = File(
            "src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt"
        ).readText()
        assertTrue(source.contains("if (httpTTS.shouldOpenLoginOnSelection())"))
        assertFalse(source.contains("&& httpTTS.getLoginInfo().isNullOrBlank()"))
    }

    @Test
    fun brotliResponseIsTransparentlyDecompressed() {
        val compressed = (
            "1bce00009c05ceb9f028d14e416230f718960a537b0922d2f7b6adef56532c08dff44551516690131494db" +
                "6021c7e3616c82c1bc2416abb919aaa06e8d30d82cc2981c2f5c900bfb8ee29d5c03deb1c0dacff80e" +
                "abe82ba64ed250a497162006824684db917963ecebe041b352a3e62d629cc97b95cac24265b175171e" +
                "5cb384cd0912aeb5b5dd9555f2dd1a9b20688201"
            ).decodeHex().toByteArray()

        val originalBody = TrackingResponseBody(compressed)
        val response = decompressResponse(encodedResponse("br", originalBody))

        val responseText = response.body.use { it.string() }
        assertTrue(responseText.contains("\"brotli\": true"))
        assertNull(response.header("Content-Encoding"))
        assertNull(response.header("Content-Length"))
        assertTrue("brotli response body was not closed", originalBody.closed)
    }

    @Test
    fun existingCompressionAndRequestGuardsRemainSupported() {
        val text = "existing compression remains supported"
        assertEquals(text, decompressResponse(encodedResponse("gzip", gzip(text))).body.string())
        assertEquals(
            text,
            decompressResponse(encodedResponse("deflate", deflateRaw(text))).body.string()
        )

        val request = Request.Builder().url("https://example.com/data").build()
        assertTrue(request.canUseTransparentDecompression())
        assertEquals("gzip, deflate, br", TRANSPARENT_ACCEPT_ENCODING)
        assertFalse(
            request.newBuilder().header("Range", "bytes=0-10").build()
                .canUseTransparentDecompression()
        )
        assertFalse(
            request.newBuilder().header("Accept-Encoding", "identity").build()
                .canUseTransparentDecompression()
        )
    }

    @Test
    fun invalidCompressedResponseClosesOriginalBody() {
        mapOf(
            "br" to byteArrayOf(0x11),
            "gzip" to byteArrayOf(0x00)
        ).forEach { (encoding, bytes) ->
            val body = TrackingResponseBody(bytes)

            assertThrows(IOException::class.java) {
                decompressResponse(encodedResponse(encoding, body))
            }
            assertTrue("$encoding response body was not closed", body.closed)
        }
    }

    private fun encodedResponse(encoding: String, bytes: ByteArray): Response {
        return encodedResponse(
            encoding,
            bytes.toResponseBody("application/octet-stream".toMediaType())
        )
    }

    private fun encodedResponse(encoding: String, body: ResponseBody): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/data").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Encoding", encoding)
            .header("Content-Length", body.contentLength().toString())
            .body(body)
            .build()
    }

    private fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
        return output.toByteArray()
    }

    private fun deflateRaw(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use {
            it.write(text.toByteArray())
        }
        return output.toByteArray()
    }

    private class TrackingResponseBody(bytes: ByteArray) : ResponseBody() {

        var closed = false
            private set

        private val length = bytes.size.toLong()
        private val bufferedSource = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = length

        override fun source(): BufferedSource = bufferedSource
    }

    class JsoupElementsBridge {
        @Suppress("UNCHECKED_CAST")
        fun getElements(rule: String): List<Any> {
            return Jsoup.parse(
                "<a id='video-artist-name' href='/artist/1'>Artist</a>"
            ).select(rule) as List<Any>
        }
    }

    class DeclaredListBridge {
        fun getValues(): List<String> = LegacyList()
    }

    class LegacyList : ArrayList<String>() {
        init {
            add("first")
            add("second")
        }

        fun legacyValue(): String = "legacy"
    }

}
