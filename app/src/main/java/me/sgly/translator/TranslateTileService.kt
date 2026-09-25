package me.sgly.translator

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService

/** The quick settings entry point into the capture flow. */
class TranslateTileService : TileService() {
    override fun onClick() {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }
}
