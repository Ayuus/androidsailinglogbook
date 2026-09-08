package com.example.mysailinglogbook

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.EnumSet

class SftpUploadError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * sshj-based mirror of upload.py's behavior (see docs/android-app-plan.md's "SFTP replacement"
 * section) -- password auth (confirmed working against the real TransIP account, 2026-09-02,
 * unlike upload.py's key-based auth), trust-on-first-use host key pinning instead of a bundled
 * known_hosts file, and the same atomic put-then-rename logbook upload. Password auth is what
 * makes plain sshj usable here without also porting upload.py's private-key/EncryptedFile
 * handling.
 */
object SftpUploader {

    // Trust-on-first-use by default: if SettingsStore.sftpHostKeyFingerprint is still empty, the
    // first connection this app makes pins whatever key the server presents. The owner can also
    // fill that setting in themselves ahead of time (a known-correct fingerprint from a trusted
    // source), in which case even that very first connection is verified against it instead of
    // blindly trusted. Either way, every later connection is rejected if the key ever changes (a
    // changed key on a previously-trusted host is what you actually want flagged -- could mean a
    // man-in-the-middle) -- mirrors upload.py's "StrictHostKeyChecking=accept-new", just without a
    // known_hosts file to keep the pin in.
    private fun hostKeyVerifier(settingsStore: SettingsStore): HostKeyVerifier =
        object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = SecurityUtils.getFingerprint(key)
                val pinned = settingsStore.sftpHostKeyFingerprint
                if (pinned.isBlank()) {
                    settingsStore.sftpHostKeyFingerprint = fingerprint
                    return true
                }
                return pinned == fingerprint
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        }

    private fun connect(settingsStore: SettingsStore): SSHClient {
        val client = SSHClient()
        // Found in practice, a real bug: sshj has no bounded default here -- a flaky/unresponsive
        // server (or network) left the whole sync Thread stuck indefinitely inside connect() or a
        // later blocking SFTP operation (put/ls/mkdirs), with no way out short of force-closing
        // the app. connectTimeout bounds the initial TCP+SSH handshake; timeout bounds every
        // later blocking read on the connection (channel open, SFTP request/response, ...) --
        // both needed, since the hang isn't necessarily during the handshake itself.
        client.connectTimeout = 15_000
        client.timeout = 30_000
        client.addHostKeyVerifier(hostKeyVerifier(settingsStore))
        try {
            client.connect(settingsStore.sftpHost, settingsStore.sftpPort)
            client.authPassword(settingsStore.sftpUser, settingsStore.sftpPassword)
        } catch (e: Exception) {
            client.close()
            throw SftpUploadError("Kan geen SFTP-verbinding maken: ${e.message}", e)
        }
        return client
    }

    // sftp paths always use "/", regardless of platform -- unlike upload.py there's no Windows
    // backslash-escaping trap here (java.io.File.separator is always "/" on Android), but local
    // paths are built with File(...) throughout this class for consistency with the rest of the
    // Kotlin code, so this only ever matters for the *remote* side.
    private fun remoteJoin(vararg parts: String): String = parts.joinToString("/").replace(Regex("/+"), "/")

    /** Uploads localPath to remotePath via a temp name + rename, same as upload.py's
     * upload_file() -- a plain in-place "put" isn't atomic, so a page read mid-upload (e.g. the
     * WordPress gatekeeper, which reads this file fresh on every request) could see a truncated
     * file. No retry (matches upload.py's own upload_file(), retries=0): a single logbook upload
     * failing should surface immediately rather than silently costing the caller several seconds
     * first.
     *
     * RenameFlags.OVERWRITE, not the plain 2-arg rename() -- found in practice: every real run
     * renames the temp file over an *already-existing* remotePath (that's the whole point --
     * yesterday's logbook is still sitting there), and plain SFTP rename fails outright when the
     * destination already exists (a real SSH_FXP_RENAME protocol rule, not a bug on the server's
     * end). upload.py's own desktop equivalent never hit this: it shells out to the system sftp
     * CLI, which transparently upgrades to the posix-rename@openssh.com extension (overwrite-
     * capable) when the server supports it, same as this app's own target server clearly does --
     * sshj's rename() doesn't do that upgrade on its own, it has to be asked for explicitly. */
    fun uploadLogbookAtomic(settingsStore: SettingsStore, localFile: File) {
        val remotePath = settingsStore.sftpRemotePath
        val remoteTmpPath = "$remotePath.tmp-upload"
        connect(settingsStore).use { client ->
            client.newSFTPClient().use { sftp ->
                try {
                    sftp.put(localFile.absolutePath, remoteTmpPath)
                    sftp.rename(remoteTmpPath, remotePath, EnumSet.of(RenameFlags.OVERWRITE))
                } catch (e: Exception) {
                    throw SftpUploadError("Uploaden van logboek mislukt: ${e.message}", e)
                }
            }
        }
    }

    /** Backs up .ebl files to the configured remote folder, preserving each file's own EBL#####
     * subfolder remotely (unlike upload.py's flat upload_files(), asked for explicitly) -- so the
     * remote copy mirrors downloadDir's own EBL000000/, EBL000001/, ... structure instead of one
     * flat directory of thousands of same-shaped filenames. relativePaths are paths like
     * "EBL000018/000018_067.ebl", relative to downloadDir. A no-op if the backup path isn't
     * configured or there's nothing to back up -- no reason to even open a connection then.
     * Retries the whole batch up to twice on failure (5s apart), same as upload.py's
     * _BACKUP_MAX_RETRIES/_BACKUP_RETRY_DELAY_S -- backing up many files in a row against shared
     * hosting has been seen to reset the connection out of the blue (see upload.py). Skips a file
     * that's already present remotely (by name, within its own subfolder) instead of re-uploading
     * every file on every run. */
    fun backupEblFiles(settingsStore: SettingsStore, downloadDir: File, relativePaths: List<String>) {
        val remoteBaseDir = settingsStore.sftpEblBackupRemotePath
        if (remoteBaseDir.isBlank() || relativePaths.isEmpty()) return

        val byFolder: Map<String, List<String>> = relativePaths.groupBy { it.substringBefore("/") }
        var lastError: Exception? = null
        for (attempt in 0..BACKUP_MAX_RETRIES) {
            if (attempt > 0) Thread.sleep((BACKUP_RETRY_DELAY_S * 1000).toLong())
            try {
                connect(settingsStore).use { client ->
                    client.newSFTPClient().use { sftp ->
                        sftp.mkdirsIfMissing(remoteBaseDir)
                        for ((folder, relPaths) in byFolder) {
                            val remoteFolderDir = remoteJoin(remoteBaseDir, folder)
                            sftp.mkdirsIfMissing(remoteFolderDir)
                            val alreadyRemote = sftp.ls(remoteFolderDir).map { it.name }.toSet()
                            for (relPath in relPaths) {
                                val fileName = relPath.substringAfterLast("/")
                                if (fileName in alreadyRemote) continue
                                val local = File(downloadDir, relPath)
                                sftp.put(local.absolutePath, remoteJoin(remoteFolderDir, fileName))
                            }
                        }
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw SftpUploadError("Back-up van .ebl-bestanden mislukt: ${lastError?.message}", lastError)
    }

    // sshj's SFTPClient.mkdir() (and mkdirs()) throws if the directory already exists, unlike
    // upload.py's "-mkdir" (a leading "-" makes the sftp CLI ignore that specific error) -- this
    // is the same "ignore only already-exists, propagate anything else" behavior via a plain
    // try/catch, since sshj doesn't expose a distinguishable exception type for it either.
    private fun SFTPClient.mkdirsIfMissing(path: String) {
        try {
            mkdirs(path)
        } catch (e: Exception) {
            // Already exists (the common case on every run after the first) or a real problem --
            // either way, the very next operation on this path (ls/put) will fail loudly if it
            // genuinely doesn't exist and couldn't be created.
        }
    }

    private const val BACKUP_MAX_RETRIES = 2
    private const val BACKUP_RETRY_DELAY_S = 5.0
}
