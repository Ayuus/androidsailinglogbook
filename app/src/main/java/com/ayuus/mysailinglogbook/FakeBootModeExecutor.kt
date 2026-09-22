package com.ayuus.mysailinglogbook

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * The simulation the boat mode runs on until the real download/build/publish is wired in: it answers
 * after a second or two, without touching the network or any file. The script lets one see every kind
 * of step in the log: the W2K-2 is "found" at once, the first two rounds report a boat underway, the
 * third a boat in harbour (so the final round follows), and the very first publish fails once, to
 * show the retry.
 */
class FakeBootModeExecutor : BootModeExecutor {
    private val handler = Handler(Looper.getMainLooper())
    private var rounds = 0
    private var publishes = 0

    override fun probeW2k(reply: (found: Boolean, hasNewFiles: Boolean) -> Unit) {
        handler.postDelayed({ reply(true, true) }, 1000)
    }

    override fun startRound(reply: (BootRoundResult) -> Unit) {
        rounds++
        val boat = if (rounds < 3) underway() else inHarbour("simulated-stay-$rounds")
        // Mirrors W2kBootExecutor's own SyncState.bootBusy bracketing (asked for explicitly: without
        // this, bootModeBusy()'s own guard in MainActivity -- what a user's own download/build/publish
        // tap checks while a round is running -- could never be exercised via the simulation at all).
        SyncState.bootBusy = true
        handler.postDelayed({ SyncState.bootBusy = false; reply(BootRoundResult.Ok(3, boat)) }, 3000)
    }

    override fun publish(reply: (ok: Boolean) -> Unit) {
        publishes++
        SyncState.bootBusy = true
        handler.postDelayed({ SyncState.bootBusy = false; reply(publishes != 1) }, 1000)
    }

    override fun cancel() {}

    private fun underway() = JSONObject()
        .put("underway", true).put("stationary_since", JSONObject.NULL).put("stationary_seconds", JSONObject.NULL)
        .put("engine_running", true).put("engine_off_seconds", JSONObject.NULL)

    private fun inHarbour(stay: String) = JSONObject()
        .put("underway", false).put("stationary_since", stay).put("stationary_seconds", 45 * 60)
        .put("engine_running", false).put("engine_off_seconds", 20 * 60)
}
