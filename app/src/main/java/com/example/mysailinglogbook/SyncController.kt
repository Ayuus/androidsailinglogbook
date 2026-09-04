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
     * run_pipeline() (decode/build/write) starts -- lets the caller drop its own network-activity
     * indicator (MainActivity's foreground sync notification) once there's no more network I/O
     * left in this call, since decode/build is pure CPU. Asked for explicitly: a "dataSync"
     * foreground service has a real cumulative time budget on Android 15+ (see
     * MainActivity.runSync()), no reason to keep spending it once downloading is done. */
    fun onDownloadComplete()
}
