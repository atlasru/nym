package dev.atlas.nym

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.nym.core.*
import kotlinx.coroutines.runBlocking
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
}
