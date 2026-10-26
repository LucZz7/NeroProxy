package com.necxa.proxy.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile: tap to connect / disconnect. If VPN permission was
 * never granted, the tile opens the app so Android can show the consent dialog
 * (a tile cannot ask for VPN permission by itself).
 */
class NeroTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (VpnService.prepare(this) != null) {
            val i = Intent(this, com.necxa.proxy.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(i)
            return
        }
        val st = VpnState.flow.value.status
        val action = if (st == VpnStatus.CONNECTED || st == VpnStatus.RECONNECTING) NeroVpnService.ACTION_STOP
            else NeroVpnService.ACTION_START
        try {
            ContextCompat.startForegroundService(this, Intent(this, NeroVpnService::class.java).setAction(action))
        } catch (_: Exception) {
            val i = Intent(this, com.necxa.proxy.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(i)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = when (VpnState.flow.value.status) {
            VpnStatus.CONNECTED, VpnStatus.RECONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Nero Proxy"
        tile.updateTile()
    }
}
