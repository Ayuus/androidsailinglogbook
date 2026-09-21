package com.ayuus.mysailinglogbook

import android.content.Context

/** Recognises the progress lines Python logs during a build, for the notification text of a run. */
object SyncProgress {
    // Matches log.py's "[info] ...decoded 42/1940 logfile(s) so far" (see _DECODE_PROGRESS_INTERVAL_S in
    // cli.py / android_entry.py) -- two groups (not one "42/1940" group) so the progress bar can set
    // current/max separately, without also having to re-parse the notification's own copy of this text.
    val decodeRegex = Regex("""decoded (\d+)/(\d+) logfile\(s\) so far""")

    // The trip-building phase after decode (the checkpoint log() calls of android_entry.py/cli.py/
    // tripbuilder.py) has no "current/total" numbers of its own the way the decode line does, just four
    // fixed checkpoints in an always-the-same order -- so the index of the one that last matched counts
    // as "step X of 4". Found in practice: without it the notification kept showing the stale
    // "opbouwen 2012/2012" all the way through this phase, which can run a real, non-trivial time on a
    // full season's worth of samples.
    val buildPhaseMarkers = listOf(
        Regex("""Building trips from \d+ GPS position\(s\)"""),
        Regex("""\d+ navigation samples merged, classifying trips"""),
        Regex("""\d+ run\(s\) classified, computing per-trip statistics"""),
        Regex("""\d+ trip\(s\) found, writing logbook"""),
    )

    /** The (translated) notification text a log line stands for, or null when it is not a progress line. */
    fun notificationText(context: Context, line: String): String? {
        val decode = decodeRegex.find(line)
        if (decode != null) {
            return context.getString(R.string.status_building_logbook, decode.groupValues[1].toInt(), decode.groupValues[2].toInt())
        }
        val step = buildPhaseMarkers.indexOfFirst { it.containsMatchIn(line) }
        if (step >= 0) return context.getString(R.string.status_building_trips, step + 1, buildPhaseMarkers.size)
        return null
    }
}
