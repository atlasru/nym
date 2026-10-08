package dev.atlas.nym

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atlas.nym.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** Android's real TLS provider, socket stack and per-connection credentials. */
@RunWith(AndroidJUnit4::class)
class ProxyDeviceTest {
    private fun certificates(): Pair<HandshakeCertificates, HandshakeCertificates> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        return HandshakeCertificates.Builder().heldCertificate(certificate).build() to
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    }
    @Test fun socks5AuthenticationAndRemoteDnsCarryRealTargetTls() = runBlocking<Unit> {
        val (serverTls, clientTls) = certificates()
        MockWebServer().use { target ->
            target.useHttps(serverTls.sslSocketFactory(), false)
            target.enqueue(MockResponse().setBody("{\"taken\":false}"))
            target.start()
            RelayFixture(target.port).use { proxy ->
                val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
                val config = ScanConfig(proxyEnabled = true, proxies = listOf("socks5://user:secret@localhost:${proxy.port}"))
                HttpTransport(config, target.url("/check").toString(), client).use {
                    val result = it.check("native_socks")
                    assertEquals(result.detail, CheckStatus.AVAILABLE, result.status)
                }
                assertEquals(listOf("user", "secret", "localhost"), proxy.observed.get(5, TimeUnit.SECONDS))
                assertEquals("POST", target.takeRequest(5, TimeUnit.SECONDS)!!.method)
            }
        }
    }
    @Test fun httpsProxyAndTargetTlsAreBothVerified() = runBlocking<Unit> {
        val (serverTls, clientTls) = certificates()
        MockWebServer().use { target ->
            target.useHttps(serverTls.sslSocketFactory(), false)
            target.enqueue(MockResponse().setBody("{\"taken\":true}"))
            target.start()
            RelayFixture(target.port, tls = serverTls).use { proxy ->
                val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
                val config = ScanConfig(proxyEnabled = true, proxies = listOf("https://user:secret@localhost:${proxy.port}"))
                HttpTransport(config, target.url("/check").toString(), client, clientTls.sslSocketFactory()).use {
                    val result = it.check("native_https_proxy")
                    assertEquals(result.detail, CheckStatus.UNAVAILABLE, result.status)
                }
                assertEquals(listOf("Basic dXNlcjpzZWNyZXQ=", "localhost:${target.port}"), proxy.observed.get(5, TimeUnit.SECONDS))
            }
        }
    }
    @Test fun failedSocksAuthenticationIsReportedWithoutSecrets() = runBlocking<Unit> {
        RelayFixture(1, reject = true).use { proxy ->
            val config = ScanConfig(proxyEnabled = true, proxies = listOf("socks5://user:secret@localhost:${proxy.port}"))
            HttpTransport(config, "http://localhost/check").use {
                val result = it.check("failure")
                assertEquals(CheckStatus.NETWORK_ERROR, result.status)
                assertTrue(result.detail.contains("authentication failed"))
                assertFalse(result.detail.contains("secret"))
            }
        }
    }
    @Test fun httpProxy407AuthenticationWorksOnAndroid() = runBlocking<Unit> {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=nym"))
            proxy.enqueue(MockResponse().setBody("{\"taken\":false}"))
            proxy.start()
            HttpTransport(ScanConfig(proxyEnabled = true, proxies = listOf("http://user:secret@localhost:${proxy.port}")), "http://localhost/check").use {
                assertEquals(CheckStatus.AVAILABLE, it.check("native_http_proxy").status)
            }
            proxy.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("Basic dXNlcjpzZWNyZXQ=", proxy.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
            assertEquals(2, proxy.requestCount)
        }
    }
}

private class RelayFixture(targetPort: Int, tls: HandshakeCertificates? = null, reject: Boolean = false) : AutoCloseable {
    private val server = if (tls == null) ServerSocket(0) else SSLContext.getInstance("TLS").apply {
        init(arrayOf(tls.keyManager), arrayOf(tls.trustManager), null)
    }.serverSocketFactory.createServerSocket(0)
    private val executor = Executors.newFixedThreadPool(3)
    @Volatile private var client: Socket? = null
    @Volatile private var upstream: Socket? = null
    val port get() = server.localPort
    val observed = CompletableFuture<List<String>>()
    init {
        executor.execute {
            try {
                server.accept().use { socket ->
                    client = socket
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    if (tls == null) {
                        val hello = input.exact(2); input.exact(hello[1].toInt() and 255)
                        output.write(byteArrayOf(5, 2)); output.flush()
                        val auth = input.exact(2)
                        val user = input.exact(auth[1].toInt() and 255).toString(Charsets.UTF_8)
                        val pass = input.exact(input.read()).toString(Charsets.UTF_8)
                        output.write(byteArrayOf(1, if (reject) 1 else 0)); output.flush()
                        if (reject) { observed.complete(listOf(user, pass)); return@execute }
                        val connect = input.exact(5)
                        val host = input.exact(connect[4].toInt() and 255).toString(Charsets.UTF_8)
                        input.exact(2)
                        observed.complete(listOf(user, pass, host))
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
                    } else {
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n") && header.length < 16_384) {
                            val byte = input.read()
                            if (byte < 0) throw IOException("CONNECT EOF")
                            header.append(byte.toChar())
                        }
                        val authorization = header.lineSequence().first { it.startsWith("Proxy-Authorization:") }.substringAfter(":").trim()
                        val authority = header.lineSequence().first().split(" ")[1]
                        observed.complete(listOf(authorization, authority))
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush()
                    }
                    Socket("127.0.0.1", targetPort).use { destination ->
                        upstream = destination
                        executor.execute { runCatching { destination.getInputStream().copyTo(output); output.flush(); socket.shutdownOutput() } }
                        input.copyTo(destination.getOutputStream())
                    }
                }
            } catch (error: Exception) { observed.completeExceptionally(error) }
        }
    }
    override fun close() { runCatching { client?.close() }; runCatching { upstream?.close() }; server.close(); executor.shutdownNow() }
}
private fun InputStream.exact(count: Int): ByteArray {
    require(count in 0..255)
    return ByteArray(count).also { bytes ->
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) throw IOException("Unexpected proxy EOF")
            offset += read
        }
    }
}
