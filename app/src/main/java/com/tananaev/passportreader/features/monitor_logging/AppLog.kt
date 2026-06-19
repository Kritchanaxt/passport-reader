package com.tananaev.passportreader.features.monitor_logging

import android.content.Context
import android.util.Log
import com.tananaev.passportreader.features.monitor_logging.contracts.ILogger
import com.tananaev.passportreader.features.monitor_logging.model.LogEntry
import timber.log.Timber

/**
 * Thread-safe, file-persisted log collector for in-app debugging.
 * Acts as a direct replacement/alias for android.util.Log.
 */
object AppLog : ILogger {
    private val logs = mutableListOf<LogEntry>()
    private val listeners = mutableListOf<() -> Unit>()
    private var logFile: java.io.File? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        logFile = java.io.File(context.cacheDir, "console_logs.txt")
        if (logFile?.exists() == true) {
            try {
                val lines = logFile!!.readLines()
                synchronized(logs) {
                    logs.addAll(lines.takeLast(1000).map { LogEntry.fromLine(it) })
                }
            } catch (e: Exception) {
                Log.e("AppLog", "Failed to load logs", e)
            }
        }

        // Plant Timber trees
        val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogOverlayTree())
        
        // Register uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
            try {
                // Yield thread to let write finish
                Thread.sleep(400)
            } catch (ignored: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun getLogs(): List<LogEntry> {
        synchronized(logs) {
            return ArrayList(logs)
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
        executor.execute {
            try {
                logFile?.delete()
            } catch (e: Exception) {
                Log.e("AppLog", "Failed to delete log file", e)
            }
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        // Create copy of listeners list to avoid ConcurrentModificationException
        val currentListeners = synchronized(listeners) { ArrayList(listeners) }
        currentListeners.forEach { it() }
    }

    internal fun addLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val timeStr = sdf.format(java.util.Date())
        val entry = LogEntry(timeStr, level, tag, message, throwable)
        
        synchronized(logs) {
            logs.add(entry)
            if (logs.size > 2000) {
                logs.removeAt(0)
            }
        }
        
        notifyListeners()

        executor.execute {
            try {
                logFile?.let { file ->
                    file.appendText(entry.toFormattedString() + "\n")
                }
            } catch (e: Exception) {
                // Avoid recursive logging on disk write error
            }
        }
    }

    // ──── Log implementations for direct replacement ────

    override fun v(tag: String, msg: String): Int {
        Timber.tag(tag).v(msg)
        return 0
    }

    override fun v(tag: String, msg: String, tr: Throwable?): Int {
        Timber.tag(tag).v(tr, msg)
        return 0
    }

    override fun d(tag: String, msg: String): Int {
        Timber.tag(tag).d(msg)
        return 0
    }

    override fun d(tag: String, msg: String, tr: Throwable?): Int {
        Timber.tag(tag).d(tr, msg)
        return 0
    }

    override fun i(tag: String, msg: String): Int {
        Timber.tag(tag).i(msg)
        return 0
    }

    override fun i(tag: String, msg: String, tr: Throwable?): Int {
        Timber.tag(tag).i(tr, msg)
        return 0
    }

    override fun w(tag: String, msg: String): Int {
        Timber.tag(tag).w(msg)
        return 0
    }

    override fun w(tag: String, msg: String, tr: Throwable?): Int {
        Timber.tag(tag).w(tr, msg)
        return 0
    }

    override fun w(tag: String, tr: Throwable?): Int {
        Timber.tag(tag).w(tr)
        return 0
    }

    override fun e(tag: String, msg: String): Int {
        Timber.tag(tag).e(msg)
        return 0
    }

    override fun e(tag: String, msg: String, tr: Throwable?): Int {
        Timber.tag(tag).e(tr, msg)
        return 0
    }

    fun getStackTraceString(tr: Throwable?): String {
        return Log.getStackTraceString(tr)
    }

    fun isLoggable(tag: String, level: Int): Boolean {
        return Log.isLoggable(tag, level)
    }

    fun println(priority: Int, tag: String, msg: String): Int {
        Timber.tag(tag).log(priority, msg)
        return 0
    }
}
