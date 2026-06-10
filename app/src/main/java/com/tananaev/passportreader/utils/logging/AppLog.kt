package com.tananaev.passportreader.utils.logging

import android.content.Context
import android.util.Log

/**
 * Thread-safe, file-persisted log collector for in-app debugging.
 * Acts as a direct replacement/alias for android.util.Log.
 */
object AppLog {
    private val logs = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private var logFile: java.io.File? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        logFile = java.io.File(context.cacheDir, "console_logs.txt")
        if (logFile?.exists() == true) {
            try {
                val lines = logFile!!.readLines()
                synchronized(logs) {
                    logs.addAll(lines.takeLast(1000))
                }
            } catch (e: Exception) {
                Log.e("AppLog", "Failed to load logs", e)
            }
        }
        
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

    fun getLogs(): List<String> {
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

    private fun addLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val timeStr = sdf.format(java.util.Date())
        val errorStr = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        val logLine = "$timeStr $level/$tag: $message$errorStr"
        
        synchronized(logs) {
            logs.add(logLine)
            if (logs.size > 2000) {
                logs.removeAt(0)
            }
        }
        
        notifyListeners()

        executor.execute {
            try {
                logFile?.let { file ->
                    file.appendText(logLine + "\n")
                }
            } catch (e: Exception) {
                // Avoid recursive logging on disk write error
            }
        }
    }

    // ──── Log implementations for direct replacement ────

    fun v(tag: String, msg: String): Int {
        Log.v(tag, msg)
        addLog("V", tag, msg)
        return 0
    }

    fun v(tag: String, msg: String, tr: Throwable?): Int {
        Log.v(tag, msg, tr)
        addLog("V", tag, msg, tr)
        return 0
    }

    fun d(tag: String, msg: String): Int {
        Log.d(tag, msg)
        addLog("D", tag, msg)
        return 0
    }

    fun d(tag: String, msg: String, tr: Throwable?): Int {
        Log.d(tag, msg, tr)
        addLog("D", tag, msg, tr)
        return 0
    }

    fun i(tag: String, msg: String): Int {
        Log.i(tag, msg)
        addLog("I", tag, msg)
        return 0
    }

    fun i(tag: String, msg: String, tr: Throwable?): Int {
        Log.i(tag, msg, tr)
        addLog("I", tag, msg, tr)
        return 0
    }

    fun w(tag: String, msg: String): Int {
        Log.w(tag, msg)
        addLog("W", tag, msg)
        return 0
    }

    fun w(tag: String, msg: String, tr: Throwable?): Int {
        Log.w(tag, msg, tr)
        addLog("W", tag, msg, tr)
        return 0
    }

    fun w(tag: String, tr: Throwable?): Int {
        Log.w(tag, tr)
        addLog("W", tag, tr?.message ?: "", tr)
        return 0
    }

    fun e(tag: String, msg: String): Int {
        Log.e(tag, msg)
        addLog("E", tag, msg)
        return 0
    }

    fun e(tag: String, msg: String, tr: Throwable?): Int {
        Log.e(tag, msg, tr)
        addLog("E", tag, msg, tr)
        return 0
    }

    fun getStackTraceString(tr: Throwable?): String {
        return Log.getStackTraceString(tr)
    }

    fun isLoggable(tag: String, level: Int): Boolean {
        return Log.isLoggable(tag, level)
    }

    fun println(priority: Int, tag: String, msg: String): Int {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "U"
        }
        Log.println(priority, tag, msg)
        addLog(level, tag, msg)
        return 0
    }
}
