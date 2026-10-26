package com.necxa.proxy.core

import java.io.IOException

enum class ProxyType { HTTP, SOCKS5 }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val tls: Boolean = false,
) {
    // Never print credentials.
    override fun toString() = "ProxyConfig($type $host:$port tls=$tls auth=${!username.isNullOrEmpty()})"
}

data class FormState(
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val testUrl: String = "https://example.com/",
    val name: String = "",
    val tls: Boolean = false,
)

class ProxyException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { TIMEOUT, AUTH_REQUIRED, AUTH_FAILED, UNSUPPORTED_AUTH, REJECTED, PROTOCOL }
}

object ConfigValidator {
    private val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val ipv6 = Regex("^[0-9A-Fa-f:.]+$")
    private val hostname = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    fun isValidHost(h: String): Boolean =
        ipv4.matches(h) || (h.contains(':') && ipv6.matches(h) && h.count { it == ':' } in 2..7) || hostname.matches(h)

    /** Returns (config, errors). Config is non-null only when errors is empty. */
    fun validate(f: FormState): Pair<ProxyConfig?, List<String>> {
        val e = mutableListOf<String>()
        val host = f.host.trim()
        if (host.isEmpty()) e += "Server address is required."
        else if (!isValidHost(host)) e += "Server address is not a valid hostname or IP."
        val port = f.port.trim().toIntOrNull()
        if (port == null || port !in 1..65535) e += "Port must be a number from 1 to 65535."
        if (f.username.isEmpty() && f.password.isNotEmpty()) e += "Password given without a username."
        if (f.username.toByteArray().size > 255 || f.password.toByteArray().size > 255)
            e += "Username and password must be at most 255 bytes (SOCKS5 limit)."
        if (f.username.any { it.isISOControl() } || f.password.any { it.isISOControl() })
            e += "Credentials contain control characters."
        return if (e.isEmpty())
            ProxyConfig(f.type, host, port!!, f.username.ifEmpty { null }, f.password.ifEmpty { null }, f.tls) to e
        else null to e
    }
}
