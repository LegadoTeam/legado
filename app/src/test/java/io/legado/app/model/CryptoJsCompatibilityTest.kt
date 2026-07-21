package io.legado.app.model

import androidx.collection.LruCache
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.jsSource.JsSourceEngine
import org.htmlunit.corejs.javascript.TopLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CryptoJsCompatibilityTest {

    private var previousCryptoJsText: Any? = null
    private var previousCryptoJsScript: Any? = null
    private val cryptoOwner = Any()

    @Before
    fun installTestAsset() {
        previousCryptoJsText = field("cryptoJsText").get(SharedJsScope)
        previousCryptoJsScript = field("cryptoJsScript").get(SharedJsScope)
        field("cryptoJsText").set(SharedJsScope, cryptoJs)
        field("cryptoJsScript").set(SharedJsScope, null)
        resetScopes()
    }

    @After
    fun restoreSharedScope() {
        resetScopes()
        field("cryptoJsText").set(SharedJsScope, previousCryptoJsText)
        field("cryptoJsScript").set(SharedJsScope, previousCryptoJsScript)
    }

    @Test
    fun `supports hashes hmac and base64`() {
        val result = evalWithCrypto(
            """
                [
                    CryptoJS.MD5('abc').toString(),
                    CryptoJS.SHA1('abc').toString(),
                    CryptoJS.SHA256('abc').toString(),
                    CryptoJS.HmacSHA256(
                        'The quick brown fox jumps over the lazy dog',
                        'key'
                    ).toString(),
                    CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse('hello'))
                ].join('|');
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "900150983cd24fb0d6963f7d28e17f72",
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                "aGVsbG8=",
            ).joinToString("|"),
            result,
        )
    }

    @Test
    fun `supports aes with explicit key and iv`() {
        val result = evalWithCrypto(
            """
                var plaintext = CryptoJS.enc.Hex.parse('00112233445566778899aabbccddeeff');
                var key = CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f');
                var iv = CryptoJS.enc.Hex.parse('00000000000000000000000000000000');
                CryptoJS.AES.encrypt(plaintext, key, {
                    iv: iv,
                    mode: CryptoJS.mode.CBC,
                    padding: CryptoJS.pad.NoPadding
                }).ciphertext.toString();
            """.trimIndent(),
        )

        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", result)
    }

    @Test
    fun `uses secure random for word arrays and passphrase salts`() {
        val scope = requireNotNull(
            SharedJsScope.getScope("var cryptoJsRandomScope = true;", null),
        )
        val result = evalInChildScope(
            scope,
            """
                Math.random = function() {
                    throw new Error('Math.random must not be used');
                };
                var firstRandom = CryptoJS.lib.WordArray.random(32).toString();
                var secondRandom = CryptoJS.lib.WordArray.random(32).toString();
                var firstCipher = CryptoJS.AES.encrypt('正文', 'password').toString();
                var secondCipher = CryptoJS.AES.encrypt('正文', 'password').toString();
                [
                    firstRandom.length === 64,
                    firstRandom !== secondRandom,
                    firstCipher !== secondCipher,
                    firstCipher.indexOf('U2FsdGVkX1') === 0,
                    CryptoJS.AES.decrypt(firstCipher, 'password')
                        .toString(CryptoJS.enc.Utf8) === '正文',
                    CryptoJS.AES.decrypt(secondCipher, 'password')
                        .toString(CryptoJS.enc.Utf8) === '正文'
                ].every(function(value) { return value; });
            """.trimIndent(),
        )

        assertEquals(true, result)
    }

    @Test
    fun `blank js libraries fall back at all four entry points`() {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val source = BookSource(
            bookSourceUrl = "https://127.0.0.1",
            bookSourceName = "Crypto source",
            jsLib = " ",
            mainJs = """
                function digest() {
                    return CryptoJS.SHA256('abc').toString();
                }
            """.trimIndent(),
        )

        assertEquals(
            expected,
            AnalyzeRule(source = source)
                .evalJS("CryptoJS.SHA256('abc').toString()"),
        )
        assertEquals(
            expected,
            AnalyzeUrl(
                "https://127.0.0.1",
                source = source,
                headerMapF = emptyMap(),
            )
                .evalJS("CryptoJS.SHA256('abc').toString()"),
        )
        assertEquals(
            expected,
            source.evalJS("CryptoJS.SHA256('abc').toString()"),
        )
        assertEquals(
            expected,
            JsSourceEngine(source).callFunction("digest", emptyList()),
        )
    }

    @Test
    fun `js source config exposes the requested top level runtime`() {
        val source = JsSourceConfig.extract(
            """
                var config = {
                    bookSourceUrl: 'https://127.0.0.1',
                    bookSourceName: CryptoJS.MD5('abc').toString(),
                    bookSourceComment: [
                        typeof Packages,
                        typeof java,
                        typeof getClass,
                        typeof __legadoSecureRandomInt
                    ].join('|')
                };
                function search(key, page) { return []; }
                function getChapters(book) { return []; }
                function getContent(chapter) { return ''; }
            """.trimIndent(),
        )

        assertEquals("900150983cd24fb0d6963f7d28e17f72", source.bookSourceName)
        assertEquals("object|object|function|undefined", source.bookSourceComment)
    }

    @Test
    fun `blank library crypto scopes are isolated by source instance`() {
        val firstSource = BookSource(
            bookSourceUrl = "https://127.0.0.1/first",
            bookSourceName = "First source",
        )
        val secondSource = BookSource(
            bookSourceUrl = "https://127.0.0.1/second",
            bookSourceName = "Second source",
        )
        val first = requireNotNull(SharedJsScope.getCryptoScope(firstSource, null))
        val second = requireNotNull(SharedJsScope.getCryptoScope(secondSource, null))

        assertNotSame(first, second)
        evalInChildScope(
            first,
            """
                CryptoJS.__legadoDefaultScopeMarker = 'first';
                Array.prototype.__legadoDefaultScopeMarker = 'first';
            """.trimIndent(),
        )
        assertEquals(
            "undefined",
            evalInChildScope(second, "typeof CryptoJS.__legadoDefaultScopeMarker"),
        )
        assertEquals(
            "undefined",
            evalInChildScope(second, "typeof Array.prototype.__legadoDefaultScopeMarker"),
        )
    }

    @Test
    fun `custom library scopes include isolated crypto instances`() {
        val first = requireNotNull(
            SharedJsScope.getScope("var customScopeName = 'first';", null),
        )
        val second = requireNotNull(
            SharedJsScope.getScope("var customScopeName = 'second';", null),
        )

        assertNotSame(first, second)
        assertEquals("function", evalInChildScope(first, "typeof CryptoJS.MD5"))
        assertEquals("function", evalInChildScope(second, "typeof CryptoJS.MD5"))
        evalInChildScope(first, "CryptoJS.__legadoScopeMarker = 'first';")
        assertEquals("first", evalInChildScope(first, "CryptoJS.__legadoScopeMarker"))
        assertEquals(
            "undefined",
            evalInChildScope(second, "typeof CryptoJS.__legadoScopeMarker"),
        )
        assertEquals("first", evalInChildScope(first, "customScopeName"))
        assertEquals("second", evalInChildScope(second, "customScopeName"))
    }

    @Test
    fun `custom library properties remain deletable after initialization`() {
        val scope = requireNotNull(
            SharedJsScope.getScope("removableLibraryValue = 'ready';", null),
        )

        assertEquals(
            "undefined",
            RhinoScriptEngine.eval(
                "delete globalThis.removableLibraryValue; " +
                    "typeof globalThis.removableLibraryValue",
                scope,
                null,
            ),
        )
    }

    @Test
    fun `same custom library is constructed once under concurrent access`() {
        clearCustomScopes()
        val jsLib = "var concurrentCryptoScope = 'ready';"
        val customScopes = requestConcurrently {
            SharedJsScope.getScope(jsLib, null)
        }
        customScopes.drop(1).forEach { assertSame(customScopes.first(), it) }
        assertEquals(
            "ready",
            evalInChildScope(customScopes.first(), "concurrentCryptoScope"),
        )
    }

    @Test
    fun `same owner reuses crypto scope on the same thread`() {
        val owner = Any()
        val first = requireNotNull(SharedJsScope.getCryptoScope(owner, null))
        val second = requireNotNull(SharedJsScope.getCryptoScope(owner, null))

        assertSame(first, second)
    }

    @Test
    fun `same owner isolates crypto by thread and remains stable concurrently`() {
        val owner = Any()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val barrier = CyclicBarrier(2)
        try {
            val futures = List(2) {
                executor.submit(Callable {
                    start.await()
                    val first = requireNotNull(SharedJsScope.getCryptoScope(owner, null))
                    val second = requireNotNull(SharedJsScope.getCryptoScope(owner, null))
                    if (first !== second) error("Same thread did not reuse its CryptoJS scope")
                    barrier.await(5, TimeUnit.SECONDS)
                    first to List(40) {
                        evalInChildScope(first, STABLE_CRYPTO_SCRIPT).toString()
                    }
                })
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertNotSame(results[0].first, results[1].first)
            results.flatMap { it.second }.forEach { assertEquals(STABLE_CRYPTO_RESULT, it) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `crypto bundle is compiled once across isolated owners`() {
        val firstOwner = Any()
        val secondOwner = Any()
        requireNotNull(SharedJsScope.getCryptoScope(firstOwner, null))
        val firstCompiledScript = requireNotNull(field("cryptoJsScript").get(SharedJsScope))

        requireNotNull(SharedJsScope.getCryptoScope(secondOwner, null))

        assertSame(firstCompiledScript, field("cryptoJsScript").get(SharedJsScope))
    }

    @Test
    fun `different owner crypto scopes initialize without a global creation lock`() {
        field("cryptoJsText").set(SharedJsScope, BARRIER_CRYPTO_SCRIPT)
        field("cryptoJsScript").set(SharedJsScope, null)
        clearCryptoScopes()
        scopeCreationBarrier.reset()
        val owners = listOf(Any(), Any())

        val scopes = requestConcurrently(owners.size) { index ->
            SharedJsScope.getCryptoScope(owners[index], null)
        }

        assertNotSame(scopes[0], scopes[1])
    }

    @Test
    fun `different custom libraries initialize concurrently`() {
        clearCustomScopes()
        scopeCreationBarrier.reset()
        val scripts = listOf(
            """
                Packages.io.legado.app.model.CryptoJsCompatibilityTest.scopeCreationBarrier
                    .await(5, java.util.concurrent.TimeUnit.SECONDS);
                var concurrentScopeName = 'first';
            """.trimIndent(),
            """
                Packages.io.legado.app.model.CryptoJsCompatibilityTest.scopeCreationBarrier
                    .await(5, java.util.concurrent.TimeUnit.SECONDS);
                var concurrentScopeName = 'second';
            """.trimIndent(),
        )

        val scopes = requestConcurrently(scripts.size) { index ->
            SharedJsScope.getScope(scripts[index], null)
        }

        assertEquals("first", evalInChildScope(scopes[0], "concurrentScopeName"))
        assertEquals("second", evalInChildScope(scopes[1], "concurrentScopeName"))
    }

    private fun evalWithCrypto(script: String): Any? {
        return evalInChildScope(
            requireNotNull(SharedJsScope.getCryptoScope(cryptoOwner, null)),
            script,
        )
    }

    private fun evalInChildScope(parent: TopLevel, script: String): Any? {
        val scope = ScriptBindings().apply { chainTo(parent) }
        return RhinoScriptEngine.eval(script, scope, null)
    }

    private fun requestConcurrently(factory: () -> ScriptBindings?): List<ScriptBindings> {
        return requestConcurrently(24) { factory() }
    }

    private fun requestConcurrently(
        count: Int,
        factory: (Int) -> ScriptBindings?,
    ): List<ScriptBindings> {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        return try {
            val futures = List(count) { index ->
                executor.submit(Callable {
                    start.await()
                    factory(index)
                })
            }
            start.countDown()
            futures.map { future ->
                requireNotNull(future.get(30, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun resetScopes() {
        clearCryptoScopes()
        clearCustomScopes()
    }

    private fun clearCryptoScopes() {
        val cache = field("cryptoScopeMap").get(SharedJsScope) as LruCache<*, *>
        cache.evictAll()
        val locks = field("cryptoScopeCreationLocks").get(SharedJsScope) as MutableMap<*, *>
        locks.clear()
    }

    private fun clearCustomScopes() {
        val cache = field("scopeMap").get(SharedJsScope) as LruCache<*, *>
        cache.evictAll()
        val locks = field("scopeCreationLocks").get(SharedJsScope) as MutableMap<*, *>
        locks.clear()
    }

    private fun field(name: String): Field {
        return SharedJsScope::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
    }

    companion object {
        private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"
        private const val STABLE_CRYPTO_RESULT =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad|" +
                "69c4e0d86a7b0430d8cdb78070b4c55a"
        private val STABLE_CRYPTO_SCRIPT = """
            var digest = CryptoJS.SHA256('abc').toString();
            var plaintext = CryptoJS.enc.Hex.parse('00112233445566778899aabbccddeeff');
            var key = CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f');
            var iv = CryptoJS.enc.Hex.parse('00000000000000000000000000000000');
            var encrypted = CryptoJS.AES.encrypt(plaintext, key, {
                iv: iv,
                mode: CryptoJS.mode.CBC,
                padding: CryptoJS.pad.NoPadding
            }).ciphertext.toString();
            digest + '|' + encrypted;
        """.trimIndent()
        private val BARRIER_CRYPTO_SCRIPT = """
            var CryptoJS = {
                lib: {
                    WordArray: {
                        create: function(words, nBytes) {
                            return { words: words, sigBytes: nBytes };
                        }
                    }
                }
            };
            Packages.io.legado.app.model.CryptoJsCompatibilityTest.scopeCreationBarrier
                .await(5, java.util.concurrent.TimeUnit.SECONDS);
        """.trimIndent()

        @JvmField
        val scopeCreationBarrier = CyclicBarrier(2)

        private val cryptoJs: String by lazy {
            CryptoJsCompatibilityTest::class.java.classLoader
                ?.getResourceAsStream(CRYPTO_JS_ASSET)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: listOf(
                    File("src/main/assets/$CRYPTO_JS_ASSET"),
                    File("app/src/main/assets/$CRYPTO_JS_ASSET"),
                ).firstOrNull { it.isFile }
                    ?.readText(Charsets.UTF_8)
                ?: error("Missing $CRYPTO_JS_ASSET")
        }
    }
}
