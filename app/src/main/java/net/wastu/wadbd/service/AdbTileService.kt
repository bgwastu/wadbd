package net.wastu.wadbd.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.wastu.wadbd.data.WadbdRepository

class AdbTileService : TileService() {

    private val repository = WadbdRepository()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val isCurrentlyActive = tile.state == Tile.STATE_ACTIVE

        scope.launch {
            repository.toggleAdb(!isCurrentlyActive, 5555)
            updateTileState()
        }
    }

    private fun updateTileState() {
        scope.launch {
            val state = repository.loadState()
            val tile = qsTile ?: return@launch

            if (state.isEnabled) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Port ${state.port}"
                tile.icon = android.graphics.drawable.Icon.createWithResource(this@AdbTileService, net.wastu.wadbd.R.drawable.ic_launcher_monochrome)
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Disabled"
                tile.icon = android.graphics.drawable.Icon.createWithResource(this@AdbTileService, net.wastu.wadbd.R.drawable.ic_launcher_monochrome)
            }
            tile.updateTile()
        }
    }
}
