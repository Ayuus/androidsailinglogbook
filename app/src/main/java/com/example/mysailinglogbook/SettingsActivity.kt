package com.example.mysailinglogbook

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Plain form for the settings SettingsStore holds -- W2K-2 login, boat identity, sync interval,
 * and (Milestone B) SFTP publish settings. Only W2K-2 user/password are required to Save -- SFTP
 * fields can stay empty until the owner is ready to publish; that's checked separately when the
 * "logboek naar ayuus.com" icon is tapped (see MainActivity.runUpload()).
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

        val userField = field("W2K-2 gebruikersnaam", store.w2k2User)
        val passwordField = field("W2K-2 wachtwoord", store.w2k2Password, isPassword = true)
        val boatNameField = field("Bootnaam", store.boatName)
        val mmsiField = field("MMSI", store.mmsi)
        val callSignField = field("Roepnaam", store.callSign)
        val intervalField = field("Sync-interval (minuten, minimaal 15)", store.syncIntervalMinutes.toString())

        sectionHeader("Publiceren naar ayuus.com")
        val sftpHostField = field("SFTP host", store.sftpHost)
        val sftpPortField = field("SFTP poort", store.sftpPort.toString())
        val sftpUserField = field("SFTP gebruikersnaam", store.sftpUser)
        val sftpPasswordField = field("SFTP wachtwoord", store.sftpPassword, isPassword = true)
        val sftpRemotePathField = field("SFTP pad op de server", store.sftpRemotePath)
        val sftpEblBackupPathField = field(
            "Back-up map voor .ebl-bestanden (leeg = geen back-up)",
            store.sftpEblBackupRemotePath,
        )

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

        val saveButton = Button(this).apply {
            text = "Opslaan"
            setPadding(0, padding, 0, 0)
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
                store.syncIntervalMinutes = intervalField.text.toString().toIntOrNull()
                    ?.coerceAtLeast(15) ?: SettingsStore.DEFAULT_SYNC_INTERVAL_MINUTES
                store.sftpHost = sftpHostField.text.toString().trim()
                store.sftpPort = sftpPortField.text.toString().toIntOrNull() ?: SettingsStore.DEFAULT_SFTP_PORT
                store.sftpUser = sftpUserField.text.toString().trim()
                store.sftpPassword = sftpPasswordField.text.toString()
                store.sftpRemotePath = sftpRemotePathField.text.toString().trim()
                store.sftpEblBackupRemotePath = sftpEblBackupPathField.text.toString().trim()
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
