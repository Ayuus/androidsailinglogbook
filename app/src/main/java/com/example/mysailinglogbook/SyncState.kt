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
}
