package dev.atlas.nym.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

class TunnelTest {
    @Test fun socks5PerConnectionAuthenticationAndRemoteDns() = runBlocking {
        SocksFixture().use { proxy ->
            HttpTransport(ScanConfig(proxyEnabled = true, proxies = listOf("socks5://user:pass@127.0.0.1:${proxy.port}")), "http://unresolvable.example/check").use {
                assertEquals(CheckStatus.AVAILABLE, it.check("name").status)
            }
            val observed = proxy.observed.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("user", "pass", "unresolvable.example"), observed)
        }
    }
    @Test fun socksAuthenticationFailureIsNetworkError() = runBlocking {
        SocksFixture(reject = true).use { proxy ->
            HttpTransport(ScanConfig(proxyEnabled = true, proxies = listOf("socks5://user:pass@127.0.0.1:${proxy.port}")), "http://unresolvable.example/check").use {
                val result = it.check("name")
                assertEquals(CheckStatus.NETWORK_ERROR, result.status)
                assertTrue(result.detail.contains("authentication failed"))
                assertFalse(result.detail.contains("pass@"))
            }
        }
    }
    @Test fun httpProxyAuthenticationAndFairRotation() = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy"))
            a.enqueue(MockResponse().setBody("{\"taken\":false}"))
            b.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy"))
            b.enqueue(MockResponse().setBody("{\"taken\":true}"))
            val cfg = ScanConfig(proxyEnabled = true, proxies = listOf("http://user:secret@localhost:${a.port}", "http://other:password@localhost:${b.port}"))
            HttpTransport(cfg, "http://unresolvable.example/check").use {
                assertEquals(CheckStatus.AVAILABLE, it.check("one").status)
                assertEquals(CheckStatus.UNAVAILABLE, it.check("two").status)
            }
            assertNull(a.takeRequest().getHeader("Proxy-Authorization")); assertNull(b.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals("Basic dXNlcjpzZWNyZXQ=", a.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals("Basic b3RoZXI6cGFzc3dvcmQ=", b.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals(2, a.requestCount); assertEquals(2, b.requestCount)
        } }
    }
    @Test fun httpsProxyUsesVerifiedTlsAndAuthenticatedConnect() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            val spec = ProxySpec("https", "localhost", server.port, "user", "secret")
            TunnelSocketFactory(spec, clientTls.sslSocketFactory()).createSocket().use { socket ->
                socket.connect(InetSocketAddress.createUnresolved("discord.com", 443), 5000)
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("CONNECT", request.method)
                assertEquals("discord.com:443", request.getHeader("Host"))
                assertEquals("Basic dXNlcjpzZWNyZXQ=", request.getHeader("Proxy-Authorization"))
            }
        }
    }
    @Test fun httpsProxyRejectsWrongCertificateHostname() {
        val cert = HeldCertificate.Builder().commonName("wrong.example").addSubjectAlternativeName("wrong.example").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            TunnelSocketFactory(ProxySpec("https", "localhost", server.port), clientTls.sslSocketFactory()).createSocket().use {
                assertFailsWith<IOException> { it.connect(InetSocketAddress.createUnresolved("discord.com", 443), 5000) }
            }
        }
    }
}

private class SocksFixture(private val reject: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val executor = Executors.newSingleThreadExecutor()
    val observed = executor.submit<List<String>> {
        server.accept().use { socket ->
            socket.soTimeout = 5000
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            val header = input.exact(2); input.exact(header[1].toInt() and 255)
            output.write(byteArrayOf(5, 2)); output.flush()
            val auth = input.exact(2)
            val user = input.exact(auth[1].toInt() and 255).toString(Charsets.UTF_8)
            val pass = input.exact(input.read()).toString(Charsets.UTF_8)
            output.write(byteArrayOf(1, if (reject) 1 else 0)); output.flush()
            if (reject) return@submit listOf(user, pass)
            val connect = input.exact(5)
            val host = input.exact(connect[4].toInt() and 255).toString(Charsets.UTF_8)
            input.exact(2)
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
            val bytes = StringBuilder()
            while (!bytes.endsWith("\r\n\r\n")) bytes.append(input.read().toChar())
            val length = Regex("(?i)content-length: (\\d+)").find(bytes)?.groupValues?.get(1)?.toInt() ?: 0
            input.exact(length)
            val body = "{\"taken\":false}"
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); output.flush()
            listOf(user, pass, host)
        }
    }
    override fun close() { server.close(); executor.shutdownNow() }
}
private fun InputStream.exact(count: Int): ByteArray = ByteArray(count).also { bytes ->
    var offset = 0
    while (offset < count) { val amount = read(bytes, offset, count - offset); if (amount < 0) throw IOException("EOF"); offset += amount }
}
