package com.example.mysailinglogbook

/**
 * Process-wide (not tied to any single MainActivity instance) so it survives Activity
 * recreation, e.g. a screen rotation -- an instance field would reset to false on a new
 * MainActivity instance even though the background sync Thread from the previous instance is
 * still running, which would let a rotation start a second, fully concurrent sync the same way
 * a double-tap used to (see MainActivity.runSync()).
 */
object SyncState {
    @Volatile
    var inProgress = false

    @Volatile
    var cancelled = false

    /** True only while MainActivity.uploadIfConfigured() is actually running (the REST/SFTP
     * publish step). Lets SyncNotificationService.onTaskRemoved() tell "safe to interrupt" --
     * discovery, download (resumes cleanly next run over HTTP Range, see w2k2_download.py), or
     * decode/build (re-runs from wherever it was, backed by the sample cache) -- apart from "let
     * it finish": a publish already underway isn't itself safely resumable mid-request the same
     * way, and it's comparatively fast anyway (asked for explicitly). */
    @Volatile
    var uploading = false

    /** True only while MainActivity.updateSyncButtonAvailability()'s own background discovery
     * scan is running -- process-wide for the same reason inProgress is: guards against
     * onCreate() then onResume() firing in quick succession (a fresh launch does both) starting
     * two concurrent discover_w2k2_only() scans instead of the second one just leaving the first
     * to finish on its own. */
    @Volatile
    var discoverScanInProgress = false

    /** Whichever MainActivity instance is currently resumed and visible, or null when none is
     * (backgrounded, or briefly between an old instance pausing and a new one resuming). Set in
     * onResume(), cleared in onPause() -- see both there.
     *
     * A sync's background Thread is started by, and stays lexically bound to, whichever Activity
     * instance was current at the time (see runSync()/syncFromW2k2()) -- found in practice, a
     * real bug: once that specific instance stopped being the one on screen (recreated for any
     * reason -- reopening the app after it was backgrounded turned out to be enough, not just a
     * screen rotation, which android:configChanges on its own only covers), every further
     * progress/log/result update the Thread produced kept updating that old instance's own,
     * now-invisible views, while the new (visible) instance showed nothing further at all. The
     * UI-mutating helpers below (see MainActivity.updateProgressBar()/handleLogLine()/
     * showSyncResult()/etc.) all target THIS instance instead of their own receiver, so whichever
     * Activity the user is actually looking at keeps receiving live updates regardless of which
     * instance's Thread/SyncController callback happens to be the one producing them. */
    @Volatile
    var active: MainActivity? = null

    /** Whether the sync notification's underlying service is currently up and already in the
     * foreground state, and whether a startForegroundService() attempt was refused while not yet
     * foregrounded -- see MainActivity.startSyncNotification()'s own doc comment for the full
     * reasoning. Process-wide like the rest of this object, not per-instance: the notification's
     * own lifecycle belongs to the sync itself, not to whichever Activity instance happens to be
     * driving it at a given moment (found in practice, the same class of bug the `active`
     * property above fixes: a freshly (re)created instance's own fields always started out false,
     * making it retry startForegroundService() from scratch even though the service was already
     * legitimately running and foregrounded). */
    @Volatile
    var notificationForegrounded = false

    @Volatile
    var notificationStartFailed = false

    /** Last known progress snapshot -- updated alongside every live UI update a sync produces
     * (see MainActivity's UI-mutating helpers) -- lets a newly (re)created/resumed Activity
     * instance immediately restore the real, current state instead of showing a static
     * "Synchronisatie loopt al..." placeholder that never updates again (found in practice, a
     * real bug: that placeholder text even overwrote the notification's own live progress text
     * via onResume()'s restore logic, permanently freezing it at that point). */
    @Volatile
    var lastStatusText: String? = null

    /** The sync notification's own last content text -- kept separately from lastStatusText
     * above, since they aren't always the same string: the notification gets its own dedicated
     * "X/Y" progress text during decode/build (see MainActivity.handleLogLine()), while
     * lastStatusText itself stays on whatever static phase message it started that phase with.
     * Found in practice, a real bug: restoring the notification from lastStatusText pushed the
     * wrong text into it -- e.g. "Logboek opbouwen met bestaande gegevens..." (correct at the
     * very start of an offline build, but never updated again) showing up well into the decode/
     * build phase, where the notification itself had already moved on to real "X/Y" progress that
     * this variable didn't know about. */
    @Volatile
    var lastNotificationText: String? = null

    @Volatile
    var lastLogText: String = ""

    @Volatile
    var lastProgressPhase: String? = null

    @Volatile
    var lastProgressCurrent: Int = 0

    @Volatile
    var lastProgressTotal: Int = 0
}
