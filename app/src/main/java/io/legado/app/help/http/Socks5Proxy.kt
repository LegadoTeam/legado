package io.legado.app.help.http

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

private val syntheticAddress = byteArrayOf(0, 0, 0, 0)

internal val socks5ProxyDns = Dns { hostname ->
    // The tunnel uses the preserved hostname; this address is never connected directly.
    listOf(InetAddress.getByAddress(hostname, syntheticAddress))
}

internal class Socks5SocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val credentials: ProxyCredentials,
) : SocketFactory() {

    override fun createSocket(): Socket =
        Socks5Socket(proxyHost, proxyPort, credentials)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply {
        if (localHost != null) bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket().apply {
        if (localAddress != null) bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

private class Socks5Socket(
    proxyHost: String,
    proxyPort: Int,
    private val credentials: ProxyCredentials,
) : Socket() {

    private val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
    private val delegate = Socket()

    @Volatile
    private var connected = false

    @Volatile
    private var closed = false

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("Socket is closed")
        if (connected) throw SocketException("Socket is already connected")
        val target = endpoint as? InetSocketAddress
            ?: throw SocketException("Unsupported endpoint")
        delegate.connect(proxyAddress, timeout)
        val originalTimeout = delegate.soTimeout
        delegate.soTimeout = timeout.takeIf { it > 0 } ?: 15_000
        try {
            Socks5Protocol.connect(
                delegate.getInputStream(),
                delegate.getOutputStream(),
                target.hostString,
                target.port,
                credentials,
            )
            connected = true
        } catch (e: IOException) {
            runCatching { delegate.close() }
            throw e
        } finally {
            runCatching { delegate.soTimeout = originalTimeout }
        }
    }

    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

    override fun getInputStream() = delegate.getInputStream()

    override fun getOutputStream() = delegate.getOutputStream()

    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setReuseAddress(on: Boolean) {
        delegate.reuseAddress = on
    }

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun shutdownInput() = delegate.shutdownInput()

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun close() {
        closed = true
        delegate.close()
    }

    override fun isConnected(): Boolean = connected && delegate.isConnected

    override fun isClosed(): Boolean = closed || delegate.isClosed

    override fun isBound(): Boolean = delegate.isBound

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun getInetAddress() = delegate.inetAddress

    override fun getLocalAddress() = delegate.localAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
}

internal object Socks5Protocol {
    private const val VERSION = 0x05
    private const val AUTH_VERSION = 0x01
    private const val USER_PASSWORD = 0x02
    private const val CONNECT = 0x01
    private const val IPV4 = 0x01
    private const val DOMAIN = 0x03
    private const val IPV6 = 0x04

    fun connect(
        input: InputStream,
        output: OutputStream,
        targetHost: String,
        targetPort: Int,
        credentials: ProxyCredentials,
    ) {
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        if (username.size !in 1..255 || password.size !in 1..255) {
            throw IOException("Invalid SOCKS5 credentials")
        }
        if (targetPort !in 1..65535) throw IOException("Invalid SOCKS5 target port")
        output.write(byteArrayOf(VERSION.toByte(), 1, USER_PASSWORD.toByte()))
        output.flush()
        val method = readFully(input, 2)
        if (method[0].unsigned() != VERSION || method[1].unsigned() != USER_PASSWORD) {
            throw IOException("SOCKS5 proxy rejected username/password authentication")
        }

        output.write(byteArrayOf(AUTH_VERSION.toByte(), username.size.toByte()))
        output.write(username)
        output.write(password.size)
        output.write(password)
        output.flush()
        val auth = readFully(input, 2)
        if (auth[0].unsigned() != AUTH_VERSION || auth[1].unsigned() != 0) {
            throw IOException("SOCKS5 proxy authentication failed")
        }

        val request = ByteArrayOutputStream().apply {
            write(VERSION)
            write(CONNECT)
            write(0)
            writeTarget(targetHost)
            write(targetPort shr 8)
            write(targetPort)
        }
        output.write(request.toByteArray())
        output.flush()

        val response = readFully(input, 4)
        if (response[0].unsigned() != VERSION || response[2].unsigned() != 0) {
            throw IOException("Invalid SOCKS5 connect response")
        }
        val status = response[1].unsigned()
        if (status != 0) throw IOException("SOCKS5 connect failed: ${replyMessage(status)}")
        when (response[3].unsigned()) {
            IPV4 -> readFully(input, 4)
            IPV6 -> readFully(input, 16)
            DOMAIN -> readFully(input, readFully(input, 1)[0].unsigned())
            else -> throw IOException("Invalid SOCKS5 address type")
        }
        readFully(input, 2)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(result, offset, size - offset)
            if (read < 0) throw EOFException("Incomplete SOCKS5 response")
            offset += read
        }
        return result
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun ByteArrayOutputStream.writeTarget(host: String) {
        val literal = when {
            ':' in host -> runCatching { InetAddress.getByName(host) }.getOrNull()
            else -> parseIpv4(host)?.let { InetAddress.getByAddress(it) }
        }
        when (literal) {
            is Inet4Address -> {
                write(IPV4)
                write(literal.address)
            }
            is Inet6Address -> {
                write(IPV6)
                write(literal.address)
            }
            else -> {
                val bytes = host.toByteArray(Charsets.UTF_8)
                if (bytes.size !in 1..255) throw IOException("Invalid SOCKS5 target host")
                write(DOMAIN)
                write(bytes.size)
                write(bytes)
            }
        }
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val address = ByteArray(4)
        for (index in address.indices) {
            val value = parts[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            address[index] = value.toByte()
        }
        return address
    }

    private fun replyMessage(status: Int): String = when (status) {
        0x01 -> "server failure"
        0x02 -> "connection not allowed"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown error ($status)"
    }
}
