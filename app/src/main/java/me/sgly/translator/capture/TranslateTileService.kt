package me.sgly.translator.capture

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService

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
