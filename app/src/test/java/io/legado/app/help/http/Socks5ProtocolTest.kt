package io.legado.app.help.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress

class Socks5ProtocolTest {

    @Test
    fun writesAuthenticatedDomainTunnelHandshake() {
        val input = successfulInput()
        val output = ByteArrayOutputStream()

        Socks5Protocol.connect(
            input,
            output,
            "hidden.example",
            443,
            ProxyCredentials("user", "pass"),
        )

        val expected = byteArrayOf(5, 1, 2, 1, 4) +
            "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray() +
            byteArrayOf(5, 1, 0, 3, 14) + "hidden.example".toByteArray() +
            byteArrayOf(1, 0xbb.toByte())
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun writesLiteralTargetAddresses() {
        val targets = listOf(
            "127.0.0.1" to byteArrayOf(1, 127, 0, 0, 1),
            "::1" to (byteArrayOf(4) + ByteArray(15) + byteArrayOf(1)),
        )

        targets.forEach { (host, encodedAddress) ->
            val output = ByteArrayOutputStream()
            Socks5Protocol.connect(
                successfulInput(),
                output,
                host,
                80,
                ProxyCredentials("user", "pass"),
            )

            val bytes = output.toByteArray()
            val connectRequest = bytes.copyOfRange(14, bytes.size)
            assertArrayEquals(byteArrayOf(5, 1, 0) + encodedAddress + byteArrayOf(0, 80), connectRequest)
        }
    }

    @Test
    fun rejectsFailedAuthenticationWithoutExposingCredentials() {
        val error = assertThrows(IOException::class.java) {
            Socks5Protocol.connect(
                ByteArrayInputStream(byteArrayOf(5, 2, 1, 1)),
                ByteArrayOutputStream(),
                "target.example",
                80,
                ProxyCredentials("user", "secret"),
            )
        }

        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun preservesTargetHostnameForProxyDns() {
        val address = socks5ProxyDns.lookup("target.invalid").single()

        assertEquals("target.invalid", address.hostName)
        assertEquals("target.invalid", InetSocketAddress(address, 443).hostString)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), address.address)
    }

    private fun successfulInput() = ByteArrayInputStream(
        byteArrayOf(
            5, 2,
            1, 0,
            5, 0, 0, 1,
            127, 0, 0, 1,
            0, 80,
        )
    )
}
