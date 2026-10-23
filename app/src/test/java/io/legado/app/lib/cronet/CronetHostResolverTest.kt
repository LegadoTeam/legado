package io.legado.app.lib.cronet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class CronetHostResolverTest {

    @Test
    fun `custom hosts map only the request host and request dns wins`() {
        val hosts = linkedMapOf(
            "B.example" to listOf(ipv4(8, 8, 8, 8)),
            "A.Example." to listOf(ipv4(1, 1, 1, 1)),
        )
        val requestConfig = CronetRequestConfig(
            dnsHost = "a.example",
            dnsAddress = "9.9.9.9",
        )

        assertEquals(
            "MAP a.example 9.9.9.9",
            buildHostResolverRules("A.EXAMPLE", hosts, requestConfig),
        )
        assertEquals(
            "MAP b.example 8.8.8.8",
            buildHostResolverRules("b.example", hosts),
        )
        assertEquals(null, buildHostResolverRules("other.example", hosts))
    }

    @Test
    fun `resolver rules support ipv6 literals without changing request host`() {
        val ipv6 = InetAddress.getByAddress(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(),
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 1,
            )
        )
        val rules = buildHostResolverRules(
            "ipv6.example",
            mapOf("ipv6.example" to listOf(ipv6)),
        )

        assertTrue(rules!!.startsWith("MAP ipv6.example ["))
        assertTrue(rules.endsWith("]"))
    }

    @Test
    fun `experimental options keep resolver and dns configuration`() {
        val helper = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetHelper.kt"
        )

        assertTrue(helper.contains("\"HostResolverRules\""))
        assertTrue(helper.contains("\"host_resolver_rules\""))
        assertTrue(helper.contains("\"AsyncDNS\""))
        assertTrue(helper.contains("\"UseDnsHttpsSvcb\""))
    }


    @Test
    fun `request options keep cronet while preserving redirect semantics`() {
        val analyzeUrl = readProjectFile(
            "app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt"
        )
        val callback = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/AbsCallBack.kt"
        )
        val coroutineInterceptor = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetCoroutineInterceptor.kt"
        )

        assertTrue(analyzeUrl.contains("shouldBypassCronetForDns"))
        assertFalse(analyzeUrl.contains("urlTimeoutConfigured"))
        assertTrue(analyzeUrl.contains("tag(CronetRequestConfig::class.java, it)"))
        assertTrue(callback.contains("requestConfig?.followRedirects"))
        assertTrue(callback.contains("returnRedirectResponse = true"))
        assertFalse(coroutineInterceptor.contains("override fun onCanceled"))
    }

    @Test
    fun `cronet requests keep the original url host`() {
        val helper = readProjectFile(
            "app/src/main/java/io/legado/app/lib/cronet/CronetHelper.kt"
        )

        assertTrue(helper.contains("request.url.toString()"))
        assertFalse(helper.contains("customHost("))
        assertTrue(helper.contains("HostResolverRules"))
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun readProjectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, path)
        }.first { it.isFile }.readText()
    }
}
