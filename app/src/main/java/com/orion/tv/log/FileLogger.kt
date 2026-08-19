package com.orion.tv.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only on-device log file, since a release build has no attached debugger/logcat for the
 * user to hand over. Writes to the app's private files dir (no storage permission needed) and
 * rotates to keep it bounded. SettingsActivity exposes a "导出日志" share action to get the file
 * off the device.
 */
object FileLogger {

    private const val MAX_BYTES = 2L * 1024 * 1024 // 2MB
    private val lock = ReentrantLock()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private lateinit var destination: File

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        destination = File(dir, "orion.log")
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    fun logFile(): File? = if (::destination.isInitialized) destination else null

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        if (level == "E") Log.e(tag, message, throwable) else Log.d(tag, message)
        if (!::destination.isInitialized) return
        lock.withLock {
            runCatching {
                rotateIfNeeded()
                destination.appendText("${timeFormat.format(Date())} $level/$tag: $message\n")
                if (throwable != null) {
                    destination.appendText(Log.getStackTraceString(throwable) + "\n")
                }
            }
        }
    }

    private fun rotateIfNeeded() {
        if (destination.exists() && destination.length() > MAX_BYTES) {
            destination.delete()
        }
    }
}
