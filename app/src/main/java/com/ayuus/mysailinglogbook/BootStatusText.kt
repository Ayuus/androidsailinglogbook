package com.ayuus.mysailinglogbook

import android.content.Context

/** The (translated) text for a status of the boat mode -- a nmea2log.bootmode.Status name. */
object BootStatusText {
    /** null for a kind this version does not know. [nextAt] is a time to show where the text has one. */
    fun format(context: Context, kind: String, nextAt: Long?): String? {
        val resId = when (kind) {
            "SEARCHING" -> R.string.boat_status_searching
            "ROUND_STARTED" -> R.string.boat_status_round_started
            "ROUND_DONE" -> R.string.boat_status_round_done
            "ROUND_FAILED" -> R.string.boat_status_round_failed
            "W2K_NOT_FOUND_RETRY" -> R.string.boat_status_w2k2_not_found_retry
            "HARBOUR_FINAL" -> R.string.boat_status_harbour_final
            "LEFT_BOAT" -> R.string.boat_status_left_boat
            "LEFT_BOAT_NOTHING_TO_PUBLISH" -> R.string.boat_status_left_boat_nothing
            "WAITING_IN_PORT" -> R.string.boat_status_waiting_in_port
            "PUBLISH_STARTED" -> R.string.boat_status_publish_started
            "PUBLISH_OK" -> R.string.boat_status_publish_ok
            "PUBLISH_FAILED" -> R.string.boat_status_publish_failed
            "STOPPED" -> R.string.boat_status_stopped
            else -> return null
        }
        if (nextAt == null) return context.getString(resId)
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(nextAt))
        return context.getString(resId, time)
    }
}
