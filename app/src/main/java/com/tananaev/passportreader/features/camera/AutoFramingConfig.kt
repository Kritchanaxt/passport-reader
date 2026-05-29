package com.tananaev.passportreader.features.camera
 
import android.graphics.RectF
 
enum class AutoFramingAlignment {
    CENTER,
    TOP_CENTER
}
 
data class AutoFramingConfig(
    val targetRect: RectF?,
    val frameWidth: Float,
    val frameHeight: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val targetHeightRatio: Float = 0.35f,
    val alignment: AutoFramingAlignment = AutoFramingAlignment.CENTER,
    val dampingAlpha: Float = 0.08f,
    val maxZoomScale: Float = 3.0f
)
