package com.ayuus.mysailinglogbook

import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Carries out the actions of the boat mode's state machine. All decisions live in Python
 * (nmea2log/bootmode.py, `step()`): this class only feeds events in as JSON and does what the returned
 * actions say -- the timer through [scheduleTick], the work through a [BootModeExecutor] -- so the same
 * controller works with the real work ([W2kBootExecutor]) and with a simulation ([FakeBootModeExecutor]).
 * It is owned by [BootModeService], which persists the state it reports.
 */

/** What a round produced, before it is turned into the JSON event the state machine expects. */
sealed class BootRoundResult {
    /** [boat]: BoatState.to_dict() as reported by the pipeline, or null when there was no new data. */
    class Ok(val downloadedCount: Int, val boat: JSONObject?) : BootRoundResult()
    data object NotFound : BootRoundResult()
    class Failed(val message: String) : BootRoundResult()
}

/** The things the state machine can ask for. Each reply may come from any thread. */
interface BootModeExecutor {
    fun probeW2k(reply: (found: Boolean, hasNewFiles: Boolean) -> Unit)
    fun startRound(reply: (BootRoundResult) -> Unit)
    fun publish(reply: (ok: Boolean) -> Unit)

    /** The mode is being stopped: end what is running as soon as it can be (the reply may still come). */
    fun cancel()
}

/**
 * A clock whose minutes can run faster, for the simulation: [now] is the "virtual" epoch time in
 * milliseconds the state machine sees, [realAt] the real epoch time at which a virtual time is reached.
 * Scale 1.0 is real time. [base] is where the clock started; it is persisted, so a restarted process
 * keeps the same virtual time line.
 */
class BootClock(private val base: Long, private val scale: Double = 1.0) {
    fun now(): Long = base + ((System.currentTimeMillis() - base) * scale).toLong()

    fun realAt(virtualAt: Long): Long = base + ((virtualAt - base) / scale).toLong()
}

class BootModeController(
    private val executor: BootModeExecutor,
    private val clock: BootClock,
    /** The current settings as the JSON BootModeConfig.from_dict() takes. */
    private val configJson: () -> String,
    /** Whether the user has a run of their own going, so a tick can be postponed. */
    private val userRunBusy: () -> Boolean,
    /** Arms the timer for a real epoch time, or cancels it for null. May be called from any thread. */
    private val scheduleTick: (realAt: Long?) -> Unit,
    /** A status the user should be told (main thread): [kind] is a bootmode.Status name, [nextAt] a virtual time or null. */
    private val onStatus: (kind: String, nextAt: Long?) -> Unit,
    /** The state machine's new state as JSON, to be persisted; after every step, on the worker thread. */
    private val onStateChanged: (stateJson: String) -> Unit,
    /** Called with true once the mode runs and false once it is off again (main thread). */
    private val onActiveChanged: (Boolean) -> Unit,
    /** The mode is over: the service can stop (main thread). */
    private val onStopService: () -> Unit,
    /** The persisted state of an earlier process; empty for a fresh start. */
    initialStateJson: String = "",
) {
    // One worker for everything that calls into Python: events are handled strictly one after another.
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var stateJson: String = initialStateJson
    private var wasActive = BootModeStateStore.phaseOf(initialStateJson) != "OFF"

    val isActive: Boolean get() = wasActive

    fun start() = post(event("start"))

    fun stop() {
        executor.cancel()
        post(event("stop"))
    }

    /** The timer fired (or the user asked for a round right now). */
    fun tick() = post(event("tick").put("busy", userRunBusy()))

    /** The process was restarted with persisted state: what was running died with it. */
    fun resume() = post(event("resume"))

    private fun event(type: String): JSONObject = JSONObject().put("type", type).put("at", clock.now())

    private fun post(event: JSONObject) {
        worker.execute { step(event) }
    }

    private fun step(event: JSONObject) {
        val module = Python.getInstance().getModule("nmea2log.bootmode")
        val result = JSONObject(module.callAttr("step", stateJson, configJson(), event.toString()).toString())
        val state = result.getJSONObject("state")
        stateJson = state.toString()
        onStateChanged(stateJson)
        val actions = result.getJSONArray("actions")
        for (i in 0 until actions.length()) perform(actions.getJSONObject(i))
        val active = state.getString("phase") != "OFF"
        if (active != wasActive) {
            wasActive = active
            handler.post { onActiveChanged(active) }
        }
    }

    private fun perform(action: JSONObject) {
        when (action.getString("type")) {
            "schedule_tick" -> scheduleTick(if (action.isNull("at")) null else clock.realAt(action.getLong("at")))
            "probe_w2k" -> executor.probeW2k { found, hasNewFiles ->
                post(event("probe").put("found", found).put("has_new_files", hasNewFiles))
            }
            "start_round" -> executor.startRound { result -> post(roundEvent(result)) }
            "publish" -> executor.publish { ok -> post(event("publish").put("ok", ok)) }
            "notify" -> {
                // next_at is a virtual time, like "schedule_tick"'s own "at" above -- must go through
                // clock.realAt() the same way, or the displayed time is nonsense once scale != 1.0
                // (found in practice, on the simulation: showed a real-looking "volgende ronde om
                // 23:07" that was actually the time-of-day of a date 16 days in the future).
                val nextAt = if (action.isNull("next_at")) null else clock.realAt(action.getLong("next_at"))
                val kind = action.getString("kind")
                handler.post { onStatus(kind, nextAt) }
            }
            "stop_service" -> handler.post { onStopService() }
        }
    }

    private fun roundEvent(result: BootRoundResult): JSONObject = when (result) {
        is BootRoundResult.Ok -> event("round").put("result", "ok")
            .put("downloaded_count", result.downloadedCount).put("boat", result.boat ?: JSONObject.NULL)
        BootRoundResult.NotFound -> event("round").put("result", "not_found")
        is BootRoundResult.Failed -> event("round").put("result", "failed").put("message", result.message)
    }
}
