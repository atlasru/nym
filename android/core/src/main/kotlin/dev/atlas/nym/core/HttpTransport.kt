package dev.atlas.nym.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpTransport(
    config: ScanConfig,
    private val endpoint: String = DISCORD_ENDPOINT,
    baseClient: OkHttpClient = OkHttpClient(),
    proxyTlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : CheckTransport {
    private data class Route(val client: OkHttpClient, val display: String, var leased: Boolean = false, var failures: Int = 0, var readyAt: Long = 0, var uses: Long = 0)
    private val timeout = config.timeoutSeconds
    private val cadence = config.intervalMs
    private val fallback = config.fallbackDirect
    private val mutex = Mutex()
    // Dispatcher stops tracking an async Call after onResponse returns, even if
    // the consumer is still reading its body. Keep ownership until body.close().
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val direct = Route(configure(baseClient.newBuilder()).proxy(Proxy.NO_PROXY).build(), "Direct")
    private val routes = if (config.proxyEnabled) config.proxies.map { raw ->
        val spec = ProxySpec.parse(raw)
        val builder = configure(baseClient.newBuilder())
        if (spec.scheme == "http") {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(spec.host, spec.port)))
            if (spec.username != null) builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null && response.code == 407) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(spec.username, spec.password ?: "")).build()
            }
        } else {
            builder.proxy(Proxy.NO_PROXY).socketFactory(TunnelSocketFactory(spec, proxyTlsFactory))
            // Target names are resolved by the proxy, never by a local DNS lookup.
            builder.dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
            })
        }
        Route(builder.build(), spec.display)
    } else listOf(direct)
    @Volatile private var canceled = false
    @Volatile var activeRoute: String = if (config.proxyEnabled) "Proxy pool" else "Direct"
        private set

    private fun configure(builder: OkHttpClient.Builder) = builder
        .connectTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).callTimeout(timeout, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)

    private suspend fun acquire(): Route {
        while (!canceled) {
            val route = mutex.withLock {
                val now = System.currentTimeMillis()
                val ready = routes.filter { !it.leased && it.readyAt <= now }.minWithOrNull(compareBy<Route> { it.failures }.thenBy { it.uses })
                    ?: if (fallback && routes.none { it.readyAt <= now }) direct else null
                ready?.also { it.leased = true; it.uses++ }
            }
            if (route != null) return route
            delay(100)
        }
        throw IOException("Transport canceled")
    }

    override suspend fun check(username: String): CheckResult {
        val route = acquire()
        activeRoute = route.display
        val started = System.nanoTime()
        try {
            val body = JsonObject(mapOf("username" to JsonPrimitive(username))).toString()
            val request = Request.Builder().url(endpoint).header("User-Agent", "NymMobile/0.1.1 (Android)")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val result = execute(route.client, request) { response ->
                val text = response.boundedBody()
                ResponseClassifier.classify(username, response.code, text, response.headers.toMap())
            }
            mutex.withLock { route.failures = 0; route.readyAt = 0 }
            return result.copy(route = route.display, latencyMs = (System.nanoTime() - started) / 1_000_000)
        } catch (failure: IOException) {
            mutex.withLock {
                route.failures++
                route.readyAt = System.currentTimeMillis() + (1000L shl route.failures.coerceAtMost(5))
            }
            return CheckResult(username, CheckStatus.NETWORK_ERROR,
                detail = "${failure.javaClass.simpleName}: ${sanitize(failure.message.orEmpty())}", route = route.display,
                latencyMs = (System.nanoTime() - started) / 1_000_000)
        } finally { withContext(NonCancellable) { mutex.withLock { route.leased = false } } }
    }

    /** Connectivity only. This target is independent of Discord's checking limits. */
    suspend fun diagnose(endpoint: String = "https://example.com/"): List<String> {
        val diagnostics = mutableListOf<String>()
        for (route in routes) {
            if (diagnostics.isNotEmpty()) delay(cadence)
            val start = System.nanoTime()
            try {
                execute(route.client, Request.Builder().url(endpoint).get().build()) {
                    val delay = (System.nanoTime() - start) / 1_000_000
                    diagnostics += "${route.display} · HTTP ${it.code} · ${delay} ms"
                    if (it.code == 429) diagnostics += "Connectivity target rate limited · diagnostics stopped"
                }
                if (diagnostics.last().contains("rate limited")) break
            } catch (failure: IOException) {
                diagnostics += "${route.display} · ${failure.javaClass.simpleName}: ${sanitize(failure.message.orEmpty())}"
            }
        }
        return diagnostics
    }

    private fun sanitize(value: String) = value.replace(Regex("(https?|socks5)://[^/@\\s]+@"), "$1://***@").take(512)
    private suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T {
        val call = client.newCall(request)
        calls.add(call)
        try {
            if (canceled) call.cancel()
            return call.await().use(read)
        } finally { calls.remove(call) }
    }
    override fun cancel() { canceled = true; calls.forEach { it.cancel() }; (routes + direct).forEach { it.client.dispatcher.cancelAll() } }
    override fun close() { cancel(); (routes + direct).forEach { it.client.connectionPool.evictAll(); it.client.dispatcher.executorService.shutdown() } }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response, onCancellation = { _, value, _ -> value.close() }) else response.close()
        }
    })
}

private fun Response.boundedBody(): String = body?.source()?.let { source ->
    source.request(65_537)
    if (source.buffer.size > 65_536) "Response exceeds 64 KiB" else source.readUtf8()
} ?: ""
