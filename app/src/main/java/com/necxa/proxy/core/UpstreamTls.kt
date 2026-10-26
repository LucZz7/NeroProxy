package com.necxa.proxy.core

import java.io.IOException
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Wraps a plain TCP connection to the proxy itself in TLS (the "TLS to proxy"
 * option, Android-only in Super Proxy). The wrapped socket owns the plain one
 * (autoClose=true), so closing the returned socket closes both. Throws on
 * handshake failure or on a certificate that does not match the proxy host.
 */
object UpstreamTls {
    fun wrap(plain: Socket, cfg: ProxyConfig): Socket {
        if (!cfg.tls) return plain
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, cfg.host, cfg.port, true) as javax.net.ssl.SSLSocket
        try {
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(cfg.host, ssl.session)) {
                try { ssl.close() } catch (_: IOException) {}
                throw IOException("Proxy TLS certificate does not match ${cfg.host}.")
            }
        } catch (e: IOException) {
            try { ssl.close() } catch (_: IOException) {}
            throw e
        }
        return ssl
    }
}
