package dev.atlas.nym.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import okhttp3.Credentials

/** Per-connection SOCKS5 auth and TLS-to-proxy without a JVM-global Authenticator. */
class TunnelSocketFactory(private val proxy: ProxySpec, private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(proxy, tlsFactory)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostName, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host.hostName, port, localHost, localPort)
}

private class TunnelSocket(private val proxy: ProxySpec, private val tlsFactory: SSLSocketFactory) : Socket() {
    @Volatile private var delegate: Socket = Socket()
    @Volatile private var closed = false
    private var readTimeout = 0
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 15_000)
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported target address")
        try {
            delegate.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
            delegate.soTimeout = if (readTimeout > 0) readTimeout else timeout.coerceAtLeast(1000)
            if (proxy.scheme == "https") {
                val secure = tlsFactory.createSocket(delegate, proxy.host, proxy.port, true) as SSLSocket
                delegate = secure
                if (closed) { secure.close(); throw IOException("Canceled") }
                secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                secure.startHandshake()
                httpConnect(target.hostString, target.port)
            } else socksConnect(target.hostString, target.port)
            if (closed) throw IOException("Canceled")
        } catch (failure: Exception) {
            close()
            throw if (failure is IOException) failure else IOException("Proxy tunnel failed: ${failure.javaClass.simpleName}", failure)
        }
    }
    private fun httpConnect(host: String, port: Int) {
        val authority = if (':' in host) "[$host]:$port" else "$host:$port"
        val auth = proxy.username?.let { "Proxy-Authorization: ${Credentials.basic(it, proxy.password ?: "")}\r\n" } ?: ""
        getOutputStream().write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${auth}Proxy-Connection: Keep-Alive\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16_384) {
            val next = getInputStream().read()
            if (next < 0) throw EOFException("HTTPS proxy closed during CONNECT")
            bytes.add(next.toByte())
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) break
        }
        val header = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        val code = header.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
        if (!header.endsWith("\r\n\r\n") || code != 200) throw IOException("HTTPS proxy CONNECT HTTP ${code ?: "malformed response"}")
    }
    private fun socksConnect(host: String, port: Int) {
        val out = getOutputStream()
        val input = getInputStream()
        out.write(if (proxy.username == null) byteArrayOf(5, 1, 0) else byteArrayOf(5, 2, 0, 2)); out.flush()
        val greeting = input.readExactly(2)
        if (greeting[0] != 5.toByte()) throw IOException("Invalid SOCKS5 greeting")
        when (greeting[1].toInt() and 255) {
            0 -> Unit
            2 -> {
                val user = proxy.username?.toByteArray() ?: throw IOException("SOCKS5 proxy requires authentication")
                val pass = (proxy.password ?: "").toByteArray()
                out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
                val reply = input.readExactly(2)
                if (reply[0] != 1.toByte() || reply[1] != 0.toByte()) throw IOException("SOCKS5 authentication failed")
            }
            else -> throw IOException("SOCKS5 has no accepted authentication method")
        }
        val address = host.toByteArray(Charsets.UTF_8)
        if (address.size > 255) throw IOException("SOCKS5 target host is too long")
        out.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf((port ushr 8).toByte(), port.toByte())); out.flush()
        val reply = input.readExactly(4)
        if (reply[0] != 5.toByte() || reply[1] != 0.toByte()) throw IOException("SOCKS5 CONNECT failed (code ${reply[1].toInt() and 255})")
        val length = when (reply[3].toInt() and 255) { 1 -> 4; 4 -> 16; 3 -> input.readExactly(1)[0].toInt() and 255; else -> throw IOException("Invalid SOCKS5 reply") }
        input.readExactly(length + 2)
    }
    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun close() { closed = true; delegate.close() }
    override fun isClosed() = closed || delegate.isClosed
    override fun isConnected() = delegate.isConnected
    override fun isInputShutdown() = delegate.isInputShutdown
    override fun isOutputShutdown() = delegate.isOutputShutdown
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun setSoTimeout(timeout: Int) { readTimeout = timeout; delegate.soTimeout = timeout }
    override fun getSoTimeout() = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay() = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive() = delegate.keepAlive
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val result = ByteArray(count)
    var position = 0
    while (position < count) {
        val read = read(result, position, count - position)
        if (read < 0) throw EOFException("Proxy closed connection")
        position += read
    }
    return result
}
