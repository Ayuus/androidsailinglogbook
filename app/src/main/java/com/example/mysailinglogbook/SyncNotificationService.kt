package com.example.mysailinglogbook

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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
        // A real Android progress bar in the notification shade, not just text -- same
        // current/max MainActivity's own bottom progress bar shows (see updateProgressBar()),
        // so the two stay in sync instead of the notification lagging behind on whichever phase
        // last happened to update it (asked for explicitly: found in practice, the notification
        // was still showing "opbouwen 2012/2012" well after decode itself had finished and the
        // run had moved on to later phases with no progress update of their own reaching it).
        val progressMax = intent?.getIntExtra(EXTRA_PROGRESS_MAX, -1) ?: -1
        val progressCurrent = intent?.getIntExtra(EXTRA_PROGRESS_CURRENT, -1) ?: -1
        // Tapping the notification opens the app (asked for explicitly) -- without a
        // setContentIntent, tapping it did nothing at all. FLAG_IMMUTABLE is required since API 31
        // (Android 12); this app's minSdk 24 means the flag itself must still be built
        // conditionally for the OS versions where it doesn't exist yet.
        // this.flags, not flags -- onStartCommand()'s own "flags: Int" parameter otherwise shadows
        // Intent's own flags property inside this block (found in practice: "'val' cannot be
        // reassigned", Kotlin resolved the unqualified name to that outer parameter instead).
        // A distinct action, not just the plain launch Intent a tap on the launcher icon would
        // send -- MainActivity.onNewIntent() uses this to tell "the user tapped the notification"
        // apart from any other way it might get resumed (icon tap, task switcher, ...), since only
        // the notification tap should toggle the UI away again on a second tap (asked for
        // explicitly: tapping the icon must always just show the app, never hide it).
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TOGGLE_FROM_NOTIFICATION
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Logboek synchroniseren")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .apply {
                if (progressMax > 0 && progressCurrent >= 0) setProgress(progressMax, progressCurrent, false)
            }
            .build()
        // Calling this again on an already-foregrounded service just updates the existing
        // notification's content in place -- used both for the initial "bezig..." state and for
        // every subsequent progress update (see MainActivity.syncFromW2k2()'s progress listener).
        //
        // Wrapped in try/catch -- regression, found in practice: a real, repeated app crash. The
        // caller (MainActivity.startSyncNotification()) already catches a refused
        // startForegroundService() call on *its* end, but that only protects the call that
        // dispatches this Intent to the service -- the service still independently has to call
        // startForeground() itself, from here, within 5 seconds of being started, and *that* call
        // can be refused on its own (same ForegroundServiceStartNotAllowedException) with nothing
        // on the calling side able to catch it: it surfaced as an uncaught RuntimeException deep
        // in ActivityThread.handleServiceArgs(), crashing the whole process -- and then crashed
        // again immediately the same way when Android auto-restarted it right after, since nothing
        // about the app's foreground eligibility had changed in between.
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Fires specifically when the app's task is swept away from Recents (a swipe, or the system
     * reclaiming it). Three cases (asked for explicitly to distinguish the second and third,
     * previously both just left running to completion regardless):
     *  - Nothing running at all: mirrors the ✕ button's own "tap to reopen" notification, so
     *    swiping away isn't a dead end either.
     *  - Something running, but not currently uploading (discovery, download, decode/build):
     *    safe to interrupt right here -- a download resumes cleanly next run over HTTP Range
     *    (see w2k2_download.py), and decode/build just re-runs from wherever it was, backed by
     *    the sample cache -- so there's nothing to gain by continuing in the background once the
     *    owner has already left. postInterruptedNotification() below stands in for the
     *    "Voltooid" completion notification a run that got to finish would otherwise end with.
     *  - Currently uploading (SyncState.uploading, see its own doc comment): left running,
     *    same as ever -- not itself safely resumable mid-request the same way, and comparatively
     *    fast anyway. Finishes and stops itself via runSync()'s/runPublish()'s own finally block,
     *    same as a run that was never interrupted at all. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!SyncState.inProgress) {
            postReopenNotification(this)
            stopSelf()
        } else if (!SyncState.uploading) {
            SyncState.cancelled = true
            SyncState.notificationForegrounded = false
            SyncState.notificationStartFailed = false
            // stopSelf() first, same ordering as postCompletionNotification()'s own call sites --
            // it tears down the foreground notification under NOTIFICATION_ID, so the follow-up
            // notification below (posted under that same id, to replace rather than add to it)
            // has to come after, not before.
            stopSelf()
            postInterruptedNotification(this)
        }
    }

    private fun createChannel() {
        // Once per process, not on every single onStartCommand() (a fresh sync's first call, and
        // every progress update after it -- easily dozens of calls per run) -- deleting a channel
        // that was already deleted, and recreating one that already exists with identical
        // settings, are both wasted binder calls to NotificationManager on every single call,
        // adding to (not the whole explanation for, but part of) the delay before the very first
        // notification actually becomes visible (asked about explicitly).
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
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
        channelCreated = true
    }

    companion object {
        // Process-wide, not an instance field -- a new Service instance is created each time it's
        // (re)started after fully stopping, but the channel itself, once created, persists at the
        // OS level regardless; re-checking per process avoids redoing that work needlessly on a
        // later sync within the same still-running process, without wrongly skipping it after a
        // genuine process restart.
        private var channelCreated = false
        private const val OLD_CHANNEL_ID = "sync"
        const val CHANNEL_ID = "sync_v2"
        const val NOTIFICATION_ID = 1
        const val REOPEN_NOTIFICATION_ID = 2
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_PROGRESS_MAX = "progress_max"

        // Shared between the ✕ button (MainActivity.closeAppAndCancelSync()) and swiping the app
        // away from Recents (onTaskRemoved() above) -- both are "the user left the app", and both
        // should leave behind the same "tap to reopen" notification rather than a dead end.
        fun postReopenNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val reopenIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(context, 0, reopenIntent, pendingIntentFlags)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Sailing Logbook")
                .setContentText("App gesloten. Tik om opnieuw te openen.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(REOPEN_NOTIFICATION_ID, notification)
        }

        /** Posted by onTaskRemoved() above in place of the ongoing sync notification, when the
         * app got closed (swipe-away/"Alles sluiten") while something interruptible -- anything
         * but an upload, see SyncState.uploading -- was still running (asked for explicitly).
         * Posted under NOTIFICATION_ID, same "replace in place" reasoning as
         * postCompletionNotification() below -- this stands in for the completion notification a
         * run that got to finish on its own would otherwise end with. */
        fun postInterruptedNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val reopenIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(context, 0, reopenIntent, pendingIntentFlags)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Logboek synchroniseren")
                .setContentText("Onderbroken door sluiten -- wordt hervat bij de volgende keer.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        /** Replaces the ongoing sync notification with a final, dismissible one once a run
         * finishes successfully -- found in practice, asked for explicitly: stopService() alone
         * (MainActivity.runSync()'s own finally) just makes the notification disappear the
         * instant a sync ends, with nothing left behind to say it actually finished (as opposed
         * to, say, having been swiped away mid-run) or when. Posted under the same NOTIFICATION_ID
         * as the ongoing one, so it replaces it in place rather than adding a second entry.
         *
         * publishedUrl is non-null only when this run's own publish step actually succeeded (see
         * uploadIfConfigured() in MainActivity.kt) -- the "Bekijk live site" action only makes
         * sense to offer then, not after a sync that only rebuilt the local logbook. */
        fun postCompletionNotification(context: Context, resultText: String, publishedUrl: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            // Deliberately no setContentIntent() here -- found in practice that reopening
            // MainActivity from a *finished* run's notification looks like it "hangs": the
            // sync already completed, but a fresh launch starts the whole app (and its own
            // sync-on-launch flow) from scratch, which reads as the previous run never
            // finishing. Tapping the body just dismisses the notification (setAutoCancel);
            // "Bekijk live site" below is its own explicit action for when there's somewhere
            // useful to go.
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Logboek synchroniseren")
                .setContentText(resultText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
            if (publishedUrl != null) {
                // A separate action, not the notification's own tap target -- tapping the body
                // still opens the app itself (consistent with every other notification here),
                // this is specifically for "go look at what just got published" without a detour
                // through the app first.
                val viewSiteIntent = Intent(Intent.ACTION_VIEW, Uri.parse(publishedUrl))
                val viewSitePendingIntent = PendingIntent.getActivity(context, 1, viewSiteIntent, pendingIntentFlags)
                builder.addAction(0, "Bekijk live site", viewSitePendingIntent)
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }
}
