package app.glimpse.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import app.glimpse.R

fun Service.captureNotification(title: String): Notification {
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
            NotificationChannel(
                "active_capture",
                "Active capture",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    val stop =
        PendingIntent.getService(
            this,
            0,
            Intent(this, javaClass).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val controls =
        if (this is ScreenTranslationService)
            PendingIntent.getActivity(
                this,
                41,
                Intent(this, CapturePermissionActivity::class.java)
                    .setAction(CapturePermissionActivity.LIVE_TRANSLATE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        else null
    return Notification.Builder(this, "active_capture")
        .setSmallIcon(R.drawable.ic_scan)
        .setContentTitle(title)
        .setContentText("Tap Stop to end capture")
        .setOngoing(true)
        .apply { if (controls != null) setContentIntent(controls) }
        .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        .build()
}
