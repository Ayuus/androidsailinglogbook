package com.ayuus.mysailinglogbook

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/** Where the downloaded .ebl files live, shared by MainActivity's syncs and the boat-mode service. */
object EblStorage {
    /**
     * App-specific *external* storage (Android/data/<package>/files/Actisense), not filesDir (internal
     * storage, completely inaccessible from outside the app): the raw .ebl archive gets large over a
     * season and the owner wants to browse/copy it from a PC over USB. No extra permission needed for
     * an app's own external directory.
     *
     * One-time migration on top: earlier versions kept the same folder under filesDir -- moved
     * wholesale into place the first time this runs after updating (not deleted and downloaded again,
     * over the W2K-2's slow hotspot connection). Falls back to the internal folder if external storage
     * is not available at all (rare, e.g. briefly right after boot on some devices) or the migration
     * copy fails partway; a failed copy's partial leftovers are cleaned up so the next call retries.
     */
    fun downloadDir(context: Context): File {
        val oldDir = File(context.filesDir, "Actisense")
        val externalBase = context.getExternalFilesDir(null) ?: return oldDir
        val newDir = File(externalBase, "Actisense")
        if (oldDir.exists() && !newDir.exists()) {
            try {
                oldDir.copyRecursively(newDir, overwrite = false)
                oldDir.deleteRecursively()
            } catch (e: Exception) {
                newDir.deleteRecursively()
                return oldDir
            }
        }
        return newDir
    }

    /**
     * Makes .ebl files visible when browsing this device from a PC over USB (MTP). Files this app
     * writes straight into its own external folder are not reported to Android's media index, and MTP
     * lists that index rather than the folder itself (found in practice on Android 8.1: the whole
     * Actisense folder was missing in Windows Explorer while adb showed 2326 files). Only files
     * modified at or after [modifiedSince] (epoch ms) are handed over -- a run passes its own start
     * time, so a normal run only ever scans what it just downloaded. Asynchronous: the platform's
     * scanner service does the work, this returns immediately.
     */
    fun indexForPc(context: Context, actisenseDir: File, modifiedSince: Long) {
        val paths = actisenseDir.walkTopDown()
            .filter { it.isFile && it.extension == "ebl" && it.lastModified() >= modifiedSince }
            .map { it.absolutePath }
            .toList()
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
        }
    }
}
