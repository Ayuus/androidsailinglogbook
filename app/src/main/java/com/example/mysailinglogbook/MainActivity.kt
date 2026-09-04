package com.example.mysailinglogbook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

/**
 * The full sync flow: hotspot detection, download from the W2K-2, decode+build the logbook, show
 * it in-app, then (only if the owner filled in the "Publiceren naar ayuus.com" settings) publish
 * it and back up new .ebl files over SFTP -- see SftpUploader. Runs automatically once per app
 * launch (see SyncState.autoStartedThisProcess) and via the manual "Nu synchroniseren" button.
 * WorkManager-based periodic background scheduling (no app open at all) is still a later step.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var webView: WebView
    private lateinit var syncButton: Button
    private lateinit var publishButton: Button
    private lateinit var settingsStore: SettingsStore

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this, sshj (used once Milestone B adds SFTP publishing) can't do Ed25519 key
        // operations on Android -- see spike 4 in docs/android-app-plan.md for the full story.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        settingsStore = SettingsStore(this)
        ensureNotificationPermission()

        val padding = (16 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            text = "Vul eerst je instellingen in (⚙️), tik dan op 🔄 om te synchroniseren."
            setPadding(0, padding, 0, padding)
            textSize = 14f
        }

        // Icon buttons (asked for explicitly): sync + publish top-left, settings top-right --
        // plain emoji as the button label, same approach as the language-switcher flags in
        // html_writer.py, so this doesn't need any drawable/vector icon assets of its own.
        syncButton = iconButton("🔄") { runSync() } // 🔄
        publishButton = iconButton("☁️") { runPublish() } // ☁️
        val settingsButton = iconButton("⚙️") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        } // ⚙️

        // Shows the exact same "[info]"/"[ok]"/"[skip]"/"[warning]" lines the desktop CLI prints
        // (see log.py's set_log_sink(), wired up in android_entry.py) -- asked for explicitly,
        // instead of only the app's own separately-worded status text (which stays too, in
        // statusView, as a quick-glance summary).
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, padding / 2, 0, padding / 2)
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true // the logbook's own trip map (Leaflet) needs this
        }

        // Sync + publish icons top-left, settings top-right (asked for explicitly) -- a weight-1
        // empty spacer pushes settingsButton to the far right without needing a second, nested
        // layout.
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(syncButton)
            addView(publishButton)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(settingsButton)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(buttonRow)
            addView(statusView)
            addView(logScroll)
            addView(webView)
        }
        setLogExpanded(true) // nothing in the WebView yet, so the log might as well use the space
        // Edge-to-edge drawing means the top of the layout would otherwise sit under the status
        // bar (found in practice during spike 2) -- pad by the system bars' own inset instead of
        // a fixed guess.
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(layout)

        // Auto-start once per process, not on every onCreate() (a screen rotation re-runs
        // onCreate() without a new process -- see SyncState.autoStartedThisProcess) -- asked for
        // explicitly: opening the app should try to reach the W2K-2 right away instead of waiting
        // for a manual tap, with the not-found/failure case still handled the same way a manual
        // attempt's failure is (see showRetryOrCloseDialog()).
        if (!SyncState.autoStartedThisProcess && settingsStore.isW2k2ConfigComplete) {
            SyncState.autoStartedThisProcess = true
            runSync()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // isChangingConfigurations is true for a rotation (this Activity instance is about to be
        // recreated immediately) -- only a genuine close (finish(), or the task being swiped away
        // from Recents) should stop an in-progress sync.
        if (!isChangingConfigurations) {
            SyncState.cancelled = true
            // Stopped here directly, not left to the background Thread's own finally block (see
            // runSync()) -- that block only runs once the Python side notices isCancelled() and
            // unwinds, which can take a while if it's currently blocked inside a single blocking
            // HTTP call (login, folder listing, a whole file's download) with no cancellation
            // check until that call returns (found in practice: the notification stayed on screen
            // for a while after closing the app, asked for explicitly to fix). Stopping the
            // service immediately removes the notification right away regardless of how long the
            // sync itself takes to actually wind down in the background; the Thread's own
            // stopService() call later is a harmless no-op against an already-stopped service.
            stopService(Intent(this, SyncNotificationService::class.java))
        }
    }

    private fun runSync() {
        if (SyncState.inProgress) return
        if (!settingsStore.isW2k2ConfigComplete) {
            statusView.text = "Vul eerst de W2K-2 gebruikersnaam en het wachtwoord in via Instellingen."
            return
        }

        SyncState.inProgress = true
        SyncState.cancelled = false
        syncButton.isEnabled = false
        publishButton.isEnabled = false
        statusView.text = "Hotspot controleren..."
        logView.text = ""
        setLogExpanded(true)

        Thread {
            val subnetPrefix = HotspotDetector.detectSubnetPrefix()
            if (subnetPrefix == null) {
                runOnUiThread {
                    showRetryOrCloseDialog(
                        "Hotspot staat uit (of de W2K-2 is er niet mee verbonden). Zet 'm aan om te " +
                            "synchroniseren."
                    )
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    publishButton.isEnabled = true
                }
                return@Thread
            }

            val initialStatusText = "Bestandenlijst ophalen (subnet ${subnetPrefix}0/24)..."
            runOnUiThread {
                statusView.text = initialStatusText
                val startIntent = Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, initialStatusText)
                startSyncNotification(startIntent)
            }
            try {
                val result = syncFromW2k2(subnetPrefix)
                runOnUiThread { showSyncResult(result) }
                // After showing the logbook, not before -- an upload problem (misconfigured
                // credentials, server unreachable) shouldn't hide the fact that the download and
                // decode themselves already succeeded. Still on this same background Thread, not
                // re-dispatched: SftpUploader's calls are blocking network I/O same as the
                // download itself was.
                if (result.ok && result.htmlPath != null) {
                    uploadIfConfigured(result.htmlPath)
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "Onverwachte fout tijdens synchroniseren: $e" }
            } finally {
                runOnUiThread {
                    stopService(Intent(this, SyncNotificationService::class.java))
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    publishButton.isEnabled = true
                }
            }
        }.start()
    }

    /** Manual re-publish (the ☁️ icon): re-uploads the *already-built* local logbook.html (and
     * backs up any not-yet-backed-up .ebl files) without running a new sync first -- for when a
     * sync already succeeded but the upload step itself failed (wrong SFTP password just fixed in
     * Instellingen, server was briefly unreachable, ...) and re-fetching from the W2K-2 again
     * would be pointless. Shares SyncState.inProgress with runSync() so this can't run at the same
     * time as a sync's own automatic upload at the end of it. */
    private fun runPublish() {
        if (SyncState.inProgress) return
        if (!settingsStore.isSftpConfigComplete) {
            statusView.text = "Vul eerst de publiceer-instellingen (SFTP) in via Instellingen."
            return
        }
        val htmlFile = File(filesDir, "logbook.html")
        if (!htmlFile.exists()) {
            statusView.text = "Nog geen logboek om te publiceren -- synchroniseer eerst."
            return
        }

        SyncState.inProgress = true
        syncButton.isEnabled = false
        publishButton.isEnabled = false
        statusView.text = "Publiceren naar ayuus.com..."

        Thread {
            try {
                uploadIfConfigured(htmlFile.absolutePath)
            } finally {
                runOnUiThread {
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    publishButton.isEnabled = true
                }
            }
        }.start()
    }

    private data class SyncResult(
        val ok: Boolean,
        val cancelled: Boolean,
        val error: String?,
        val tripCount: Int?,
        val htmlPath: String?,
        val downloadedCount: Int?,
    )

    /** Runs android_entry.sync_from_w2k2() via Chaquopy -- one Python call does discovery,
     * download, and the decode/build/write pipeline (see android_entry.py for why this isn't
     * split into several separate Chaquopy calls). Must be called off the main thread: Python
     * startup plus decoding real multi-MB .ebl files easily takes several seconds (found in
     * practice during spike 3 -- ANR otherwise). */
    private fun syncFromW2k2(subnetPrefix: String): SyncResult {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        val androidEntry = py.getModule("nmea2000processor.android_entry")

        val downloadDir = File(filesDir, "Actisense")
        val outputHtmlPath = File(filesDir, "logbook.html")
        val sampleCachePath = File(filesDir, "sample_cache.pkl")

        // Called by Python between files (android_entry.py): report() lets the status text and
        // notification show real "current/total" progress instead of one static message for
        // however long a sync takes (a first-ever sync fetches the whole historical archive,
        // easily several GB, found in practice); isCancelled() lets a sync stop cleanly once
        // onDestroy() sets the cancelled flag, instead of continuing after the app is closed.
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {
                runOnUiThread { statusView.text = "Downloaden: $current/$total ($fileName)" }
                // Also visible from the notification shade while the app isn't on screen -- see
                // SyncNotificationService.onStartCommand(), which updates its existing
                // notification in place rather than posting a new one each time.
                val progressIntent = Intent(this@MainActivity, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_CURRENT, current)
                    .putExtra(SyncNotificationService.EXTRA_TOTAL, total)
                    .putExtra(SyncNotificationService.EXTRA_FILE_NAME, fileName)
                startSyncNotification(progressIntent)
            }

            override fun isCancelled(): Boolean = SyncState.cancelled

            override fun onLogLine(line: String) {
                runOnUiThread {
                    logView.append(if (logView.text.isEmpty()) line else "\n$line")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }

            override fun onDownloadComplete() {
                // Decode/build below is pure CPU, no more network I/O left in this call -- drop
                // the foreground notification now instead of keeping it up (and spending its
                // "dataSync" time budget, see SyncController.kt) until the whole call returns.
                runOnUiThread { stopService(Intent(this@MainActivity, SyncNotificationService::class.java)) }
            }
        }

        val result = androidEntry.callAttr(
            "sync_from_w2k2",
            settingsStore.w2k2User,
            settingsStore.w2k2Password,
            subnetPrefix,
            downloadDir.absolutePath,
            outputHtmlPath.absolutePath,
            sampleCachePath.absolutePath,
            settingsStore.boatName,
            settingsStore.mmsi,
            settingsStore.callSign,
            controller,
        )

        val ok = result.get("ok")?.toBoolean() ?: false
        return SyncResult(
            ok = ok,
            cancelled = result.get("cancelled")?.toBoolean() ?: false,
            error = result.get("error")?.toString(),
            tripCount = if (ok) result.get("trip_count")?.toInt() else null,
            htmlPath = if (ok) result.get("html_path")?.toString() else null,
            downloadedCount = if (ok) result.get("downloaded_count")?.toInt() else null,
        )
    }

    /** The log view (see logView/onLogLine) starts out filling the space the WebView would
     * otherwise waste while there's nothing to show it -- once a logbook actually loads, the log
     * shrinks back down to a small scrollable strip and the WebView takes the space instead. */
    private fun setLogExpanded(expanded: Boolean) {
        if (expanded) {
            logScroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f)
        } else {
            val collapsedHeight = (150 * resources.displayMetrics.density).toInt()
            logScroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, collapsedHeight)
            webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    }

    private fun showSyncResult(result: SyncResult) {
        if (result.ok && result.htmlPath != null) {
            statusView.text = "Klaar: ${result.tripCount} reis(en), ${result.downloadedCount} bestand(en) gedownload."
            setLogExpanded(false)
            webView.loadUrl("file://${result.htmlPath}")
        } else if (result.cancelled) {
            // The app was closed mid-sync (see onDestroy()) -- by the time this runs the Activity
            // is normally already gone, so this mostly matters when cancellation raced a rotation
            // (config change) instead. The next "Nu synchroniseren" simply resumes where it left
            // off, no special handling needed (see _needs_download() in w2k2_download.py).
            statusView.text = "Synchronisatie gestopt. Volgende keer wordt verdergegaan waar het gebleven was."
        } else {
            // Covers every non-cancelled failure, including the download never reaching a usable
            // state at all (e.g. the W2K-2/host becoming unreachable partway through) -- Python's
            // own android_entry.py never calls run_pipeline() in that case (see
            // sync_from_w2k2()'s except clauses), so there's no stale/partial logbook.html to
            // accidentally show; this dialog is the only thing the user sees (asked for
            // explicitly).
            statusView.text = "Fout: ${result.error ?: "onbekende fout"}"
            showRetryOrCloseDialog("Fout: ${result.error ?: "onbekende fout"}")
        }
    }

    /** Two-button dialog for "the app just tried to reach the W2K-2 (on launch or on retry) and
     * that didn't work" -- asked for explicitly, covers both the hotspot-not-detected case and any
     * other sync failure uniformly, instead of leaving the user looking at a status line with no
     * obvious next step. Not cancelable by tapping outside/back -- one of the two buttons is the
     * only way out.
     *
     * The non-closing button doesn't retry immediately -- an immediate retry while genuinely out
     * of range of the W2K-2 (asked to expect this regularly, e.g. sailing away from the boat)
     * would just fail again right away and show this exact same dialog again, which reads as
     * nagging (asked for explicitly to fix). It waits quietly instead (see
     * waitForConnectionThenAsk()) and only asks again once the W2K-2 is actually reachable
     * again. */
    private fun showRetryOrCloseDialog(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Wachten op verbinding") { _, _ -> waitForConnectionThenAsk() }
            .setNegativeButton("App sluiten") { _, _ -> finishAffinity() }
            .show()
    }

    /** Polls in the background for the W2K-2 to become reachable again, then asks once -- not
     * repeatedly -- whether to resume or close. Checking the phone's own hotspot interface alone
     * (see HotspotDetector) isn't enough here: the hotspot itself can stay switched on the whole
     * time while the W2K-2 drops off it (found in practice: still out of range walking away from
     * the boat, hotspot untouched) -- so each tick re-attempts the real discover_w2k2() scan, the
     * same one a normal sync starts with. Shares SyncState.inProgress/cancelled with runSync() so
     * this counts as "busy" the same way an active sync does (blocks the sync/publish icons, stops
     * cleanly on onDestroy()) without needing its own separate state.
     *
     * Deliberately no foreground service for the wait itself (unlike an active sync): this can run
     * for hours while genuinely out of range, and a "dataSync" foreground service is capped at 6
     * cumulative hours per 24h on Android 15+ (this app targets 37) -- keeping one up the whole
     * wait risks exhausting that budget before a real sync even gets to use it. Accepted trade-off:
     * if Android eventually suspends this background Thread while the app sits unopened for a long
     * time, the wait silently stops -- reopening the app re-triggers detection anyway (see
     * onCreate()'s own auto-start), so nothing is lost, just delayed until next looked at. */
    private fun waitForConnectionThenAsk() {
        SyncState.inProgress = true
        SyncState.cancelled = false
        syncButton.isEnabled = false
        publishButton.isEnabled = false
        statusView.text = "Wachten op verbinding met de W2K-2..."

        Thread {
            while (!SyncState.cancelled) {
                val subnetPrefix = HotspotDetector.detectSubnetPrefix()
                val host = if (subnetPrefix != null) discoverW2k2(subnetPrefix) else null
                if (host != null) {
                    runOnUiThread {
                        SyncState.inProgress = false
                        syncButton.isEnabled = true
                        publishButton.isEnabled = true
                        if (SyncState.cancelled) return@runOnUiThread // app closed while waiting
                        AlertDialog.Builder(this)
                            .setMessage(
                                "Verbinding met de W2K-2 is hersteld. Doorgaan met downloaden, of de app sluiten?"
                            )
                            .setCancelable(false)
                            .setPositiveButton("Doorgaan") { _, _ -> runSync() }
                            .setNegativeButton("App sluiten") { _, _ -> finishAffinity() }
                            .show()
                    }
                    return@Thread
                }
                Thread.sleep(settingsStore.syncIntervalMinutes * 60_000L)
            }
        }.start()
    }

    /** Chaquopy call to w2k2_download.discover_w2k2() -- the base URL if the W2K-2 answers on
     * this subnet right now, or null if it doesn't. */
    private fun discoverW2k2(subnetPrefix: String): String? {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val module = Python.getInstance().getModule("nmea2000processor.w2k2_download")
        val result = module.callAttr("discover_w2k2", subnetPrefix) ?: return null
        val text = result.toString()
        return if (text == "None") null else text
    }

    /** Uploads the fresh logbook (always, if SFTP publish settings are filled in) and backs up
     * any not-yet-backed-up .ebl files (only if a back-up folder is configured, see
     * SettingsStore.sftpEblBackupRemotePath) -- called after a successful sync, still on its
     * background Thread. Runs at most once per sync; failures here are reported in statusView but
     * never hide the logbook that's already showing in the WebView by that point. */
    private fun uploadIfConfigured(htmlPath: String) {
        if (!settingsStore.isSftpConfigComplete) return
        runOnUiThread { statusView.append("\nUploaden naar ayuus.com...") }
        try {
            SftpUploader.uploadLogbookAtomic(settingsStore, File(htmlPath))
            runOnUiThread { statusView.append(" gelukt.") }
        } catch (e: SftpUploadError) {
            runOnUiThread { statusView.append(" mislukt: ${e.message}") }
            return // the .ebl backup uses the same connection settings -- no point trying those too
        }

        val eblBackupRemotePath = settingsStore.sftpEblBackupRemotePath
        if (eblBackupRemotePath.isBlank()) return
        val downloadDir = File(filesDir, "Actisense")
        val relativePaths = downloadDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ebl", ignoreCase = true) }
            .map { it.relativeTo(downloadDir).path.replace(File.separatorChar, '/') }
            .toList()
        if (relativePaths.isEmpty()) return
        runOnUiThread { statusView.append("\nBack-up van .ebl-bestanden...") }
        try {
            SftpUploader.backupEblFiles(settingsStore, downloadDir, relativePaths)
            runOnUiThread { statusView.append(" gelukt.") }
        } catch (e: SftpUploadError) {
            runOnUiThread { statusView.append(" mislukt: ${e.message}") }
        }
    }

    /** Starts/updates the sync notification, tolerating Android refusing to start it -- a
     * "dataSync" foreground service is capped at 6 cumulative hours per 24h on Android 15+ (this
     * app targets 37); once that budget is exhausted the system throws instead of starting it,
     * until either 24h roll around or the owner brings the app to the foreground themselves. The
     * sync/upload work itself still proceeds either way (this call only ever drives the visible
     * notification, nothing functional depends on it) -- just without a notification, rather than
     * the whole sync crashing over a UI nicety it couldn't get. Called from SyncController.report()
     * on Python's own background thread as well as from the main thread, so the fallback status
     * update is wrapped in runOnUiThread rather than assuming either. */
    private fun startSyncNotification(intent: Intent) {
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            runOnUiThread { statusView.text = "${statusView.text}\n(melding kon niet worden getoond: $e)" }
        }
    }

    /** A Button whose label is a single emoji, styled to read as a toolbar icon (larger glyph,
     * tight padding, no background) rather than a normal text button -- see the buttonRow comment
     * in onCreate() for why this is emoji rather than a drawable/vector asset. */
    private fun iconButton(emoji: String, onClick: () -> Unit): Button {
        val size = (16 * resources.displayMetrics.density).toInt()
        // Borderless + no minimum size: a plain Button here still carries the default Material
        // button chrome (background box, shadow/elevation, a fairly large minimum touch target)
        // even with just an emoji as its label, which reads as a boxed button rather than a
        // standalone icon (found in practice, asked for explicitly). A borderless circular ripple
        // background (the same one Android's own icon buttons use) plus dropping the minimum
        // width/height gets the plain-icon look without needing a drawable/vector asset of its own.
        val backgroundValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
        return Button(this).apply {
            text = emoji
            textSize = 20f
            setPadding(size, size / 2, size, size / 2)
            setBackgroundResource(backgroundValue.resourceId)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null // drops the default press elevation animation/shadow
            setOnClickListener { onClick() }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

}
