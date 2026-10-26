package com.necxa.proxy.vpn

import android.app.*
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.necxa.proxy.R
import com.necxa.proxy.core.*
import com.necxa.proxy.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

class NeroVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.necxa.proxy.START"
        const val ACTION_STOP = "com.necxa.proxy.STOP"
        const val ACTION_TOGGLE = "com.necxa.proxy.TOGGLE"
        private const val CHANNEL = "nero_vpn"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var monitorJob: Job? = null
    private var pfd: ParcelFileDescriptor? = null
    private var bridge: LocalBridge? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var cfgFile: File? = null
    @Volatile private var running = false
    @Volatile private var probeFails = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = if (intent?.action == ACTION_TOGGLE) {
            if (running || startJob?.isActive == true) ACTION_STOP else ACTION_START
        } else intent?.action
        when (action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, notification())
                if (running || startJob?.isActive == true) return START_NOT_STICKY // single instance
                startJob = scope.launch {
                    try { doStart() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail("Unexpected error: ${e.javaClass.simpleName}") }
                }
            }
            ACTION_STOP -> stopVpn(null)
            else -> stopSelf() // no state restoration: the VPN dies with the process, by design
        }
        return START_NOT_STICKY
    }

    private suspend fun doStart() {
        VpnState.update { VpnUiState(VpnStatus.CONNECTING) }
        val store = ProfileStore(this)
        store.migrateLegacy(ConfigStore(this))
        val profile = store.active()
        val cfg = profile?.toConfig()
        if (profile == null || cfg == null) return fail("No valid proxy profile selected. Add a profile first.")
        if (!Engine.available) return fail("Native tunnel engine missing from this build. Run setup.sh and rebuild (see README).")

        LogBuffer.add("Pre-flight test for ${profile.displayName()} $cfg")
        val steps = ConnectionTester.run(cfg, profile.testUrl)
        steps.forEach { LogBuffer.add("${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.detail}") }
        coroutineContext.ensureActive()
        steps.firstOrNull { !it.ok }?.let { return fail("${it.name}: ${it.detail}") }

        val b = LocalBridge(cfg, { LogBuffer.add(it) }) { msg ->
            LogBuffer.add(msg); VpnState.update { s -> s.copy(message = msg) }
        }
        b.start(); bridge = b

        val builder = Builder()
            .setSession("NERO Proxy").setMtu(1500)
            .addAddress("198.18.0.1", 32).addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)   // IPv6 enters the TUN and is dropped (fail-closed)
            .addDnsServer("198.18.0.2")                 // answered locally by the engine's MapDNS
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {} // our own sockets bypass the TUN
        // App filter: EXCLUDE = those apps bypass, INCLUDE = only those apps use the VPN.
        // Our own package is never in the INCLUDE list, or its upstream sockets would loop.
        val mode = store.filterMode(); val pkgs = store.filterPackages()
        if (mode == FilterMode.EXCLUDE) {
            pkgs.forEach { try { builder.addDisallowedApplication(it) } catch (_: Exception) { LogBuffer.add("App filter: could not exclude $it") } }
        } else if (mode == FilterMode.INCLUDE && pkgs.isNotEmpty()) {
            (pkgs - packageName).forEach { try { builder.addAllowedApplication(it) } catch (_: Exception) { LogBuffer.add("App filter: could not include $it") } }
        }
        val fd = builder.establish() ?: return fail("Android did not create the VPN interface (permission revoked or denied).")
        pfd = fd

        val yml = File(filesDir, "tunnel.yml"); cfgFile = yml
        yml.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
            socks5:
              port: ${b.port}
              address: 127.0.0.1
              udp: 'udp'
              username: '${b.user}'
              password: '${b.pass}'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: warn
            """.trimIndent()
        )
        coroutineContext.ensureActive()
        Engine.start(yml.absolutePath, fd.fd)
        running = true
        probeFails = 0
        VpnState.update { VpnUiState(VpnStatus.CONNECTED, startedAt = System.currentTimeMillis()) }
        LogBuffer.add("Tunnel engine started. Waiting for traffic.")
        registerNetworkCallback(cfg)
        monitorJob = scope.launch { monitor(cfg, b) }
    }

    private suspend fun monitor(cfg: ProxyConfig, b: LocalBridge) {
        var lastUp = 0L; var lastDown = 0L; var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(1000)
            val u = b.up.get(); val d = b.down.get()
            VpnState.update { it.copy(upBytes = u, downBytes = d, upSpeed = u - lastUp, downSpeed = d - lastDown,
                forwardingVerified = it.forwardingVerified || d > 0) }
            lastUp = u; lastDown = d
            if (tick++ % 15 == 0) probe(cfg)
        }
    }

    /** Real latency = TCP connect time to the upstream proxy. Failures flip to RECONNECTING; TUN stays up (fail-closed). */
    private fun probe(cfg: ProxyConfig) {
        val t0 = System.nanoTime()
        val ok = try { Socket().use { it.connect(InetSocketAddress(cfg.host, cfg.port), 5000) }; true } catch (e: Exception) { false }
        val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
        if (ok) {
            probeFails = 0
            VpnState.update { s ->
                val st = if (s.status == VpnStatus.RECONNECTING) VpnStatus.CONNECTED else s.status
                s.copy(status = st, latencyMs = ms, message = if (s.status == VpnStatus.RECONNECTING) null else s.message)
            }
        } else if (++probeFails >= 3 || connectivityLost) {
            LogBuffer.add("Upstream proxy not reachable")
            VpnState.update { s ->
                if (s.status == VpnStatus.CONNECTED || s.status == VpnStatus.RECONNECTING)
                    s.copy(status = VpnStatus.RECONNECTING, latencyMs = null,
                        message = "Proxy server not reachable. Traffic is blocked, not sent directly.") else s
            }
        }
    }
    @Volatile private var connectivityLost = false

    private fun registerNetworkCallback(cfg: ProxyConfig) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityLost = false
                setUnderlyingNetworks(arrayOf(network))
                LogBuffer.add("Network changed")
                scope.launch { probe(cfg) }
            }
            override fun onLost(network: Network) {
                connectivityLost = true
                LogBuffer.add("Network lost")
                VpnState.update { s -> if (s.status == VpnStatus.CONNECTED) s.copy(status = VpnStatus.RECONNECTING, message = "Waiting for network") else s }
            }
        }
        netCb = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) { netCb = null }
    }

    private fun fail(msg: String) {
        LogBuffer.add("ERROR: $msg")
        cleanup()
        VpnState.update { VpnUiState(VpnStatus.ERROR, msg) }
        stopForeground(true); stopSelf()
    }

    private fun stopVpn(message: String?) {
        VpnState.update { it.copy(status = VpnStatus.STOPPING) }
        startJob?.cancel()
        cleanup()
        VpnState.update { VpnUiState(VpnStatus.DISCONNECTED, message) }
        LogBuffer.add(message ?: "Disconnected")
        stopForeground(true); stopSelf()
    }

    @Synchronized private fun cleanup() {
        monitorJob?.cancel(); monitorJob = null
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
        Engine.stop()
        bridge?.close(); bridge = null
        try { pfd?.close() } catch (_: Exception) {}; pfd = null
        cfgFile?.delete(); cfgFile = null
        running = false; connectivityLost = false
    }

    /** User switched the VPN off (or another VPN took over) in Android settings. */
    override fun onRevoke() { stopVpn("VPN was turned off from Android settings.") }

    override fun onDestroy() {
        cleanup(); scope.cancel()
        if (VpnState.flow.value.status == VpnStatus.CONNECTED || VpnState.flow.value.status == VpnStatus.RECONNECTING ||
            VpnState.flow.value.status == VpnStatus.CONNECTING)
            VpnState.update { VpnUiState(VpnStatus.DISCONNECTED, "VPN service stopped unexpectedly.") }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, NeroVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nero).setContentTitle("NERO Proxy").setContentText("VPN is active")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_nero), "Disconnect", stop).build())
            .build()
    }
}
