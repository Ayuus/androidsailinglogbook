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
 * launch (see onCreate()'s own savedInstanceState check) and via the manual "Nu synchroniseren" button.
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

        // Found in practice: the app sometimes closed immediately on launch with no visible error
        // at all -- SettingsStore's EncryptedSharedPreferences relies on the Android Keystore,
        // which can transiently fail (e.g. right after boot, or in certain lock states). Without a
        // real crash log yet to pin down the exact failure, this at least turns a silent crash
        // (nothing ever got past this point before) into a visible, retryable message instead.
        val store = try {
            SettingsStore(this)
        } catch (e: Exception) {
            setContentView(
                TextView(this).apply {
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    text = "Instellingen konden niet worden geladen:\n$e\n\nProbeer de app opnieuw te openen."
                    setPadding(padding, padding, padding, padding)
                }
            )
            return
        }
        settingsStore = store
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

        // Auto-start on a genuinely fresh launch, not on every onCreate() -- asked for explicitly:
        // opening the app should try to reach the W2K-2 right away instead of waiting for a manual
        // tap, with the not-found/failure case still handled the same way a manual attempt's
        // failure is (see showRetryOrCloseDialog()). savedInstanceState == null is what actually
        // distinguishes the two cases: non-null for a screen rotation (this exact session/Activity
        // being restored, must not silently kick off a second sync on top of -- or right after --
        // whatever the first one already did), null for a real fresh start.
        //
        // A previous version instead gated this on a custom "already auto-started once this
        // process" flag (SyncState.autoStartedThisProcess), which assumed a "restart" always means
        // a new OS process -- found in practice not reliably true: closing and reopening the app
        // (e.g. to un-stick a frozen sync, see onDestroy()'s own notes on OS-level freezes) can
        // leave the same process alive underneath a brand new Activity, which left that flag stuck
        // "already done" and silently disabled auto-start until the user noticed and tapped 🔄
        // themselves.
        if (savedInstanceState == null) {
            if (settingsStore.isW2k2ConfigComplete) {
                runSync()
            } else {
                // Found in practice (not fully understood yet, no crash log to pin it down):
                // right after the OS kills and relaunches this app's process (e.g. after sitting
                // backgrounded for a while), isW2k2ConfigComplete has sometimes read false here
                // even though the real settings were still saved fine -- EncryptedSharedPreferences
                // relies on the Android Keystore, which could plausibly still be settling right
                // after a cold process start. One short, silent re-check before concluding
                // settings are genuinely empty and showing that message -- cheap, and either
                // papers over exactly that race or costs nothing if this was a real empty-settings
                // case after all (the message still shows, just fractionally later).
                android.os.Handler(mainLooper).postDelayed({
                    if (settingsStore.isW2k2ConfigComplete) {
                        runSync()
                    }
                }, 300L)
            }
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
        val initialStatusText = "Hotspot controleren..."
        statusView.text = initialStatusText
        logView.text = ""
        setLogExpanded(true)
        // Shown immediately, before hotspot detection even starts -- not only once the first
        // "Downloaden: 1/X" progress update arrives (asked for explicitly: hotspot detection and
        // then listing every folder that still needs checking can itself take a real moment on a
        // big archive, during which nothing was visible outside the app at all before this).
        val startIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, initialStatusText)
        startSyncNotification(startIntent)

        Thread {
            val subnetPrefix = HotspotDetector.detectSubnetPrefix()
            if (subnetPrefix == null) {
                runOnUiThread {
                    stopService(Intent(this, SyncNotificationService::class.java))
                    showOfflineOrCloseDialog(
                        "Hotspot staat uit (of de W2K-2 is er niet mee verbonden). Zet 'm aan om te " +
                            "synchroniseren."
                    )
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    publishButton.isEnabled = true
                }
                return@Thread
            }

            val listingStatusText = "Bestandenlijst ophalen (subnet ${subnetPrefix}0/24)..."
            runOnUiThread {
                statusView.text = listingStatusText
                val listingIntent = Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, listingStatusText)
                startSyncNotification(listingIntent)
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
                // A "[warning]" line (a failed attempt being retried, e.g. connection lost) means
                // report()'s own "current/total" notification text is about to sit frozen and
                // stale for a while -- found in practice: half an hour out of range looked from
                // the notification alone like the app was just stuck on file 26/625, no
                // indication anything had actually gone wrong. A short, plain message here, not
                // the raw warning text itself (asked for explicitly) -- that's already visible
                // verbatim in the in-app log for anyone who wants the technical detail (which
                // host/file, the exact OS error, which retry attempt).
                // contains(), not startsWith(): log.py's log() always prepends a "YYYY-MM-DD
                // HH:MM:SS " timestamp before handing the line to this sink, so it never actually
                // starts with the "[warning]" tag itself (found in practice: this check never
                // matched at all, so the notification silently never updated during a real
                // connection-loss test).
                if (line.contains("[warning]")) {
                    val warningIntent = Intent(this@MainActivity, SyncNotificationService::class.java)
                        .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, "Verbinding verloren, opnieuw proberen...")
                    startSyncNotification(warningIntent)
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
            // downloadedCount is null for runOfflineBuild()'s own result (no download happened
            // that run at all) -- omit that clause entirely rather than showing a literal "null".
            statusView.text = if (result.downloadedCount != null) {
                "Klaar: ${result.tripCount} reis(en), ${result.downloadedCount} bestand(en) gedownload."
            } else {
                "Klaar: ${result.tripCount} reis(en) (bestaande gegevens, niet opnieuw gedownload)."
            }
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
            showOfflineOrCloseDialog("Fout: ${result.error ?: "onbekende fout"}")
        }
    }

    /** Two-button dialog for "the app just tried to reach the W2K-2 (on launch or on retry) and
     * that didn't work" -- asked for explicitly, covers both the hotspot-not-detected case and any
     * other sync failure uniformly, instead of leaving the user looking at a status line with no
     * obvious next step. Not cancelable by tapping outside/back -- one of the two buttons is the
     * only way out.
     *
     * No "wait for connection" option (an earlier version had one, polling in the background) --
     * found in practice not actually useful, closing the app and trying again later reads better
     * than a long silent wait with an uncertain outcome. Offers building/showing the logbook from
     * whatever's already been downloaded instead, since that's genuinely useful precisely when the
     * W2K-2 can't be reached right now (asked for explicitly), and closing the app remains always
     * available as the simple way out. */
    private fun showOfflineOrCloseDialog(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Logboek tonen met bestaande data") { _, _ -> runOfflineBuild() }
            .setNegativeButton("App sluiten") { _, _ -> finishAffinity() }
            .show()
    }

    /** Builds and shows the logbook from whatever .ebl files are already sitting in filesDir --
     * no W2K-2 connection needed at all, for exactly the case that's otherwise a dead end: the
     * device can't be reached right now, but there's still real (if possibly not fully current)
     * data already on the phone worth seeing (asked for explicitly). Publishes it too, same as a
     * normal sync's own auto-publish, if the SFTP settings are filled in. fetch_failed=true marks
     * the page's own "Laatst bijgewerkt" timestamp in red -- this run didn't actually fetch
     * anything new, so the shown data may already be stale. */
    private fun runOfflineBuild() {
        if (SyncState.inProgress) return
        SyncState.inProgress = true
        syncButton.isEnabled = false
        publishButton.isEnabled = false
        statusView.text = "Logboek opbouwen met bestaande gegevens..."
        logView.text = ""
        setLogExpanded(true)

        Thread {
            try {
                val result = buildFromLocalFiles()
                runOnUiThread { showSyncResult(result) }
                if (result.ok && result.htmlPath != null) {
                    uploadIfConfigured(result.htmlPath)
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "Onverwachte fout: $e" }
            } finally {
                runOnUiThread {
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    publishButton.isEnabled = true
                }
            }
        }.start()
    }

    /** Chaquopy call to android_entry.run_pipeline() -- decode/build/write only, no discovery or
     * download, over every .ebl file already present under filesDir/Actisense. */
    private fun buildFromLocalFiles(): SyncResult {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val androidEntry = Python.getInstance().getModule("nmea2000processor.android_entry")

        val downloadDir = File(filesDir, "Actisense")
        val outputHtmlPath = File(filesDir, "logbook.html")
        val sampleCachePath = File(filesDir, "sample_cache.pkl")
        val eblPaths = downloadDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ebl", ignoreCase = true) }
            .map { it.absolutePath }
            .toList()

        val result = androidEntry.callAttr(
            "run_pipeline",
            eblPaths,
            outputHtmlPath.absolutePath,
            sampleCachePath.absolutePath,
            settingsStore.boatName,
            settingsStore.mmsi,
            settingsStore.callSign,
            true, // fetch_failed
        )

        val ok = result.get("ok")?.toBoolean() ?: false
        return SyncResult(
            ok = ok,
            cancelled = false,
            error = result.get("error")?.toString(),
            tripCount = if (ok) result.get("trip_count")?.toInt() else null,
            htmlPath = if (ok) result.get("html_path")?.toString() else null,
            downloadedCount = null, // no download happened this run
        )
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
