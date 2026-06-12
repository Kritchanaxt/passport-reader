package com.tananaev.passportreader.core.camera

import android.graphics.ImageFormat

data class CameraInfo(
    val title: String,
    val cameraId: String,
    val format: Int = ImageFormat.JPEG,
    val cameraType: String,
    val iconResId: Int = 0,
    val isAvailable: Boolean = true,
    val physicalCameraIds: List<String> = emptyList()
)
