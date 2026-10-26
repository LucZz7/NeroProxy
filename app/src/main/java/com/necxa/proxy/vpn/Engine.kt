package com.necxa.proxy.vpn

/** Loads the native engine lazily; `available` is false when the app was built without it. */
object Engine {
    val available: Boolean by lazy {
        try { System.loadLibrary("hev-socks5-tunnel"); true } catch (e: UnsatisfiedLinkError) { false }
    }
    @Volatile private var started = false
    fun start(configPath: String, fd: Int) { TProxy.TProxyStartService(configPath, fd); started = true }
    fun stop() { if (started) { try { TProxy.TProxyStopService() } catch (_: Throwable) {}; started = false } }
}
