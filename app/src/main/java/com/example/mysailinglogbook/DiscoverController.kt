package com.example.mysailinglogbook

/** Passed into android_entry.discover_w2k2_only() via Chaquopy -- Python calls this method like
 * a normal Python method (see MainActivity.updateSyncButtonAvailability()). A callback, not a
 * return value this call's caller reads afterward, for the same reason SyncController.onResult()
 * is one: reading a returned PyObject's fields after a Chaquopy call has already returned has
 * been unreliable in this app (see that interface's own doc comment), while calling into Kotlin
 * *during* the call has not. */
interface DiscoverController {
    fun onDiscoverResult(found: Boolean)
}
