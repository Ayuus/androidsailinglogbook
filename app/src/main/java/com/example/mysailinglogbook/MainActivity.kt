package com.example.mysailinglogbook

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.ConsoleMessage
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

/**
 * The full sync flow: hotspot detection, download from the W2K-2, decode+build the logbook, show
 * it in-app, then (only if the owner filled in the "Publiceren naar ayuus.com" settings) publish
 * it via REST or SFTP -- see RestUploader/SftpUploader. Runs automatically once per app
 * launch (see onCreate()'s own savedInstanceState check) and via the manual "Nu synchroniseren" button.
 * WorkManager-based periodic background scheduling (no app open at all) is still a later step.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var webView: WebView
    private lateinit var syncButton: Button
    private lateinit var publishButton: Button
    private lateinit var settingsStore: SettingsStore
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // Matches log.py's "[info] ...decoded 42/1940 logfile(s) so far" (see
    // _DECODE_PROGRESS_INTERVAL_S in cli.py / android_entry.py) -- two groups (not one "42/1940"
    // group) so the progress bar (see updateProgressBar()) can set current/max separately,
    // without also having to re-parse the notification's own copy of this same text.
    private val decodeProgressRegex = Regex("""decoded (\d+)/(\d+) logfile\(s\) so far""")

    // The trip-building phase after decode (android_entry.py/cli.py/tripbuilder.py's own
    // checkpoint log() calls, added purely as a diagnostic aid -- see their own comments) has no
    // "current/total" numbers of its own the way decodeProgressRegex's line does, just four fixed
    // checkpoints in a always-the-same order -- so BUILD_PHASE_MARKERS below just counts which one
    // last matched as "step X of 4" instead. Asked for explicitly: found in practice, the
    // notification was still showing "opbouwen 2012/2012" (decodeProgressRegex's own last message,
    // stale-but-not-wrong text left behind once decode itself was done) all the way through this
    // phase, with nothing of its own updating it since build_trips() can run for a real,
    // non-trivial amount of time on a full season's worth of samples.
    private val buildPhaseMarkers = listOf(
        Regex("""Reizen opbouwen uit \d+ GPS-posities"""),
        Regex("""\d+ navigation samples merged, classifying trips"""),
        Regex("""\d+ run\(s\) classified, computing per-trip statistics"""),
        Regex("""\d+ trip\(s\) found, writing logbook"""),
    )

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
                    text = getString(R.string.error_settings_load_failed, e.toString())
                    setPadding(padding, padding, padding, padding)
                }
            )
            return
        }
        settingsStore = store
        ensureNotificationPermission()

        // Belt-and-suspenders on top of onDestroy()'s/SyncNotificationService.onTaskRemoved()'s
        // own cleanup -- found in practice, a real gap: this device's launcher "Alles sluiten"
        // (close all recent apps) kills the process directly, which skips every in-process
        // lifecycle callback entirely (no onDestroy(), no onTaskRemoved() -- neither can run once
        // the process is already gone), so a leftover "W2K-2 niet gevonden"/completion
        // notification from before survived indefinitely across that specific close path. There's
        // no way to intercept a hard process kill from inside the app, so this is the next best
        // guarantee: whatever's stale gets cleared the moment the app is next opened, rather than
        // sitting there forever. Only when nothing is in progress -- a genuinely still-running
        // sync's own notification must survive a fresh Activity instance being created on top of
        // it (e.g. a process restart while a sync is still alive), same guard as the other two.
        if (!SyncState.inProgress) {
            NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
            NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
        }

        val padding = (16 * resources.displayMetrics.density).toInt()

        // Icon buttons (asked for explicitly): sync + publish top-left, settings top-right --
        // plain emoji as the button label, same approach as the language-switcher flags in
        // html_writer.py, so this doesn't need any drawable/vector icon assets of its own.
        // ↺ and ⚙ specifically, not 🔄/⚙️ -- found in practice (asked to fix): 🔄's official
        // Unicode name is "arrows button" and it (and ⚙️, the variation-selected, emoji-
        // presentation gear) render on this device with a visible rounded-square badge baked
        // into the glyph itself, inconsistent with ☁️/✕ which don't have one. ↺ (a plain
        // dingbat, U+21BA) and ⚙ (the same gear character without the U+FE0F emoji variation
        // selector, requesting *text* presentation instead) render as plain glyphs with no badge.
        // Tapping this while a sync (or offline build) is already running cancels it instead of
        // starting a new one -- see cancelSyncStayInApp()'s own doc comment for why.
        syncButton = iconButton(
            // -12px was the originally measured value on a ~3.0-density phone; -4dp reproduces
            // that same on-screen shift there (now density-scaled, see iconButton()) while
            // scaling down correctly on lower-density screens.
            getString(R.string.tooltip_sync), emoji = "↺", emojiSize = 30f, emojiBold = true, verticalNudgePx = -4f,
        ) {
            if (SyncState.inProgress) cancelSyncStayInApp() else runSync()
        } // ↺
        // Material's own "upload" icon (ic_upload_24), not the ☁️ emoji it replaced -- asked for
        // explicitly, found in practice: a plain cloud alone didn't read as obviously "publish"
        // as a real, recognized icon does. Icon/tooltip/behavior below (see
        // updatePublishButtonEnabled()) switch to a plain local build whenever no publish
        // destination is configured -- asked for explicitly, found in practice: a disabled
        // button with no explanation read as broken rather than "not configured", especially
        // on a freshly set-up device with real .ebl data already on it but no publish settings.
        publishButton = iconButton(getString(R.string.tooltip_publish), iconRes = R.drawable.ic_upload_24) {
            if (settingsStore.isRestUploadConfigComplete || settingsStore.isSftpConfigComplete) {
                runPublish()
            } else {
                runOfflineBuild()
            }
        }
        // Loads whatever logbook.html is already on the phone into the WebView, without syncing
        // or publishing anything -- asked for explicitly, for when the owner just wants to check
        // the already-built logbook (e.g. after switching "Automatisch publiceren na bouwen" off
        // in Instellingen) without that also sending it to ayuus.com. Material's "article" icon
        // (ic_article_24), not the 📖 emoji it replaced -- asked for explicitly, found in
        // practice: an open book read as too old-fashioned.
        val viewLocalButton = iconButton(getString(R.string.tooltip_view_local), iconRes = R.drawable.ic_article_24) {
            viewLocalLogbook()
        }
        val settingsButton = iconButton(getString(R.string.tooltip_settings), emoji = "⚙") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        } // ⚙
        // No standalone toolbar close button (removed -- asked for explicitly, found in
        // practice: unclear what tapping it actually did, since it both cancelled a running sync
        // and left the app entirely in one tap). The back button/swiping away from Recents cover
        // "actually leave" on their own (see onDestroy()/onTaskRemoved(), both of which already
        // stop a running sync and post the same "tap to reopen" notification
        // closeAppAndCancelSync() does -- that function itself stays, still used by the
        // offline/close dialog's own "App sluiten" button, see showOfflineOrCloseDialog()), and
        // ↺ now separately covers "cancel without leaving" (see cancelSyncStayInApp()).

        // Shows the exact same "[info]"/"[ok]"/"[skip]"/"[warning]" lines the desktop CLI prints
        // (see log.py's set_log_sink(), wired up in android_entry.py) -- the only progress/status
        // surface left in the app itself (asked for explicitly: a separate one-line statusView
        // banner used to sit above this, but it kept ending up saying much the same thing as
        // whatever the log already showed right below it).
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, padding / 2, 0, padding / 2)
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true // the logbook's own trip map (Leaflet) needs this
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("LogbookWebView", "${msg.messageLevel()} ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
        }

        // Bottom progress bar (asked for explicitly): a visual bar reads faster at a glance than
        // scanning the log's own text for the current "x/y" count, and shows the phase
        // (downloading vs. decoding) as its own label rather than folding it into a longer
        // sentence -- see updateProgressBar(), fed from the exact same report()/decodeProgressRegex
        // signals the notification already uses. Hidden (not just empty) whenever nothing is
        // running, rather than sitting there at 0/0.
        progressLabel = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            visibility = View.GONE
        }

        // Sync + publish + view-local icons top-left, settings top-right (asked for explicitly)
        // -- a weight-1 empty spacer pushes settingsButton to the far right without needing a
        // second, nested layout.
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Off by default a horizontal LinearLayout aligns children on their text baseline --
            // harmless while every button used the same emoji textSize, but syncButton's larger,
            // bolder ↺ (see iconButton()'s emojiSize/emojiBold) sits on a different baseline than
            // the plain-icon buttons (no text at all) and settingsButton's smaller ⚙, so it drifted
            // a few pixels above the rest (found in practice). Centering vertically instead ignores
            // baselines entirely and keeps every button's visual center aligned regardless of its
            // own content size.
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            addView(syncButton)
            addView(publishButton)
            addView(viewLocalButton)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(settingsButton)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(buttonRow)
            addView(logScroll)
            addView(webView)
            addView(progressLabel)
            addView(progressBar)
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
        updatePublishButtonEnabled()

        // The logbook page's own popups (Details/Opmerkingen/Overzicht -- all plain HTML
        // <dialog> elements inside the WebView) are invisible to the system back button by
        // default -- without this, pressing back while one was open closed the whole app instead
        // of just the popup (found in practice, asked for explicitly to fix): a single-Activity
        // app with no fragment back stack falls straight through to finishing the Activity
        // otherwise. Checks the page itself via JS (not some Kotlin-side "is a dialog open" flag
        // that would need to be kept in sync with every popup this page ever adds), so this stays
        // correct regardless of which dialog -- or none -- happens to be open.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // window.__handleBackPress (defined in the logbook's own page script) closes
                // whichever dialog is open, or -- if none is, but a trip was just opened from the
                // Overzicht map -- reopens Overzicht instead, and reports back whether it handled
                // anything. Falls back to the plain "any dialog open" check for an older cached
                // logbook.html that predates that function (e.g. reopened without a fresh sync).
                webView.evaluateJavascript(
                    "typeof window.__handleBackPress === 'function' ? window.__handleBackPress() " +
                        ": (function(d){ if (d) d.close(); return !!d; })(document.querySelector('dialog[open]'))"
                ) { handled ->
                    Log.d("LogbookBack", "handled=$handled")
                    if (handled == "true") {
                        // Already handled entirely in JS above.
                    } else {
                        // Falls through to whatever back would otherwise have done (finishing the
                        // Activity, same as before this callback existed) -- disabling this
                        // callback first, rather than calling finish() directly here, so that
                        // "otherwise" stays correct even if a later change ever adds another
                        // callback of its own instead of relying on the plain system default.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

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
            // Asked for explicitly: opt-out via Instellingen ("Automatisch downloaden bij
            // starten") for whoever doesn't want opening the app to try reaching the W2K-2 on its
            // own -- a manual ↺ tap still works exactly the same either way.
            if (settingsStore.autoSyncOnLaunch) {
                autoStartSyncWithSettingsRetry()
            } else {
                // Shows whatever's already on the phone right away (asked for explicitly) --
                // exactly what tapping 📖 itself does, not a separate code path of its own. Without
                // this, the log's own placeholder text would sit there doing nothing until the
                // owner tapped something (📖, or ↺ to sync anyway) -- onResume(), called right
                // after this either way, only loads the file into the WebView underneath; it
                // doesn't also switch to 📖's fully-covering layout the way this does.
                viewLocalLogbook()
            }
        } else if (SyncState.inProgress) {
            // The savedInstanceState != null branch above skips autoStartSyncWithSettingsRetry()
            // entirely -- including its own guard -- so a brand new Activity instance recreated
            // WITH saved state (e.g. this device's "Freecess" background-process freezer thawing
            // it back out after unlocking the screen, see the isW2k2ConfigComplete doc comment
            // above for the same underlying OS behavior) needs the same live-state restore.
            restoreLiveSyncUi()
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
            if (!SyncState.inProgress) {
                // Nothing running -- SyncNotificationService.onTaskRemoved()'s own cleanup only
                // fires for a service that's actually running, but autoStartSyncWithSettingsRetry()'s
                // cheap pre-check posts "W2K-2 niet gevonden" straight via NotificationManagerCompat
                // without ever starting the service at all, so that notification had no way to be
                // cleared by swiping the app away (found in practice, asked for explicitly: it just
                // sat there indefinitely). onDestroy() fires regardless of whether the service was
                // ever started, so it covers that gap onTaskRemoved() structurally can't.
                NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
                NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
            }
        }
    }

    /** Restores full live sync state (log, progress bar) from SyncState's own cached fields (see
     * there) -- called whenever this Activity instance becomes the active one while a sync is
     * already known to be in progress, instead of showing a static, never-updating placeholder
     * (found in practice, a real bug -- see SyncState.active's own doc comment). Harmless to call
     * even when nothing has been cached yet (a sync that's only just started, before its very
     * first progress update reached SyncState). */
    private fun restoreLiveSyncUi() {
        logView.text = SyncState.lastLogText
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        val phase = SyncState.lastProgressPhase
        if (phase != null && SyncState.lastProgressTotal > 0) {
            progressBar.visibility = View.VISIBLE
            progressLabel.visibility = View.VISIBLE
            progressBar.max = SyncState.lastProgressTotal
            progressBar.progress = SyncState.lastProgressCurrent
            progressLabel.text = getString(
                R.string.progress_label_format, phase, SyncState.lastProgressCurrent, SyncState.lastProgressTotal,
            )
        } else {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
        }
        updatePublishButtonEnabled()
    }

    /** ☁️ swaps to a plain "build" wrench icon/tooltip whenever neither WordPress nor SFTP is
     * configured (see SettingsStore) -- asked for explicitly: a disabled button with no
     * explanation (the previous behavior; tapping it while disabled obviously did nothing) read
     * as broken rather than "not configured", especially with real .ebl data already on the
     * device and nothing to publish it to. See the iconButton() call site above for the matching
     * click-handler branch (runPublish() vs. runOfflineBuild()). Stays disabled only while a sync
     * or offline build is already running, same as before this existed. Called from onResume()
     * too, since the only way settings change is a round trip through SettingsActivity and back. */
    private fun updatePublishButtonEnabled() {
        publishButton.isEnabled = !SyncState.inProgress
        val configured = settingsStore.isRestUploadConfigComplete || settingsStore.isSftpConfigComplete
        publishButton.setCompoundDrawablesWithIntrinsicBounds(
            if (configured) R.drawable.ic_upload_24 else R.drawable.ic_build_24, 0, 0, 0,
        )
        publishButton.compoundDrawableTintList = ColorStateList.valueOf(publishButton.currentTextColor)
        ViewCompat.setTooltipText(
            publishButton,
            getString(if (configured) R.string.tooltip_publish else R.string.tooltip_build_local),
        )
    }

    /** Found in practice, still not fully understood at the OS level: right after this Activity's
     * process is resumed (a cold start, or -- confirmed by logcat on this device -- Samsung's own
     * "Freecess" background-process freezer thawing it back out) isW2k2ConfigComplete has
     * sometimes read false here even though the real settings were still saved fine, and stayed
     * false for longer than the single 300ms re-check this used to do (found in practice: that
     * one-shot version still wasn't enough, see the same bug reported again after this was
     * already in place). EncryptedSharedPreferences relies on the Android Keystore, which could
     * plausibly still be settling for a bit after either kind of resume.
     *
     * Retries a few times, spaced out, before concluding settings are genuinely incomplete --
     * cheap either way: it either papers over that race, or costs a little under a second before
     * showing the same message runSync() itself would show for a real empty-settings case. Only
     * used for the automatic startup attempt; a manual 🔄 tap goes straight to runSync() and its
     * own immediate check, since by then the app has already been running long enough that this
     * race isn't a concern. */
    private fun autoStartSyncWithSettingsRetry(attemptsLeft: Int = 5) {
        // A sync from an earlier instance of this Activity can still be genuinely running in the
        // background right now -- SyncNotificationService's android:stopWithTask="false" means
        // closing the app (or it getting recreated for any other reason) doesn't stop it (found
        // in practice: a fresh instance's own settings re-check raced against this and showed
        // "vul eerst je instellingen in" over a sync that was actually still progressing fine,
        // confusing but not actually broken). Nothing to auto-start in that case.
        //
        // Full live state is restored here too (found in practice, a second real bug on top of
        // the first): a brand new Activity instance's log/progress bar always start out blank/
        // hidden, and returning here without touching them left that placeholder state on screen
        // indefinitely, even though the sync was genuinely progressing the whole time.
        if (SyncState.inProgress) {
            restoreLiveSyncUi()
            return
        }
        if (settingsStore.isW2k2ConfigComplete) {
            // Only actually starts a sync when the W2K-2's own hotspot looks reachable right now
            // (a cheap, local, synchronous check -- see HotspotDetector, no network I/O) -- asked
            // for explicitly: always trying (and usually failing, away from the boat) on every
            // single app launch used to mean a visible "Hotspot controleren..." cycle each time,
            // for no benefit when there was never any real chance of finding it. A real full sync
            // (see runSync()) still does its own, more thorough discover_w2k2() scan, which can
            // still come back "not found" (the hotspot's on, but the W2K-2 itself never actually
            // joined it) -- that outcome (and this cheap check's own, right below) is always a
            // quiet log line + notification rather than a popup, auto-started or manual alike.
            if (HotspotDetector.detectSubnetPrefix() != null) {
                // No longer minimized automatically after starting (tried this -- see git history
                // for both a fixed-delay and an event-based version) -- asked for explicitly: the
                // app should only minimize once its notification is fully visible, and since
                // Android has no callback for "now visually rendered" (only for the
                // startForeground() call itself, which found in practice can precede the real,
                // on-screen appearance by several seconds, especially right after a fresh
                // install), that can't be guaranteed -- so per the fallback instruction, it just
                // stays open instead of guessing at a delay again.
                runSync()
            } else {
                val existing = File(filesDir, "logbook.html")
                if (existing.exists()) {
                    loadLogbookIntoWebView(existing.absolutePath)
                }
                handleLogLine("[info] " + getString(R.string.log_hotspot_precheck_skipped))
                // A real Android notification too, not just the in-app log (asked for explicitly)
                // -- this can fire well before the owner ever looks at the app again (e.g. the
                // very first check after a fresh launch), so it's the only way to learn about it
                // without watching the screen right at this moment. Plain statement, not "tik
                // om..." -- tapping it does exactly what tapping any notification does (opens the
                // app), nothing beyond that specific to this one (found in practice: worded like
                // there was a dedicated action behind the tap, there wasn't).
                SyncNotificationService.postNotFoundNotification(this, getString(R.string.notif_w2k2_not_found))
            }
        } else if (attemptsLeft > 0) {
            android.os.Handler(mainLooper).postDelayed(
                { autoStartSyncWithSettingsRetry(attemptsLeft - 1) }, 300L
            )
        } else {
            handleLogLine("[info] " + getString(R.string.log_fill_w2k2_credentials))
        }
    }

    private fun runSync() {
        if (SyncState.inProgress) return
        if (!settingsStore.isW2k2ConfigComplete) {
            handleLogLine("[info] " + getString(R.string.log_fill_w2k2_credentials))
            return
        }

        SyncState.inProgress = true
        SyncState.cancelled = false
        // syncButton deliberately stays enabled here (unlike publishButton) -- tapping it again
        // while a sync is running cancels it instead (see the button's own onClick below and
        // cancelSyncStayInApp()), asked for explicitly: the app's own auto-start-on-launch (see
        // autoStartSyncWithSettingsRetry()) has no way to be skipped otherwise, so opening the
        // app to use ☁️ Publiceren on its own was never actually reachable -- both buttons stayed
        // disabled for as long as that auto-started sync kept running.
        publishButton.isEnabled = false
        val initialStatusText = getString(R.string.status_checking_hotspot)
        // Log deliberately NOT cleared here (asked for explicitly) -- it now accumulates across
        // every sync this process runs instead of starting over each time, so a run's own history
        // stays visible/scrollable-back-to after later runs. Only the progress state below still
        // resets per-run, since that's specifically about the run in progress right now.
        SyncState.lastStatusText = initialStatusText
        SyncState.lastNotificationText = initialStatusText
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        showingLocalLogbook = false
        setLogExpanded(true)
        // Shown immediately, before hotspot detection even starts -- not only once the first
        // "Downloaden: 1/X" progress update arrives (asked for explicitly: hotspot detection and
        // then listing every folder that still needs checking can itself take a real moment on a
        // big archive, during which nothing was visible outside the app at all before this).
        val startIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, initialStatusText)
        startSyncNotification(startIntent)
        handleLogLine("[info] $initialStatusText")

        // Captured before this run starts -- see the "actually produced a fresh file" fallback
        // check below, right after syncFromW2k2() returns.
        val htmlFile = File(filesDir, "logbook.html")
        val htmlMtimeBeforeThisRun = if (htmlFile.exists()) htmlFile.lastModified() else -1L

        Thread {
            val subnetPrefix = HotspotDetector.detectSubnetPrefix()
            if (subnetPrefix == null) {
                val message = getString(R.string.status_hotspot_off)
                SyncState.lastStatusText = message
                // This specific check is pure Kotlin (HotspotDetector, no Python/Chaquopy call
                // involved at all), unlike the "No W2K-2 found on <subnet>" case a few lines
                // further down in this same Thread -- that one already gets a log line for free,
                // from discover_w2k2()'s own log() calls in Python. This one didn't have an
                // equivalent until now, so it's added explicitly here to match.
                handleLogLine("[info] $message")
                withActiveActivity {
                    stopService(Intent(this, SyncNotificationService::class.java))
                    SyncState.notificationForegrounded = false
                    SyncState.notificationStartFailed = false
                    // No popup, auto-started or manual ↺ tap alike (asked for explicitly) --
                    // "W2K-2 not reachable yet" is the expected, common outcome of not being at
                    // the boat, not something worth a modal interruption; the log line above plus
                    // a real Android notification (in place of the ongoing sync one this
                    // replaces) are enough either way.
                    SyncNotificationService.postNotFoundNotification(this, message)
                    SyncState.inProgress = false
                    syncButton.isEnabled = true
                    updatePublishButtonEnabled()
                }
                return@Thread
            }

            val listingStatusText = getString(R.string.status_listing_files, subnetPrefix)
            SyncState.lastStatusText = listingStatusText
            SyncState.lastNotificationText = listingStatusText
            handleLogLine("[info] $listingStatusText")
            withActiveActivity {
                val listingIntent = Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, listingStatusText)
                startSyncNotification(listingIntent)
            }
            // Hoisted above the try block so the finally below can still see the outcome --
            // needed to decide between a plain "sync stopped" cleanup and posting the "Voltooid"
            // completion notification (see SyncNotificationService.postCompletionNotification()).
            var syncSucceeded = false
            var didPublish = false
            try {
                var result = syncFromW2k2(subnetPrefix)
                // Belt-and-suspenders on top of SyncController.onResult() (see its own doc
                // comment for the real fix -- capturing the outcome via a direct method call
                // during sync_from_w2k2()'s own execution, instead of reading callAttr()'s
                // returned PyObject's fields afterward, which is what was actually unreliable).
                // Kept as a second, independent check rather than removed once onResult() landed:
                // logbook.html's own mtime is a signal Kotlin already has regardless of anything
                // Python reports, so an inconclusive "ok: false, error: null" outcome (should no
                // longer happen at all post-onResult(), but cheap to guard anyway) still isn't
                // trusted blindly over a file that's demonstrably newer than before this run.
                if (!result.ok && result.error == null && !result.cancelled) {
                    val mtimeNow = if (htmlFile.exists()) htmlFile.lastModified() else -1L
                    if (mtimeNow > htmlMtimeBeforeThisRun) {
                        result = SyncResult(
                            ok = true,
                            cancelled = false,
                            error = null,
                            tripCount = null,
                            htmlPath = htmlFile.absolutePath,
                            downloadedCount = result.downloadedCount,
                        )
                    }
                }
                syncSucceeded = result.ok
                withActiveActivity { showSyncResult(result) }
                // After showing the logbook, not before -- an upload problem (misconfigured
                // credentials, server unreachable) shouldn't hide the fact that the download and
                // decode themselves already succeeded. Still on this same background Thread, not
                // re-dispatched: SftpUploader's calls are blocking network I/O same as the
                // download itself was. Gated on the setting (asked for explicitly) -- off, this
                // sync only ever builds the logbook locally; the owner checks it via 📖 and
                // publishes on their own terms via ☁️ (runPublish(), unaffected by this setting).
                if (result.ok && result.htmlPath != null && settingsStore.autoPublishAfterBuild) {
                    didPublish = uploadIfConfigured(result.htmlPath)
                }
                if (syncSucceeded) {
                    // A plain Kotlin-originated line (not one of log.py's own), reused through
                    // the exact same accumulator/log-view path as every other line -- asked for
                    // explicitly: there was previously no single, unambiguous "this whole sync,
                    // including any publish step, is now finished" marker in the log at all.
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    handleLogLine(getString(R.string.log_sync_done_at, timeText))
                }
            } catch (e: Exception) {
                val message = getString(R.string.error_unexpected_sync, e.toString())
                SyncState.lastStatusText = message
                handleLogLine("[error] $message")
            } finally {
                // Real, must-always-happen state -- not gated behind withActiveActivity (which
                // no-ops when nothing is currently active, e.g. the app is fully backgrounded
                // right as the sync finishes): SyncState.inProgress staying stuck true forever
                // would block every later sync attempt, and this instance is a valid Context for
                // stopService()/postCompletionNotification() regardless of whether it's the
                // currently active one.
                stopService(Intent(this, SyncNotificationService::class.java))
                if (syncSucceeded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    SyncNotificationService.postCompletionNotification(
                        this,
                        getString(R.string.notif_sync_done_at, timeText),
                        if (didPublish) MainActivity.LIVE_SITE_URL else null,
                    )
                }
                SyncState.notificationForegrounded = false
                SyncState.notificationStartFailed = false
                SyncState.inProgress = false
                // Purely cosmetic UI state, safe to skip when nothing is active right now -- the
                // next Activity to resume starts from a fresh, already-correct button/progress
                // state on its own (see onCreate()/restoreLiveSyncUi()).
                withActiveActivity {
                    syncButton.isEnabled = true
                    updatePublishButtonEnabled()
                    hideProgressBar()
                }
            }
        }.start()
    }

    /** 📖 icon: shows whatever logbook.html is already on the phone, purely local -- no sync, no
     * publish, nothing sent anywhere. Doesn't touch SyncState at all, so it works even while a
     * sync is running (just shows the *previous* build until that one finishes and replaces it
     * via showSyncResult()'s own success branch). */
    private fun viewLocalLogbook() {
        if (showingLocalLogbook) {
            // Toggle back to the log (asked for explicitly) -- the logbook itself is already
            // loaded in the WebView from the tap that showed it, nothing to reload.
            showingLocalLogbook = false
            setLogExpanded(true)
            return
        }
        val htmlFile = File(filesDir, "logbook.html")
        if (!htmlFile.exists()) {
            handleLogLine("[info] " + getString(R.string.log_no_logbook_to_view))
            return
        }
        showingLocalLogbook = true
        // Fully hides the log rather than leaving setLogExpanded(false)'s own small collapsed
        // strip (still used as-is after a normal sync/publish completes) -- asked for explicitly,
        // this view is meant to cover the whole screen, not share it with a log peek.
        logScroll.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
        webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        loadLogbookIntoWebView(htmlFile.absolutePath)
    }

    // Tracks whether 📖 is currently showing the fully-covering local view above, so a second tap
    // knows to toggle back to the log instead of just reloading the same file again. Reset to
    // false wherever a sync/offline-build starts (see runSync()/runOfflineBuild()) -- those
    // already re-expand the log themselves via setLogExpanded(true), so this only needs to stay
    // in sync with that, not drive it.
    private var showingLocalLogbook = false

    /** Manual re-publish (the ☁️ icon): builds the logbook from whatever .ebl files are already
     * on the phone (same as runOfflineBuild(), see buildFromLocalFilesAndMaybePublish()'s own
     * doc comment) and then always uploads it, ignoring "Automatisch publiceren na bouwen" --
     * tapping ☁️ is itself the explicit request to publish. Building first here, not just
     * re-uploading whatever logbook.html already happened to be on disk, fixes a real gap found
     * in practice: downloading from the W2K-2 over its own hotspot (no internet there to publish
     * over anyway), then switching to a different network later specifically to publish -- if
     * that download's own decode/build step never finished (app closed, network switched first),
     * there was no fresh logbook.html for ☁️ to send, or an old one sat there unchanged. A
     * cache-hit rebuild when nothing's actually missing is fast, so this costs little even when
     * ☁️ alone (an already-fresh logbook.html) would have been enough. Shares SyncState.inProgress
     * with runSync() so this can't run at the same time as a sync's own automatic upload at the
     * end of it. */
    private fun runPublish() {
        if (SyncState.inProgress) return
        // Both, not just SFTP -- found in practice, a real bug: an owner with only REST
        // configured (no SFTP at all, the whole point of preferring REST) tapped ☁️ and got told
        // to fill in "de publiceer-instellingen (SFTP)" even though publishing itself would have
        // worked fine via REST. Matches uploadIfConfigured()'s own check exactly.
        if (!settingsStore.isRestUploadConfigComplete && !settingsStore.isSftpConfigComplete) {
            handleLogLine("[info] " + getString(R.string.log_fill_publish_settings))
            return
        }
        buildFromLocalFilesAndMaybePublish(forcePublish = true)
    }

    private data class SyncResult(
        val ok: Boolean,
        val cancelled: Boolean,
        val error: String?,
        val tripCount: Int?,
        val htmlPath: String?,
        val downloadedCount: Int?,
    )

    /** Where downloaded .ebl files live -- app-specific *external* storage (Android/data/
     * <package>/files/Actisense), not filesDir (internal storage, completely inaccessible from
     * outside the app) -- asked for explicitly: the raw .ebl archive gets large over a full
     * season and the owner wants to browse/copy it from a PC over USB, which only works for
     * external storage. No extra permission needed for an app's own external directory (unlike
     * the public Downloads folder, which would need "All files access" -- incompatible with an
     * eventual Play Store release).
     *
     * One-time migration on top: earlier versions of this app kept the same folder under filesDir
     * -- moved wholesale into place here (not deleted-and-redownloaded) the first time this runs
     * after updating, so a real, possibly gigabytes-large existing archive doesn't have to come
     * back down over the W2K-2's own slow hotspot connection again. Falls back to the internal
     * folder (old behavior) if external storage isn't currently available at all (rare -- e.g.
     * briefly right after boot on some devices) or the migration copy itself fails partway --
     * either way, nothing already downloaded is lost, and a failed copy's partial leftovers are
     * cleaned up so the next launch retries instead of getting stuck thinking it already moved. */
    private fun eblDownloadDir(): File {
        val oldDir = File(filesDir, "Actisense")
        val result = run {
            val externalBase = getExternalFilesDir(null) ?: return@run oldDir
            val newDir = File(externalBase, "Actisense")
            if (oldDir.exists() && !newDir.exists()) {
                try {
                    oldDir.copyRecursively(newDir, overwrite = false)
                    oldDir.deleteRecursively()
                } catch (e: Exception) {
                    newDir.deleteRecursively()
                    return@run oldDir
                }
            }
            newDir
        }
        // Asked for explicitly, now that USB file transfer to this exact path is the owner's own
        // way to browse the .ebl archive from a PC -- one line per sync/offline-build run (both
        // callers only ever call this once each), not spammy. "/storage/emulated/0/" dropped
        // (asked for explicitly too) -- that prefix is never what's shown in Explorer/a file
        // picker on the PC side, just noise; starts at "Android/..." instead, which is. Falls
        // back to the full path on the rare internal-storage fallback (eblDownloadDir() above),
        // whose path never has an "/Android/" segment to trim from in the first place.
        val fullPath = result.absolutePath
        val androidIndex = fullPath.indexOf("/Android/")
        val shownPath = if (androidIndex >= 0) fullPath.substring(androidIndex + 1) else fullPath
        handleLogLine("[info] " + getString(R.string.log_ebl_files_location, shownPath))
        return result
    }

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
        val androidEntry = py.getModule("nmea2log.android_entry")

        val downloadDir = eblDownloadDir()
        val outputHtmlPath = File(filesDir, "logbook.html")
        val sampleCachePath = File(filesDir, "sample_cache.pkl")

        // Captured by the controller's onResult() below, not read back out of callAttr()'s own
        // return value afterward -- see SyncController.onResult()'s own doc comment for why.
        var capturedResult: SyncResult? = null

        // Called by Python between files (android_entry.py): report() lets the status text and
        // notification show real "current/total" progress instead of one static message for
        // however long a sync takes (a first-ever sync fetches the whole historical archive,
        // easily several GB, found in practice); isCancelled() lets a sync stop cleanly once
        // onDestroy() sets the cancelled flag, instead of continuing after the app is closed.
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {
                val text = getString(R.string.status_downloading, current, total, fileName)
                SyncState.lastStatusText = text
                // Matches what SyncNotificationService itself independently computes from the
                // current/total/fileName extras below -- cached here too so a later restore (see
                // onResume()) has the right text without needing to resend those three extras.
                // Not logged per file either (this fires once per file, easily thousands of times
                // for a first-ever sync) -- the progress bar (see updateProgressBar() below) and
                // this same text in the notification are enough; a log line per file would just
                // flood the log for no benefit.
                SyncState.lastNotificationText = text
                // Also visible from the notification shade while the app isn't on screen -- see
                // SyncNotificationService.onStartCommand(), which updates its existing
                // notification in place rather than posting a new one each time.
                val progressIntent = Intent(this@MainActivity, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_CURRENT, current)
                    .putExtra(SyncNotificationService.EXTRA_TOTAL, total)
                    .putExtra(SyncNotificationService.EXTRA_FILE_NAME, fileName)
                startSyncNotification(progressIntent)
                updateProgressBar(getString(R.string.phase_downloading), current, total)
            }

            override fun isCancelled(): Boolean = SyncState.cancelled

            override fun onLogLine(line: String) = handleLogLine(line)

            // No longer stops the notification here (decode/build is pure CPU, no more network
            // I/O left once this fires) -- tried that, found in practice it backfired: decoding
            // this app's real archives routinely takes long enough to need its own progress
            // shown again anyway (see decodeProgressRegex below), so stopping here only meant a
            // *second* startForegroundService() eligibility check later in the same run, and on
            // Android 15+'s per-24h "dataSync" budget (see startSyncNotification()'s own doc
            // comment) that's a second chance to get refused instead of one. Leaving the service
            // running continuously from the start of the sync through decode costs at most a
            // handful of extra seconds of that budget on the (uncommon, on a real archive)
            // all-cache-hit case, for a real reduction in how often this budget gets hit at all.
            override fun onDownloadComplete() {}

            override fun onResult(
                ok: Boolean,
                error: String?,
                cancelled: Boolean,
                tripCount: Int,
                htmlPath: String?,
                downloadedCount: Int,
            ) {
                capturedResult = SyncResult(
                    ok = ok,
                    cancelled = cancelled,
                    error = error,
                    tripCount = if (ok && tripCount >= 0) tripCount else null,
                    htmlPath = if (ok) htmlPath else null,
                    downloadedCount = if (ok && downloadedCount >= 0) downloadedCount else null,
                )
            }
        }

        androidEntry.callAttr(
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
            settingsStore.minStopMinutes,
        )

        // onResult() is always called, unconditionally, right before sync_from_w2k2() returns
        // (see android_entry.py's own _report_result()) -- this being null would mean that call
        // never happened at all, which callAttr() above returning normally already rules out.
        return capturedResult ?: SyncResult(
            ok = false, cancelled = false, error = getString(R.string.error_no_result),
            tripCount = null, htmlPath = null, downloadedCount = null,
        )
    }

    /** Runs [block] against whichever Activity instance is currently active (see
     * SyncState.active), with that instance as the receiver, on the main thread -- lets code
     * reached from a background Thread/SyncController callback (report(), handleLogLine(), the
     * sync Thread's own finally block, ...) update whichever Activity is actually on screen right
     * now, not necessarily the specific instance that started the sync (see SyncState.active's
     * own doc comment for why that distinction matters). A no-op when nothing is currently active
     * (app fully backgrounded) -- there's nothing to update in that case; SyncState's own cached
     * fields (updated separately, alongside every call site below) still let the next Activity
     * that resumes restore the real state instead (see restoreLiveSyncUi()). */
    private fun withActiveActivity(block: MainActivity.() -> Unit) {
        val target = SyncState.active ?: return
        target.runOnUiThread { target.block() }
    }

    /** Shared between syncFromW2k2()'s and buildFromLocalFiles()'s own SyncController.onLogLine()
     * -- found in practice: the offline-build path (see runOfflineBuild()) had no log-line
     * handling of its own at all, so a real (not cache-hit) decode there left the screen stuck on
     * a single static "Logboek opbouwen..." message with nothing to show it wasn't just hung,
     * instead of the same "...decoded X/Y" progress a normal sync already shows via the block
     * below. */
    private fun handleLogLine(line: String) {
        // The accumulator, not logView.text itself -- logView may belong to an orphaned
        // instance, or there may be no active instance at all right now (see withActiveActivity),
        // so the running log has to live somewhere that survives either.
        SyncState.lastLogText = if (SyncState.lastLogText.isEmpty()) line else "${SyncState.lastLogText}\n$line"
        withActiveActivity {
            logView.text = SyncState.lastLogText
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
            val text = getString(R.string.notif_connection_lost_retrying)
            SyncState.lastNotificationText = text
            val warningIntent = Intent(this, SyncNotificationService::class.java)
                .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
            startSyncNotification(warningIntent)
        } else if (decodeProgressRegex.containsMatchIn(line)) {
            // Found in practice: decoding logfiles not already in the sample cache is
            // CPU-bound and, on a phone's much weaker CPU than a desktop's, can silently
            // run for many minutes -- with the screen off there was nothing at all to show
            // this wasn't just hung. The notification is left running continuously from the
            // start of the sync now (see onDownloadComplete() above), so this is just a
            // cheap content update most of the time, not a fresh eligibility-gated start.
            val match = decodeProgressRegex.find(line)!!
            val current = match.groupValues[1].toInt()
            val total = match.groupValues[2].toInt()
            val text = getString(R.string.status_building_logbook, current, total)
            SyncState.lastNotificationText = text
            val progressIntent = Intent(this, SyncNotificationService::class.java)
                .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
            startSyncNotification(progressIntent)
            updateProgressBar(getString(R.string.phase_decoding), current, total)
        } else {
            val step = buildPhaseMarkers.indexOfFirst { it.containsMatchIn(line) }
            if (step >= 0) {
                val current = step + 1
                val total = buildPhaseMarkers.size
                val text = getString(R.string.status_building_trips, current, total)
                SyncState.lastNotificationText = text
                val progressIntent = Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_CURRENT, current)
                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_MAX, total)
                startSyncNotification(progressIntent)
                updateProgressBar(getString(R.string.phase_building_trips), current, total)
            }
        }
    }

    /** Bottom progress bar + "phase: x/y" label (see progressBar/progressLabel, asked for
     * explicitly) -- fed from report() (download) and handleLogLine()'s own decodeProgressRegex
     * match (decode), the same two signals the notification already shows as text. Hidden rather
     * than shown at 0/0 for a total <= 0 (nothing meaningful to show yet, or the phase hasn't
     * started). */
    private fun updateProgressBar(phase: String, current: Int, total: Int) {
        SyncState.lastProgressPhase = if (total > 0) phase else null
        SyncState.lastProgressCurrent = current
        SyncState.lastProgressTotal = total
        withActiveActivity {
            if (total <= 0) {
                progressBar.visibility = View.GONE
                progressLabel.visibility = View.GONE
                return@withActiveActivity
            }
            progressBar.visibility = View.VISIBLE
            progressLabel.visibility = View.VISIBLE
            progressBar.max = total
            progressBar.progress = current
            progressLabel.text = getString(R.string.progress_label_format, phase, current, total)
        }
    }

    private fun hideProgressBar() {
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        withActiveActivity {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
        }
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
            // tripCount is null specifically for runSync()'s own "result.ok came back false with
            // no error text, but logbook.html's mtime proves it actually succeeded" recovery --
            // the real count isn't independently knowable there without re-parsing the file, so
            // this is worded around rather than showing a literal "null" (found in practice).
            // downloadedCount is null for runOfflineBuild()'s own result (no download happened
            // that run at all) -- omit that clause entirely rather than showing a literal "null".
            val resultText = if (result.tripCount == null) {
                getString(R.string.status_ready_updated)
            } else if (result.downloadedCount != null) {
                getString(R.string.status_ready_with_download, result.tripCount, result.downloadedCount)
            } else {
                getString(R.string.status_ready_no_download, result.tripCount)
            }
            SyncState.lastStatusText = resultText
            handleLogLine("[info] $resultText")
            setLogExpanded(false)
            loadLogbookIntoWebView(result.htmlPath)
        } else if (result.cancelled) {
            // The app was closed mid-sync (see onDestroy()) -- by the time this runs the Activity
            // is normally already gone, so this mostly matters when cancellation raced a rotation
            // (config change) instead. The next "Nu synchroniseren" simply resumes where it left
            // off, no special handling needed (see _needs_download() in w2k2_download.py).
            val resultText = getString(R.string.status_sync_stopped)
            SyncState.lastStatusText = resultText
            handleLogLine("[info] $resultText")
        } else {
            // Same "no popup, auto-started or manual alike" carve-out as the earlier "hotspot
            // staat uit" case (see runSync()) -- "W2K-2 not found on this subnet" is the other
            // half of that same expected, common not-at-the-boat outcome, so it gets the same
            // calm treatment (existing logbook shown if there is one, a log line, a real
            // notification in place of a popup) instead of the loud "Fout: ..." dialog, regardless
            // of how the run was started; any other, genuinely unexpected error (a decode crash,
            // HTTP 401, ...) still gets the normal treatment below, since that's worth surfacing
            // loudly no matter what.
            val isNotFoundError = result.error?.startsWith("No W2K-2 found") == true
            if (isNotFoundError) {
                val existing = File(filesDir, "logbook.html")
                if (existing.exists()) {
                    loadLogbookIntoWebView(existing.absolutePath)
                }
                handleLogLine("[info] ${result.error}")
                // Plain statement, not "tik om..." -- tapping this notification does exactly
                // what tapping any notification does (opens the app), nothing beyond that
                // specific to this one (found in practice: worded like there was a dedicated
                // action behind the tap, there wasn't).
                SyncNotificationService.postNotFoundNotification(this, getString(R.string.notif_w2k2_not_found))
                return
            }
            // Covers every non-cancelled failure, including the download never reaching a usable
            // state at all (e.g. the W2K-2/host becoming unreachable partway through) -- Python's
            // own android_entry.py never calls run_pipeline() in that case (see
            // sync_from_w2k2()'s except clauses), so there's no stale/partial logbook.html to
            // accidentally show; this dialog is the only thing the user sees (asked for
            // explicitly).
            val errorText = getString(R.string.error_generic_prefix, result.error ?: getString(R.string.error_unknown))
            SyncState.lastStatusText = errorText
            handleLogLine("[error] $errorText")
            showOfflineOrCloseDialog(errorText)
        }
    }

    /** Reads the freshly-written logbook and feeds its content to the WebView directly, instead
     * of webView.loadUrl("file://$htmlPath") -- found in practice, a real regression (this exact
     * loadUrl() call had worked fine earlier this same session): a file:// navigation into the
     * app's own private storage started failing with net::ERR_ACCESS_DENIED, on-device, with
     * nothing in this app's own code having changed about how or where the file is written.
     * loadDataWithBaseURL() never makes the WebView navigate to a file:// URL at all -- the HTML
     * is handed over as a plain string -- sidestepping whatever changed about that policy rather
     * than chasing it. baseUrl is still the file's own directory (as a file:// URL), so any
     * relative resource reference the page itself makes (none right now, the logbook is fully
     * self-contained, but this keeps that option open) would still resolve correctly. */
    private fun loadLogbookIntoWebView(htmlPath: String) {
        val html = try {
            File(htmlPath).readText()
        } catch (e: Exception) {
            handleLogLine("[error] " + getString(R.string.error_logbook_display_failed, e.toString()))
            return
        }
        webView.loadDataWithBaseURL("file://${File(htmlPath).parent}/", html, "text/html", "utf-8", null)
        lastLoadedHtmlMtime = File(htmlPath).lastModified()
    }

    // Tracks which version of logbook.html is currently showing (see onResume()'s own reload
    // guard) -- lastModified(), not a content hash: cheap, and this file is only ever written
    // whole by write_html_logbook(), never appended to, so its mtime alone is enough to tell
    // "already showing this" from "there's a newer build to load".
    private var lastLoadedHtmlMtime: Long = -1L

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
     * available as the simple way out.
     *
     * Deferred to onResume() instead of shown right away if the Activity isn't currently visible
     * (regression, found in practice: a real crash, android.view.WindowManager$BadTokenException
     * "token ... is not valid; is your activity running?") -- since the app now minimizes itself
     * shortly after an auto-started sync begins (see autoStartSyncWithSettingsRetry()), a sync
     * that then fails to even find the W2K-2 calls this from a background thread's callback well
     * after that minimize already happened, and AlertDialog.show() can't add a new window to an
     * Activity that isn't in the foreground. The log already has the failure line either way (by
     * the caller, before this is even called) so it's still reflected the moment the app is next
     * opened, dialog or not. */
    private fun showOfflineOrCloseDialog(message: String) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingOfflineOrCloseMessage = message
            return
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_build_logbook_button)) { _, _ -> runOfflineBuild() }
            .setNegativeButton(getString(R.string.dialog_close_app_button)) { _, _ -> closeAppAndCancelSync() }
            .show()
    }

    /** Shared core of closeAppAndCancelSync() and cancelSyncStayInApp() below -- sets the flag
     * runSync()'s background Thread checks (both during download, between files, and during
     * decode, see run_pipeline()'s should_cancel) and actually stops SyncNotificationService,
     * rather than just leaving it: that service has android:stopWithTask="false" (see the
     * manifest), so it wouldn't otherwise notice a cancellation that doesn't also finish this
     * Activity (found in practice, for the app-close case this was originally written for). */
    private fun cancelSync() {
        SyncState.cancelled = true
        stopService(Intent(this, SyncNotificationService::class.java))
        SyncState.notificationForegrounded = false
        SyncState.notificationStartFailed = false
    }

    /** "App sluiten" above -- cancelSync() plus actually leaving, unlike cancelSyncStayInApp()
     * below. Used to also leave behind a plain, dismissible "tap to reopen" notification (the
     * same one onTaskRemoved() below still posts for a swipe-away) -- dropped here specifically,
     * asked for explicitly: a real close via this button left a notification sitting in the
     * shade, which read as "still not actually closed" rather than the convenience it was meant
     * to be. Explicitly cancels both notification ids too, not just relying on cancelSync()'s own
     * stopService() -- that only tears down the foreground service's own notification (id 1);
     * nothing else here would otherwise clear an already-posted reopen notification (id 2) if one
     * happened to exist from an earlier close.
     *
     * finishAffinity() alone (the "normal" way to close every Activity in the task) still leaves
     * the process itself alive in the background -- ordinarily fine (that's how most Android apps
     * behave, including this one everywhere else), but found in practice: asked for explicitly
     * that this specific button, unlike just backgrounding the app, actually exits -- Process.
     * killProcess() is the standard way to guarantee that, rather than leave it to the OS's own
     * discretion about when (or whether) to reclaim a merely-backgrounded process. */
    private fun closeAppAndCancelSync() {
        cancelSync()
        NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Tapping ↺ again while the app's own auto-start sync (see autoStartSyncWithSettingsRetry())
     * is already running it -- asked for explicitly: that auto-start has no way to be skipped, so
     * opening the app to use ☁️ Publiceren on its own (re-send an already-built logbook.html
     * without a fresh sync) was never actually reachable, both buttons stay disabled for as long
     * as the auto-started sync keeps running. Unlike closeAppAndCancelSync(), the app stays open
     * and runSync()'s own Thread (once it notices the cancellation, same as any other cancelled
     * sync) re-enables both buttons itself in its finally block -- nothing else to do here. */
    private fun cancelSyncStayInApp() {
        cancelSync()
        SyncState.lastStatusText = getString(R.string.status_sync_cancelled)
        handleLogLine("[info] " + getString(R.string.status_sync_cancelled))
        hideProgressBar()
    }

    /** Builds and shows the logbook from whatever .ebl files are already sitting in
     * eblDownloadDir() -- no W2K-2 connection needed at all, for exactly the case that's
     * otherwise a dead end: the
     * device can't be reached right now, but there's still real (if possibly not fully current)
     * data already on the phone worth seeing (asked for explicitly). Publishes it too, same as a
     * normal sync's own auto-publish, if the SFTP settings are filled in. */
    private fun runOfflineBuild() {
        buildFromLocalFilesAndMaybePublish(forcePublish = false)
    }

    /** Shared by runOfflineBuild() (the offline/close dialog's "Logboek bouwen..." button) and
     * runPublish() (the ☁️ icon) -- both build the logbook from whatever .ebl files are already
     * on the phone, no W2K-2 connection needed. forcePublish=false keeps runOfflineBuild()'s own
     * existing behavior (gated on "Automatisch publiceren na bouwen"); runPublish() passes true
     * instead, since tapping ☁️ is itself the explicit request to publish, same as it already was
     * before this was shared. */
    private fun buildFromLocalFilesAndMaybePublish(forcePublish: Boolean) {
        if (SyncState.inProgress) return
        SyncState.inProgress = true
        // syncButton stays enabled here too -- same reasoning as runSync()'s own version of this
        // comment: a long local decode (see run_pipeline()'s should_cancel) should be cancellable
        // by tapping it again, same as a normal sync.
        publishButton.isEnabled = false
        // Log deliberately NOT cleared here (asked for explicitly, see runSync()'s own matching
        // comment) -- it accumulates across every run this process makes instead.
        SyncState.lastStatusText = getString(R.string.status_building_with_existing_data)
        handleLogLine("[info] ${SyncState.lastStatusText}")
        // No initial notification text of its own here (unlike runSync()) -- this path doesn't
        // start the notification until handleLogLine()'s first progress line arrives, so there's
        // nothing yet for a restore to show; null rather than stale text from a previous run.
        SyncState.lastNotificationText = null
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        showingLocalLogbook = false
        setLogExpanded(true)

        Thread {
            var syncSucceeded = false
            var didPublish = false
            try {
                val result = buildFromLocalFiles()
                syncSucceeded = result.ok
                withActiveActivity { showSyncResult(result) }
                // Same "Automatisch publiceren na bouwen" gate as runSync()'s own matching call,
                // unless forcePublish overrides it (see this function's own doc comment).
                if (result.ok && result.htmlPath != null && (forcePublish || settingsStore.autoPublishAfterBuild)) {
                    didPublish = uploadIfConfigured(result.htmlPath)
                }
                if (syncSucceeded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    handleLogLine(getString(R.string.log_sync_done_at, timeText))
                }
            } catch (e: Exception) {
                val message = getString(R.string.error_unexpected, e.toString())
                SyncState.lastStatusText = message
                handleLogLine("[error] $message")
            } finally {
                // Same "Voltooid"-completion treatment as runSync() -- see its own finally for
                // the full reasoning. Only posted if a notification was ever actually shown for
                // this run (see startSyncNotification()'s own eligibility check) -- this path,
                // unlike runSync(), doesn't start one up front, only lazily once handleLogLine()
                // sees real decode progress, so a short cache-hit-only rebuild may never have
                // shown one at all.
                stopService(Intent(this, SyncNotificationService::class.java))
                if (syncSucceeded && SyncState.notificationForegrounded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    SyncNotificationService.postCompletionNotification(
                        this,
                        getString(R.string.notif_sync_done_at, timeText),
                        if (didPublish) MainActivity.LIVE_SITE_URL else null,
                    )
                }
                SyncState.notificationForegrounded = false
                SyncState.notificationStartFailed = false
                SyncState.inProgress = false  // must always happen, see runSync()'s own finally
                withActiveActivity {
                    syncButton.isEnabled = true
                    updatePublishButtonEnabled()
                    hideProgressBar()
                }
            }
        }.start()
    }

    /** Chaquopy call to android_entry.build_from_local_files() -- decode/build/write only, no
     * discovery or download, over every .ebl file already present under eblDownloadDir(). */
    private fun buildFromLocalFiles(): SyncResult {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val androidEntry = Python.getInstance().getModule("nmea2log.android_entry")

        val downloadDir = eblDownloadDir()
        val outputHtmlPath = File(filesDir, "logbook.html")
        val sampleCachePath = File(filesDir, "sample_cache.pkl")
        // A plain array, not a Kotlin List -- found in practice: passing a List straight across
        // the Chaquopy boundary via callAttr() reached Python as something that raised
        // "TypeError: 'ArrayList' object is not iterable" the moment run_pipeline() tried to
        // iterate over it, apparently never actually exercised before today (this offline-build
        // path had no way to be reached without crashing the app first -- see
        // showOfflineOrCloseDialog()'s own fix). A String[] converts to a genuine Python
        // list/tuple instead.
        val eblPaths = downloadDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ebl", ignoreCase = true) }
            .map { it.absolutePath }
            .toList().toTypedArray()

        // Captured by the controller's onResult() below, not read back out of callAttr()'s own
        // return value afterward -- see SyncController.onResult()'s own doc comment for why.
        var capturedResult: SyncResult? = null

        // report() and onDownloadComplete() don't apply here (nothing is downloaded on this
        // path) -- only onLogLine() (see handleLogLine(), shared with syncFromW2k2()'s own
        // controller, found in practice: this call used to have none of this wiring at all, so a
        // real decode left the screen stuck on one static message) and isCancelled() (lets the ✕
        // button stop a long offline decode too, same as a normal sync) are meaningful.
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {}
            override fun isCancelled(): Boolean = SyncState.cancelled
            override fun onLogLine(line: String) = handleLogLine(line)
            override fun onDownloadComplete() {}

            override fun onResult(
                ok: Boolean,
                error: String?,
                cancelled: Boolean,
                tripCount: Int,
                htmlPath: String?,
                downloadedCount: Int,
            ) {
                capturedResult = SyncResult(
                    ok = ok,
                    cancelled = cancelled,
                    error = error,
                    tripCount = if (ok && tripCount >= 0) tripCount else null,
                    htmlPath = if (ok) htmlPath else null,
                    downloadedCount = null, // no download happened this run
                )
            }
        }

        androidEntry.callAttr(
            "build_from_local_files",
            eblPaths,
            outputHtmlPath.absolutePath,
            sampleCachePath.absolutePath,
            settingsStore.boatName,
            settingsStore.mmsi,
            settingsStore.callSign,
            controller,
            settingsStore.minStopMinutes,
        )

        return capturedResult ?: SyncResult(
            ok = false, cancelled = false, error = getString(R.string.error_no_result),
            tripCount = null, htmlPath = null, downloadedCount = null,
        )
    }

    /** Uploads the fresh logbook (always, if SFTP or REST publish settings are filled in) --
     * called after a successful sync, still on its background Thread. Runs at most once per
     * sync; failures here are reported in the log but never hide the logbook that's already
     * showing in the WebView by that point.
     *
     * No longer gated on wifi-vs-mobile-data (removed, asked for explicitly): the logbook upload
     * is small enough now that the data cost is negligible, so it always runs regardless of
     * connection type instead of silently skipping on cellular. */
    private fun uploadIfConfigured(htmlPath: String): Boolean {
        // REST (see RestUploader.kt) is preferred over SFTP whenever both happen to be
        // configured, same choice cli.py's own _run() makes -- needs no SSH key/password on this
        // device at all, just a WordPress Application Password. Not "REST, falling back to SFTP
        // if REST fails" within the same run: a failed upload should surface as a failed upload,
        // not silently retry a completely different transport the owner may not have intended to
        // lean on at all.
        val useRest = settingsStore.isRestUploadConfigComplete
        if (!useRest && !settingsStore.isSftpConfigComplete) {
            handleLogLine("[skip] " + getString(R.string.log_upload_not_configured))
            return false
        }
        val statusText = if (useRest) getString(R.string.status_uploading_wordpress) else getString(R.string.status_uploading_sftp)
        handleLogLine("[info] $statusText")
        // Also pushed to the OS notification itself, not just the log -- asked for explicitly:
        // SyncState.uploading below (see its own doc comment) means closing the app
        // mid-upload no longer interrupts it, so the notification is now the only place this
        // phase is visible at all for as long as the owner's actually looking at it instead of
        // the app. Without this it kept showing whatever the last download/decode-phase text
        // happened to be, well past the point that was still true. Shorter than the log line
        // above, without "(naar WordPress)"/"(via SFTP)" -- asked for explicitly, that detail belongs
        // in the log (which is right there to check), not repeated in the notification too.
        startSyncNotification(
            Intent(this, SyncNotificationService::class.java)
                .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, getString(R.string.notif_uploading)),
        )
        // Sets SyncState.uploading for SyncNotificationService.onTaskRemoved() -- unlike a
        // download (resumes cleanly next run over HTTP Range, see w2k2_download.py) or a decode/
        // build (re-runs from wherever it was, backed by the sample cache), a publish isn't
        // itself safely resumable mid-request, and it's comparatively fast anyway (asked for
        // explicitly: closing the app should be free to interrupt everything else, just not
        // this). Always reset in finally, including on the early-return failure paths below.
        SyncState.uploading = true
        try {
            if (useRest) {
                RestUploader.uploadLogbook(
                    this, settingsStore.restUploadUrl, settingsStore.restUploadUser,
                    settingsStore.restUploadPassword, File(htmlPath),
                )
                handleLogLine("[ok] " + getString(R.string.log_upload_ok_wordpress, settingsStore.restUploadUrl))
            } else {
                SftpUploader.uploadLogbookAtomic(this, settingsStore, File(htmlPath))
                handleLogLine(
                    "[ok] " + getString(
                        R.string.log_upload_ok_sftp,
                        settingsStore.sftpUser, settingsStore.sftpHost, settingsStore.sftpRemotePath,
                    ),
                )
            }
        } catch (e: RestUploadError) {
            handleLogLine("[error] " + getString(R.string.log_upload_failed_wordpress, e.message))
            return false
        } catch (e: SftpUploadError) {
            handleLogLine("[error] " + getString(R.string.log_upload_failed_sftp, e.message))
            return false
        } finally {
            SyncState.uploading = false
        }
        return true
    }

    // Set by showOfflineOrCloseDialog() when it couldn't show right away because the Activity
    // wasn't visible -- shown as soon as onResume() sees it's non-null instead.
    private var pendingOfflineOrCloseMessage: String? = null

    // The notification-foregrounded/start-failed flags live on SyncState now (process-wide, not
    // per-instance) -- see SyncState.notificationForegrounded's own doc comment for why.

    /** Starts/updates the sync notification, tolerating Android refusing to start it -- a
     * "dataSync" foreground service is capped at 6 cumulative hours per 24h on Android 15+ (this
     * app targets 37); once that budget is exhausted the system throws instead of starting it,
     * until either 24h roll around or the owner brings the app to the foreground themselves. The
     * sync/upload work itself still proceeds either way (this call only ever drives the visible
     * notification, nothing functional depends on it) -- just without a notification, rather than
     * the whole sync crashing over a UI nicety it couldn't get. Called from SyncController.report()
     * on Python's own background thread as well as from the main thread, so the fallback status
     * update is wrapped in runOnUiThread rather than assuming either.
     *
     * Once the service is already running and foreground, later calls redeliver the intent via a
     * plain startService() instead of startForegroundService() -- found in practice: Android can
     * refuse a *new* startForegroundService() call the moment the app is no longer in an eligible
     * state (e.g. the screen just locked), even though the service is already legitimately
     * foreground and only needs its notification *text* updated, not a fresh foreground grant.
     * Before this, every decode-progress update (one every couple of seconds, see
     * decodeProgressRegex) hit that refusal and appended its own copy of the failure message to
     * the on-screen status text of the day, flooding the screen with dozens of identical lines
     * within a minute.
     *
     * That fix alone wasn't enough, though (found in practice, again): if the *very first* call
     * of a run is itself refused -- now routine since the app minimizes itself shortly after
     * starting (see autoStartSyncWithSettingsRetry()), which is exactly the kind of state change
     * that can revoke foreground-start eligibility -- SyncState.notificationForegrounded never becomes
     * true, so *every* later call kept retrying startForegroundService() and hitting the same
     * refusal, reproducing the exact same flood one level up. SyncState.notificationStartFailed short-
     * circuits that: once refused, silently skip every further attempt (no repeated failure text
     * either) until onResume() restores it -- see onResume(). */
    private fun startSyncNotification(intent: Intent) {
        if (SyncState.notificationStartFailed) {
            return
        }
        try {
            if (SyncState.notificationForegrounded) {
                startService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
                SyncState.notificationForegrounded = true
            }
        } catch (e: Exception) {
            SyncState.notificationStartFailed = true
            // A short, plain message, not the raw exception -- found in practice: dumping
            // "android.app.ForegroundServiceStartNotAllowedException: startForegroundService()
            // not allowed due to mAllowStartForeground false: service com.example...." into the
            // log reads like a crash even though the sync itself is completely unaffected (see
            // this function's own doc comment above). The budget-exhaustion case (the routine
            // one, see that doc comment) gets its own specific wording; anything else still shows
            // the real exception, since that would be genuinely unexpected here.
            val reason = if (e is ForegroundServiceStartNotAllowedException) {
                getString(R.string.reason_notification_daily_limit)
            } else {
                e.toString()
            }
            handleLogLine("[info] " + getString(R.string.log_notification_could_not_be_shown, reason))
        }
    }

    /** A Button that's just a toolbar icon (tight padding, no background) -- either a single
     * emoji glyph (still used for ↺/⚙, which never had a legibility complaint), or a real
     * Material vector icon (iconRes) shown as a compound "drawable" with no text, tinted to
     * match the button's own default text color so it follows the app's DayNight theme the same
     * way the emoji glyphs already did (found in practice: ☁️ alone for "publish" and 📖 for
     * "view the logbook" both read as unclear/too old-fashioned respectively -- asked for
     * explicitly to replace with recognizable Material icons instead: ic_upload_24, ic_article_24).
     * tooltip shows on a long-press (standard Android behavior for View.setTooltipText(), asked
     * for explicitly, covers both styles the same way). */
    private fun iconButton(
        tooltip: String,
        emoji: String? = null,
        iconRes: Int? = null,
        emojiSize: Float = 26f,
        emojiBold: Boolean = false,
        verticalNudgePx: Float = 0f,
        onClick: () -> Unit,
    ): Button {
        val size = (16 * resources.displayMetrics.density).toInt()
        // Borderless + no minimum size: a plain Button here still carries the default Material
        // button chrome (background box, shadow/elevation, a fairly large minimum touch target)
        // even with just an icon as its content, which reads as a boxed button rather than a
        // standalone icon (found in practice, asked for explicitly). A borderless circular ripple
        // background (the same one Android's own icon buttons use) plus dropping the minimum
        // width/height gets the plain-icon look without needing a drawable/vector asset for the
        // emoji case, and without the default Button chrome for the vector-icon case either.
        val backgroundValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
        return Button(this).apply {
            if (iconRes != null) {
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(currentTextColor)
            } else {
                text = emoji
                // Bumped up from 20f (asked for explicitly, found in practice: next to the real
                // 24dp Material icons above, the plain-text emoji glyphs read noticeably smaller
                // even at the same nominal size) -- brings ↺/⚙ closer to the same visual weight.
                // syncButton overrides emojiSize/emojiBold further still (asked for explicitly:
                // ↺ alone still read too small/thin after this general bump).
                textSize = emojiSize
                if (emojiBold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            setPadding(size, size / 2, size, size / 2)
            // Row-level centering (see buttonRow's isBaselineAligned/gravity) aligns each button's
            // whole bounding box, not the visible ink inside it -- doesn't help when a specific
            // glyph's own font metrics place its ink off-center within that box. Measured directly
            // on-device (found in practice): ↺ at emojiBold/30f sits ~12px lower than the vector
            // icons' visible ink even though their boxes now line up, so it alone gets nudged.
            // verticalNudgePx is in dp, scaled to this device's actual pixel density here -- found
            // in practice on a second, lower-density tablet: a raw (un-scaled) px nudge tuned on
            // one phone's screen translated to a visibly larger shift (the icon sitting noticeably
            // too high) on a device with fewer pixels per dp.
            if (verticalNudgePx != 0f) translationY = verticalNudgePx * resources.displayMetrics.density
            setBackgroundResource(backgroundValue.resourceId)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null // drops the default press elevation animation/shadow
            ViewCompat.setTooltipText(this, tooltip)
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


    /** Tapping the notification a second time while the app is already showing hides it again
     * (asked for explicitly: an auto-started sync minimizes itself right away -- see
     * autoStartSyncWithSettingsRetry() -- and the notification is then the primary way to check
     * on it; tapping it should toggle the full UI open and closed rather than being a one-way
     * "show" button). Only reachable via the notification's own PendingIntent (see
     * SyncNotificationService's openAppIntent, which sets this action) -- a plain relaunch (the
     * launcher icon, the task switcher) never carries it, so those always just show the app,
     * never hide it (asked for explicitly too).
     *
     * lifecycle.currentState reflects whether this Activity is still the visible, resumed one at
     * the moment the intent arrives: still RESUMED when tapped again while already on top (the
     * OS delivers straight here without pausing it first), not yet RESUMED when tapped while it
     * had been minimized via moveTaskToBack (delivered here first, then the OS resumes it) -- so
     * checking this instead of a separately hand-tracked boolean can't drift out of sync with the
     * real lifecycle state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TOGGLE_FROM_NOTIFICATION && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // This instance is now the one live sync updates should reach -- set before
        // restoreLiveSyncUi() below, whose whole point is to bring THIS instance's own views up
        // to date (see SyncState.active's own doc comment for the bug this fixes).
        SyncState.active = this
        // Settings can only have changed via a round trip through SettingsActivity and back --
        // re-checks here so a publish method that was just filled in (or cleared) is reflected
        // immediately, without waiting for a sync to finish.
        updatePublishButtonEnabled()
        // Brings logView/the progress bar up to date with whatever a sync -- still in
        // progress, or one that already finished while this Activity wasn't the active one --
        // has produced so far. Not gated on SyncState.inProgress alone: found in practice, a
        // real, reported "app hangs" bug -- a sync that finishes while the app is backgrounded
        // runs its own finally block (showSyncResult()/hideProgressBar()) entirely through
        // withActiveActivity, which no-ops with nothing active to update; by the time this
        // Activity resumes, inProgress is already back to false, so the old "still in progress"-
        // only condition here skipped restoring anything at all, leaving whatever mid-sync
        // progress bar/log text was on screen before backgrounding frozen there indefinitely --
        // looking exactly like a hang, even though the sync itself had completed normally.
        // lastStatusText is only ever null before the very first sync this install has ever run,
        // in which case there's nothing to restore and onCreate()'s own placeholder text is
        // still correct as-is.
        if (SyncState.inProgress || SyncState.lastStatusText != null) {
            restoreLiveSyncUi()
        }
        // Same background-completion gap as above, but for the WebView specifically: loading the
        // freshly-built logbook only ever happens inside showSyncResult()'s own success branch,
        // which (like the rest of that finally block) silently no-ops via withActiveActivity if
        // this Activity wasn't the active one when a sync finished. Without this, the status text
        // above would correctly say "Klaar: N reis(en)..." after reopening the app, while the map/
        // table underneath it kept showing whatever logbook (possibly none at all) was loaded
        // before backgrounding -- reopening the app to check on the wait wouldn't actually show
        // its result. Gated on the file's own mtime, not reloaded unconditionally: this runs on
        // every resume (including a plain app-switch with nothing new to show), and force-
        // reloading an unchanged page would discard an unsaved Remarks edit sitting open in the
        // WebView for no reason.
        if (!SyncState.inProgress) {
            val htmlFile = File(filesDir, "logbook.html")
            if (htmlFile.exists() && htmlFile.lastModified() != lastLoadedHtmlMtime) {
                loadLogbookIntoWebView(htmlFile.absolutePath)
            }
        }
        // Covers being brought back via the launcher icon (or the task switcher) while a sync is
        // still genuinely running but its notification isn't up right now -- e.g. the brief
        // download-to-decode transition gap, or an earlier startForegroundService() refusal while
        // the app was in the background (see startSyncNotification()) -- now that the app is
        // visibly in the foreground again, a fresh start is allowed to go through, restoring it
        // instead of leaving the user with no visible sync indicator at all outside the app.
        //
        // Checks the real, current notification shade (this app's own postable notifications --
        // no special permission needed, unlike reading *other* apps' notifications), not just
        // SyncState.notificationForegrounded -- found in practice, a real bug reported directly:
        // that flag only ever gets set back to false by this app's own code (the sync Thread's
        // finally block, cancelSync(), ...), so if the service or its notification instead
        // disappeared some other way (the OS reclaiming it, a crash inside the service itself),
        // the flag stayed stuck "still up" and this restore never fired at all.
        // NotificationManagerCompat has no activeNotifications accessor -- only the platform
        // NotificationManager does (API 23+, well below this app's minSdk 24).
        val notificationActuallyUp = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.any { it.id == SyncNotificationService.NOTIFICATION_ID }
        if (SyncState.inProgress && !notificationActuallyUp) {
            // Give it a fresh chance even if an earlier attempt was refused -- see
            // SyncState.notificationStartFailed's own doc on startSyncNotification() -- now that the app
            // is genuinely foreground again, that earlier refusal no longer applies.
            SyncState.notificationForegrounded = false
            SyncState.notificationStartFailed = false
            // SyncState.lastNotificationText, not SyncState.lastStatusText -- found in practice,
            // a real bug: decode/build's own progress ("Reizen opbouwen: 3/4") only ever gets
            // written straight into the notification (see handleLogLine()) and was never
            // reflected in lastStatusText at all -- so falling back to that here restored the
            // wrong, stale text (e.g. "Logboek opbouwen met bestaande gegevens...", correct only
            // at the very start of an offline build) well into that later phase. lastNotificationText
            // is kept in lockstep with the notification's own real content at every call site that
            // sets it, so restoring from it can't drift the same way; the plain fallback only
            // matters for a sync so early nothing has set either field yet.
            val restoreIntent = Intent(this, SyncNotificationService::class.java).putExtra(
                SyncNotificationService.EXTRA_STATUS_TEXT,
                SyncState.lastNotificationText ?: SyncState.lastStatusText ?: getString(R.string.status_sync_already_running_fallback),
            )
            startSyncNotification(restoreIntent)
        }
        pendingOfflineOrCloseMessage?.let { message ->
            pendingOfflineOrCloseMessage = null
            showOfflineOrCloseDialog(message)
        }
    }

    override fun onPause() {
        super.onPause()
        // Only clear if this instance is still the one recorded as active -- a newer instance's
        // own onResume() (already having set itself) must never be undone by this older
        // instance's onPause() running after it, which order-of-events would otherwise allow
        // (Android pauses the old instance only partway through creating/resuming the new one).
        if (SyncState.active === this) {
            SyncState.active = null
        }
    }

    companion object {
        const val ACTION_TOGGLE_FROM_NOTIFICATION = "com.example.mysailinglogbook.ACTION_TOGGLE_FROM_NOTIFICATION"

        // The public, WordPress-gated view of whatever was just published (see uploadIfConfigured()
        // and the "Bekijk live site" notification action) -- not derived from SettingsStore's own
        // sftpRemotePath, which is the *private* SFTP destination (outside the web root, see
        // little_endian-index.php's own doc comment), not a browsable URL at all.
        const val LIVE_SITE_URL = "https://ayuus.com/little_endian/"
    }
}
