package com.example.mysailinglogbook

/**
 * Passed into android_entry.sync_from_w2k2() via Chaquopy -- Python calls these methods on this
 * object like normal Python methods (see MainActivity.syncFromW2k2()).
 *
 * report() is called after each file actually downloaded, so the UI can show real "current/total"
 * progress. isCancelled() is checked before listing each folder's files and before starting each
 * file's own download (not mid-transfer -- see download_file() in w2k2_download.py), so a sync
 * stops once MainActivity sets it to true in onDestroy(): closing the app should stop
 * downloading, not keep going in the background -- the next sync simply resumes with whatever
 * file wasn't complete yet (see download_file()'s own resume-from-incomplete-size logic), same as
 * any other interrupted run. A file already mid-download when the app closes finishes that
 * transfer before the next check takes effect. onLogLine() receives every line the Python side's
 * log() produces,
 * verbatim -- the exact same "[info]"/"[ok]"/"[skip]"/"[warning]" messages the desktop CLI shows
 * (see log.py's set_log_sink()), so the Android app doesn't need its own separately-maintained
 * set of status text for what's happening (asked for explicitly).
 */
interface SyncController {
    fun report(current: Int, total: Int, fileName: String)
    fun isCancelled(): Boolean
    fun onLogLine(line: String)

    /** Called exactly once, right after the last file's download attempt and before
     * run_pipeline() (decode/build/write) starts -- originally dropped MainActivity's own
     * foreground sync notification here (no more network I/O left in this call at that point,
     * since decode/build is pure CPU), but found in practice that just meant a second, separately
     * refusable startForegroundService() eligibility check once decode needed to show progress of
     * its own again (see MainActivity.startSyncNotification()'s own doc comment on the "dataSync"
     * foreground service's per-24h time budget) -- MainActivity now leaves the notification
     * running straight through and this is a no-op there. Kept as a real callback (not removed)
     * in case a future caller wants a "network I/O done" signal for something other than the
     * notification. */
    fun onDownloadComplete()

    /** Called exactly once, right before sync_from_w2k2()/build_from_local_files() returns, with
     * the same outcome as their return value's own "ok"/"error"/"cancelled"/"trip_count"/
     * "html_path"/"downloaded_count" dict entries -- but as plain primitive arguments to a real
     * method call, not fields read back out of the returned dict afterward.
     *
     * Found in practice, real and reproducible (seen after both a full multi-hour sync and an
     * early cancellation within seconds -- not tied to memory pressure the way it first looked):
     * reading result.get("ok") etc. on the PyObject callAttr() returns sometimes came back wrong
     * (ok as false with error as null, even though nmea2log.log and a freshly-written
     * logbook.html both confirmed the run had genuinely succeeded) -- while every *other*
     * Chaquopy interaction on that same call, all of them invoked *during* it rather than after
     * it returns (report(), onLogLine(), isCancelled()), never showed that problem all session.
     * Capturing the outcome here instead avoids relying on whatever goes wrong with the returned
     * PyObject's fields after the call has already unwound.
     *
     * tripCount/downloadedCount are -1 for Python's None (not applicable -- a failed run, or
     * build_from_local_files()'s own result, which never sets a downloaded count at all) rather
     * than a nullable Int, matching how android_entry.py's own _report_result() sends them. */
    fun onResult(
        ok: Boolean,
        error: String?,
        cancelled: Boolean,
        tripCount: Int,
        htmlPath: String?,
        downloadedCount: Int,
    )
}
