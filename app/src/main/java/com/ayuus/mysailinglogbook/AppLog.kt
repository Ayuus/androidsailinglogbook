package com.ayuus.mysailinglogbook

import android.content.Context
import java.io.File

/**
 * The app's own log lines (as opposed to Python's, which log.py stamps and writes itself): every line
 * starts with "YYYY-MM-DD HH:MM:SS", and lines made here also go to the same nmea2log.log file Python
 * appends to, so they are still there for later troubleshooting. Shared by MainActivity and the
 * boat-mode service, which has no Activity of its own.
 */
object AppLog {
    // What Python's log() puts in front of every line.
    private val TIMESTAMP_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} ")

    // Serializes appends of app-made lines to the log file.
    private val fileLock = Any()

    /** [line] with a timestamp in front of every one of its lines that has none yet (Python's have). */
    fun stamp(line: String): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        return line.split("\n").joinToString("\n") { part ->
            if (TIMESTAMP_REGEX.containsMatchIn(part)) part else "$now $part"
        }
    }

    /** Appends an already stamped line to nmea2log.log. Failures are ignored: a log line is never worth
     * breaking the app over. */
    fun appendToFile(context: Context, stampedLine: String) {
        try {
            synchronized(fileLock) {
                File(context.filesDir, "nmea2log.log").appendText(stampedLine + "\n", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // best effort only
        }
    }

    /**
     * A line from outside MainActivity: stamped, written to the log file, kept in the running log text
     * and shown by the visible Activity, if there is one.
     */
    fun post(context: Context, rawLine: String) {
        val line = stamp(rawLine)
        appendToFile(context, line)
        SyncState.lastLogText = if (SyncState.lastLogText.isEmpty()) line else "${SyncState.lastLogText}\n$line"
        SyncState.active?.refreshLogView()
    }
}
