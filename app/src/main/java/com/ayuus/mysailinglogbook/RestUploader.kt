package com.ayuus.mysailinglogbook

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import java.io.File

class RestUploadError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Uploads the built HTML logbook to the WordPress REST endpoint (see wordpress-plugin/
 * nmea2log-remarks.php's /logbook route) by calling straight into upload.py's own
 * upload_via_rest() over Chaquopy, instead of reimplementing HTTP + Basic Auth here in Kotlin --
 * there's only one canonical implementation of this (Python's), same as every other part of the
 * sync pipeline this app already shares with the desktop CLI via android_entry.py. Unlike SFTP
 * (see SftpUploader.kt, which needed its own from-scratch Kotlin implementation since there's no
 * SSH client on Android to shell out to the way upload.py does on desktop), a plain authenticated
 * HTTP POST has nothing platform-specific about it worth duplicating.
 */
object RestUploader {
    fun uploadLogbook(context: Context, url: String, user: String, appPassword: String, localFile: File) {
        val uploadModule = Python.getInstance().getModule("nmea2log.upload")
        try {
            uploadModule.callAttr("upload_via_rest", localFile.readBytes(), url, user, appPassword)
        } catch (e: PyException) {
            throw RestUploadError(context.getString(R.string.error_upload_failed, e.message), e)
        }
    }
}
