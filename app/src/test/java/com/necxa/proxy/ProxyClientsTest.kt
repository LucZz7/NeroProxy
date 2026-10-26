package com.necxa.proxy

import com.necxa.proxy.core.*
import com.necxa.proxy.core.ProxyException.Kind
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

class ProxyClientsTest {
    private fun serve(handler: (Socket) -> Unit): ServerSocket {
        val ss = ServerSocket(0)
        Thread { try { ss.accept().use(handler) } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        return ss
    }
    private fun cfg(t: ProxyType, port: Int, u: String? = null, p: String? = null) = ProxyConfig(t, "127.0.0.1", port, u, p)
    private fun kind(block: () -> Unit): Kind? = try { block(); null } catch (e: ProxyException) { e.kind }

    // ---- Validation
    @Test fun validConfig() = assertNotNull(ConfigValidator.validate(FormState(host = "proxy.example.com", port = "1080")).first)
    @Test fun badPort() = assertNull(ConfigValidator.validate(FormState(host = "1.2.3.4", port = "70000")).first)
    @Test fun badHost() = assertNull(ConfigValidator.validate(FormState(host = "bad host!", port = "80")).first)
    @Test fun ipv6Host() = assertTrue(ConfigValidator.isValidHost("2001:db8::1"))
    @Test fun passwordWithoutUser() = assertNull(ConfigValidator.validate(FormState(host = "a.com", port = "1", password = "x")).first)
    @Test fun toStringHidesPassword() = assertFalse(cfg(ProxyType.HTTP, 1, "u", "secret").toString().contains("secret"))

    // ---- HTTP CONNECT
    @Test fun httpRequestFormat() {
        val r = HttpConnectClient.buildRequest(cfg(ProxyType.HTTP, 1, "user", "pass"), "example.com", 443)
        assertTrue(r.startsWith("CONNECT example.com:443 HTTP/1.1\r\n"))
        assertTrue(r.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"))
        assertTrue(r.endsWith("\r\n\r\n"))
    }
    @Test fun httpNoAuthHeaderWithoutCreds() =
        assertFalse(HttpConnectClient.buildRequest(cfg(ProxyType.HTTP, 1), "a.com", 443).contains("Proxy-Authorization"))
    @Test fun httpIpv6Authority() =
        assertTrue(HttpConnectClient.buildRequest(cfg(ProxyType.HTTP, 1), "::1", 443).startsWith("CONNECT [::1]:443"))
    @Test fun httpStatusParsing() { assertEquals(200, HttpConnectClient.parseStatus("HTTP/1.1 200 Connection established")); assertEquals(Kind.PROTOCOL, kind { HttpConnectClient.parseStatus("garbage") }) }
    private fun httpReply(code: String): ServerSocket = serve { s ->
        HttpConnectClient.readHead(s.getInputStream()); s.getOutputStream().write("HTTP/1.1 $code\r\n\r\n".toByteArray())
    }
    @Test fun http200() { val s = httpReply("200 OK"); assertNull(kind { Socket("127.0.0.1", s.localPort).use { HttpConnectClient.handshake(it, cfg(ProxyType.HTTP, s.localPort), "a.com", 443) } }) }
    @Test fun http407WithCreds() { val s = httpReply("407 Proxy Authentication Required"); assertEquals(Kind.AUTH_FAILED, kind { Socket("127.0.0.1", s.localPort).use { HttpConnectClient.handshake(it, cfg(ProxyType.HTTP, s.localPort, "u", "p"), "a.com", 443) } }) }
    @Test fun http407NoCreds() { val s = httpReply("407 Proxy Authentication Required"); assertEquals(Kind.AUTH_REQUIRED, kind { Socket("127.0.0.1", s.localPort).use { HttpConnectClient.handshake(it, cfg(ProxyType.HTTP, s.localPort), "a.com", 443) } }) }
    @Test fun http403Rejected() { val s = httpReply("403 Forbidden"); assertEquals(Kind.REJECTED, kind { Socket("127.0.0.1", s.localPort).use { HttpConnectClient.handshake(it, cfg(ProxyType.HTTP, s.localPort), "a.com", 443) } }) }

    // ---- SOCKS5
    private fun socks(method: Int, authOk: Boolean = true, rep: Int = 0): ServerSocket = serve { s ->
        val i = DataInputStream(s.getInputStream()); val o = s.getOutputStream()
        i.readUnsignedByte(); val n = i.readUnsignedByte(); i.skipBytes(n)
        o.write(byteArrayOf(5, method.toByte())); o.flush()
        if (method == 0xFF) return@serve
        if (method == 2) { i.readUnsignedByte(); i.skipBytes(i.readUnsignedByte()); i.skipBytes(i.readUnsignedByte())
            o.write(byteArrayOf(1, if (authOk) 0 else 1)); o.flush(); if (!authOk) return@serve }
        i.skipBytes(3); val len = i.readUnsignedByte(); i.skipBytes(len + 2)
        o.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
    }
    private fun runSocks(s: ServerSocket, u: String? = null, p: String? = null) =
        kind { Socket("127.0.0.1", s.localPort).use { Socks5Client.handshake(it, cfg(ProxyType.SOCKS5, s.localPort, u, p), "example.com", 443) } }

    @Test fun socksNoAuthConnect() = assertNull(runSocks(socks(0)))
    @Test fun socksAuthOk() = assertNull(runSocks(socks(2), "u", "p"))
    @Test fun socksAuthFail() = assertEquals(Kind.AUTH_FAILED, runSocks(socks(2, authOk = false), "u", "p"))
    @Test fun socksAuthRequiredButNoCreds() = assertEquals(Kind.AUTH_REQUIRED, runSocks(socks(2)))
    @Test fun socksNoAcceptableMethod() = assertEquals(Kind.UNSUPPORTED_AUTH, runSocks(socks(0xFF)))
    @Test fun socksRefused() = assertEquals(Kind.REJECTED, runSocks(socks(0, rep = 5)))
    @Test fun socksTimeout() {
        val ss = serve { Thread.sleep(2000) }
        assertEquals(Kind.TIMEOUT, kind { Socket("127.0.0.1", ss.localPort).use { it.soTimeout = 300; Socks5Client.handshake(it, cfg(ProxyType.SOCKS5, ss.localPort), "a.com", 443) } })
    }
}
