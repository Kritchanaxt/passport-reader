package com.tananaev.passportreader.view.components.camera
 
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import com.tananaev.passportreader.core.AiMode
import com.tananaev.passportreader.core.AIDetectedItem
import com.tananaev.passportreader.core.AIManager
import com.tananaev.passportreader.utils.safeCreateScaledBitmap
import com.tananaev.passportreader.core.camera.Camera2Controller
 
@Composable
fun CameraFrameProcessor(
    cameraKey: String,
    aiMode: AiMode,
    isProcessingBusy: Boolean,
    useCropMode: Boolean,
    horizontalFlip: Boolean,
    verticalFlip: Boolean,
    autoFramingEnabled: Boolean,
    stableTimeState: MutableState<Long>,
    bitmapWidthState: MutableState<Float>,
    bitmapHeightState: MutableState<Float>,
    cameraController: Camera2Controller?,
    currentOnStableDetection: State<suspend (Bitmap, Boolean) -> Pair<Boolean, List<AIDetectedItem>>>,
    currentOnImageCaptured: State<(Bitmap, Boolean) -> Unit>,
    latestItemsOcr: MutableState<List<AIDetectedItem>>,
    latestDetectionsState: MutableState<List<AIDetectedItem>>,
    faceBuffer2sState: MutableState<Bitmap?>,
    isPreviewPausedState: MutableState<Boolean>,
    context: Context,
    wrapper: CameraStateWrapper
) {
    var stableTime by stableTimeState
    var bitmapWidth by bitmapWidthState
    var bitmapHeight by bitmapHeightState
    var latestDetections by latestDetectionsState
    var isPreviewPaused by isPreviewPausedState
 
    with(wrapper) {
        LaunchedEffect(cameraKey, aiMode, isProcessingBusy, useCropMode, horizontalFlip, verticalFlip, autoFramingEnabled) {
            isCapturing = false
            stableTime = 0L
            
            if (isProcessingBusy) return@LaunchedEffect
    
            withContext(Dispatchers.Default) {
                var lastFrameTime = System.currentTimeMillis()
    
                try {
                    while (isActive && !isProcessingBusy) {
                        val iterationStart = System.currentTimeMillis()
        
                        if (AIManager.isBusy()) {
                            kotlinx.coroutines.delay(10)
                            continue
                        }
        
                        if (isPreviewPaused) {
                            kotlinx.coroutines.delay(500)
                            continue
                        }
        
                        if (!isCapturing && cameraController?.textureView != null) {
                            val rawBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val tv = cameraController.textureView ?: return@withContext null
                                val pSize = cameraController.previewSize
                                if (pSize != null) {
                                    val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                                    val rotation = windowManager?.defaultDisplay?.rotation ?: 0
                                    val isLandscape = (rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270)
                                    
                                    val pWidth = if (isLandscape) maxOf(pSize.width, pSize.height) else minOf(pSize.width, pSize.height)
                                    val pHeight = if (isLandscape) minOf(pSize.width, pSize.height) else maxOf(pSize.width, pSize.height)
                                    try {
                                        tv.getBitmap(pWidth, pHeight)
                                    } catch (e: Exception) {
                                        tv.bitmap
                                    }
                                } else {
                                    tv.bitmap
                                }
                            }
                            if (rawBitmap != null) {
                                var activeBitmap = rawBitmap
                                val targetAR = cameraController.aspectRatio.value
                                if (targetAR != null) {
                                    val w = activeBitmap.width
                                    val h = activeBitmap.height
                                    val srcAR = w.toFloat() / h.toFloat()
                                    
                                    var cropW = w
                                    var cropH = h
                                    var cropX = 0
                                    var cropY = 0
                                    
                                    if (srcAR > targetAR) {
                                        cropW = (h * targetAR).toInt()
                                        cropX = (w - cropW) / 2
                                    } else if (srcAR < targetAR) {
                                        cropH = (w / targetAR).toInt()
                                        cropY = (h - cropH) / 2
                                    }
                                    
                                    if (cropW > 0 && cropH > 0 && (cropW != w || cropH != h)) {
                                        try {
                                            val cropped = Bitmap.createBitmap(activeBitmap, cropX, cropY, cropW, cropH)
                                            if (cropped != activeBitmap) {
                                                activeBitmap.recycle()
                                                activeBitmap = cropped
                                            }
                                        } catch (e: Exception) {
                                            Log.e("CameraFrameProcessor", "Error cropping bitmap", e)
                                        }
                                    }
                                }
                                
                                val maxDetectionDim = 720f
                                val currentMax = maxOf(activeBitmap.width, activeBitmap.height).toFloat()
                                
                                val baseBitmap = if (currentMax > maxDetectionDim) {
                                    val scale = maxDetectionDim / currentMax
                                    safeCreateScaledBitmap(activeBitmap, (activeBitmap.width * scale).toInt(), (activeBitmap.height * scale).toInt(), true).also {
                                        activeBitmap.recycle()
                                    }
                                } else {
                                    activeBitmap
                                }
 
                                val bitmap = if (useCropMode) {
                                    val cw = baseBitmap.width.toFloat()
                                    val ch = baseBitmap.height.toFloat()
                                    val maxW = cw * 0.9f
                                    val idealH = ch * 0.6f
                                    val frameW = if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
                                    val frameH = frameW / 1.58f
                                    val left = ((cw - frameW) / 2).toInt().coerceAtLeast(0)
                                    val top = ((ch - frameH) / 2).toInt().coerceAtLeast(0)
                                    val width = frameW.toInt().coerceAtMost(baseBitmap.width - left)
                                    val height = frameH.toInt().coerceAtMost(baseBitmap.height - top)
         
                                    if (width > 0 && height > 0) {
                                        val cropped = Bitmap.createBitmap(baseBitmap, left, top, width, height)
                                        if (baseBitmap !== rawBitmap) baseBitmap.recycle()
                                        
                                        if (aiMode == AiMode.OCR) {
                                            val mrzTop = (height * 0.70f).toInt().coerceAtLeast(0)
                                            val mrzHeight = (height * 0.30f).toInt().coerceAtMost(height - mrzTop)
                                            if (mrzHeight > 0) {
                                                val mrzCropped = Bitmap.createBitmap(cropped, 0, mrzTop, width, mrzHeight)
                                                cropped.recycle()
                                                mrzCropped
                                            } else {
                                                cropped
                                            }
                                        } else {
                                            cropped
                                        }
                                    } else {
                                        baseBitmap
                                    }
                                } else {
                                    baseBitmap
                                }
         
                                val now = System.currentTimeMillis()
                                frameLatency = now - lastFrameTime
                                lastFrameTime = now
         
                                if (isProcessingBusy) {
                                    bitmap.recycle()
                                    break
                                }
         
                                bitmapWidth = bitmap.width.toFloat()
                                bitmapHeight = bitmap.height.toFloat()
         
                                val startInference = System.currentTimeMillis()
                                val isFront = cameraController?.isFrontCamera ?: false
                                isFrontCamera = isFront
         
                                var (success, items) = try {
                                    currentOnStableDetection.value(
                                        bitmap,
                                        isFront
                                    )
                                } catch (e: Exception) {
                                    Pair(false, emptyList())
                                }
         
                                latestItemsOcr.value = items
                                latestDetections = items
         
                                var criteriaMet = success
         
                                if (!criteriaMet && useCropMode) {
                                    val expandedBitmap = try {
                                        val cw = baseBitmap.width.toFloat()
                                        val ch = baseBitmap.height.toFloat()
                                        val expansion = 1.20f
                                        val maxW = cw * 0.9f
                                        val idealH = ch * 0.6f
                                        val frameW = (if (idealH * 1.58f > maxW) maxW else idealH * 1.58f) * expansion
                                        val frameH = frameW / (1.58f * expansion)
                                        val left = ((cw - frameW) / 2).toInt().coerceAtLeast(0)
                                        val top = ((ch - frameH) / 2).toInt().coerceAtLeast(0)
                                        val width = frameW.toInt().coerceAtMost(baseBitmap.width - left)
                                        val height = frameH.toInt().coerceAtMost(baseBitmap.height - top)
         
                                        val cropped = Bitmap.createBitmap(baseBitmap, left, top, width, height)
                                        if (aiMode == AiMode.OCR) {
                                            val mrzTop = (height * 0.70f).toInt().coerceAtLeast(0)
                                            val mrzHeight = (height * 0.30f).toInt().coerceAtMost(height - mrzTop)
                                            if (mrzHeight > 0) {
                                                val mrzCropped = Bitmap.createBitmap(cropped, 0, mrzTop, width, mrzHeight)
                                                cropped.recycle()
                                                mrzCropped
                                            } else {
                                                cropped
                                            }
                                        } else {
                                            cropped
                                        }
                                    } catch (e: Throwable) { null }
         
                                    if (expandedBitmap != null) {
                                        try {
                                            val (retrySuccess, retryItems) = currentOnStableDetection.value(
                                                expandedBitmap,
                                                isFront
                                            )
                                            if (retrySuccess) {
                                                success = true
                                                criteriaMet = true
                                                items = retryItems
                                            }
                                        } finally {
                                            expandedBitmap.recycle()
                                        }
                                    }
                                }
         
                                val elapsedInference = System.currentTimeMillis() - startInference
                                detectorLatency = elapsedInference
         
                                var passedToCapture = false
         
                                if (criteriaMet) {
                                    val elapsedSinceStart = System.currentTimeMillis() - iterationStart
                                    stableTime += elapsedSinceStart
 
                                    if (stableTime >= 2000L) {
                                        if (faceBuffer2sState.value == null) {
                                            faceBuffer2sState.value = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                        }
                                    }
 
                                    if (stableTime >= 3000L) {
                                        isCapturing = true
                                        isPreviewPaused = true
                                        passedToCapture = true
 
                                        val buf = faceBuffer2sState.value
                                        var captureBitmap = if (buf != null) {
                                            buf.copy(Bitmap.Config.ARGB_8888, true)
                                        } else {
                                            bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                        }
 
                                        if (horizontalFlip || verticalFlip) {
                                            val flipMatrix = android.graphics.Matrix()
                                            val hScale = if (horizontalFlip) -1f else 1f
                                            val vScale = if (verticalFlip) -1f else 1f
                                            flipMatrix.postScale(hScale, vScale, captureBitmap.width / 2f, captureBitmap.height / 2f)
                                            val flipped = Bitmap.createBitmap(captureBitmap, 0, 0, captureBitmap.width, captureBitmap.height, flipMatrix, true)
                                            captureBitmap.recycle()
                                            captureBitmap = flipped
                                        }
 
                                        currentOnImageCaptured.value(
                                            captureBitmap,
                                            isFront
                                        )
 
                                        stableTime = 0L
                                        faceBuffer2sState.value?.recycle()
                                        faceBuffer2sState.value = null
                                    }
                                } else {
                                    stableTime = 0L
                                    faceBuffer2sState.value?.recycle()
                                    faceBuffer2sState.value = null
                                }
         
                                if (!passedToCapture && !bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
         
                                val pollDelay = if (stableTime > 0 || isCapturing) 30L else 100L
                                kotlinx.coroutines.delay(pollDelay)
                            }
                        }
                    }
                } finally {
                    // Cleanup if any resources are allocated
                }
            }
        }
    }
}
