package dev.atlas.nym.core

import kotlin.test.*
import org.junit.Test

class ResponseTest {
    private fun classify(code: Int, body: String, headers: Map<String, String> = emptyMap()) = ResponseClassifier.classify("name", code, body, headers, 0)
    @Test fun strictBooleanClassification() {
        assertEquals(CheckStatus.AVAILABLE, classify(200, "{\"taken\":false}").status)
        assertEquals(CheckStatus.UNAVAILABLE, classify(200, "{\"taken\":true}").status)
        for (body in listOf("{}", "[]", "{\"taken\":1}", "{\"taken\":\"false\"}", "<html>error</html>")) assertEquals(CheckStatus.UNKNOWN, classify(200, body).status)
    }
    @Test fun invalidRequiresUsernameError() {
        assertEquals(CheckStatus.INVALID, classify(400, "{\"errors\":{\"username\":{\"_errors\":[]}}}").status)
        assertEquals(CheckStatus.UNKNOWN, classify(400, "{\"captcha_key\":[]}").status)
        assertEquals(CheckStatus.UNKNOWN, classify(403, "denied").status)
        assertEquals(CheckStatus.UNKNOWN, classify(500, "failure").status)
    }
    @Test fun cooldownUsesMaximumHeaderBodyAndReset() {
        val result = classify(429, "{\"retry_after\":5.2}", mapOf("Retry-After" to "2.25", "X-RateLimit-Reset-After" to "9.1"))
        assertEquals(9100L, result.retryAfterMs)
        assertEquals(CheckStatus.RATE_LIMITED, result.status)
    }
    @Test fun httpDateAndFractionalDelay() {
        assertEquals(4000L, classify(429, "{}", mapOf("retry-after" to "Thu, 01 Jan 1970 00:00:04 GMT")).retryAfterMs)
        assertEquals(1251L, classify(429, "{\"retry_after\":1.2505}").retryAfterMs)
    }
    @Test fun missingOrMalformedCooldownIsConservative() {
        for (header in listOf("garbage", "NaN", "Infinity", "-10")) assertEquals(60_000L, classify(429, "bad", mapOf("retry-after" to header)).retryAfterMs)
        assertEquals(1000L, classify(429, "{}", mapOf("retry-after" to "0")).retryAfterMs)
    }
    @Test fun bodyNonObjectDoesNotCrash() {
        for (body in listOf("null", "true", "[]", "\"string\"")) assertEquals(CheckStatus.RATE_LIMITED, classify(429, body).status)
    }
    @Test fun thirtyMinuteRetryAfterAndReliableDiscordScopes() {
        assertEquals(1_800_000L, classify(429, "{}", mapOf("Retry-After" to "1800")).retryAfterMs)
        assertEquals(RateLimitScope.GLOBAL, classify(429, "{\"global\":true,\"retry_after\":1800}").rateLimitScope)
        assertEquals(RateLimitScope.GLOBAL, classify(429, "{}", mapOf("X-RateLimit-Global" to "true")).rateLimitScope)
        assertEquals(RateLimitScope.SHARED, classify(429, "{}", mapOf("X-RateLimit-Scope" to "shared")).rateLimitScope)
        assertEquals(RateLimitScope.USER, classify(429, "{}", mapOf("X-RateLimit-Scope" to "user")).rateLimitScope)
        assertEquals(RateLimitScope.UNKNOWN, classify(429, "{\"global\":\"true\"}", mapOf("X-RateLimit-Scope" to "proxy")).rateLimitScope)
    }
    @Test fun absoluteResetAndOverflowDoNotShortenCooldown() {
        assertEquals(1_800_000L, classify(429, "{}", mapOf("X-RateLimit-Reset" to "1800")).retryAfterMs)
        assertEquals(Long.MAX_VALUE, classify(429, "{}", mapOf("Retry-After" to "1e300")).retryAfterMs)
        assertEquals(Long.MAX_VALUE, cooldownDeadline(100, Long.MAX_VALUE))
    }
}
