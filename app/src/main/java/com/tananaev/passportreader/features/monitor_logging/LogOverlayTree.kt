package com.tananaev.passportreader.features.monitor_logging

import android.util.Log
import timber.log.Timber

class LogOverlayTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "U"
        }
        AppLog.addLog(level, tag ?: "Log", message, t)
    }
}
