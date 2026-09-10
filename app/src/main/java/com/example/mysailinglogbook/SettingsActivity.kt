package com.example.mysailinglogbook

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

        fun field(label: String, initialValue: String, isPassword: Boolean = false): EditText {
            layout.addView(
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
            layout.addView(editText)
            return editText
        }

        fun sectionHeader(text: String) {
            layout.addView(
                TextView(this).apply {
                    this.text = text
                    setPadding(0, padding * 2, 0, 0)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
        }

        fun checkbox(label: String, initialValue: Boolean): CheckBox {
            val box = CheckBox(this).apply {
                text = label
                isChecked = initialValue
                setPadding(0, padding, 0, 0)
            }
            layout.addView(box)
            return box
        }

        val userField = field("W2K-2 gebruikersnaam", store.w2k2User)
        val passwordField = field("W2K-2 wachtwoord", store.w2k2Password, isPassword = true)
        val boatNameField = field("Bootnaam", store.boatName)
        val mmsiField = field("MMSI", store.mmsi)
        val callSignField = field("Roepnaam", store.callSign)
        val autoSyncOnLaunchBox = checkbox(
            "Automatisch downloaden bij starten (anders alleen via ↺)",
            store.autoSyncOnLaunch,
        )

        sectionHeader("Reizen")
        val minStopMinutesField = field(
            "Minimale stop-tijd om als havenbezoek te tellen (minuten)",
            store.minStopMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

        sectionHeader("Publiceren naar ayuus.com")
        val autoPublishAfterBuildBox = checkbox(
            "Automatisch publiceren na bouwen (anders alleen via ☁️)",
            store.autoPublishAfterBuild,
        )
        // REST is preferred over SFTP below whenever both happen to be filled in (see
        // MainActivity.uploadIfConfigured()) -- needs no SSH key/password on this device at all,
        // just a WordPress Application Password (Users > Profile > Application Passwords on the
        // account's own profile page, not the account's real login password) for an account in
        // the logboek_editor role.
        val restUploadUrlField = field("REST upload-URL (WordPress-plugin)", store.restUploadUrl)
        val restUploadUserField = field("WordPress gebruikersnaam", store.restUploadUser)
        val restUploadPasswordField = field(
            "WordPress application password", store.restUploadPassword, isPassword = true,
        )
        val sftpHostField = field("SFTP host", store.sftpHost)
        val sftpPortField = field("SFTP poort", store.sftpPort.toString())
        val sftpUserField = field("SFTP gebruikersnaam", store.sftpUser)
        val sftpPasswordField = field("SFTP wachtwoord", store.sftpPassword, isPassword = true)
        val sftpRemotePathField = field("SFTP pad op de server", store.sftpRemotePath)

        // The host-key fingerprint (see SftpUploader.kt) isn't a credential -- it's the server's
        // own public key, used to reject a *later, different* key instead of silently trusting it
        // (could mean a man-in-the-middle). Left empty (the default), it's pinned automatically on
        // the very first connection (trust-on-first-use) -- filled in here instead, that first
        // connection is verified against it too, rather than blindly trusted. sshj reports it in
        // the same colon-separated-hex form ssh-keygen -lf/-E md5 shows.
        val sftpHostKeyField = field(
            "SFTP host-key fingerprint (optioneel; leeg = automatisch vertrouwen bij eerste " +
                "verbinding)",
            store.sftpHostKeyFingerprint,
        )

        // Cache-legen: two separate buttons rather than one "clear everything" -- the two caches
        // are cleared for different reasons (a decode/trip-build bug vs. a wrong/stale place
        // name or weather value) and clearing the wrong one is real, avoidable extra network/CPU
        // cost (a cleared "data" cache re-decodes and re-classifies every .ebl file from
        // scratch; a cleared "plaatsnamen" cache re-does every geocoding/weather/marine lookup),
        // so keeping them separate lets whichever one is actually the problem be cleared without
        // paying for the other (asked for explicitly).
        sectionHeader("Cache")

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
                            .setPositiveButton("Legen") { _, _ ->
                                files().forEach { it.deleteRecursively() }
                                Toast.makeText(this@SettingsActivity, "Cache geleegd", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Annuleren", null)
                            .show()
                    }
                },
            )
        }

        clearCacheButton(
            "Cache: Data",
            "Alle gedecodeerde en opgebouwde reisdata wissen? De eerstvolgende synchronisatie " +
                "decodeert en verwerkt dan alle .ebl-bestanden opnieuw vanaf het begin (duurt " +
                "langer, maar er gaat niets verloren -- de .ebl-bestanden zelf blijven staan).",
        ) {
            listOf(File(filesDir, "sample_cache.pkl"), File(filesDir, ".trip_cache.pkl"))
        }
        clearCacheButton(
            "Cache: Plaatsnamen",
            "Alle opgezochte plaatsnamen, weer- en golfgegevens wissen? De eerstvolgende " +
                "synchronisatie zoekt deze dan opnieuw op (kost extra mobiele data).",
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
            text = "Opslaan"
            isAllCaps = false // see clearCacheButton()'s own comment on this
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(padding, padding, padding, padding) }
            setOnClickListener {
                if (userField.text.isBlank() || passwordField.text.isBlank()) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Gebruikersnaam en wachtwoord zijn verplicht",
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
                store.restUploadUrl = restUploadUrlField.text.toString().trim()
                store.restUploadUser = restUploadUserField.text.toString().trim()
                store.restUploadPassword = restUploadPasswordField.text.toString()
                store.sftpHost = sftpHostField.text.toString().trim()
                store.sftpPort = sftpPortField.text.toString().toIntOrNull() ?: SettingsStore.DEFAULT_SFTP_PORT
                store.sftpUser = sftpUserField.text.toString().trim()
                store.sftpPassword = sftpPasswordField.text.toString()
                store.sftpRemotePath = sftpRemotePathField.text.toString().trim()
                store.sftpHostKeyFingerprint = sftpHostKeyField.text.toString().trim()
                Toast.makeText(this@SettingsActivity, "Instellingen opgeslagen", Toast.LENGTH_SHORT).show()
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
