package com.tananaev.passportreader.features.camera
 
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
 
class SoftwareAutoFramingController(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onApplyMatrix: () -> Unit
) {
    companion object {
        private const val TAG = "AutoFramingController"
        private const val FRAME_DELAY_MS = 16L
    }
 
    var isActive = false
        private set
 
    var currentZoom = 1.0f
        private set
    var currentPanX = 0.0f
        private set
    var currentPanY = 0.0f
        private set
 
    private var targetZoom = 1.0f
    private var targetPanX = 0.0f
    private var targetPanY = 0.0f
    private var dampingAlpha = 0.08f
 
    private var isLoopRunning = false
 
    private val animationRunnable = object : Runnable {
        override fun run() {
            if (!isActive) {
                isLoopRunning = false
                return
            }
 
            currentZoom += (targetZoom - currentZoom) * dampingAlpha
            currentPanX += (targetPanX - currentPanX) * dampingAlpha
            currentPanY += (targetPanY - currentPanY) * dampingAlpha
 
            onApplyMatrix()
 
            if (targetZoom == 1.0f &&
                abs(currentZoom - 1.0f) < 0.005f &&
                abs(currentPanX) < 0.2f &&
                abs(currentPanY) < 0.2f
            ) {
                currentZoom = 1.0f
                currentPanX = 0.0f
                currentPanY = 0.0f
                isActive = false
                isLoopRunning = false
                
                onApplyMatrix()
                return
            }
 
            mainHandler.postDelayed(this, FRAME_DELAY_MS)
        }
    }
 
    fun updateTracking(config: AutoFramingConfig) {
        val targetRect = config.targetRect ?: return
 
        isActive = true
        dampingAlpha = config.dampingAlpha
 
        val targetHeight = targetRect.height()
        val currentHeightRatio = targetHeight / config.frameHeight
        val calculatedZoom = config.targetHeightRatio / currentHeightRatio.coerceAtLeast(0.01f)
        targetZoom = calculatedZoom.coerceIn(1.0f, config.maxZoomScale)
 
        val targetCenterX = targetRect.centerX()
        val targetCenterY = targetRect.centerY()
 
        val offsetX = (config.frameWidth / 2f) - targetCenterX
        val offsetY = if (config.alignment == AutoFramingAlignment.TOP_CENTER) {
            (config.frameHeight * 0.35f) - targetCenterY
        } else {
            (config.frameHeight / 2f) - targetCenterY
        }
 
        val scaledOffsetX = offsetX * (config.viewportWidth / config.frameWidth)
        val scaledOffsetY = offsetY * (config.viewportHeight / config.frameHeight)
 
        val maxPanX = (config.viewportWidth * (targetZoom - 1f)) / 2f
        val maxPanY = (config.viewportHeight * (targetZoom - 1f)) / 2f
 
        targetPanX = scaledOffsetX.coerceIn(-maxPanX, maxPanX)
        targetPanY = scaledOffsetY.coerceIn(-maxPanY, maxPanY)
 
        if (!isLoopRunning) {
            isLoopRunning = true
            mainHandler.post(animationRunnable)
        }
    }
 
    fun resetTracking() {
        if (!isActive) return
 
        targetZoom = 1.0f
        targetPanX = 0.0f
        targetPanY = 0.0f
 
        if (!isLoopRunning) {
            isLoopRunning = true
            mainHandler.post(animationRunnable)
        }
    }
 
    fun cropCapturedPhoto(
        bitmap: Bitmap,
        textureViewWidth: Float,
        textureViewHeight: Float
    ): Bitmap {
        if (!isActive || currentZoom <= 1.0f) return bitmap
 
        return try {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
 
            val viewW = if (textureViewWidth > 0f) textureViewWidth else w
            val viewH = if (textureViewHeight > 0f) textureViewHeight else h
 
            val cropW = (w / currentZoom).coerceIn(1f, w)
            val cropH = (h / currentZoom).coerceIn(1f, h)
 
            val bitmapPanX = -currentPanX * (w / viewW)
            val bitmapPanY = -currentPanY * (h / viewH)
 
            val left = ((w - cropW) / 2f + bitmapPanX).coerceIn(0f, w - cropW).toInt()
            val top = ((h - cropH) / 2f + bitmapPanY).coerceIn(0f, h - cropH).toInt()
            val width = cropW.toInt().coerceAtMost(bitmap.width - left)
            val height = cropH.toInt().coerceAtMost(bitmap.height - top)
 
            if (width > 0 && height > 0) {
                val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
                if (cropped !== bitmap) {
                    bitmap.recycle()
                    return cropped
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop captured photo with software auto-framing", e)
            bitmap
        }
    }
}
