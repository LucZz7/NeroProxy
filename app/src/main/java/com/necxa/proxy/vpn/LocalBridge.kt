package com.necxa.proxy.vpn

import com.necxa.proxy.core.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback SOCKS5 server (TCP CONNECT only) that the native tunnel talks to.
 * It opens every connection to the real upstream (HTTP CONNECT or SOCKS5), counts real bytes,
 * and requires random per-session credentials so other apps on the phone cannot use it.
 * There is no direct-internet fallback: if upstream fails, the connection is refused.
 */
class LocalBridge(
    private val cfg: ProxyConfig,
    private val log: (String) -> Unit,
    private val onUpstreamError: (String) -> Unit,
) {
    private fun rnd() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val user = rnd()
    val pass = rnd()
    private val server = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val up = AtomicLong()
    val down = AtomicLong()
    private val pool = Executors.newCachedThreadPool()
    private val active: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var closed = false

    fun start() {
        pool.execute {
            while (!closed) {
                val c = try { server.accept() } catch (e: IOException) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { closeQuiet(c) }
            }
        }
    }

    fun close() {
        closed = true
        closeQuiet(server)
        active.toList().forEach { closeQuiet(it) }
        pool.shutdownNow()
    }

    private fun closeQuiet(c: Closeable?) { try { c?.close() } catch (_: IOException) {} }
    private fun reply(o: OutputStream, code: Int) { o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush() }

    private fun handle(c: Socket) {
        var upSock: Socket? = null
        var out: OutputStream? = null
        try {
            active.add(c)
            c.soTimeout = 15_000; c.tcpNoDelay = true
            val inp = DataInputStream(c.getInputStream()); val o = c.getOutputStream(); out = o
            if (inp.readUnsignedByte() != 5) return
            val ms = ByteArray(inp.readUnsignedByte()); inp.readFully(ms)
            if (!ms.contains(2.toByte())) { o.write(byteArrayOf(5, -1)); o.flush(); return }
            o.write(byteArrayOf(5, 2)); o.flush()
            inp.readUnsignedByte()
            val u = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
            val p = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
            val ok = MessageDigest.isEqual(u, user.toByteArray()) and MessageDigest.isEqual(p, pass.toByteArray())
            o.write(byteArrayOf(1, if (ok) 0 else 1)); o.flush()
            if (!ok) return
            if (inp.readUnsignedByte() != 5) return
            val cmd = inp.readUnsignedByte(); inp.readUnsignedByte()
            val host = when (inp.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { inp.readFully(it) }).hostAddress
                3 -> String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { inp.readFully(it) }).hostAddress
                else -> { reply(o, 8); return }
            } ?: return
            val port = inp.readUnsignedShort()
            if (cmd != 1) { reply(o, 7); return } // UDP ASSOCIATE / BIND unsupported -> fail closed

            val s = Socket(); upSock = s; active.add(s)
            var upstream: Socket = s
            try {
                val addr = InetSocketAddress(cfg.host, cfg.port)
                if (addr.isUnresolved) throw UnknownHostException(cfg.host)
                s.connect(addr, 10_000)
                s.soTimeout = 15_000; s.tcpNoDelay = true
                if (cfg.tls) {
                    upstream = UpstreamTls.wrap(s, cfg)
                    upSock = upstream; active.add(upstream)
                    upstream.soTimeout = 15_000
                }
                if (cfg.type == ProxyType.HTTP) HttpConnectClient.handshake(upstream, cfg, host, port)
                else Socks5Client.handshake(upstream, cfg, host, port)
            } catch (e: ProxyException) {
                onUpstreamError("Upstream: ${e.message}")
                reply(o, when (e.kind) { ProxyException.Kind.REJECTED -> 5; ProxyException.Kind.TIMEOUT -> 6; else -> 1 })
                return
            } catch (e: IOException) {
                onUpstreamError("Cannot reach proxy server.")
                reply(o, 1); return
            }
            reply(o, 0)
            c.soTimeout = 0; upstream.soTimeout = 0
            pool.execute { pump(upstream, c, down) }
            pump(c, upstream, up)
        } catch (e: Exception) {
            // malformed client / shutdown; nothing sensitive to log
        } finally {
            closeQuiet(c); closeQuiet(upSock)
            active.remove(c); upSock?.let { active.remove(it) }
        }
    }

    private fun pump(src: Socket, dst: Socket, ctr: AtomicLong) {
        try {
            val i = src.getInputStream(); val o = dst.getOutputStream(); val buf = ByteArray(16 * 1024)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); o.flush(); ctr.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
        } finally { closeQuiet(src); closeQuiet(dst) }
    }
}
