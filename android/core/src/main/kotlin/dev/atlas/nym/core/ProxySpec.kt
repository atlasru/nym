package dev.atlas.nym.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ProxySpec(val scheme: String, val host: String, val port: Int, val username: String? = null, val password: String? = null) {
    val display: String get() = "$scheme://${if (username != null) "***@" else ""}${if (':' in host) "[$host]" else host}:$port"

    companion object {
        fun parse(raw: String): ProxySpec {
            var value = raw.trim()
            if ("://" !in value && '@' !in value && value.count { it == ':' } == 3) {
                val (host, port, user, pass) = value.split(':', limit = 4)
                val encode = { text: String -> java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20") }
                value = "http://${encode(user)}:${encode(pass)}@$host:$port"
            }
            if ("://" !in value) value = "http://$value"
            val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid proxy URL") }
            require(uri.scheme in setOf("http", "https", "socks5")) { "Proxy scheme must be http, https or socks5" }
            require(!uri.host.isNullOrBlank() && uri.port in 1..65535) { "Proxy requires host and port 1–65535" }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Proxy URL must not contain a path" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "Proxy URL must not contain query or fragment" }
            val auth = uri.rawUserInfo?.split(':', limit = 2)
            val decode = { text: String -> URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
            val user = auth?.get(0)?.let(decode)
            val pass = auth?.getOrNull(1)?.let(decode) ?: if (user != null) "" else null
            require(user?.toByteArray()?.size?.let { it <= 255 } != false && pass?.toByteArray()?.size?.let { it <= 255 } != false) { "Proxy credentials must be at most 255 bytes" }
            require(user?.any { it == '\r' || it == '\n' } != true && pass?.any { it == '\r' || it == '\n' } != true) { "Invalid proxy credentials" }
            return ProxySpec(uri.scheme, uri.host.removePrefix("[").removeSuffix("]"), uri.port, user, pass)
        }
    }
}
