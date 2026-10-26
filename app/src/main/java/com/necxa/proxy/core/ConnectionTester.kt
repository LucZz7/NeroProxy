package com.necxa.proxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class StepResult(val name: String, val ok: Boolean, val detail: String)

/**
 * Real end-to-end test. Uses its own sockets (not the VPN path).
 * Note: the test endpoint sees the proxy's exit address, not necessarily your phone's.
 */
object ConnectionTester {
    suspend fun run(cfg: ProxyConfig, testUrl: String, timeoutMs: Int = 10_000): List<StepResult> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<StepResult>()
            fun ok(n: String, d: String) { out += StepResult(n, true, d) }
            fun bad(n: String, d: String) { out += StepResult(n, false, d) }

            val uri = try { URI(testUrl.trim()) } catch (e: URISyntaxException) { null }
            val host = uri?.host
            if (uri == null || uri.scheme != "https" || host == null) {
                bad("Test endpoint", "Must be a valid https:// URL."); return@withContext out
            }
            val port = if (uri.port == -1) 443 else uri.port
            val path = (uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/")

            val addr = try { InetAddress.getByName(cfg.host) } catch (e: UnknownHostException) {
                bad("Resolve proxy host", "DNS lookup failed for ${cfg.host}."); return@withContext out
            }
            ok("Resolve proxy host", addr.hostAddress ?: "resolved")

            val sock = Socket()
            var tunnelSock: Socket = sock
            try {
                try { sock.connect(InetSocketAddress(addr, cfg.port), timeoutMs) }
                catch (e: SocketTimeoutException) { bad("Proxy reachable", "Timed out connecting."); return@withContext out }
                catch (e: IOException) { bad("Proxy reachable", e.message ?: "Connection failed."); return@withContext out }
                ok("Proxy reachable", "TCP connected (this alone does not prove it works)")
                sock.soTimeout = timeoutMs

                // TLS to the proxy itself, before any HTTP CONNECT / SOCKS5 bytes.
                if (cfg.tls) {
                    try { tunnelSock = UpstreamTls.wrap(sock, cfg) }
                    catch (e: IOException) { bad("Proxy TLS", e.message ?: "TLS handshake failed."); return@withContext out }
                    tunnelSock.soTimeout = timeoutMs
                    ok("Proxy TLS", "TLS to ${cfg.host} verified")
                }

                try {
                    if (cfg.type == ProxyType.HTTP) HttpConnectClient.handshake(tunnelSock, cfg, host, port)
                    else Socks5Client.handshake(tunnelSock, cfg, host, port)
                } catch (e: ProxyException) {
                    val authKind = e.kind in listOf(ProxyException.Kind.AUTH_FAILED, ProxyException.Kind.AUTH_REQUIRED, ProxyException.Kind.UNSUPPORTED_AUTH)
                    bad(if (authKind) "Authentication" else "Tunnel to $host:$port", e.message ?: "Failed")
                    return@withContext out
                } catch (e: IOException) { bad("Tunnel to $host:$port", e.message ?: "I/O error"); return@withContext out }
                ok("Authentication", if (cfg.username.isNullOrEmpty()) "None required/used" else "Accepted")
                ok("Tunnel established", "$host:$port")

                try {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tunnelSock, host, port, true) as javax.net.ssl.SSLSocket
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        bad("TLS through tunnel", "Certificate does not match $host."); return@withContext out
                    }
                    ssl.outputStream.apply { write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: NERO-Proxy-Test\r\n\r\n".toByteArray()); flush() }
                    val line = HttpConnectClient.readHead(ssl.inputStream).first()
                    ok("End-to-end request", line)
                } catch (e: IOException) { bad("End-to-end request", e.message ?: "TLS/HTTP failed") }
            } finally { try { tunnelSock.close() } catch (_: IOException) {}; try { sock.close() } catch (_: IOException) {} }
            out
        }
}
