package com.necxa.proxy

import com.necxa.proxy.core.*
import com.necxa.proxy.vpn.LocalBridge
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

class BridgeTest {
    private fun fakeHttpProxy(): ServerSocket {
        val ss = ServerSocket(0)
        Thread {
            while (true) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        HttpConnectClient.readHead(s.getInputStream())
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                        val b = ByteArray(1024); val n = s.getInputStream().read(b)
                        s.getOutputStream().write(b, 0, n); s.getOutputStream().flush()
                    } catch (_: Exception) {}
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return ss
    }

    private fun newBridge(): LocalBridge {
        val up = fakeHttpProxy()
        return LocalBridge(ProxyConfig(ProxyType.HTTP, "127.0.0.1", up.localPort), {}, {}).also { it.start() }
    }

    private fun login(b: LocalBridge, s: Socket, user: String = b.user): Boolean {
        val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 2)); o.flush()
        assertEquals(5, i.readUnsignedByte()); assertEquals(2, i.readUnsignedByte())
        val u = user.toByteArray(); val p = b.pass.toByteArray()
        o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
        i.readUnsignedByte()
        return i.readUnsignedByte() == 0
    }

    private fun request(s: Socket, cmd: Int): Int {
        val d = "example.com".toByteArray()
        s.getOutputStream().write(byteArrayOf(5, cmd.toByte(), 0, 3, d.size.toByte()) + d + byteArrayOf(0, 80)); s.getOutputStream().flush()
        val r = ByteArray(10); DataInputStream(s.getInputStream()).readFully(r)
        return r[1].toInt()
    }

    @Test fun relaysBytesAndCounts() {
        val b = newBridge()
        Socket("127.0.0.1", b.port).use { s ->
            s.soTimeout = 5000
            assertTrue(login(b, s)); assertEquals(0, request(s, 1))
            s.getOutputStream().write("hello".toByteArray()); s.getOutputStream().flush()
            val buf = ByteArray(5); DataInputStream(s.getInputStream()).readFully(buf)
            assertEquals("hello", String(buf))
        }
        Thread.sleep(200)
        assertEquals(5, b.up.get()); assertEquals(5, b.down.get())
        b.close()
    }
    @Test fun wrongCredentialsRejected() { val b = newBridge(); Socket("127.0.0.1", b.port).use { assertFalse(login(b, it, "intruder")) }; b.close() }
    @Test fun udpAssociateRefused() { val b = newBridge(); Socket("127.0.0.1", b.port).use { assertTrue(login(b, it)); assertEquals(7, request(it, 3)) }; b.close() }
    @Test fun upstreamDownIsRefusedNotBypassed() {
        val dead = ServerSocket(0).also { it.close() }
        val b = LocalBridge(ProxyConfig(ProxyType.HTTP, "127.0.0.1", dead.localPort), {}, {}); b.start()
        Socket("127.0.0.1", b.port).use { assertTrue(login(b, it)); assertEquals(1, request(it, 1)) }
        b.close()
    }
}
