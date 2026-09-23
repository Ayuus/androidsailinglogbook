package com.ayuus.mysailinglogbook

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
 * Hosts the download-in-progress notification as a real foreground service, not a notification
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
            current >= 0 && total >= 0 && fileName != null -> getString(R.string.status_downloading, current, total, fileName)
            statusText != null -> statusText
            else -> getString(R.string.status_downloading_processing_fallback)
        }
        // A real Android progress bar in the notification shade, not just text -- same
        // current/max MainActivity's own bottom progress bar shows (see updateProgressBar()),
        // so the two stay in sync instead of the notification lagging behind on whichever phase
        // last happened to update it (asked for explicitly: found in practice, the notification
        // was still showing "opbouwen 2012/2012" well after decode itself had finished and the
        // run had moved on to later phases with no progress update of their own reaching it).
        val progressMax = intent?.getIntExtra(EXTRA_PROGRESS_MAX, -1) ?: -1
        val progressCurrent = intent?.getIntExtra(EXTRA_PROGRESS_CURRENT, -1) ?: -1
        // Whether a file is genuinely being fetched right now, as opposed to the decode/build
        // phase after it (found in practice, reported: the animated download icon stayed up
        // through the whole build too, though nothing was downloading by then).
        val isDownloading = current >= 0 && total >= 0 && fileName != null
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            // The animated system "download in progress" icon only while a file is genuinely being
            // fetched; the build button's own static icon (ic_refresh_24, see MainActivity) once
            // that is done and decode/build is running instead -- found in practice: the download
            // icon staying up through the whole build too read as still downloading when it wasn't.
            // The other three notifications this class posts (interrupted, not found, completed)
            // are never in progress when shown, so they use the static stat_sys_download_done.
            .setSmallIcon(if (isDownloading) android.R.drawable.stat_sys_download else R.drawable.ic_refresh_24)
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
     *  - Nothing running at all: cancels both notification ids and stops, same as the ✕ button's
     *    own closeAppAndCancelSync() -- no "tap to reopen" notification either (dropped, asked for
     *    explicitly: found in practice, a leftover notification after swiping the app away read as
     *    "still not actually closed", the exact same complaint that already got the ✕ button its
     *    own no-notification treatment; a leftover "W2K-2 niet gevonden"/completion notification
     *    from before the app was closed is cleared here too, not just a stale reopen one).
     *  - Something running, but not currently uploading (discovery, download, decode/build):
     *    safe to interrupt right here -- a download resumes cleanly next run over HTTP Range
     *    (see w2k2_download.py), and decode/build just re-runs from wherever it was, backed by
     *    the sample cache -- so there's nothing to gain by continuing in the background once the
     *    owner has already left. postInterruptedNotification() below stands in for the
     *    "Voltooid" completion notification a run that got to finish would otherwise end with.
     *  - Currently uploading (SyncState.uploading, see its own doc comment): left running,
     *    same as ever -- not itself safely resumable mid-request the same way, and comparatively
     *    fast anyway. Finishes and stops itself via runSync()'s/runPublish()'s own finally block,
     *    same as a run that was never interrupted at all. The ongoing notification's text is
     *    overwritten here to say so explicitly (asked for explicitly, found in practice: closing
     *    the app right during an upload otherwise looked like the close had no effect at all --
     *    the notification just kept showing whatever upload-progress text it already had, with
     *    nothing acknowledging the close actually happened). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!SyncState.inProgress) {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            NotificationManagerCompat.from(this).cancel(REOPEN_NOTIFICATION_ID)
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
        } else {
            // Still foreground/ongoing -- not a replacement notification the way the other two
            // branches post one, just this same service's own notification updated in place, the
            // same way a live upload-progress update from MainActivity.uploadIfConfigured() would
            // (see onStartCommand()) -- calling that directly here reuses its exact notification-
            // building logic instead of duplicating it.
            onStartCommand(
                Intent(this, SyncNotificationService::class.java)
                    .putExtra(EXTRA_STATUS_TEXT, getString(R.string.notif_app_closing_after_upload)),
                0,
                0,
            )
        }
    }

    private fun createChannel() {
        // Once per process, not on every single onStartCommand() (a fresh download's first call, and
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
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_channel_description)
        }
        manager.createNotificationChannel(channel)
        channelCreated = true
    }

    companion object {
        // Process-wide, not an instance field -- a new Service instance is created each time it's
        // (re)started after fully stopping, but the channel itself, once created, persists at the
        // OS level regardless; re-checking per process avoids redoing that work needlessly on a
        // later download within the same still-running process, without wrongly skipping it after a
        // genuine process restart.
        private var channelCreated = false
        private const val OLD_CHANNEL_ID = "sync"
        const val CHANNEL_ID = "sync_v2"
        const val NOTIFICATION_ID = 1
        const val REOPEN_NOTIFICATION_ID = 2

        // Handled by the system's own NotificationManagerService, not this app's process -- found
        // in practice, asked for explicitly: MainActivity's own cleanup (onDestroy(),
        // autoStartSyncWithSettingsRetry()'s onCreate() guard) can only ever run while the app's
        // process is still alive, but this device's launcher "Alles sluiten" (close all recent
        // apps) kills the process directly, skipping every one of those in-process callbacks
        // entirely -- a stale "W2K-2 niet gevonden"/completion notification then had no way to be
        // cleared short of the owner tapping it themselves. setTimeoutAfter() below is a genuine
        // OS-level guarantee instead, the same mechanism other apps rely on for exactly this: it
        // survives the app's own process dying in any way at all, since the countdown and the
        // eventual cancel both live in the system server, not in this app's code.
        private const val STALE_NOTIFICATION_TIMEOUT_MS = 5 * 60 * 1000L
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_PROGRESS_MAX = "progress_max"

        /** Posted by onTaskRemoved() above in place of the ongoing download notification, when the
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
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_sync_interrupted_by_close))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setTimeoutAfter(STALE_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        /** Posted in place of the ongoing download notification when the *automatic*, on-launch
         * download attempt (see MainActivity.autoStartSyncWithSettingsRetry()) couldn't reach the
         * W2K-2 -- asked for explicitly: this is the routine, expected outcome of opening the app
         * away from the boat, not something worth a modal popup (see runSync()'s own isAutoStart
         * handling) or even a loud in-app status banner -- a plain log line covers the in-app
         * side, and this notification covers being told about it without having to be looking at
         * the app right when it happens. A manual tap on the download button still gets the
         * normal dialog instead, no notification of its own needed there since the owner is
         * already looking at the app. */
        fun postNotFoundNotification(context: Context, message: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            // Same action + flags as onStartCommand()'s own openAppIntent above, not a plain
            // FLAG_ACTIVITY_NEW_TASK launch -- found in practice, asked for explicitly: a plain
            // launch Intent stacks a brand new MainActivity instance on top even when one is
            // already alive, which re-runs onCreate() and its own autoStartSyncWithSettingsRetry()
            // call -- so tapping "W2K-2 niet gevonden" started a fresh download attempt that had no
            // better chance of finding the W2K-2 than the one that had just failed. SINGLE_TOP/
            // CLEAR_TOP instead bring an already-alive instance to the front via onNewIntent()
            // (a no-op beyond that, see its own doc comment), which just shows the app window.
            val reopenIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_TOGGLE_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(context, 0, reopenIntent, pendingIntentFlags)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setTimeoutAfter(STALE_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        /** Replaces the ongoing download notification with a final, dismissible one once a run
         * finishes successfully -- found in practice, asked for explicitly: stopService() alone
         * (MainActivity.runSync()'s own finally) just makes the notification disappear the
         * instant a download ends, with nothing left behind to say it actually finished (as
         * opposed to, say, having been swiped away mid-run) or when. Posted under the same
         * NOTIFICATION_ID as the ongoing one, so it replaces it in place rather than adding a
         * second entry.
         *
         * publishedUrl is non-null only when this run's own publish step actually succeeded (see
         * uploadIfConfigured() in MainActivity.kt) -- the "Bekijk live site" action only makes
         * sense to offer then, not after a download that only rebuilt the local logbook. */
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
            // Tapping the body opens the app (asked for explicitly) -- same ACTION_TOGGLE_FROM_
            // NOTIFICATION + SINGLE_TOP/CLEAR_TOP pattern as postNotFoundNotification() above,
            // which brings an already-alive MainActivity to the front via onNewIntent() rather
            // than starting a fresh one -- this used to deliberately have no content intent at
            // all, over a concern (from before that pattern existed here) that reopening would
            // look like it "hangs" by re-running the auto-start-on-launch flow from scratch;
            // SINGLE_TOP/CLEAR_TOP avoids that exact problem, same as it already does for the
            // other notifications in this class.
            val reopenIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_TOGGLE_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentIntent = PendingIntent.getActivity(context, 0, reopenIntent, pendingIntentFlags)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(resultText)
                // The app's own icon, not a download icon (asked for explicitly, found in
                // practice: shown even after a run that never downloaded anything, e.g. a local
                // rebuild) -- see ic_notification.xml's own doc comment.
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setTimeoutAfter(STALE_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(contentIntent)
            if (publishedUrl != null) {
                // A separate action, not the notification's own tap target -- tapping the body
                // still opens the app itself (consistent with every other notification here),
                // this is specifically for "go look at what just got published" without a detour
                // through the app first.
                val viewSiteIntent = Intent(Intent.ACTION_VIEW, Uri.parse(publishedUrl))
                val viewSitePendingIntent = PendingIntent.getActivity(context, 1, viewSiteIntent, pendingIntentFlags)
                builder.addAction(0, context.getString(R.string.notif_action_view_live_site), viewSitePendingIntent)
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }
}
