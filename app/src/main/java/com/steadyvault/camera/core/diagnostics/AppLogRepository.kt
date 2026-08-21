package com.steadyvault.camera.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AppLogRepository {
    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "steadyvault.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val KEEP_BYTES = 1024L * 1024L
    private const val PREFS = "steadyvault_diagnostics"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private val lock = Any()
    private val crashHandlerInstalled = AtomicBoolean(false)

    data class Snapshot(val file: File, val bytes: Long, val lines: List<String>)


    fun installCrashCapture(context: Context) {
        val app = context.applicationContext
        recordPreviousProcessExits(app)
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(app, "UNCAUGHT", "Falha não tratada na thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordPreviousProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastLogged = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .orEmpty()
        }.getOrDefault(emptyList())
            .filter { it.timestamp > lastLogged }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        exits.forEach { exit ->
            val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
            warn(context, "PROCESS_EXIT", "$whenText | motivo=${exit.reason} | ${exit.description.orEmpty().take(600)}")
        }
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exits.maxOf { it.timestamp }).apply()
    }

    fun info(context: Context, category: String, message: String) = append(context, "INFO", category, message, null)
    fun warn(context: Context, category: String, message: String, error: Throwable? = null) = append(context, "WARN", category, message, error)
    fun error(context: Context, category: String, message: String, error: Throwable? = null) = append(context, "ERROR", category, message, error)

    fun snapshot(context: Context, maxLines: Int = 350): Snapshot = synchronized(lock) {
        val file = file(context)
        val lines = if (file.isFile) runCatching { file.readLines().takeLast(maxLines.coerceIn(1, 2_000)) }.getOrDefault(emptyList()) else emptyList()
        Snapshot(file, file.takeIf(File::isFile)?.length() ?: 0L, lines)
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        val file = file(context)
        !file.exists() || file.delete()
    }

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun file(context: Context): File = File(directory(context), FILE_NAME)

    private fun append(context: Context, level: String, category: String, message: String, error: Throwable?) {
        val app = context.applicationContext
        synchronized(lock) {
            val file = file(app)
            rotateIfNeeded(file)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val safeCategory = category.replace('\n', ' ').take(40)
            val safeMessage = message.replace('\n', ' ').take(2_000)
            val throwable = error?.let { throwable ->
                val head = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().replace('\n', ' ').take(1_000)}"
                val trace = throwable.stackTrace.take(12).joinToString(" <- ") { it.toString() }.take(4_000)
                " | $head${if (trace.isBlank()) "" else " | $trace"}"
            }.orEmpty()
            runCatching { file.appendText("$stamp [$level] [$safeCategory] $safeMessage$throwable\n") }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.isFile || file.length() < MAX_BYTES) return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        val start = (bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0)
        var newline = start
        for (index in start until bytes.size) {
            if (bytes[index] == '\n'.code.toByte()) {
                newline = index + 1
                break
            }
        }
        runCatching { file.writeBytes(bytes.copyOfRange(newline, bytes.size)) }
    }
}
