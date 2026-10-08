package dev.atlas.nym.core

import kotlinx.serialization.Serializable

const val DEFAULT_CHARSET = "abcdefghijklmnopqrstuvwxyz0123456789_."
const val DISCORD_ENDPOINT = "https://discord.com/api/v9/unique-username/username-attempt-unauthed"

@Serializable enum class GenerationMode { SEQUENTIAL, RANDOM, PATTERN, DICTIONARY }
@Serializable enum class CheckStatus { AVAILABLE, UNAVAILABLE, INVALID, UNKNOWN, NETWORK_ERROR, RATE_LIMITED }
@Serializable enum class SessionStatus { RUNNING, PAUSED, STOPPED, COMPLETED, COOLDOWN, INTERRUPTED, ERROR }

@Serializable data class ScanConfig(
    val mode: GenerationMode = GenerationMode.RANDOM,
    val length: Int = 4,
    val charset: String = DEFAULT_CHARSET,
    val pattern: String = "@@@#",
    val dictionary: String = "",
    val seed: Long = 42,
    val limit: Long = 100,
    val intervalMs: Long = 1500,
    val jitterMs: Long = 250,
    val workers: Int = 1,
    val retries: Int = 2,
    val timeoutSeconds: Long = 15,
    val proxies: List<String> = emptyList(),
    val proxyEnabled: Boolean = false,
    val fallbackDirect: Boolean = false,
) {
    fun validate() {
        require(length in 2..32) { "Length must be 2–32" }
        require(charset.isNotEmpty() && charset.all { it in DEFAULT_CHARSET }) { "Charset: lowercase letters, digits, _ and . only" }
        require(limit in 1..1_000_000) { "Check limit must be 1–1,000,000" }
        require(intervalMs in 250..300_000) { "Interval must be 250–300,000 ms" }
        require(jitterMs in 0..60_000) { "Jitter must be 0–60,000 ms" }
        require(workers in 1..4) { "Concurrency must be 1–4" }
        require(retries in 0..3) { "Retries must be 0–3" }
        require(timeoutSeconds in 5..60) { "Timeout must be 5–60 seconds" }
        require(proxies.size <= 100) { "Maximum 100 proxies" }
        proxies.forEach { ProxySpec.parse(it) }
        require(!proxyEnabled || proxies.isNotEmpty()) { "Add a proxy before enabling proxy routing" }
        if (mode == GenerationMode.PATTERN) {
            require(pattern.length in 2..32 && pattern.all { it in DEFAULT_CHARSET || it in "@#*" }) { "Pattern: 2–32 characters; @ letter, # digit, * charset" }
        }
        if (mode == GenerationMode.DICTIONARY) {
            require(dictionary.length <= 2_000_000) { "Dictionary is limited to 2 MB" }
            require(dictionary.lineSequence().count() <= 100_000) { "Maximum 100,000 dictionary lines" }
            require(CandidateGenerator.dictionaryWords(dictionary).isNotEmpty()) { "Dictionary has no valid usernames" }
        }
    }
}

data class CheckResult(
    val username: String,
    val status: CheckStatus,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    val detail: String = "",
    val route: String = "Direct",
    val latencyMs: Long = 0,
    val checkedAt: Long = System.currentTimeMillis(),
)

data class Candidate(val username: String, val ordinal: String)
data class Session(
    val id: String,
    val config: ScanConfig,
    val status: SessionStatus,
    val cursor: String = "0",
    val createdAt: Long = System.currentTimeMillis(),
    val elapsedMs: Long = 0,
    val checked: Long = 0,
    val available: Long = 0,
    val errors: Long = 0,
    val requests: Long = 0,
    val current: String = "",
    val detail: String = "",
)

interface ScanStore {
    suspend fun session(id: String): Session?
    suspend fun reserve(id: String, generator: CandidateGenerator): Candidate?
    suspend fun complete(id: String, candidate: Candidate, result: CheckResult)
    suspend fun recordAttempt(id: String, result: CheckResult)
    suspend fun status(id: String, status: SessionStatus, detail: String = "")
    suspend fun releasePending(id: String)
    suspend fun cooldownUntil(): Long
    suspend fun setCooldown(until: Long)
    suspend fun addElapsed(id: String, milliseconds: Long)
}

interface CheckTransport : AutoCloseable {
    suspend fun check(username: String): CheckResult
    fun cancel()
}
