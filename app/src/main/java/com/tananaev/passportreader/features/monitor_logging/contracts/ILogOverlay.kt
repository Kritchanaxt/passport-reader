package com.tananaev.passportreader.features.monitor_logging.contracts

import android.app.Activity

interface ILogOverlay {
    fun attach(activity: Activity)
}
