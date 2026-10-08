package dev.atlas.nym.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

class TransportTest {
    @Test fun realHttpRequestUsesDesktopEndpointPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"taken\":false}"))
            HttpTransport(ScanConfig(), server.url("/check").toString()).use { transport ->
                assertEquals(CheckStatus.AVAILABLE, transport.check("free").status)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("{\"username\":\"free\"}", request.body.readUtf8())
                assertNull(request.getHeader("Authorization"))
            }
        }
    }
    @Test fun redirectsAreNeverFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/different")))
            HttpTransport(ScanConfig(), server.url("/check").toString()).use { transport -> assertEquals(CheckStatus.UNKNOWN, transport.check("name").status) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun proxyFailureReportedWithoutLeakingPassword() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        HttpTransport(ScanConfig(proxyEnabled = true, proxies = listOf("http://user:secret@127.0.0.1:$port")), "https://example.test/check").use {
            val result = it.check("name")
            assertEquals(CheckStatus.NETWORK_ERROR, result.status)
            assertFalse((result.route + result.detail).contains("secret"))
            assertTrue(result.route.contains("***"))
        }
    }
    @Test fun malformedAndOversizedResponsesAreUnknown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(70_000)))
            HttpTransport(ScanConfig(), server.url("/check").toString()).use { assertEquals(CheckStatus.UNKNOWN, it.check("name").status) }
        }
    }
    @Test fun rateLimitDoesNotRetryInsideTransport() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
            HttpTransport(ScanConfig(), server.url("/check").toString()).use { assertEquals(120_000L, it.check("name").retryAfterMs) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun credentialsAndProxyFormats() {
        assertEquals("http", ProxySpec.parse("host.example:8080").scheme)
        assertEquals("secret", ProxySpec.parse("host.example:80:user:secret").password)
        assertEquals("p+ss", ProxySpec.parse("socks5://u:p%2Bss@localhost:1080").password)
        assertEquals("https://***@localhost:443", ProxySpec.parse("https://u:pass@localhost:443").display)
        assertEquals("::1", ProxySpec.parse("http://[::1]:8080").host)
        listOf("host", "ftp://host:80", "host:99999", "http://host:80/path", "http://host:80?url=target").forEach { assertFailsWith<IllegalArgumentException> { ProxySpec.parse(it) } }
    }
}
