package com.ayuus.mysailinglogbook

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

/** What android_entry.probe_w2k2() reports back, during its call (see there). */
interface BootProbeListener {
    fun onProbeResult(found: Boolean, hasNewFiles: Boolean)
}

/**
 * The real work of the boat mode, without an Activity: looks for the W2K-2, downloads and builds a round
 * through the same Python entry points MainActivity's sync uses, and publishes through the same
 * [LogbookPublisher]. Every call runs on a thread of its own and answers through [reply]. What is
 * decided about the results (in harbour? left the boat?) is Python's (bootmode.py).
 */
class W2kBootExecutor(
    private val context: Context,
    private val settings: SettingsStore,
    /** Progress text for the service's notification ("Downloading: 3/12 ..."), from any thread. */
    private val onProgress: (String) -> Unit,
) : BootModeExecutor {
    @Volatile
    private var stopRequested = false
    private var lastProgressAt = 0L

    override fun cancel() {
        stopRequested = true
    }

    override fun probeW2k(reply: (found: Boolean, hasNewFiles: Boolean) -> Unit) {
        stopRequested = false
        Thread {
            var result = Probe(found = false, hasNewFiles = false)
            try {
                val subnet = HotspotDetector.detectSubnetPrefix()
                if (subnet != null && settings.isW2k2ConfigComplete) result = probe(subnet)
            } catch (e: Exception) {
                AppLog.post(context, "[warning] $e")
            }
            reply(result.found, result.hasNewFiles)
        }.start()
    }

    private class Probe(val found: Boolean, val hasNewFiles: Boolean)

    private fun probe(subnet: String): Probe {
        var result = Probe(found = false, hasNewFiles = false)
        val listener = object : BootProbeListener {
            override fun onProbeResult(found: Boolean, hasNewFiles: Boolean) {
                result = Probe(found, hasNewFiles)
            }
        }
        entry().callAttr(
            "probe_w2k2", settings.w2k2User, settings.w2k2Password, subnet,
            EblStorage.downloadDir(context).absolutePath, listener,
        )
        return result
    }

    override fun startRound(reply: (BootRoundResult) -> Unit) {
        stopRequested = false
        Thread {
            SyncState.bootBusy = true
            val result = try {
                runRound()
            } catch (e: Exception) {
                BootRoundResult.Failed(e.toString())
            } finally {
                SyncState.bootBusy = false
            }
            reply(result)
        }.start()
    }

    private fun runRound(): BootRoundResult {
        if (!settings.isW2k2ConfigComplete) {
            return BootRoundResult.Failed(context.getString(R.string.log_fill_w2k2_credentials))
        }
        val subnet = HotspotDetector.detectSubnetPrefix() ?: return BootRoundResult.NotFound
        val downloadDir = EblStorage.downloadDir(context)
        // Only a quick look first: the machine needs "not reachable" apart from "failed", and the sync
        // below reports a missing W2K-2 as just another error.
        if (!probe(subnet).found) return BootRoundResult.NotFound

        var outcome: SyncOutcome? = null
        var boatJson: String? = null
        val startedAt = System.currentTimeMillis()
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {
                val now = System.currentTimeMillis()
                if (now - lastProgressAt < PROGRESS_INTERVAL_MS && current < total) return
                lastProgressAt = now
                onProgress(context.getString(R.string.status_downloading, current, total, fileName))
            }

            override fun isCancelled(): Boolean = stopRequested

            override fun onLogLine(line: String) {
                AppLog.show(line)
                SyncProgress.notificationText(context, line)?.let(onProgress)
            }

            override fun onDownloadComplete() = EblStorage.indexForPc(context, downloadDir, startedAt)

            override fun onBoatState(boatStateJson: String?) {
                boatJson = boatStateJson
            }

            override fun onResult(ok: Boolean, error: String?, cancelled: Boolean, tripCount: Int, htmlPath: String?, downloadedCount: Int) {
                outcome = SyncOutcome(ok, error, cancelled, downloadedCount)
            }
        }
        entry().callAttr(
            "sync_from_w2k2",
            settings.w2k2User, settings.w2k2Password, subnet, downloadDir.absolutePath,
            File(context.filesDir, "logbook.html").absolutePath,
            File(context.filesDir, "sample_cache.pkl").absolutePath,
            settings.boatName, settings.mmsi, settings.callSign, controller, settings.minStopMinutes,
        )
        val done = outcome ?: return BootRoundResult.Failed(context.getString(R.string.error_no_result))
        return when {
            done.cancelled -> BootRoundResult.Failed(done.error ?: "cancelled")
            !done.ok -> BootRoundResult.Failed(done.error ?: context.getString(R.string.error_no_result))
            else -> BootRoundResult.Ok(maxOf(done.downloadedCount, 0), boatJson?.let { JSONObject(it) })
        }
    }

    override fun publish(reply: (ok: Boolean) -> Unit) {
        stopRequested = false
        Thread {
            SyncState.bootBusy = true
            val ok = try {
                val html = File(context.filesDir, "logbook.html")
                html.exists() && LogbookPublisher.publish(context, settings, html, ::logLine) { onProgress(it) }
            } catch (e: Exception) {
                logLine("[error] $e")
                false
            } finally {
                SyncState.bootBusy = false
            }
            reply(ok)
        }.start()
    }

    private fun logLine(line: String) = AppLog.post(context, line)

    private fun entry() = run {
        PythonStarter.ensureStarted(context)
        Python.getInstance().getModule("nmea2log.android_entry")
    }

    private class SyncOutcome(val ok: Boolean, val error: String?, val cancelled: Boolean, val downloadedCount: Int)

    private companion object {
        const val PROGRESS_INTERVAL_MS = 2000L
    }
}
