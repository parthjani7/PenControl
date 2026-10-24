package dev.parth.penscroll

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that starts and stops scrolling in the app underneath. */
class ScrollTileService : TileService() {

    override fun onStartListening() = update()

    override fun onClick() {
        val service = PenScrollService.instance
        if (service == null) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapseLegacy(intent)
            }
            return
        }
        if (service.direction == null) {
            // Close the shade first, otherwise the first drag would scroll the shade itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            service.toggleScroll(delayMs = 600)
        } else {
            service.toggleScroll()
        }
        update()
    }

    /** Android 13 and older only accept an Intent here. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private fun update() {
        val tile = qsTile ?: return
        val service = PenScrollService.instance
        val dir = service?.direction
        tile.state = when {
            service == null -> Tile.STATE_UNAVAILABLE
            dir == null -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.subtitle = getString(
            when (dir) {
                PenScrollService.Direction.DOWN -> R.string.tile_down
                PenScrollService.Direction.UP -> R.string.tile_up
                null -> R.string.tile_off
            },
        )
        tile.updateTile()
    }
}
