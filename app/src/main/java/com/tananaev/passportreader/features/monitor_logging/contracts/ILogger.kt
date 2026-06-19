package com.tananaev.passportreader.features.monitor_logging.contracts

interface ILogger {
    fun v(tag: String, msg: String): Int
    fun v(tag: String, msg: String, tr: Throwable?): Int
    fun d(tag: String, msg: String): Int
    fun d(tag: String, msg: String, tr: Throwable?): Int
    fun i(tag: String, msg: String): Int
    fun i(tag: String, msg: String, tr: Throwable?): Int
    fun w(tag: String, msg: String): Int
    fun w(tag: String, msg: String, tr: Throwable?): Int
    fun w(tag: String, tr: Throwable?): Int
    fun e(tag: String, msg: String): Int
    fun e(tag: String, msg: String, tr: Throwable?): Int
}
