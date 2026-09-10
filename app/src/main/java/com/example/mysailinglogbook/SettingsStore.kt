package com.example.mysailinglogbook

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences for the handful of settings this app needs -- W2K-2 login,
 * boat identity, and the SFTP publish settings. Replaces nmea2log.ini on Android (see
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

    val isW2k2ConfigComplete: Boolean
        get() = w2k2User.isNotBlank() && w2k2Password.isNotBlank()

    // On by default (preserves the original, always-on behavior for anyone upgrading) -- asked
    // for explicitly: the owner wants to choose whether opening the app tries to reach the W2K-2
    // right away (see MainActivity.onCreate()'s own autoStartSyncWithSettingsRetry() call) or
    // only ever syncs on an explicit ↺ tap.
    var autoSyncOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ON_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_LAUNCH, value).apply()

    // Password auth, not a private key -- confirmed working against the real TransIP account
    // (2026-09-02), unlike the Ed25519-key account spike 4 used. Host and remote path default to
    // the real production values (not secret); user/password are never defaulted.
    // Minimum stationary duration (minutes) to count as a real port visit -- same threshold as
    // the desktop CLI's --min-stop-minutes, but a real app setting here instead of an ini value
    // (there's no ini file on Android, see android_entry.py's run_pipeline docstring), asked for
    // explicitly. Stored as a Float (SharedPreferences has no Double getter/setter); Double at
    // the call site since that's what run_pipeline()'s own min_stop_minutes parameter expects.
    var minStopMinutes: Double
        get() = prefs.getFloat(KEY_MIN_STOP_MINUTES, DEFAULT_MIN_STOP_MINUTES).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_STOP_MINUTES, value.toFloat()).apply()

    // Preferred over SFTP below when configured (see MainActivity.uploadIfConfigured()) -- posts
    // straight to the WordPress REST endpoint (see wordpress-plugin/nmea2log-remarks.php's
    // /logbook route, and upload.py's own upload_via_rest() on the desktop side, which this calls
    // into over Chaquopy rather than reimplementing HTTP + Basic Auth here), so publishing needs
    // no SSH key/password on this device at all -- just a WordPress Application Password for an
    // account in the logboek_editor role. Never defaulted, unlike DEFAULT_SFTP_HOST/
    // DEFAULT_SFTP_REMOTE_PATH below -- found in practice: a brand new app install (a fresh
    // applicationId, see build.gradle.kts) still showed ayuus.com here despite nothing ever being
    // entered on that install, surprising enough to ask for it to stay blank until typed in.
    var restUploadUrl: String
        get() = prefs.getString(KEY_REST_UPLOAD_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_URL, value).apply()

    var restUploadUser: String
        get() = prefs.getString(KEY_REST_UPLOAD_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_USER, value).apply()

    var restUploadPassword: String
        get() = prefs.getString(KEY_REST_UPLOAD_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REST_UPLOAD_PASSWORD, value).apply()

    val isRestUploadConfigComplete: Boolean
        get() = restUploadUrl.isNotBlank() && restUploadUser.isNotBlank() && restUploadPassword.isNotBlank()

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

    // Optionally user-editable (see SettingsActivity): filled in, the very first SFTP connection
    // is verified against it instead of blindly trusted; empty (the default), it's pinned
    // automatically on that first connection (trust-on-first-use) instead. Either way, a later
    // connection presenting a *different* key than what's stored here gets rejected (see
    // SftpUploader) -- could mean a man-in-the-middle.
    var sftpHostKeyFingerprint: String
        get() = prefs.getString(KEY_SFTP_HOST_KEY_FINGERPRINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SFTP_HOST_KEY_FINGERPRINT, value).apply()

    companion object {
        const val DEFAULT_SFTP_HOST = "ayuusc.ssh.transip.me"
        const val DEFAULT_SFTP_PORT = 22
        const val DEFAULT_SFTP_REMOTE_PATH = "private/little_endian/logbook.html"
        // Same default as build_arg_parser()'s own --min-stop-minutes (see cli.py).
        const val DEFAULT_MIN_STOP_MINUTES = 10.0f
        private const val KEY_W2K2_USER = "w2k2_user"
        private const val KEY_W2K2_PASSWORD = "w2k2_password"
        private const val KEY_BOAT_NAME = "boat_name"
        private const val KEY_MMSI = "mmsi"
        private const val KEY_CALL_SIGN = "call_sign"
        private const val KEY_AUTO_SYNC_ON_LAUNCH = "auto_sync_on_launch"
        private const val KEY_MIN_STOP_MINUTES = "min_stop_minutes"
        private const val KEY_REST_UPLOAD_URL = "rest_upload_url"
        private const val KEY_REST_UPLOAD_USER = "rest_upload_user"
        private const val KEY_REST_UPLOAD_PASSWORD = "rest_upload_password"
        private const val KEY_SFTP_HOST = "sftp_host"
        private const val KEY_SFTP_PORT = "sftp_port"
        private const val KEY_SFTP_USER = "sftp_user"
        private const val KEY_SFTP_PASSWORD = "sftp_password"
        private const val KEY_SFTP_REMOTE_PATH = "sftp_remote_path"
        private const val KEY_SFTP_HOST_KEY_FINGERPRINT = "sftp_host_key_fingerprint"
    }
}
