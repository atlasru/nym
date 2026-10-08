package dev.atlas.nym.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Bounded batches, serialized aggregate cadence, one-request startup gate. */
class ScanEngine(private val store: ScanStore, private val transport: CheckTransport, private val clock: () -> Long = System::currentTimeMillis) {
    private val pace = Mutex()
    private var nextAt = 0L
    private val halt = AtomicBoolean(false)

    suspend fun run(id: String) {
        val session = requireNotNull(store.session(id))
        val config = session.config
        config.validate()
        check(store.cooldownUntil() <= clock()) { "Server cooldown is still active" }
        val generator = CandidateGenerator(config)
        val random = Random(config.seed)
        var warmup = false
        store.status(id, SessionStatus.RUNNING)
        try {
            while (!halt.get()) {
                coroutineContext.ensureActive()
                val candidates = buildList {
                    repeat(if (warmup) config.workers else 1) {
                        store.reserve(id, generator)?.let { add(it) }
                    }
                }
                if (candidates.isEmpty()) {
                    store.status(id, SessionStatus.COMPLETED)
                    break
                }
                val results = coroutineScope {
                    candidates.map { candidate -> async {
                        checkCandidate(id, candidate, config, random)
                    } }.awaitAll()
                }
                if (results.any { it?.status in setOf(CheckStatus.AVAILABLE, CheckStatus.UNAVAILABLE, CheckStatus.INVALID) }) warmup = true
            }
        } finally {
            transport.cancel()
            withContext(NonCancellable) { store.releasePending(id) }
            transport.close()
        }
    }

    private suspend fun checkCandidate(id: String, candidate: Candidate, config: ScanConfig, random: Random): CheckResult? {
        if (!CandidateGenerator.validUsername(candidate.username)) {
            val invalid = CheckResult(candidate.username, CheckStatus.INVALID, detail = "Invalid username syntax (local validation)")
            store.complete(id, candidate, invalid)
            return invalid
        }
        var attempt = 0
        while (!halt.get()) {
            val allowed = pace.withLock {
                delay((nextAt - clock()).coerceAtLeast(0))
                if (halt.get() || store.cooldownUntil() > clock()) false else {
                    val jitter = if (config.jitterMs == 0L) 0 else random.nextLong().ushr(1) % (config.jitterMs + 1)
                    nextAt = clock() + config.intervalMs + jitter
                    true
                }
            }
            if (!allowed) return null
            val result = transport.check(candidate.username)
            if (halt.get() && result.status != CheckStatus.RATE_LIMITED) return null
            if (result.status == CheckStatus.RATE_LIMITED) {
                halt.set(true)
                // Persist the cooldown before releasing any route or candidate.
                withContext(NonCancellable) {
                    val now = clock()
                    val wait = result.retryAfterMs ?: 60_000L
                    store.setCooldown(if (wait > Long.MAX_VALUE - now) Long.MAX_VALUE else now + wait)
                    store.recordAttempt(id, result)
                    store.status(id, SessionStatus.COOLDOWN, result.detail)
                }
                transport.cancel()
                return result
            }
            store.recordAttempt(id, result)
            val retriable = result.status == CheckStatus.NETWORK_ERROR || (result.status == CheckStatus.UNKNOWN && result.httpStatus in 500..599)
            if (retriable && attempt < config.retries && !halt.get()) {
                delay(1000L shl attempt++)
                continue
            }
            store.complete(id, candidate, result)
            if (result.httpStatus in setOf(401, 403) || result.detail.contains("captcha", ignoreCase = true)) {
                halt.set(true)
                store.status(id, SessionStatus.ERROR, "Access denied / verification required. Checking stopped. ${result.detail}")
                transport.cancel()
            }
            return result
        }
        return null
    }
}
