package com.ayuus.mysailinglogbook

import android.content.Context
import org.json.JSONObject

/**
 * What the boat mode has to remember when its process is killed and restarted by the system: the state
 * machine's state (JSON, see nmea2log/bootmode.py) and the base of its clock. Plain preferences: there
 * is nothing secret in here.
 */
class BootModeStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("boot_mode", Context.MODE_PRIVATE)

    var stateJson: String
        get() = prefs.getString(KEY_STATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STATE, value).apply()

    /** Epoch millis the mode's clock started counting from (see BootClock); 0 when the mode is off. */
    var clockBase: Long
        get() = prefs.getLong(KEY_CLOCK_BASE, 0L)
        set(value) = prefs.edit().putLong(KEY_CLOCK_BASE, value).apply()

    /** Developer switch (set with `adb shell run-as ... `): run the mode on the simulation, in 30x
     * speed, without touching the network. Off unless set. */
    val simulation: Boolean
        get() = prefs.getBoolean(KEY_SIMULATION, false)

    /** The user switched the mode off themselves (button or notification), so opening the app must not
     * start it again by itself until the hotspot has been off in between (see MainActivity). */
    var userStopped: Boolean
        get() = prefs.getBoolean(KEY_USER_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STOPPED, value).apply()

    /** Whether the persisted state says the mode is on. */
    val isActive: Boolean
        get() = phaseOf(stateJson) != "OFF"

    companion object {
        private const val KEY_STATE = "state"
        private const val KEY_CLOCK_BASE = "clock_base"
        private const val KEY_SIMULATION = "simulation"
        private const val KEY_USER_STOPPED = "user_stopped"

        fun phaseOf(stateJson: String): String =
            if (stateJson.isEmpty()) "OFF" else runCatching { JSONObject(stateJson).getString("phase") }.getOrDefault("OFF")
    }
}
