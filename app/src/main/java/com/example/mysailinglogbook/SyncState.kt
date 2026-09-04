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

    // Set once the very first time MainActivity.onCreate() auto-starts a sync after this process
    // launched -- also process-wide, same reasoning as inProgress above: a screen rotation creates
    // a new MainActivity instance (and re-runs onCreate()) without a new process, and without this
    // flag every rotation after the first sync had already finished would silently kick off
    // another one (found in practice).
    @Volatile
    var autoStartedThisProcess = false
}
