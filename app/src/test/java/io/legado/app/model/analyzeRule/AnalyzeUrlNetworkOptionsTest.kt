package io.legado.app.model.analyzeRule

import io.legado.app.utils.GSONStrict
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AnalyzeUrlNetworkOptionsTest {

    @Test
    fun parsesSupportedTimeoutAndRedirectValues() {
        assertEquals(5_000L, parseRequestTimeoutMillis(5_000))
        assertEquals(5_000L, parseRequestTimeoutMillis("5000"))
        assertEquals(5_000L, parseRequestTimeoutMillis(5_000.0))
        assertNull(parseRequestTimeoutMillis(0))
        assertNull(parseRequestTimeoutMillis(-1))
        assertNull(parseRequestTimeoutMillis(1.5))
        assertNull(parseRequestTimeoutMillis(Int.MAX_VALUE.toLong() + 1))

        assertEquals(true, parseBooleanOption(true))
        assertEquals(true, parseBooleanOption("1"))
        assertEquals(false, parseBooleanOption(0))
        assertEquals(false, parseBooleanOption("false"))
        assertNull(parseBooleanOption(2))
        assertNull(parseBooleanOption("yes"))
    }

    @Test
    fun urlOptionExposesNetworkSettings() {
        val option = AnalyzeUrl.UrlOption()

        option.setTimeout("5000")
        option.setFollowRedirects("false")
        option.setDnsIp(" 1.1.1.1 ")

        assertEquals(5_000L, option.getTimeout())
        assertEquals(false, option.getFollowRedirects())
        assertEquals("1.1.1.1", option.getDnsIp())
    }

    @Test
    fun urlOptionDeserializesNetworkSettingsFromJson() {
        val option = GSONStrict.fromJson(
            """{"timeout":5000,"followRedirects":false,"dnsIp":"1.1.1.1"}""",
            AnalyzeUrl.UrlOption::class.java,
        )

        assertEquals(5_000L, option.getTimeout())
        assertEquals(false, option.getFollowRedirects())
        assertEquals("1.1.1.1", option.getDnsIp())
    }

    @Test
    fun parsesOnlyIpLiterals() {
        val addresses = parseDnsIpAddresses("1.1.1.1, [2001:db8::1]")

        assertEquals("1.1.1.1", addresses[0].hostAddress)
        assertTrue(addresses[1] is Inet6Address)
        assertThrows(IllegalArgumentException::class.java) {
            parseDnsIpAddresses("dns.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseDnsIpAddresses("999.1.1.1")
        }
    }

    @Test
    fun dnsOverrideAppliesOnlyToTargetHost() {
        val fixedAddress = address(1, 1, 1, 1)
        val fallbackAddress = address(8, 8, 8, 8)
        var fallbackHost: String? = null
        val fallback = Dns { hostname ->
            fallbackHost = hostname
            listOf(fallbackAddress)
        }
        val dns = buildScopedDns("target.example", listOf(fixedAddress), fallback)

        assertEquals(listOf(fixedAddress), dns.lookup("TARGET.example"))
        assertEquals(listOf(fallbackAddress), dns.lookup("cdn.example"))
        assertEquals("cdn.example", fallbackHost)
    }

    @Test
    fun rejectsDnsOverrideTogetherWithProxy() {
        validateDnsIpProxyCompatibility(null, "1.1.1.1")
        validateDnsIpProxyCompatibility("http://proxy.example:8080", null)
        assertThrows(IllegalArgumentException::class.java) {
            validateDnsIpProxyCompatibility("http://proxy.example:8080", "1.1.1.1")
        }
    }

    @Test
    fun disabledRedirectIsReturnedBeforeEnteringWebView() {
        assertTrue(shouldReturnRedirectBeforeWebView(false, 302))
        assertFalse(shouldReturnRedirectBeforeWebView(true, 302))
        assertFalse(shouldReturnRedirectBeforeWebView(false, 200))
    }

    @Test
    fun buildsClientWithBoundedTimeoutsAndRedirectPolicy() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val fallback = Dns { listOf(address(8, 8, 8, 8)) }
        val baseClient = OkHttpClient.Builder()
            .dns(fallback)
            .addInterceptor(marker)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        val configured = buildRequestClient(
            baseClient = baseClient,
            readTimeoutMillis = 5_000,
            callTimeoutMillis = null,
            followRedirects = false,
            targetHost = "target.example",
            dnsAddresses = listOf(address(1, 1, 1, 1)),
            interceptorToRemove = marker,
        )

        assertEquals(5_000, configured.readTimeoutMillis)
        assertEquals(60_000, configured.callTimeoutMillis)
        assertFalse(configured.followRedirects)
        assertFalse(configured.followSslRedirects)
        assertFalse(configured.interceptors.contains(marker))
    }

    @Test
    fun explicitCallTimeoutWinsAndUrlTimeoutCanRemoveInterceptor() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val baseClient = OkHttpClient.Builder().addInterceptor(marker).build()

        val configured = buildRequestClient(
            baseClient = baseClient,
            readTimeoutMillis = 40_000,
            callTimeoutMillis = 10_000,
            followRedirects = null,
            targetHost = null,
            dnsAddresses = null,
            interceptorToRemove = marker,
        )

        assertEquals(40_000, configured.readTimeoutMillis)
        assertEquals(10_000, configured.callTimeoutMillis)
        assertFalse(configured.interceptors.contains(marker))
        val constructorConfigured = buildRequestClient(
            baseClient = baseClient,
            readTimeoutMillis = 40_000,
            callTimeoutMillis = 10_000,
            followRedirects = null,
            targetHost = null,
            dnsAddresses = null,
            interceptorToRemove = null,
        )
        assertTrue(constructorConfigured.interceptors.contains(marker))
        assertSame(baseClient, buildRequestClient(baseClient, null, null, null, null, null))
        assertEquals(80_000L, derivedCallTimeoutMillis(40_000))
        assertEquals(Int.MAX_VALUE.toLong(), derivedCallTimeoutMillis(Int.MAX_VALUE.toLong()))
    }

    private fun address(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
