package com.ayuus.mysailinglogbook

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the boat mode alive while the app is in the background: a foreground service (type
 * connectedDevice -- the W2K-2 -- so Android 15's time budget for dataSync services does not apply)
 * with an ongoing notification, an alarm for each tick of the state machine, and a wake lock around
 * every probe/round/publish. The decisions are Python's (see [BootModeController]); this class only
 * owns what Android needs around it. The state machine's state is persisted ([BootModeStateStore]), so
 * a process that Android killed picks up where it was (START_STICKY: restarted with a null intent).
 */
class BootModeService : Service() {
    private var controller: BootModeController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var statusText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val store = BootModeStateStore(this)
        // Started with startForegroundService(): this has to follow within seconds, whatever else happens.
        createChannel()
        if (statusText.isEmpty()) statusText = getString(R.string.boat_notif_text_starting)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (action != ACTION_START && !store.isActive) {
            // Nothing to do (a stale alarm or a stop for a mode that is off already).
            finish()
            return START_NOT_STICKY
        }
        val fresh = controller == null
        val ctl = controller ?: createController(store).also { controller = it }
        when {
            action == ACTION_STOP -> ctl.stop()
            action == ACTION_START && !(fresh && store.isActive) -> {
                if (!store.isActive && store.simulation) AppLog.post(this, "[info] " + getString(R.string.boat_log_simulation))
                ctl.start()
            }
            // A new process with persisted state: whatever was running is gone, so redo it -- this
            // also covers the tick (or the restart) that got us here.
            fresh -> ctl.resume()
            action == ACTION_TICK || action == ACTION_NOW -> ctl.tick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createController(store: BootModeStateStore): BootModeController {
        val settings = SettingsStore(applicationContext)
        if (store.clockBase == 0L) store.clockBase = System.currentTimeMillis()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MySailingLogbook:BootMode")
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        val simulation = store.simulation
        val inner = if (simulation) FakeBootModeExecutor() else W2kBootExecutor(applicationContext, settings) { showProgress(it) }
        return BootModeController(
            executor = WakeLockedExecutor(inner, lock),
            clock = BootClock(store.clockBase, if (simulation) SIMULATION_SCALE else 1.0),
            configJson = { settings.bootModeConfigJson() },
            userRunBusy = { SyncState.inProgress },
            scheduleTick = { realAt -> scheduleAlarm(realAt) },
            onStatus = { kind, nextAt -> showStatus(kind, nextAt) },
            onStateChanged = { json -> store.stateJson = json },
            onActiveChanged = { SyncState.active?.updateBootButton() },
            onStopService = { finish() },
            initialStateJson = store.stateJson,
        )
    }

    /** The mode is over (or was never on): no more alarms, no notification, no service. */
    private fun finish() {
        scheduleAlarm(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** What the current round is doing ("Downloading: 3/12...", "Building the logbook: 40/300"): only the
     * notification's text, not the log, which has its own (Python's) lines. From any thread. */
    private fun showProgress(text: String) {
        statusText = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun showStatus(kind: String, nextAt: Long?) {
        val text = BootStatusText.format(this, kind, nextAt) ?: return
        AppLog.post(this, "[info] $text")
        statusText = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun scheduleAlarm(realAt: Long?) {
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = servicePendingIntent(this, ACTION_TICK, REQUEST_TICK)
        alarms.cancel(pending)
        if (realAt != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(realAt, System.currentTimeMillis()), pending)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.boat_notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.boat_notif_channel_description)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(statusText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
        .setSmallIcon(R.drawable.ic_schedule_filled_24)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(0, getString(R.string.boat_notif_action_now), servicePendingIntent(this, ACTION_NOW, REQUEST_NOW))
        .addAction(0, getString(R.string.boat_notif_action_stop), servicePendingIntent(this, ACTION_STOP, REQUEST_STOP))
        .build()

    /** Keeps the device awake while a probe, round or publish runs; released when its reply comes in. */
    private class WakeLockedExecutor(private val inner: BootModeExecutor, private val lock: PowerManager.WakeLock) : BootModeExecutor {
        // A safety net only: the lock is released as soon as the reply arrives.
        private fun hold() = lock.acquire(WAKE_LOCK_TIMEOUT_MS)

        private fun release() {
            if (lock.isHeld) lock.release()
        }

        override fun probeW2k(reply: (found: Boolean, hasNewFiles: Boolean) -> Unit) {
            hold()
            inner.probeW2k { found, hasNewFiles -> release(); reply(found, hasNewFiles) }
        }

        override fun startRound(reply: (BootRoundResult) -> Unit) {
            hold()
            inner.startRound { result -> release(); reply(result) }
        }

        override fun publish(reply: (ok: Boolean) -> Unit) {
            hold()
            inner.publish { ok -> release(); reply(ok) }
        }

        override fun cancel() = inner.cancel()
    }

    companion object {
        const val ACTION_START = "com.ayuus.mysailinglogbook.BOOT_START"
        const val ACTION_STOP = "com.ayuus.mysailinglogbook.BOOT_STOP"
        const val ACTION_TICK = "com.ayuus.mysailinglogbook.BOOT_TICK"
        const val ACTION_NOW = "com.ayuus.mysailinglogbook.BOOT_NOW"

        /** Sent when the app is opened while the mode is on: a service that was force-stopped (which also
         * cancels its alarms and is not restarted by Android) carries on from its persisted state; a
         * running one ignores it. */
        const val ACTION_RESUME = "com.ayuus.mysailinglogbook.BOOT_RESUME"

        private const val CHANNEL_ID = "boat_mode"
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_TICK = 10
        private const val REQUEST_NOW = 11
        private const val REQUEST_STOP = 12
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        // Minutes run this much faster while the simulation (BootModeStateStore.simulation) is in use.
        private const val SIMULATION_SCALE = 30.0

        /** Sends [action] to the service, starting it (in the foreground) when it is not running yet. */
        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, BootModeService::class.java).setAction(action))
        }

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, BootModeService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }
    }
}
