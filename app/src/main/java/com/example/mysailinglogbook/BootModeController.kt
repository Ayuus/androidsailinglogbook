package com.example.mysailinglogbook

import android.os.Handler
import android.os.Looper
import com.chaquo.python.Python
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Carries out the actions of the boat mode's state machine. All decisions live in Python
 * (nmea2log/bootmode.py, `step()`): this class only owns the timer, feeds events in as JSON, and does
 * what the returned actions say through a [BootModeExecutor] -- so the same controller works with the
 * simulation used for now ([FakeBootModeExecutor]) and the real download/build/publish later.
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
}

/**
 * A clock whose minutes can run faster, for the simulation: [now] is the "virtual" epoch time in
 * milliseconds the state machine sees, [realDelayMs] how long to really wait for a virtual time.
 * Scale 1.0 is real time.
 */
class BootClock(private val scale: Double = 1.0) {
    private val base = System.currentTimeMillis()

    fun now(): Long = base + ((System.currentTimeMillis() - base) * scale).toLong()

    fun realDelayMs(virtualAt: Long): Long = ((virtualAt - now()) / scale).toLong().coerceAtLeast(0L)
}

/** Process-wide, like SyncState: the controller must outlive any one Activity instance. */
object BootModeRuntime {
    @Volatile
    var controller: BootModeController? = null
}

class BootModeController(
    private val executor: BootModeExecutor,
    private val clock: BootClock,
    /** The current settings as the JSON BootModeConfig.from_dict() takes. */
    private val configJson: () -> String,
    /** Whether the user has a run of their own going, so a tick can be postponed. */
    private val userRunBusy: () -> Boolean,
    /** A status the user should be told: [kind] is a bootmode.Status name, [nextAt] a virtual time or null. */
    private val onStatus: (kind: String, nextAt: Long?) -> Unit,
    /** Called with true once the mode runs and false once it is off again. */
    private val onActiveChanged: (Boolean) -> Unit,
) {
    // One worker for everything that calls into Python: events are handled strictly one after another.
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var stateJson: String = ""
    private var wasActive = false

    private val tickRunnable = Runnable { post(event("tick").put("busy", userRunBusy())) }

    val isActive: Boolean get() = wasActive

    fun start() = post(event("start"))

    fun stop() = post(event("stop"))

    private fun event(type: String): JSONObject = JSONObject().put("type", type).put("at", clock.now())

    private fun post(event: JSONObject) {
        worker.execute { step(event) }
    }

    private fun step(event: JSONObject) {
        val module = Python.getInstance().getModule("nmea2log.bootmode")
        val result = JSONObject(module.callAttr("step", stateJson, configJson(), event.toString()).toString())
        val state = result.getJSONObject("state")
        stateJson = state.toString()
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
            "schedule_tick" -> {
                handler.removeCallbacks(tickRunnable)
                if (!action.isNull("at")) handler.postDelayed(tickRunnable, clock.realDelayMs(action.getLong("at")))
            }
            "probe_w2k" -> executor.probeW2k { found, hasNewFiles ->
                post(event("probe").put("found", found).put("has_new_files", hasNewFiles))
            }
            "start_round" -> executor.startRound { result -> post(roundEvent(result)) }
            "publish" -> executor.publish { ok -> post(event("publish").put("ok", ok)) }
            "notify" -> {
                val nextAt = if (action.isNull("next_at")) null else action.getLong("next_at")
                val kind = action.getString("kind")
                handler.post { onStatus(kind, nextAt) }
            }
            "stop_service" -> handler.removeCallbacks(tickRunnable)
        }
    }

    private fun roundEvent(result: BootRoundResult): JSONObject = when (result) {
        is BootRoundResult.Ok -> event("round").put("result", "ok")
            .put("downloaded_count", result.downloadedCount).put("boat", result.boat ?: JSONObject.NULL)
        BootRoundResult.NotFound -> event("round").put("result", "not_found")
        is BootRoundResult.Failed -> event("round").put("result", "failed").put("message", result.message)
    }
}
