package dev.atlas.nym.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

object ResponseClassifier {
    fun classify(username: String, code: Int, body: String, headers: Map<String, String>, now: Long = System.currentTimeMillis()): CheckResult {
        val normalized = headers.mapKeys { it.key.lowercase() }
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        fun number(name: String) = runCatching { json?.get(name)?.jsonPrimitive?.doubleOrNull }.getOrNull()
        if (code == 429) {
            val header = normalized["retry-after"]
            val seconds = header?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it * 1000).toLong() }
            val date = header?.let { runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull() }
            val bodyDelay = number("retry_after")?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it * 1000).toLong() }
            val reset = normalized["x-ratelimit-reset-after"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it * 1000).toLong() }
            val delay = listOfNotNull(seconds, date, bodyDelay, reset).maxOrNull() ?: 60_000L
            return CheckResult(username, CheckStatus.RATE_LIMITED, code, delay.coerceAtLeast(1000),
                "HTTP 429 · scope=${normalized["x-ratelimit-scope"] ?: "unspecified"} · all routes paused")
        }
        if (code in 200..299) {
            val taken = runCatching { json?.get("taken")?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull }.getOrNull()
            val status = when (taken) { false -> CheckStatus.AVAILABLE; true -> CheckStatus.UNAVAILABLE; null -> CheckStatus.UNKNOWN }
            return CheckResult(username, status, code, detail = if (status == CheckStatus.UNKNOWN) "HTTP $code: expected boolean taken; availability unconfirmed" else "")
        }
        val invalid = code in setOf(400, 422) && ((json?.get("errors") as? JsonObject)?.containsKey("username") == true)
        return CheckResult(username, if (invalid) CheckStatus.INVALID else CheckStatus.UNKNOWN, code,
            detail = "HTTP $code: ${body.take(512)}")
    }
}
