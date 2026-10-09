@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atlas.nym.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import kotlin.test.*
import org.junit.Test

private class MemoryStore(config: ScanConfig) : ScanStore {
    var item = Session("session", config, SessionStatus.PAUSED)
    val pending = linkedMapOf<String, Pair<Candidate, Boolean>>()
    val results = linkedMapOf<String, CheckResult>()
    var cooldown = 0L
    override suspend fun session(id: String) = item
    override suspend fun reserve(id: String, generator: CandidateGenerator): Candidate? {
        val existing = pending.values.firstOrNull { !it.second }?.first
        val candidate = existing ?: run {
            if (item.checked + pending.size >= item.config.limit) return null
            generator.at(item.cursor)?.also { item = item.copy(cursor = (item.cursor.toBigInteger() + BigInteger.ONE).toString()) } ?: return null
        }
        pending[candidate.username] = candidate to true
        return candidate
    }
    override suspend fun complete(id: String, candidate: Candidate, result: CheckResult) {
        check(results.put(candidate.username, result) == null)
        pending.remove(candidate.username)
        item = item.copy(checked = item.checked + 1)
    }
    override suspend fun recordAttempt(id: String, result: CheckResult) { item = item.copy(requests = item.requests + 1) }
    override suspend fun status(id: String, status: SessionStatus, detail: String) { item = item.copy(status = status, detail = detail) }
    override suspend fun network(id: String, status: NetworkStatus) { item = item.copy(networkStatus = status) }
    override suspend fun releasePending(id: String) { pending.replaceAll { _, value -> value.first to false } }
    override suspend fun cooldownUntil() = cooldown
    override suspend fun setCooldown(until: Long) { cooldown = maxOf(cooldown, until) }
    override suspend fun addElapsed(id: String, milliseconds: Long) { item = item.copy(elapsedMs = item.elapsedMs + milliseconds) }
}
private class FakeTransport(val action: suspend (String) -> CheckResult) : CheckTransport {
    var calls = 0
    var canceled = false
    override suspend fun check(username: String): CheckResult { calls++; return action(username) }
    override fun cancel() { canceled = true }
    override fun close() = cancel()
}
class EngineTest {
    private val base = ScanConfig(mode = GenerationMode.SEQUENTIAL, length = 2, charset = "ab", intervalMs = 250, jitterMs = 0, limit = 4)
    @Test fun exhaustsFiniteGeneratorOnce() = runTest {
        val store = MemoryStore(base)
        val transport = FakeTransport { CheckResult(it, CheckStatus.AVAILABLE) }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(setOf("aa", "ab", "ba", "bb"), store.results.keys)
        assertEquals(SessionStatus.COMPLETED, store.item.status)
        assertTrue(store.pending.isEmpty())
    }
    @Test fun warmup429DoesNotBurstAcrossWorkersOrRoutes() = runTest {
        val store = MemoryStore(base.copy(workers = 4, proxyEnabled = true, proxies = listOf("http://one:80", "http://two:80")))
        val transport = FakeTransport { CheckResult(it, CheckStatus.RATE_LIMITED, 429, 120_000) }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(1, transport.calls)
        assertEquals(120_000, store.cooldown)
        assertEquals(SessionStatus.PAUSED, store.item.status)
        assertEquals(NetworkStatus.RATE_LIMITED, store.item.networkStatus)
        assertEquals(listOf("aa"), store.pending.keys.toList())
        assertEquals(0, store.results.size)
    }
    @Test fun cooldownPersistsAcrossNewEngine() = runTest {
        val store = MemoryStore(base).apply { cooldown = 1700_000 }
        val transport = FakeTransport { error("No request allowed") }
        assertFailsWith<IllegalStateException> { ScanEngine(store, transport) { 0 }.run("session") }
        assertEquals(0, transport.calls)
    }
    @Test fun cancelAndResumePreservesReservedCandidatesAndCursor() = runTest {
        val store = MemoryStore(base.copy(workers = 3))
        val transport = FakeTransport { delay(5000); CheckResult(it, CheckStatus.UNAVAILABLE) }
        val job = launch { ScanEngine(store, transport) { testScheduler.currentTime }.run("session") }
        runCurrent()
        assertEquals("1", store.item.cursor)
        job.cancelAndJoin()
        assertEquals(false, store.pending["aa"]!!.second)
        ScanEngine(store, FakeTransport { CheckResult(it, CheckStatus.UNAVAILABLE) }) { testScheduler.currentTime }.run("session")
        assertEquals(4, store.results.size)
        assertEquals("4", store.item.cursor)
    }
    @Test fun boundedConcurrencyAndGlobalCadence() = runTest {
        val store = MemoryStore(base.copy(workers = 4))
        var inFlight = 0; var maximum = 0
        val times = mutableListOf<Long>()
        val transport = FakeTransport {
            inFlight++; maximum = maxOf(maximum, inFlight); times += testScheduler.currentTime
            delay(1000); inFlight--; CheckResult(it, CheckStatus.AVAILABLE)
        }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertTrue(maximum in 2..4)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 250 })
    }
    @Test fun networkRetriesAreBoundedAndBackedOff() = runTest {
        val store = MemoryStore(base.copy(limit = 1, retries = 2))
        val transport = FakeTransport { CheckResult(it, CheckStatus.NETWORK_ERROR) }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(3, transport.calls)
        assertEquals(1, store.results.size)
        assertTrue(testScheduler.currentTime >= 3000)
    }
    @Test fun forbiddenStopsSession() = runTest {
        val store = MemoryStore(base.copy(workers = 4))
        val transport = FakeTransport { CheckResult(it, CheckStatus.UNKNOWN, 403, detail = "Denied") }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(1, transport.calls)
        assertEquals(SessionStatus.ERROR, store.item.status)
    }
    @Test fun serverErrorsRetryWithoutLosingCandidate() = runTest {
        val store = MemoryStore(base.copy(limit = 1))
        var calls = 0
        val transport = FakeTransport { if (calls++ == 0) CheckResult(it, CheckStatus.UNKNOWN, 503) else CheckResult(it, CheckStatus.AVAILABLE, 200) }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(2, transport.calls)
        assertEquals(CheckStatus.AVAILABLE, store.results["aa"]!!.status)
    }
    @Test fun thirtyMinute429RetainsCheckpointAndNeverSchedulesAutomaticResume() = runTest {
        val store = MemoryStore(base.copy(workers = 4))
        val transport = FakeTransport { CheckResult(it, CheckStatus.RATE_LIMITED, 429, 1_800_000) }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(0, testScheduler.currentTime)
        assertEquals(1_800_000, store.cooldown)
        assertEquals(SessionStatus.PAUSED, store.item.status)
        assertEquals("1", store.item.cursor)
        assertEquals(false, store.pending["aa"]!!.second)
        store.status("session", SessionStatus.STOPPED)
        testScheduler.advanceTimeBy(1_800_001)
        assertEquals(SessionStatus.STOPPED, store.item.status)
        assertEquals(1, transport.calls)
        val resumed = FakeTransport { CheckResult(it, CheckStatus.AVAILABLE, 200) }
        ScanEngine(store, resumed) { testScheduler.currentTime }.run("session")
        assertEquals(4, store.results.size)
        assertEquals(setOf("aa", "ab", "ba", "bb"), store.results.keys)
    }
    @Test fun late429CannotReplaceStoppingOrStoppedLifecycle() = runTest {
        for (status in listOf(SessionStatus.STOPPING, SessionStatus.STOPPED, SessionStatus.PAUSED)) {
            val store = MemoryStore(base)
            val transport = FakeTransport {
                store.status("session", status)
                CheckResult(it, CheckStatus.RATE_LIMITED, 429, 1_800_000)
            }
            ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
            assertEquals(status, store.item.status)
            assertEquals(1_800_000, store.cooldown)
            assertEquals(false, store.pending["aa"]!!.second)
        }
    }
    @Test fun workerBatch429CancelsOtherRoutesWithoutLosingPendingNames() = runTest {
        val store = MemoryStore(base.copy(workers = 3))
        val transport = FakeTransport { name ->
            if (name == "aa") CheckResult(name, CheckStatus.AVAILABLE, 200)
            else { delay(if (name == "ab") 1000 else 2000); CheckResult(name, if (name == "ab") CheckStatus.RATE_LIMITED else CheckStatus.AVAILABLE, if (name == "ab") 429 else 200, 1_800_000) }
        }
        ScanEngine(store, transport) { testScheduler.currentTime }.run("session")
        assertEquals(setOf("aa"), store.results.keys)
        assertEquals(setOf("ab", "ba", "bb"), store.pending.keys)
        assertTrue(store.pending.values.none { it.second })
        assertEquals(SessionStatus.PAUSED, store.item.status)
    }
    @Test fun cancellationInterruptsLongCadenceAndRetryDelays() = runTest {
        for (networkError in listOf(false, true)) {
            val store = MemoryStore(base.copy(intervalMs = 300_000))
            val transport = FakeTransport { CheckResult(it, if (networkError) CheckStatus.NETWORK_ERROR else CheckStatus.AVAILABLE) }
            val job = launch { ScanEngine(store, transport) { testScheduler.currentTime }.run("session") }
            runCurrent()
            store.status("session", SessionStatus.STOPPING)
            job.cancelAndJoin()
            store.status("session", SessionStatus.STOPPED)
            assertEquals(0, testScheduler.currentTime)
            assertTrue(transport.canceled)
            assertEquals(1, transport.calls)
            assertTrue(store.pending.values.none { it.second })
        }
    }
}
