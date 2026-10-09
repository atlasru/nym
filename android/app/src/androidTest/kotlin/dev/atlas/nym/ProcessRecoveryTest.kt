package dev.atlas.nym

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.nym.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two opt-in stages, separated by an actual force-stop and manual relaunch in CI. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    private val application get() = ApplicationProvider.getApplicationContext<NymApplication>()
    private val graph get() = application.graph
    private val directory get() = File(application.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    private val stage get() = InstrumentationRegistry.getArguments().getString("recoveryStage")
    @Test fun prepareCheckpoint() = runBlocking<Unit> {
        assumeTrue(stage == "prepare")
        graph.ready.await()
        assertNull(graph.serviceSession.value)
        val previous = graph.store.resultCount()
        val config = ScanConfig(mode = GenerationMode.RANDOM, length = 2, charset = "ab", seed = -42, limit = 4, intervalMs = 250, jitterMs = 0)
        val session = graph.store.create(config)
        graph.store.status(session.id, SessionStatus.RUNNING)
        val generator = CandidateGenerator(config)
        val completed = graph.store.reserve(session.id, generator)!!
        val inFlight = graph.store.reserve(session.id, generator)!!
        graph.store.complete(session.id, completed, CheckResult(completed.username, CheckStatus.AVAILABLE, 200))
        File(directory, "recovery-fixture.json").writeText(JSONObject().put("session", session.id)
            .put("pending", inFlight.username).put("previous_results", previous).toString())
        assertEquals("2", graph.store.session(session.id)!!.cursor)
    }
    @Test fun resumeAfterProcessRestart() = runBlocking<Unit> {
        assumeTrue(stage == "verify")
        graph.ready.await()
        val fixture = JSONObject(File(directory, "recovery-fixture.json").readText())
        val id = fixture.getString("session")
        val restored = graph.store.session(id)!!
        assertEquals(SessionStatus.INTERRUPTED, restored.status)
        assertEquals("2", restored.cursor)
        assertEquals(1L, restored.checked)
        val generator = CandidateGenerator(restored.config)
        graph.store.status(id, SessionStatus.RUNNING)
        assertEquals(fixture.getString("pending"), graph.store.reserve(id, generator)!!.username)
        graph.store.releasePending(id)
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("{\"taken\":false}")) }
            server.start()
            HttpTransport(restored.config, server.url("/check").toString()).use {
                ScanEngine(graph.store, it).run(id)
            }
            assertEquals(3, server.requestCount)
        }
        val results = graph.store.results("", null, false, 100, id)
        assertEquals(4, results.size)
        assertEquals(4, results.map { it.result.username }.distinct().size)
        assertEquals(SessionStatus.COMPLETED, graph.store.session(id)!!.status)
        assertEquals(fixture.getLong("previous_results") + 4, graph.store.resultCount())
        File(directory, "process-recovery.txt").writeText("Actual process force-stop and manual relaunch\nrestored_status=INTERRUPTED\nchecked=4\nunique_results=4\nskipped_candidates=0\nprevious_results_retained=${fixture.getLong("previous_results")}\nmock_requests_after_resume=3\n")
    }
    @Test fun prepareRateLimitedCheckpoint() = runBlocking<Unit> {
        assumeTrue(stage == "cooldown-prepare")
        graph.ready.await()
        val cfg = ScanConfig(mode = GenerationMode.RANDOM, length = 2, charset = "ab", seed = -42,
            limit = 4, intervalMs = 250, jitterMs = 0, proxies = listOf("socks5://user:password@localhost:1080"))
        val session = graph.store.create(cfg)
        val previous = graph.store.resultCount()
        graph.settings.save(dev.atlas.nym.data.Preferences(cfg))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"taken\":false}"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1800")
                .setBody("{\"retry_after\":1800,\"global\":true}"))
            server.start()
            ScanEngine(graph.store, HttpTransport(cfg, server.url("/check").toString())).run(session.id)
            assertEquals(2, server.requestCount)
        }
        val limited = graph.store.session(session.id)!!
        assertEquals(SessionStatus.PAUSED, limited.status)
        assertEquals(NetworkStatus.RATE_LIMITED, limited.networkStatus)
        assertEquals("2", limited.cursor)
        File(directory, "cooldown-recovery-fixture.json").writeText(JSONObject().put("session", session.id)
            .put("deadline", graph.store.cooldownUntil()).put("previous_results", previous).toString())
    }
    @Test fun rateLimitSurvivesActualProcessTerminationAndStop() = runBlocking<Unit> {
        assumeTrue(stage == "cooldown-verify")
        graph.ready.await()
        val fixture = JSONObject(File(directory, "cooldown-recovery-fixture.json").readText())
        val id = fixture.getString("session")
        val deadline = fixture.getLong("deadline")
        val restored = graph.store.session(id)!!
        assertEquals(SessionStatus.PAUSED, restored.status)
        assertEquals(NetworkStatus.RATE_LIMITED, restored.networkStatus)
        assertEquals("2", restored.cursor)
        assertEquals(1L, restored.checked)
        assertEquals(1L, restored.available)
        assertEquals(2L, restored.requests)
        assertEquals(1L, restored.errors)
        assertEquals(deadline, graph.store.cooldownUntil())
        assertTrue(deadline > System.currentTimeMillis())
        assertEquals(restored.config, graph.settings.flow.first().scan)
        MockWebServer().use { server ->
            server.start()
            try {
                ScanEngine(graph.store, HttpTransport(restored.config, server.url("/check").toString())).run(id)
                fail("Active global cooldown must block requests after process restart")
            } catch (_: IllegalStateException) { }
            assertEquals(0, server.requestCount)
            ScanService.send(application, ScanService.STOP, id)
            kotlinx.coroutines.withTimeout(2000) {
                while (graph.store.session(id)?.status != SessionStatus.STOPPED) kotlinx.coroutines.delay(10)
            }
            assertEquals(deadline, graph.store.cooldownUntil())
            repeat(3) { server.enqueue(MockResponse().setBody("{\"taken\":false}")) }
            // Advance the engine's test clock, not the durable server deadline.
            ScanEngine(graph.store, HttpTransport(restored.config, server.url("/check").toString())) { deadline + 1 }.run(id)
            assertEquals(3, server.requestCount)
        }
        val results = graph.store.results("", null, false, 100, id)
        assertEquals(4, results.size)
        assertEquals(4, results.map { it.result.username }.distinct().size)
        assertEquals(fixture.getLong("previous_results") + 4, graph.store.resultCount())
        assertEquals(deadline, graph.store.cooldownUntil())
        File(directory, "cooldown-process-recovery.txt").writeText("Actual force-stop/manual-relaunch during mock Retry-After: 1800\n" +
            "cooldown_preserved=true\nstop=STOPPED\nrequests_before_deadline=0\nunique_results_after_test_clock_expiry=4\n")
        clearMockCooldown(application)
    }
}
