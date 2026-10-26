package com.necxa.proxy.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import com.necxa.proxy.core.ProxyException.Kind

/** SOCKS5 (RFC 1928) + username/password auth (RFC 1929), CONNECT only, domain addressing. */
object Socks5Client {
    fun handshake(sock: Socket, cfg: ProxyConfig, destHost: String, destPort: Int) {
        try {
            val out = sock.getOutputStream()
            val inp = DataInputStream(sock.getInputStream())
            val hasAuth = !cfg.username.isNullOrEmpty()
            out.write(if (hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
            if (inp.readUnsignedByte() != 5) throw ProxyException(Kind.PROTOCOL, "Server is not a SOCKS5 proxy.")
            when (val m = inp.readUnsignedByte()) {
                0x00 -> {}
                0x02 -> {
                    if (!hasAuth) throw ProxyException(Kind.AUTH_REQUIRED, "Proxy requires a username and password.")
                    val u = cfg.username!!.toByteArray(); val p = (cfg.password ?: "").toByteArray()
                    val b = ByteArrayOutputStream()
                    b.write(1); b.write(u.size); b.write(u); b.write(p.size); b.write(p)
                    out.write(b.toByteArray()); out.flush()
                    inp.readUnsignedByte() // sub-negotiation version
                    if (inp.readUnsignedByte() != 0) throw ProxyException(Kind.AUTH_FAILED, "Proxy rejected the username/password.")
                }
                0xFF -> throw ProxyException(Kind.UNSUPPORTED_AUTH, "Proxy accepts none of the offered authentication methods.")
                else -> throw ProxyException(Kind.UNSUPPORTED_AUTH, "Proxy selected unsupported method 0x%02X.".format(m))
            }
            val d = destHost.toByteArray()
            if (d.isEmpty() || d.size > 255) throw ProxyException(Kind.PROTOCOL, "Destination hostname length invalid.")
            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0, 3)); req.write(d.size); req.write(d)
            req.write(destPort shr 8); req.write(destPort and 0xFF)
            out.write(req.toByteArray()); out.flush()
            if (inp.readUnsignedByte() != 5) throw ProxyException(Kind.PROTOCOL, "Bad SOCKS5 reply version.")
            val rep = inp.readUnsignedByte(); inp.readUnsignedByte()
            val skip = when (inp.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> inp.readUnsignedByte(); else -> throw ProxyException(Kind.PROTOCOL, "Bad address type in reply.") }
            inp.skipBytes(skip + 2)
            if (rep != 0) throw ProxyException(Kind.REJECTED, replyText(rep))
        } catch (e: SocketTimeoutException) {
            throw ProxyException(Kind.TIMEOUT, "Proxy did not respond in time.")
        } catch (e: EOFException) {
            throw ProxyException(Kind.PROTOCOL, "Proxy closed the connection unexpectedly.")
        }
    }

    fun replyText(code: Int) = when (code) {
        1 -> "General SOCKS server failure."
        2 -> "Connection not allowed by proxy ruleset."
        3 -> "Network unreachable (from proxy)."
        4 -> "Host unreachable / DNS failure at proxy."
        5 -> "Connection refused by destination."
        6 -> "TTL expired."
        7 -> "Command not supported by proxy."
        8 -> "Address type not supported by proxy."
        else -> "Unknown SOCKS5 error 0x%02X.".format(code)
    }
}
