package com.necxa.proxy.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, STOPPING, ERROR }

data class VpnUiState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val message: String? = null,
    val startedAt: Long = 0,
    val upBytes: Long = 0, val downBytes: Long = 0,
    val upSpeed: Long = 0, val downSpeed: Long = 0,
    val latencyMs: Int? = null,
    val forwardingVerified: Boolean = false,
)

object VpnState {
    val flow = MutableStateFlow(VpnUiState())
    fun update(f: (VpnUiState) -> VpnUiState) = flow.update(f)
}

/** In-memory ring buffer. Callers must never pass credentials or destination hosts. */
object LogBuffer {
    val flow = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun add(msg: String) = flow.update { (it + "${fmt.format(Date())}  $msg").takeLast(300) }
    fun clear() { flow.value = emptyList() }
}
