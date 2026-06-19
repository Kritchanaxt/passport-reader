package com.tananaev.passportreader.features.monitor_logging.model

import android.util.Log

data class LogEntry(
    val timestamp: String = "",
    val level: String = "",
    val tag: String = "",
    val message: String,
    val throwable: Throwable? = null
) {
    fun toFormattedString(): String {
        if (timestamp.isEmpty() && level.isEmpty() && tag.isEmpty()) {
            return message
        }
        val errorStr = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        return "$timestamp $level/$tag: $message$errorStr"
    }

    companion object {
        fun fromLine(line: String): LogEntry {
            return try {
                // Check if line matches timestamp pattern "MM-dd HH:mm:ss.SSS "
                if (line.length >= 19 && 
                    line[2] == '-' && 
                    line[5] == ' ' && 
                    line[8] == ':' && 
                    line[11] == ':' && 
                    line[14] == '.'
                ) {
                    val timestamp = line.substring(0, 18)
                    val remaining = line.substring(19)
                    val firstSlash = remaining.indexOf('/')
                    if (firstSlash != -1) {
                        val level = remaining.substring(0, firstSlash)
                        val colonSpace = remaining.indexOf(": ")
                        if (colonSpace != -1 && colonSpace > firstSlash) {
                            val tag = remaining.substring(firstSlash + 1, colonSpace)
                            val message = remaining.substring(colonSpace + 2)
                            return LogEntry(timestamp, level, tag, message)
                        }
                    }
                }
                LogEntry(message = line)
            } catch (e: Exception) {
                LogEntry(message = line)
            }
        }
    }
}
