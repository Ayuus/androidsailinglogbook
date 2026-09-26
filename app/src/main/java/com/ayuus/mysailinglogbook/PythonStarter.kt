package com.ayuus.mysailinglogbook

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * The one place that starts the Chaquopy Python runtime -- every call site used to do its own
 * unsynchronized `if (!Python.isStarted()) Python.start(...)`, which is a real, reproducible
 * crash: on a fresh launch, more than one background thread can race to this (the W2K-2
 * discovery scan, boot mode's own resume probe, an auto-started sync, ...), each one seeing
 * isStarted() == false before either has actually started it, and the second Python.start() call
 * throws `IllegalStateException: Python already started` (found in practice, reported directly --
 * the app crashed right after launch, with that exact exception in the log on the rare run where
 * the crash was slow enough to actually see it logged first). @Synchronized makes the
 * check-and-start atomic across every caller instead of each one checking independently.
 */
object PythonStarter {
    @Synchronized
    fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }
}
