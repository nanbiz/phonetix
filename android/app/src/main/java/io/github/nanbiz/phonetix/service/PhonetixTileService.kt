package io.github.nanbiz.phonetix.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.nanbiz.phonetix.core.SettingsStore

/**
 * The switch in the quick settings shade.
 *
 * An overlay that rewrites the words of every app is something a reader wants to be able to
 * turn off from wherever they are, at once, without finding this app first: in the middle
 * of typing an address, or handing the phone to someone. A tile is one pull and one tap.
 */
class PhonetixTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        SettingsStore.init(this)
        show()
    }

    override fun onClick() {
        super.onClick()
        SettingsStore.init(this)
        SettingsStore.setEnabled(!SettingsStore.current.enabled)
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "TILE enabled=${SettingsStore.current.enabled}")
        }
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val on = SettingsStore.current.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Phonetix"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "Transcribing" else "Off"
        }
        tile.updateTile()
    }
}
