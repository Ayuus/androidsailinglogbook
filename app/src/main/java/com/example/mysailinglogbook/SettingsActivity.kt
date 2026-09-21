package com.example.mysailinglogbook

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Plain form for the settings SettingsStore holds -- W2K-2 login, boat identity, and SFTP publish
 * settings. Only W2K-2 user/password are required to Save -- SFTP fields can stay empty until the
 * owner is ready to publish; that's checked separately (isSftpConfigComplete) when the ☁️ icon is
 * tapped or a sync's own auto-publish runs (see MainActivity.uploadIfConfigured()).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        val padding = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun field(
            label: String,
            initialValue: String,
            isPassword: Boolean = false,
            container: LinearLayout = layout,
        ): EditText {
            container.addView(
                TextView(this).apply {
                    text = label
                    setPadding(0, padding, 0, 0)
                }
            )
            val editText = EditText(this).apply {
                setText(initialValue)
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            container.addView(editText)
            return editText
        }

        fun sectionHeader(text: String, disabled: Boolean = false) {
            layout.addView(
                TextView(this).apply {
                    this.text = text
                    setPadding(0, padding * 2, 0, 0)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    // Dimmed, not hidden -- still names the section (asked for explicitly: the
                    // header used to always claim "ayuus.com" even on a brand new install with
                    // nothing configured at all yet, see publishHeaderText below), just visually
                    // reads as "nothing here yet" rather than an active destination.
                    if (disabled) alpha = 0.5f
                }
            )
        }

        fun checkbox(label: String, initialValue: Boolean): CheckBox {
            val box = CheckBox(this).apply {
                text = label
                isChecked = initialValue
            }
            // Found in practice, asked for explicitly, and confirmed with pixel measurements:
            // CompoundButton positions its check-glyph using the view's raw height, ignoring
            // padding -- so a top *padding* (as used for every other field's spacing) shifts the
            // label text down without moving the glyph, breaking their shared vertical center. A
            // top *margin* doesn't have that bug, since it's handled by the parent layout instead
            // of CompoundButton's own draw logic.
            layout.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = padding },
            )
            return box
        }

        val userField = field(getString(R.string.label_w2k2_user), store.w2k2User)
        val passwordField = field(getString(R.string.label_w2k2_password), store.w2k2Password, isPassword = true)
        val boatNameField = field(getString(R.string.label_boat_name), store.boatName)
        val mmsiField = field(getString(R.string.label_mmsi), store.mmsi)
        val callSignField = field(getString(R.string.label_call_sign), store.callSign)
        val autoSyncOnLaunchBox = checkbox(
            getString(R.string.checkbox_auto_sync_on_launch),
            store.autoSyncOnLaunch,
        )

        sectionHeader(getString(R.string.section_trips))
        val minStopMinutesField = field(
            getString(R.string.label_min_stop_minutes),
            store.minStopMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

        // Derived from whatever's actually configured, not hardcoded -- asked for explicitly,
        // found in practice: this always read "Publiceren naar ayuus.com" even on a fresh
        // install with nothing filled in at all, misleadingly claiming a destination that wasn't
        // really set up yet (the exact same concern that already keeps restUploadUrl/sftpHost
        // themselves un-defaulted, see SettingsStore's own doc comments). REST preferred over
        // SFTP here too, matching uploadIfConfigured()'s own choice -- the host shown is whichever
        // one publishing would actually use right now. java.net.URI, not a manual string split:
        // handles a URL with or without a path/port/query correctly; a malformed URL (still being
        // typed, not yet a real URL) just falls through to the "not configured" state instead of
        // crashing this screen.
        val publishHost = when {
            store.restUploadUrl.isNotBlank() ->
                runCatching { java.net.URI(store.restUploadUrl).host }.getOrNull()
            store.sftpHost.isNotBlank() -> store.sftpHost
            else -> null
        }
        if (publishHost != null) {
            sectionHeader(getString(R.string.section_publish_to, publishHost))
        } else {
            sectionHeader(getString(R.string.section_publish_not_configured), disabled = true)
        }
        val autoPublishAfterBuildBox = checkbox(
            getString(R.string.checkbox_auto_publish_after_build),
            store.autoPublishAfterBuild,
        )

        // WordPress, SFTP, or not publishing at all -- exactly one at a time, never a
        // combination (uploadIfConfigured() only ever uses one, REST/WordPress preferred
        // whenever both happen to be filled in). All the relevant field blocks used to always
        // show together with no hint that only one is actually used, which read as "fill in
        // everything" -- asked for explicitly: make the choice explicit, expanding only the
        // fields it needs (none, for "don't publish").
        val publishMethodGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val wordpressRadio = RadioButton(this).apply { text = getString(R.string.radio_publish_wordpress) }
        val sftpRadio = RadioButton(this).apply { text = getString(R.string.radio_publish_sftp) }
        val noPublishRadio = RadioButton(this).apply { text = getString(R.string.radio_publish_none) }
        publishMethodGroup.addView(noPublishRadio)
        publishMethodGroup.addView(wordpressRadio)
        publishMethodGroup.addView(sftpRadio)
        layout.addView(
            publishMethodGroup,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = padding },
        )

        val wordpressFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sftpFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(wordpressFields)
        layout.addView(sftpFields)

        // Needs no SSH key/password on this device at all, just a WordPress Application Password
        // (Users > Profile > Application Passwords on the account's own profile page, not the
        // account's real login password) for an account in the logboek_editor role.
        val restUploadUrlField = field(getString(R.string.label_rest_upload_url), store.restUploadUrl, container = wordpressFields)
        val restUploadUserField = field(getString(R.string.label_rest_upload_user), store.restUploadUser, container = wordpressFields)
        val restUploadPasswordField = field(
            getString(R.string.label_rest_upload_password), store.restUploadPassword, isPassword = true, container = wordpressFields,
        )

        val sftpHostField = field(getString(R.string.label_sftp_host), store.sftpHost, container = sftpFields)
        val sftpPortField = field(getString(R.string.label_sftp_port), store.sftpPort.toString(), container = sftpFields)
        val sftpUserField = field(getString(R.string.label_sftp_user), store.sftpUser, container = sftpFields)
        val sftpPasswordField = field(
            getString(R.string.label_sftp_password), store.sftpPassword, isPassword = true, container = sftpFields,
        )
        val sftpRemotePathField = field(getString(R.string.label_sftp_remote_path), store.sftpRemotePath, container = sftpFields)

        // The host-key fingerprint (see SftpUploader.kt) isn't a credential -- it's the server's
        // own public key, used to reject a *later, different* key instead of silently trusting it
        // (could mean a man-in-the-middle). Left empty (the default), it's pinned automatically on
        // the very first connection (trust-on-first-use) -- filled in here instead, that first
        // connection is verified against it too, rather than blindly trusted. sshj reports it in
        // the same colon-separated-hex form ssh-keygen -lf/-E md5 shows.
        val sftpHostKeyField = field(
            getString(R.string.label_sftp_host_key_fingerprint),
            store.sftpHostKeyFingerprint,
            container = sftpFields,
        )

        // Only the picked method's fields are shown -- GONE, not just visually hidden, so the
        // collapsed block doesn't leave a blank gap ("don't publish" shows neither). Whichever
        // one is picked here also decides what Opslaan actually saves (see its click listener
        // below) -- the other route(s) are cleared, not just left untouched, so a leftover,
        // unpicked config from before can never silently win via uploadIfConfigured()'s own
        // REST-preferred order once "don't publish" (or the other method) has been chosen instead.
        fun updatePublishMethodVisibility() {
            wordpressFields.visibility = if (wordpressRadio.isChecked) View.VISIBLE else View.GONE
            sftpFields.visibility = if (sftpRadio.isChecked) View.VISIBLE else View.GONE
        }
        publishMethodGroup.setOnCheckedChangeListener { _, _ -> updatePublishMethodVisibility() }
        // Preselects whatever is already actually configured (isRestUploadConfigComplete/
        // isSftpConfigComplete require every field of that route to be filled in, not just one),
        // matching uploadIfConfigured()'s own REST-preferred order. "Don't publish" if neither is
        // complete yet -- also the correct default on a brand new install, replacing what used to
        // incorrectly default to WordPress even with nothing filled in at all.
        when {
            store.isRestUploadConfigComplete -> wordpressRadio.isChecked = true
            store.isSftpConfigComplete -> sftpRadio.isChecked = true
            else -> noPublishRadio.isChecked = true
        }
        updatePublishMethodVisibility()

        // Boat mode: rounds while the W2K-2 is reachable, a final round in the harbour or on
        // leaving the boat (see BootModeController / nmea2log/bootmode.py). The values only feed
        // BootModeConfig; the decisions themselves are made in Python.
        sectionHeader(getString(R.string.section_boat_mode))
        val bootIntervalOptions = listOf(30, 60, 120, 180)
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.label_boat_interval)
                setPadding(0, padding, 0, 0)
            }
        )
        val bootIntervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.boat_interval_30, R.string.boat_interval_60, R.string.boat_interval_120, R.string.boat_interval_180)
                    .map { getString(it) },
            )
            setSelection(bootIntervalOptions.indexOf(store.bootRoundIntervalMinutes).let { if (it < 0) 1 else it })
        }
        layout.addView(bootIntervalSpinner)
        val bootPublishEveryRoundBox = checkbox(getString(R.string.checkbox_boat_publish_every_round), store.bootPublishEveryRound)
        val bootFinalHarbourBox = checkbox(getString(R.string.checkbox_boat_final_harbour), store.bootFinalOnHarbour)
        val bootStationaryField = field(
            getString(R.string.label_boat_harbour_stationary_minutes), store.bootHarbourStationaryMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootEngineOffField = field(
            getString(R.string.label_boat_harbour_engine_off_minutes), store.bootHarbourEngineOffMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootFinalLeftBox = checkbox(getString(R.string.checkbox_boat_final_left), store.bootFinalOnLeftBoat)
        val bootLeftMinutesField = field(
            getString(R.string.label_boat_left_minutes), store.bootLeftBoatMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootStopAfterFinalBox = checkbox(getString(R.string.checkbox_boat_stop_after_final), store.bootStopAfterFinal)
        val bootAutoStartBox = checkbox(getString(R.string.checkbox_boat_auto_start), store.bootAutoStart)

        // Cache-legen: two separate buttons rather than one "clear everything" -- the two caches
        // are cleared for different reasons (a decode/trip-build bug vs. a wrong/stale place
        // name or weather value) and clearing the wrong one is real, avoidable extra network/CPU
        // cost (a cleared "data" cache re-decodes and re-classifies every .ebl file from
        // scratch; a cleared "plaatsnamen" cache re-does every geocoding/weather/marine lookup),
        // so keeping them separate lets whichever one is actually the problem be cleared without
        // paying for the other (asked for explicitly).
        sectionHeader(getString(R.string.section_cache))

        // deleteRecursively() rather than delete() -- sample_cache.pkl is a *directory* (one
        // small file per decoded .ebl file, see sample_cache.py's own module docstring for why),
        // not a plain file despite the name; delete() alone silently does nothing to a non-empty
        // directory. Harmless to call on a file that doesn't exist (or doesn't exist at all yet,
        // e.g. before the very first sync) -- deleteRecursively() returns false either way and
        // there's nothing further to do.
        fun clearCacheButton(label: String, confirmMessage: String, files: () -> List<File>) {
            layout.addView(
                // Outlined, not the default filled style -- these are secondary/occasional
                // actions (asked for explicitly to look nicer, and outlined reads as lower-
                // emphasis than the filled Opslaan button below without needing a whole separate
                // color). Full width + a real top margin (not just internal padding, which the
                // plain Button(this) this replaces was using) matches the full-width fields above
                // instead of a small, left-aligned, edge-touching button.
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    // MaterialButton's own default style forces all-caps regardless of the
                    // theme's android:textAllCaps=false (see themes.xml's own comment on why
                    // that's set app-wide) -- a style-level attribute wins over a theme-level one
                    // of the same name, found in practice: these still rendered as "CACHE: DATA"
                    // despite that theme override, until set explicitly here too.
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = padding }
                    setOnClickListener {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setMessage(confirmMessage)
                            .setPositiveButton(getString(R.string.button_clear)) { _, _ ->
                                files().forEach { it.deleteRecursively() }
                                Toast.makeText(this@SettingsActivity, getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(getString(R.string.button_cancel), null)
                            .show()
                    }
                },
            )
        }

        clearCacheButton(
            getString(R.string.button_cache_data),
            getString(R.string.dialog_clear_data_cache_message),
        ) {
            listOf(File(filesDir, "sample_cache.pkl"), File(filesDir, ".trip_cache.pkl"))
        }
        clearCacheButton(
            getString(R.string.button_cache_places),
            getString(R.string.dialog_clear_places_cache_message),
        ) {
            listOf(
                File(filesDir, ".geocode_cache.json"),
                File(filesDir, ".weather_cache.json"),
                File(filesDir, ".marine_cache.json"),
            )
        }

        // Default filled MaterialButton style (unlike the outlined cache buttons above) -- the
        // one clearly primary action on this screen, full width and with real margins on every
        // side so it reads as a deliberate bar rather than a small button touching the screen edge.
        val saveButton = MaterialButton(this).apply {
            text = getString(R.string.button_save)
            isAllCaps = false // see clearCacheButton()'s own comment on this
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(padding, padding, padding, padding) }
            setOnClickListener {
                if (userField.text.isBlank() || passwordField.text.isBlank()) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_username_password_required),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }
                store.w2k2User = userField.text.toString().trim()
                store.w2k2Password = passwordField.text.toString()
                store.boatName = boatNameField.text.toString().trim()
                store.mmsi = mmsiField.text.toString().trim()
                store.callSign = callSignField.text.toString().trim()
                store.autoSyncOnLaunch = autoSyncOnLaunchBox.isChecked
                store.minStopMinutes = minStopMinutesField.text.toString().toDoubleOrNull()
                    ?: SettingsStore.DEFAULT_MIN_STOP_MINUTES.toDouble()
                store.autoPublishAfterBuild = autoPublishAfterBuildBox.isChecked
                store.bootRoundIntervalMinutes = bootIntervalOptions[bootIntervalSpinner.selectedItemPosition]
                store.bootPublishEveryRound = bootPublishEveryRoundBox.isChecked
                store.bootFinalOnHarbour = bootFinalHarbourBox.isChecked
                store.bootHarbourStationaryMinutes = bootStationaryField.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 30
                store.bootHarbourEngineOffMinutes = bootEngineOffField.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
                store.bootFinalOnLeftBoat = bootFinalLeftBox.isChecked
                store.bootLeftBoatMinutes = bootLeftMinutesField.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 20
                store.bootStopAfterFinal = bootStopAfterFinalBox.isChecked
                store.bootAutoStart = bootAutoStartBox.isChecked
                // Only the picked method's fields are actually saved -- the other route(s) are
                // cleared instead of just left untouched, so the radio choice is a real,
                // unambiguous either-or-or-neither rather than just a display filter (see
                // updatePublishMethodVisibility()'s own comment on why). Whatever's still typed
                // into a currently-collapsed block on screen simply isn't saved -- switching the
                // choice without saving in between doesn't lose it, it's just not what gets
                // stored once Opslaan is actually tapped.
                if (wordpressRadio.isChecked) {
                    store.restUploadUrl = restUploadUrlField.text.toString().trim()
                    store.restUploadUser = restUploadUserField.text.toString().trim()
                    store.restUploadPassword = restUploadPasswordField.text.toString()
                    store.sftpHost = ""
                    store.sftpPort = SettingsStore.DEFAULT_SFTP_PORT
                    store.sftpUser = ""
                    store.sftpPassword = ""
                    store.sftpRemotePath = ""
                    store.sftpHostKeyFingerprint = ""
                } else if (sftpRadio.isChecked) {
                    store.restUploadUrl = ""
                    store.restUploadUser = ""
                    store.restUploadPassword = ""
                    store.sftpHost = sftpHostField.text.toString().trim()
                    store.sftpPort = sftpPortField.text.toString().toIntOrNull() ?: SettingsStore.DEFAULT_SFTP_PORT
                    store.sftpUser = sftpUserField.text.toString().trim()
                    store.sftpPassword = sftpPasswordField.text.toString()
                    store.sftpRemotePath = sftpRemotePathField.text.toString().trim()
                    store.sftpHostKeyFingerprint = sftpHostKeyField.text.toString().trim()
                } else {
                    store.restUploadUrl = ""
                    store.restUploadUser = ""
                    store.restUploadPassword = ""
                    store.sftpHost = ""
                    store.sftpPort = SettingsStore.DEFAULT_SFTP_PORT
                    store.sftpUser = ""
                    store.sftpPassword = ""
                    store.sftpRemotePath = ""
                    store.sftpHostKeyFingerprint = ""
                }
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Opslaan lives outside the ScrollView, not at the bottom of the scrolling field list --
        // with this many fields (W2K-2, boat identity, and the whole SFTP section) the button used
        // to only be reachable by scrolling all the way down, and a quick "fill in the SFTP fields,
        // then just tap back" felt like it saved but silently didn't (found in practice, asked for
        // explicitly to fix: settings appeared not to be remembered at all). Now it's always
        // visible regardless of scroll position, so there's no way to miss it.
        val scrollArea = ScrollView(this).apply {
            addView(layout)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollArea)
            addView(saveButton)
        }
        // Same edge-to-edge insets fix as MainActivity (found in practice during spike 2).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
    }
}
