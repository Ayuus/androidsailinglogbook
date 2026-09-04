package com.example.mysailinglogbook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Hosts the sync-in-progress notification as a real foreground service, not a notification
 * posted from a plain background Thread -- found in practice (real device test) that a plain
 * ongoing notification has no OS guarantee of being cleared if the process dies before its own
 * cleanup code runs (force-stop, the OS killing a background thread, a crash), and setOngoing(true)
 * makes it non-swipeable too, so it got stuck permanently. A foreground service's notification is
 * tied to the service's own lifecycle instead -- the OS removes it automatically the moment the
 * service (or its process) stops, clean or not.
 */
class SyncNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val current = intent?.getIntExtra(EXTRA_CURRENT, -1) ?: -1
        val total = intent?.getIntExtra(EXTRA_TOTAL, -1) ?: -1
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
        val contentText = when {
            current >= 0 && total >= 0 && fileName != null -> "Downloaden: $current/$total ($fileName)"
            statusText != null -> statusText
            else -> "Bezig met downloaden en verwerken..."
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Logboek synchroniseren")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // Calling this again on an already-foregrounded service just updates the existing
        // notification's content in place -- used both for the initial "bezig..." state and for
        // every subsequent progress update (see MainActivity.syncFromW2k2()'s progress listener).
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // IMPORTANCE_LOW put this notification in Samsung One UI's collapsed "Silent"
            // section and made it trivially swipeable despite setOngoing(true) (found in
            // practice, asked for explicitly) -- channel importance is fixed once created, so
            // bumping it in code alone wouldn't affect the "sync" channel already on the test
            // device; OLD_CHANNEL_ID is deleted here and a differently-named channel created
            // instead, forcing a fresh one at the new importance.
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID, "Synchronisatie", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Toont wanneer de app bezig is met het ophalen en verwerken van het logboek."
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val OLD_CHANNEL_ID = "sync"
        const val CHANNEL_ID = "sync_v2"
        const val NOTIFICATION_ID = 1
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_STATUS_TEXT = "status_text"
    }
}
