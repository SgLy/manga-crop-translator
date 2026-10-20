package me.sgly.translator.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import me.sgly.translator.R

internal fun createNotificationChannel(context: Context) {
    val channel =
        NotificationChannel(
            "floating_service",
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
    channel.description = context.getString(R.string.notification_channel_description)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}

internal fun createNotification(context: Context): Notification {
    val builder = Notification.Builder(context, "floating_service")
    return builder
        .setContentTitle(context.getString(R.string.notification_title))
        .setContentText(context.getString(R.string.notification_text))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()
}
