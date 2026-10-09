package dev.atlas.nym

import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.atlas.nym.core.*
import dev.atlas.nym.data.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Every HTTP response is local. No test contacts Discord or rotates around a limit. */
@RunWith(AndroidJUnit4::class)
class CriticalLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val graph get() = (rule.activity.application as NymApplication).graph
    private val model get() = rule.activity.model
    private lateinit var server: MockWebServer
    private val config = ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 2, charset = "ab", limit = 4,
        intervalMs = 250, jitterMs = 0, workers = 4, timeoutSeconds = 60)
    @Before fun prepare() {
        server = MockWebServer().apply { start() }
        runBlocking { graph.ready.await(); graph.store.clearHistory() }
        graph.transportFactory = { HttpTransport(it, server.url("/check").toString()) }
        rule.waitUntil(10_000) { model.loaded.value }
        rule.runOnUiThread { model.update(Preferences(config)); model.navigate("Home") }
        if (Build.VERSION.SDK_INT >= 33) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("pm grant dev.atlas.nym android.permission.POST_NOTIFICATIONS")
    }
    @After fun cleanup() {
        val id = runBlocking { graph.store.sessions().firstOrNull()?.id }
        if (id != null) {
            ScanService.send(rule.activity, ScanService.STOP, id)
            rule.waitUntil(10_000) { graph.serviceSession.value == null && runBlocking {
                graph.store.session(id)?.status in setOf(SessionStatus.STOPPED, SessionStatus.COMPLETED)
            } }
        }
        // Remove ONLY this mock's deadline; production has no cooldown-reset API.
        clearMockCooldown(rule.activity)
        graph.diagnosticEndpoint = "https://example.com/"
        server.shutdown()
    }
    private fun limited(): Session {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1800")
            .setHeader("X-RateLimit-Global", "true").setBody("{\"retry_after\":1800,\"global\":true}"))
        rule.onNodeWithTag("start").performClick()
        rule.waitUntil(10_000) { server.requestCount == 1 && graph.serviceSession.value == null && runBlocking {
            graph.store.sessions().firstOrNull()?.networkStatus == NetworkStatus.RATE_LIMITED
        } }
        rule.waitUntil(5000) { model.current.value?.networkStatus == NetworkStatus.RATE_LIMITED }
        return runBlocking { graph.store.session(graph.store.sessions().first().id)!! }
    }
    private fun status(id: String, status: SessionStatus, timeout: Long = 5000) {
        rule.waitUntil(timeout) { graph.serviceSession.value == null && runBlocking { graph.store.session(id)?.status == status } }
    }
    @Test fun thirtyMinuteCooldownStopIsImmediateIdempotentAndDoesNotEraseRestriction() {
        val session = limited()
        val deadline = runBlocking { graph.store.cooldownUntil() }
        assertTrue(deadline - System.currentTimeMillis() > 1_790_000)
        rule.onNodeWithTag("session_status").assertTextEquals("Paused")
        rule.onNodeWithTag("network_status").assertTextContains("Rate limited", substring = true)
        rule.onNodeWithTag("stop").assertIsEnabled()
        val started = SystemClock.elapsedRealtime()
        rule.onNodeWithTag("stop").performClick()
        repeat(8) { ScanService.send(rule.activity, ScanService.STOP, session.id) }
        status(session.id, SessionStatus.STOPPED, 2000)
        val stopMs = SystemClock.elapsedRealtime() - started
        assertTrue("Stop took $stopMs ms", stopMs < 2000)
        rule.waitUntil(5000) { model.current.value?.status == SessionStatus.STOPPED }
        rule.onNodeWithTag("session_status").assertTextEquals("Stopped")
        rule.onNodeWithTag("resume").assertIsNotEnabled()
        rule.onNodeWithTag("new_session").assertIsNotEnabled()
        val checkpoint = runBlocking { graph.store.session(session.id)!! }
        assertEquals(session.cursor, checkpoint.cursor)
        assertEquals(session.config, checkpoint.config)
        assertEquals(session.requests, checkpoint.requests)
        assertEquals(deadline, runBlocking { graph.store.cooldownUntil() })
        ScanService.send(rule.activity, ScanService.START, session.id)
        rule.waitUntil(5000) { graph.serviceSession.value == null }
        assertEquals(1, server.requestCount)
        val dir = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(dir, "06-429-stopped.png"))
        File(dir, "429-stop.txt").writeText("mock_retry_after_seconds=1800\nstop_elapsed_ms=$stopMs\nstatus=STOPPED\nrequests=1\ncooldown_preserved=true\n")
    }
    @Test fun pauseAndNotificationStopWorkDuringCooldown() {
        val session = limited()
        val deadline = runBlocking { graph.store.cooldownUntil() }
        ScanService.send(rule.activity, ScanService.PAUSE, session.id)
        status(session.id, SessionStatus.PAUSED)
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        rule.waitUntil(5000) { manager.activeNotifications.any { it.id == ScanService.NOTIFICATION &&
            it.notification.actions.any { action -> action.title.toString() == "Stop" } } }
        manager.activeNotifications.first { it.id == ScanService.NOTIFICATION }.notification
            .actions.first { it.title.toString() == "Stop" }.actionIntent.send()
        status(session.id, SessionStatus.STOPPED, 2000)
        assertEquals(deadline, runBlocking { graph.store.cooldownUntil() })
        assertEquals(1, server.requestCount)
    }
    @Test fun proxyImportEditRemoveRoutingAndConnectivityStayAccessibleDuringCooldown() {
        val session = limited()
        val deadline = runBlocking { graph.store.cooldownUntil() }
        rule.onNodeWithTag("nav_Settings").performClick()
        rule.onNodeWithTag("group_Proxies").performScrollTo().performClick()
        rule.onNodeWithTag("proxy_import").performScrollTo().assertIsEnabled()
        val proxy = "http://user:password@127.0.0.1:${server.port}"
        val file = File(rule.activity.cacheDir, "mock-proxies.txt").apply { writeText("$proxy\nsocks5://u:p@localhost:1080\n") }
        rule.runOnUiThread { model.importProxies(Uri.fromFile(file)) }
        rule.waitUntil(5000) { model.preferences.value.scan.proxies.size == 2 && !model.busy.value }
        rule.runOnUiThread { assertTrue(model.addProxies(proxy.replace("password", "edited"), proxy)) }
        assertTrue(model.preferences.value.scan.proxies.first().contains("socks5"))
        rule.runOnUiThread { model.update(model.preferences.value.copy(scan = model.preferences.value.scan.copy(
            proxies = listOf(proxy), proxyEnabled = true))) }
        graph.diagnosticEndpoint = server.url("/connectivity").toString()
        server.enqueue(MockResponse().setResponseCode(204))
        rule.runOnUiThread { model.diagnose() }
        rule.waitUntil(10_000) { model.diagnostics.value.any { it.contains("HTTP 204") } && !model.busy.value }
        assertEquals(2, server.requestCount) // one check + independent connectivity GET
        assertEquals(session.config, runBlocking { graph.store.session(session.id)!!.config })
        assertEquals(deadline, runBlocking { graph.store.cooldownUntil() })
        rule.runOnUiThread { model.update(model.preferences.value.copy(scan = model.preferences.value.scan.copy(proxies = emptyList(), proxyEnabled = false))) }
        rule.onNodeWithTag("nav_Home").performClick()
        rule.onNodeWithTag("stop").performClick()
        status(session.id, SessionStatus.STOPPED)
    }
    @Test fun pendingHttpBodyStopThenRestartResumesSameCandidateOnce() {
        val bodyStarted = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = requireNotNull(response.body)
            response.newBuilder().body(object : ResponseBody() {
                private val stream = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyStarted.complete(Unit)
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = stream
            }).build()
        }.build()
        graph.transportFactory = { HttpTransport(it, server.url("/check").toString(), client) }
        server.enqueue(MockResponse().setBody("{\"taken\":false}").setBodyDelay(5, TimeUnit.SECONDS))
        rule.onNodeWithTag("start").performClick()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        rule.waitUntil(5000) { bodyStarted.isCompleted }
        val session = runBlocking { graph.store.sessions().first() }
        val started = SystemClock.elapsedRealtime()
        ScanService.send(rule.activity, ScanService.STOP, session.id)
        status(session.id, SessionStatus.STOPPED, 2000)
        assertTrue(SystemClock.elapsedRealtime() - started < 2000)
        assertEquals(0L, runBlocking { graph.store.session(session.id)!!.checked })
        graph.transportFactory = { HttpTransport(it, server.url("/check").toString()) }
        repeat(4) { server.enqueue(MockResponse().setBody("{\"taken\":false}")) }
        ScanService.send(rule.activity, ScanService.START, session.id)
        status(session.id, SessionStatus.COMPLETED, 10_000)
        val results = runBlocking { graph.store.results("", null, false, 100, session.id) }
        assertEquals(setOf("aa", "ab", "ba", "bb"), results.map { it.result.username }.toSet())
        assertEquals(4, results.size)
    }
    @Test fun stopCancelsOfflineWaitAndRapidStartStopCannotResurrectWorker() {
        val waiting = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        graph.transportFactory = { NetworkAwareTransport(HttpTransport(it, server.url("/check").toString())) { entered.complete(Unit); waiting.await() } }
        rule.onNodeWithTag("start").performClick()
        rule.waitUntil(5000) { graph.serviceSession.value != null && entered.isCompleted }
        val id = graph.serviceSession.value!!
        repeat(8) { ScanService.send(rule.activity, ScanService.STOP, id) }
        status(id, SessionStatus.STOPPED, 2000)
        waiting.complete(Unit)
        assertEquals(0, server.requestCount)
        assertEquals("1", runBlocking { graph.store.session(id)!!.cursor })
    }
    @Test fun staleNotificationStopDoesNotCancelNewSession() {
        val old = runBlocking { graph.store.create(config) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        rule.runOnUiThread { model.start() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val active = graph.serviceSession.value!!
        ScanService.send(rule.activity, ScanService.STOP, old.id)
        rule.waitUntil(5000) { runBlocking { graph.store.session(old.id)!!.status == SessionStatus.STOPPED } }
        assertEquals(active, graph.serviceSession.value)
        ScanService.send(rule.activity, ScanService.STOP, active)
        status(active, SessionStatus.STOPPED, 2000)
    }
    @Test fun stopIsAvailableFromEveryPersistedNonterminalState() {
        for (initial in listOf(SessionStatus.PAUSED, SessionStatus.ERROR, SessionStatus.INTERRUPTED, SessionStatus.COOLDOWN, SessionStatus.STOPPING)) {
            val id = runBlocking {
                graph.store.create(config).id.also { graph.store.status(it, initial) }
            }
            ScanService.send(rule.activity, ScanService.STOP, id)
            status(id, SessionStatus.STOPPED, 2000)
        }
        assertEquals(0, server.requestCount)
    }
}

internal fun clearMockCooldown(context: android.content.Context) {
    SQLiteDatabase.openDatabase(context.getDatabasePath("nym.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
        it.delete("metadata", "key='cooldown'", null)
    }
}
