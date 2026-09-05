package com.example.mysailinglogbook

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences for the handful of settings this app needs -- W2K-2 login,
 * boat identity, and the sync interval. Replaces nmea2log.ini on Android (see
 * docs/android-app-plan.md): no config file, values entered once via SettingsActivity.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var w2k2User: String
        get() = prefs.getString(KEY_W2K2_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_W2K2_USER, value).apply()

    var w2k2Password: String
        get() = prefs.getString(KEY_W2K2_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_W2K2_PASSWORD, value).apply()

    var boatName: String
        get() = prefs.getString(KEY_BOAT_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOAT_NAME, value).apply()

    var mmsi: String
        get() = prefs.getString(KEY_MMSI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MMSI, value).apply()

    var callSign: String
        get() = prefs.getString(KEY_CALL_SIGN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALL_SIGN, value).apply()

    // WorkManager's own 15-minute floor applies once Milestone B wires up periodic scheduling
    // (see docs/android-app-plan.md) -- a lower value is accepted here but won't be honored by
    // the OS yet.
    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL_MINUTES, value).apply()

    val isW2k2ConfigComplete: Boolean
        get() = w2k2User.isNotBlank() && w2k2Password.isNotBlank()

    // Password auth, not a private key -- confirmed working against the real TransIP account
    // (2026-09-02), unlike the Ed25519-key account spike 4 used. Host and remote path default to
    // the real production values (not secret); user/password are never defaulted.
    var sftpHost: String
        get() = prefs.getString(KEY_SFTP_HOST, DEFAULT_SFTP_HOST) ?: DEFAULT_SFTP_HOST
        set(value) = prefs.edit().putString(KEY_SFTP_HOST, value).apply()

    var sftpPort: Int
        get() = prefs.getInt(KEY_SFTP_PORT, DEFAULT_SFTP_PORT)
        set(value) = prefs.edit().putInt(KEY_SFTP_PORT, value).apply()

    var sftpUser: String
        get() = prefs.getString(KEY_SFTP_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SFTP_USER, value).apply()

    var sftpPassword: String
        get() = prefs.getString(KEY_SFTP_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SFTP_PASSWORD, value).apply()

    var sftpRemotePath: String
        get() = prefs.getString(KEY_SFTP_REMOTE_PATH, DEFAULT_SFTP_REMOTE_PATH) ?: DEFAULT_SFTP_REMOTE_PATH
        set(value) = prefs.edit().putString(KEY_SFTP_REMOTE_PATH, value).apply()

    val isSftpConfigComplete: Boolean
        get() = sftpHost.isNotBlank() && sftpUser.isNotBlank() && sftpPassword.isNotBlank() && sftpRemotePath.isNotBlank()

    // Empty (the default) means "don't back up .ebl files" -- the owner opts in by filling in a
    // remote folder, same as the desktop CLI's backup_ebl/backup_remote_path pair, but collapsed
    // into one field since Android has no separate on/off toggle to keep in sync with it (asked
    // for explicitly: "alleen doen als in instellingen een map staat waar ze naar toe moeten").
    var sftpEblBackupRemotePath: String
        get() = prefs.getString(KEY_SFTP_EBL_BACKUP_REMOTE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SFTP_EBL_BACKUP_REMOTE_PATH, value).apply()

    // Optionally user-editable (see SettingsActivity): filled in, the very first SFTP connection
    // is verified against it instead of blindly trusted; empty (the default), it's pinned
    // automatically on that first connection (trust-on-first-use) instead. Either way, a later
    // connection presenting a *different* key than what's stored here gets rejected (see
    // SftpUploader) -- could mean a man-in-the-middle.
    var sftpHostKeyFingerprint: String
        get() = prefs.getString(KEY_SFTP_HOST_KEY_FINGERPRINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SFTP_HOST_KEY_FINGERPRINT, value).apply()

    companion object {
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        const val DEFAULT_SFTP_HOST = "ayuusc.ssh.transip.me"
        const val DEFAULT_SFTP_PORT = 22
        const val DEFAULT_SFTP_REMOTE_PATH = "private/little_endian/logbook.html"
        private const val KEY_W2K2_USER = "w2k2_user"
        private const val KEY_W2K2_PASSWORD = "w2k2_password"
        private const val KEY_BOAT_NAME = "boat_name"
        private const val KEY_MMSI = "mmsi"
        private const val KEY_CALL_SIGN = "call_sign"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
        private const val KEY_SFTP_HOST = "sftp_host"
        private const val KEY_SFTP_PORT = "sftp_port"
        private const val KEY_SFTP_USER = "sftp_user"
        private const val KEY_SFTP_PASSWORD = "sftp_password"
        private const val KEY_SFTP_REMOTE_PATH = "sftp_remote_path"
        private const val KEY_SFTP_EBL_BACKUP_REMOTE_PATH = "sftp_ebl_backup_remote_path"
        private const val KEY_SFTP_HOST_KEY_FINGERPRINT = "sftp_host_key_fingerprint"
    }
}
