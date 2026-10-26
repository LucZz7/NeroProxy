package com.necxa.proxy.core

import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import com.necxa.proxy.core.ProxyException.Kind

/** HTTP CONNECT tunnelling. Credentials go only to the proxy itself (Proxy-Authorization). */
object HttpConnectClient {
    fun buildRequest(cfg: ProxyConfig, host: String, port: Int): String {
        require(host.none { it.isISOControl() || it == ' ' }) { "Illegal host" }
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val sb = StringBuilder("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n")
        if (!cfg.username.isNullOrEmpty()) {
            val tok = Base64.getEncoder().encodeToString("${cfg.username}:${cfg.password.orEmpty()}".toByteArray())
            sb.append("Proxy-Authorization: Basic $tok\r\n")
        }
        return sb.append("Proxy-Connection: Keep-Alive\r\n\r\n").toString()
    }

    fun parseStatus(line: String): Int {
        val p = line.trim().split(" ")
        if (p.size < 2 || !p[0].startsWith("HTTP/")) throw ProxyException(Kind.PROTOCOL, "Not an HTTP proxy response.")
        return p[1].toIntOrNull() ?: throw ProxyException(Kind.PROTOCOL, "Malformed HTTP status line.")
    }

    /** Reads byte-by-byte so no tunnel data is consumed. */
    fun readHead(inp: InputStream): List<String> {
        val sb = StringBuilder(); var last = 0
        while (true) {
            val b = inp.read(); if (b < 0) throw EOFException()
            sb.append(b.toChar()); last = (last shl 8) or b
            if (sb.length > 16384) throw ProxyException(Kind.PROTOCOL, "Proxy response headers too large.")
            if (last == 0x0D0A0D0A) return sb.toString().split("\r\n").filter { it.isNotEmpty() }
        }
    }

    fun handshake(sock: Socket, cfg: ProxyConfig, destHost: String, destPort: Int) {
        try {
            sock.getOutputStream().apply { write(buildRequest(cfg, destHost, destPort).toByteArray()); flush() }
            val status = parseStatus(readHead(sock.getInputStream()).first())
            when {
                status in 200..299 -> {}
                status == 407 -> throw ProxyException(
                    if (cfg.username.isNullOrEmpty()) Kind.AUTH_REQUIRED else Kind.AUTH_FAILED,
                    if (cfg.username.isNullOrEmpty()) "Proxy requires authentication (407)." else "Proxy rejected the credentials (407).")
                else -> throw ProxyException(Kind.REJECTED, "Proxy refused CONNECT (HTTP $status).")
            }
        } catch (e: SocketTimeoutException) {
            throw ProxyException(Kind.TIMEOUT, "Proxy did not respond in time.")
        } catch (e: EOFException) {
            throw ProxyException(Kind.PROTOCOL, "Proxy closed the connection unexpectedly.")
        }
    }
}
