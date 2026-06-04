package com.tananaev.passportreader.core.camera
 
import com.tananaev.passportreader.utils.safeCreateScaledBitmap
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.utils.ResolutionItem
import com.tananaev.passportreader.utils.predefinedResolutionsByRatio
import com.tananaev.passportreader.features.camera.SoftwareAutoFramingController
 
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
 
/**
 * Encapsulated Camera2 Logic derived from CameraFragment.kt
 */
class Camera2Controller(
    private val context: Context,
    private val onImageCaptured: (android.graphics.Bitmap, Boolean) -> Unit
) : Closeable {
 
    private val cameraManager: CameraManager by lazy {
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
 
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
 
    // For TextureView
    var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    var previewSize: Size? = null
    private var layoutChangeListener: android.view.View.OnLayoutChangeListener? = null
 
    // Camera State
    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
 
    // Configurable Settings
    var targetResolution: Size? = null
    var aspectRatio: UiAspectRatio = UiAspectRatio.RATIO_1_1
    var zoomScale: Float = 1.0f
    var manualRotationOffset: Int = 0
        set(value) {
            field = value
            textureView?.let { view ->
                mainHandler.post {
                    configureTransform(view.width, view.height)
                }
            }
        }
 
    fun isCroppingNeeded(): Boolean {
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return !isLandscape && (aspectRatio == UiAspectRatio.RATIO_16_9 || aspectRatio == UiAspectRatio.RATIO_4_3)
    }
 
    fun getActiveAspectRatio(): UiAspectRatio {
        return if (isCroppingNeeded()) {
            when (aspectRatio) {
                UiAspectRatio.RATIO_16_9 -> UiAspectRatio.RATIO_9_16
                UiAspectRatio.RATIO_4_3 -> UiAspectRatio.RATIO_3_4
                else -> aspectRatio
            }
        } else {
            aspectRatio
        }
    }
 
    fun getActiveResolution(selectedRes: Size?): Size? {
        if (selectedRes == null) return null
        return if (isCroppingNeeded()) {
            Size(min(selectedRes.width, selectedRes.height), max(selectedRes.width, selectedRes.height))
        } else {
            selectedRes
        }
    }
 
    // Logic from OutputSettingsDialogFragment
    private var maxSensorProcessingSize: Size = Size(1920, 1080)
    var isFrontCamera: Boolean = false
    private var isFlashEnabled: Boolean = false
 
    // 🌟 Software Auto-Framing (Center Stage) Controller
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    val softwareAutoFramingController = SoftwareAutoFramingController(mainHandler) {
        textureView?.let { view ->
            mainHandler.post {
                configureTransform(view.width, view.height)
            }
        }
    }
 
    /**
     * 🌟 Auto-framing (Center Stage)
     * Ignored: Hardware auto-framing is fully replaced by premium Software Auto-Framing (Center Stage).
     */
    fun setAutoFraming(enable: Boolean) {
        // Staged for Software Auto-Framing
    }
 
    fun isAutoFramingSupported(): Boolean {
        return true // Fully supported via Software Auto-Framing fallback
    }
 
    fun setZoom(scale: Float) {
        if (zoomScale == scale) return
        zoomScale = scale
        applyZoom()
    }
 
    private fun applyZoom() {
        try {
            val session = captureSession ?: return
            val charas = characteristics ?: return
            val builder = previewRequestBuilder ?: return
 
            val rect = charas.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = charas.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f
 
            zoomScale = zoomScale.coerceIn(1.0f, maxZoom)
 
            // 🌟 Ensure Aspect Ratio Alignment for MTK HAL
            // The crop region must have the SAME aspect ratio as the sensor array
            val sensorWidth = rect.width()
            val sensorHeight = rect.height()
            
            val cropWidth = (sensorWidth / zoomScale).toInt()
            val cropHeight = (sensorHeight / zoomScale).toInt()
            
            val left = (sensorWidth - cropWidth) / 2
            val top = (sensorHeight - cropHeight) / 2
            
            val cropRect = android.graphics.Rect(
                rect.left + left,
                rect.top + top,
                rect.left + left + cropWidth,
                rect.top + top + cropHeight
            )
 
            Log.d(TAG, "Applying zoom: $zoomScale. CropRect: $cropRect (Sensor: ${rect.width()}x${rect.height()})")
 
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "CameraDevice closed while applying zoom", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom", e)
        }
    }
 
    fun setFlashMode(enable: Boolean) {
        if (isFlashEnabled == enable) return
        isFlashEnabled = enable
 
        try {
            val session = captureSession ?: return
            val builder = previewRequestBuilder ?: return
 
            applyCameraQualityAndFocusSettings(builder)
 
            if (isFlashEnabled && !isFrontCamera) {
                // If the camera supports flash
                val flashAvailable = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                // Use default AE mode
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
 
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flash", e)
        }
    }
 
    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }
 
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping background thread", e)
        }
    }
 
    @SuppressLint("MissingPermission")
    fun openCamera(textureView: TextureView, cameraIdToOpen: String, desiredResolution: Size? = null) {
        cameraExecutor.execute {
            val activeRes = getActiveResolution(desiredResolution)
            val activeRatio = getActiveAspectRatio()
            if (cameraDevice != null && cameraId == cameraIdToOpen && 
                getActiveResolution(targetResolution) == activeRes && 
                getActiveAspectRatio() == activeRatio && 
                this.textureView == textureView) {
                mainHandler.post {
                    configureTransform(textureView.width, textureView.height)
                }
                return@execute
            }
 
            closeInternal()
 
            this.textureView = textureView
            this.cameraId = cameraIdToOpen
            this.targetResolution = desiredResolution
 
            startBackgroundThread()
 
            if (textureView.isAvailable) {
                mainHandler.post {
                    configureTransform(textureView.width, textureView.height)
                }
                openCameraDeviceInternal(cameraId)
            }
 
            mainHandler.post {
                this.textureView?.let { oldTv ->
                    layoutChangeListener?.let { listener ->
                        oldTv.removeOnLayoutChangeListener(listener)
                    }
                }
 
                val listener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val width = right - left
                    val height = bottom - top
                    if (width > 0 && height > 0 && (width != (oldRight - oldLeft) || height != (oldBottom - oldTop))) {
                        Log.d(TAG, "OnLayoutChangeListener triggered: width=$width, height=$height")
                        configureTransform(width, height)
                    }
                }
                layoutChangeListener = listener
                textureView.addOnLayoutChangeListener(listener)
 
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        cameraExecutor.execute { openCameraDeviceInternal(cameraId) }
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        configureTransform(width, height)
                    }
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        }
    }
 
    @SuppressLint("MissingPermission")
    private fun openCameraDeviceInternal(cameraId: String) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
 
            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            isFrontCamera = characteristics!!.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
 
            val map = characteristics!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
 
            val availableJpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.sortedByDescending { size -> size.width * size.height } ?: emptyList()
 
            maxSensorProcessingSize = availableJpegSizes.firstOrNull()
                ?: characteristics!!.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Size(it.width(), it.height()) }
                        ?: Size(4000, 3000)
 
            Log.d(TAG, "Max sensor processing size: $maxSensorProcessingSize. Is Front Camera: $isFrontCamera")
 
            val activeResolution = getActiveResolution(targetResolution)
            val finalCaptureSize = if (activeResolution != null) {
                if (availableJpegSizes.contains(activeResolution)) {
                    activeResolution
                } else {
                    val targetArea = activeResolution.width * activeResolution.height
                    val targetRatio = activeResolution.width.toFloat() / activeResolution.height.toFloat()
 
                    availableJpegSizes.minByOrNull { size ->
                        val ratio = size.width.toFloat() / size.height.toFloat()
                        val ratioDiff = Math.abs(ratio - targetRatio)
                        val area = size.width * size.height
 
                        if (area >= targetArea) ratioDiff else ratioDiff + 100f
                    } ?: availableJpegSizes.last()
                }
            } else {
                 val validResolutions = getResolutionsForAspectRatio(getActiveAspectRatio())
                 validResolutions.mapNotNull { it.size }.minByOrNull { it.width * it.height } ?: availableJpegSizes.last()
            }
 
            Log.d(TAG, "Selected capture size: $finalCaptureSize")
 
            Log.d(TAG, "CAMERA_PREVIEW_INIT chosen_resolution=${targetResolution?.toString() ?: "AUTO"} is_front_camera=$isFrontCamera")
 
            imageReader = ImageReader.newInstance(
                finalCaptureSize.width, finalCaptureSize.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    backgroundHandler?.post {
                        val image = reader.acquireLatestImage()
                        image?.use {
                            val buffer = it.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
 
                            val decodeTargetArea = if (targetResolution != null) targetResolution!!.width * targetResolution!!.height else finalCaptureSize.width * finalCaptureSize.height
                            var sampleSize = 1
                            while ((finalCaptureSize.width * finalCaptureSize.height) / (sampleSize * sampleSize * 2) >= decodeTargetArea && sampleSize < 8) {
                                sampleSize *= 2
                            }
 
                            val options = android.graphics.BitmapFactory.Options()
                            options.inSampleSize = sampleSize
                            var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
 
                            bitmap = softwareAutoFramingController.cropCapturedPhoto(
                                bitmap = bitmap,
                                textureViewWidth = textureView?.width?.toFloat() ?: 0f,
                                textureViewHeight = textureView?.height?.toFloat() ?: 0f
                            )
 
                            if (isCroppingNeeded()) {
                                val targetAR = aspectRatio.value ?: (16f / 9f)
                                val w = bitmap.width
                                val h = bitmap.height
                                val cropW = w
                                val cropH = (w / targetAR).toInt()
                                val cropX = 0
                                val cropY = (h - cropH) / 2
                                if (cropH > 0 && cropY >= 0 && cropY + cropH <= h) {
                                    val cropped = android.graphics.Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                                    if (cropped != bitmap) {
                                        bitmap.recycle()
                                        bitmap = cropped
                                    }
                                }
                            }
 
                            if (targetResolution != null && (bitmap.width != targetResolution!!.width || bitmap.height != targetResolution!!.height)) {
                                val targetW = targetResolution!!.width
                                val targetH = targetResolution!!.height
 
                                val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                val tgtRatio = targetW.toFloat() / targetH.toFloat()
 
                                var cropW = bitmap.width
                                var cropH = bitmap.height
                                var cropX = 0
                                var cropY = 0
 
                                if (srcRatio > tgtRatio) {
                                    cropW = (bitmap.height * tgtRatio).toInt()
                                    cropX = (bitmap.width - cropW) / 2
                                } else if (srcRatio < tgtRatio) {
                                    cropH = (bitmap.width / tgtRatio).toInt()
                                    cropY = (bitmap.height - cropH) / 2
                                }
 
                                val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                                if (croppedBitmap != bitmap) {
                                    bitmap.recycle()
                                    bitmap = croppedBitmap
                                }
 
                                if (bitmap.width != targetW || bitmap.height != targetH) {
                                    val scaledBitmap = safeCreateScaledBitmap(bitmap, targetW, targetH, true)
                                    if (scaledBitmap != bitmap) {
                                        bitmap.recycle()
                                        bitmap = scaledBitmap
                                    }
                                }
                            }
 
                            onImageCaptured(bitmap, isFrontCamera)
                        }
                    }
                }, backgroundHandler)
            }
 
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
            previewSize = getOptimalPreviewSize(previewSizes, finalCaptureSize)
 
            Log.d(TAG, "Selected preview size: $previewSize")
 
            textureView?.surfaceTexture?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
 
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
 
                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
 
                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
 
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            cameraOpenCloseLock.release()
        }
    }
 
    fun getResolutionsForAspectRatio(aspectRatio: UiAspectRatio): List<ResolutionItem> {
        if (characteristics == null) return emptyList()
 
        val resolutionItems = mutableListOf<ResolutionItem>()
        val sourceW = maxSensorProcessingSize.width
        val sourceH = maxSensorProcessingSize.height
        val targetAR = aspectRatio.value ?: (sourceW.toFloat() / sourceH.toFloat())
 
        var maxFinalW: Int
        var maxFinalH: Int
 
        if (aspectRatio.isPortraitDefault) {
            val canvasW = min(sourceW, sourceH)
            val canvasH = max(sourceW, sourceH)
            val canvasAR = canvasW.toFloat() / canvasH.toFloat()
 
            if (canvasAR > targetAR) {
                maxFinalH = canvasH
                maxFinalW = (canvasH * targetAR).roundToInt()
            } else {
                maxFinalW = canvasW
                maxFinalH = (canvasW / targetAR).roundToInt()
            }
        } else {
            maxFinalW = min(sourceW, sourceH)
            maxFinalH = (maxFinalW / targetAR).roundToInt()
        }
 
        if (maxFinalW >= 720 && maxFinalH >= 720) {
            val maxForArText = "Max for AR (${maxFinalW}x${maxFinalH})"
            resolutionItems.add(ResolutionItem(null, maxForArText))
        }
 
        val resolutionStrings = predefinedResolutionsByRatio[aspectRatio] ?: emptyList()
 
        resolutionStrings.forEach { resString ->
            try {
                val parts = resString.split("x")
                if (parts.size == 2) {
                    val width = parts[0].toInt()
                    val height = parts[1].toInt()
                    val candidateSize = Size(width, height)
 
                    if (candidateSize.width < 720 || candidateSize.height < 720) return@forEach
 
                    val isSupported = if (aspectRatio == UiAspectRatio.RATIO_1_1 && candidateSize.width == 1920 && candidateSize.height == 1920) {
                        true
                    } else if (aspectRatio.isPortraitDefault) {
                        candidateSize.width <= maxFinalW && candidateSize.height <= maxFinalH
                    } else {
                        candidateSize.width <= maxFinalW && candidateSize.height <= maxFinalH
                    }
 
                    val isWithinFrontCamLimit = if (isFrontCamera) {
                        (candidateSize.width * candidateSize.height) <= (2160L * 2160L)
                    } else {
                        true
                    }
 
                    if (isSupported && isWithinFrontCamLimit) {
                        resolutionItems.add(ResolutionItem(candidateSize, resString))
                    }
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Could not parse resolution string: $resString", e)
            }
        }
 
        return resolutionItems.distinctBy { it.displayText }
    }
 
    private fun getOptimalPreviewSize(sizes: Array<Size>, targetSize: Size): Size {
        val targetW = targetSize.width
        val targetH = targetSize.height
        val targetRatio = max(targetW, targetH).toDouble() / min(targetW, targetH).toDouble()
 
        val tolerance = 0.05
        val matchedAspect = sizes.filter {
            val ratio = max(it.width, it.height).toDouble() / min(it.width, it.height).toDouble()
            Math.abs(ratio - targetRatio) < tolerance
        }
 
        if (matchedAspect.isNotEmpty()) {
            return matchedAspect.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height }
                ?: matchedAspect.maxByOrNull { it.width * it.height }!!
        }
 
        return sizes.minByOrNull {
            val ratio = max(it.width, it.height).toDouble() / min(it.width, it.height).toDouble()
            Math.abs(ratio - targetRatio)
        } ?: sizes[0]
    }
 
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val textureView = this.textureView ?: return
        val previewSize = this.previewSize ?: return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = windowManager?.defaultDisplay?.rotation ?: 0
        val matrix = android.graphics.Matrix()
        
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        
        Log.d(TAG, "configureTransform: viewSize=${viewWidth}x${viewHeight}, previewSize=${previewSize.width}x${previewSize.height}, rotation=$rotation")

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        } else {
            val scaleX = viewWidth.toFloat() / previewSize.height
            val scaleY = viewHeight.toFloat() / previewSize.width
            val targetScale = max(scaleX, scaleY)
            
            val scaleXRelative = (targetScale * previewSize.height) / viewWidth
            val scaleYRelative = (targetScale * previewSize.width) / viewHeight
            
            matrix.setScale(scaleXRelative, scaleYRelative, centerX, centerY)
        }
        
        if (softwareAutoFramingController.isActive) {
            matrix.postScale(
                softwareAutoFramingController.currentZoom,
                softwareAutoFramingController.currentZoom,
                centerX,
                centerY
            )
            matrix.postTranslate(
                softwareAutoFramingController.currentPanX,
                softwareAutoFramingController.currentPanY
            )
        }

        textureView.setTransform(matrix)
    }
 
    private fun createCameraPreviewSession() {
        try {
            val device = cameraDevice ?: return
            val texture = textureView?.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            configureTransform(textureView!!.width, textureView!!.height)
 
            if (previewSurface == null) {
                previewSurface = Surface(texture)
            }
            val surface = previewSurface!!
            val imageSurface = imageReader?.surface ?: return
 
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val builder = previewRequestBuilder!!
            
            builder.addTarget(surface)
 
            val charas = characteristics
            if (charas != null) {
                val rect = charas.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (rect != null) {
                    val zoomLevel = zoomScale.coerceIn(1.0f, charas.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f)
                    
                    val sensorWidth = rect.width()
                    val sensorHeight = rect.height()
                    val cropWidth = (sensorWidth / zoomLevel).toInt()
                    val cropHeight = (sensorHeight / zoomLevel).toInt()
                    val left = (sensorWidth - cropWidth) / 2
                    val top = (sensorHeight - cropHeight) / 2
                    
                    val cropRect = android.graphics.Rect(
                        rect.left + left,
                        rect.top + top,
                        rect.left + left + cropWidth,
                        rect.top + top + cropHeight
                    )
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }
 
            device.createCaptureSession(
                listOf(surface, imageSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            applyCameraQualityAndFocusSettings(builder)
                            if (isFlashEnabled && !isFrontCamera) {
                                val flashAvailable = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                                if (flashAvailable) {
                                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }
                            } else {
                                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }
 
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "CameraDevice was already closed during session configuration.")
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession error", e)
                        }
                    }
 
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                       // Failed
                    }
                },
                backgroundHandler
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "CameraDevice was already closed when creating session.")
        } catch (e: Exception) {
            Log.e(TAG, "createCameraPreviewSession error", e)
        }
    }
 
    fun pausePreview() {
        Log.d(TAG, "pausePreview requested: Bypassing stopRepeating() to prevent MediaTek HAL deadlock")
    }
 
    fun resumePreview() {
        try {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return
 
            applyCameraQualityAndFocusSettings(builder)
 
            val charas = characteristics
            if (charas != null) {
                val rect = charas.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (rect != null) {
                    val zoomLevel = zoomScale.coerceIn(1.0f, charas.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f)
                    
                    val sensorWidth = rect.width()
                    val sensorHeight = rect.height()
                    val cropWidth = (sensorWidth / zoomLevel).toInt()
                    val cropHeight = (sensorHeight / zoomLevel).toInt()
                    val left = (sensorWidth - cropWidth) / 2
                    val top = (sensorHeight - cropHeight) / 2
                    
                    val cropRect = android.graphics.Rect(
                        rect.left + left,
                        rect.top + top,
                        rect.left + left + cropWidth,
                        rect.top + top + cropHeight
                    )
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }
 
            if (isFlashEnabled && !isFrontCamera) {
                val flashAvailable = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
 
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "resumePreview error", e)
        }
    }
 
    fun takePhoto() {
        try {
            if (cameraDevice == null) return
 
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            applyCameraQualityAndFocusSettings(captureBuilder)
 
            val charas = characteristics
            if (charas != null) {
                val rect = charas.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (rect != null) {
                    val zoomLevel = zoomScale.coerceIn(1.0f, charas.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f)
                    
                    val sensorWidth = rect.width()
                    val sensorHeight = rect.height()
                    val cropWidth = (sensorWidth / zoomLevel).toInt()
                    val cropHeight = (sensorHeight / zoomLevel).toInt()
                    val left = (sensorWidth - cropWidth) / 2
                    val top = (sensorHeight - cropHeight) / 2
                    
                    val cropRect = android.graphics.Rect(
                        rect.left + left,
                        rect.top + top,
                        rect.left + left + cropWidth,
                        rect.top + top + cropHeight
                    )
                    captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }
 
            if (isFlashEnabled && !isFrontCamera) {
                val flashAvailable = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            } else {
                captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
 
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val rotation = windowManager?.defaultDisplay?.rotation ?: 0
            val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val displayRotation = when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val jpegOrientation = if (isFrontCamera) {
                (sensorOrientation + displayRotation) % 360
            } else {
                (sensorOrientation - displayRotation + 360) % 360
            }
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
 
            captureSession?.stopRepeating()
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                          createCameraPreviewSession()
                    }
            }, backgroundHandler)
 
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error", e)
        }
    }
 
    fun getAvailableCameras(): List<String> {
        return try {
            cameraManager.cameraIdList.toList()
        } catch(e:Exception) { emptyList() }
    }
 
    data class CameraInfo(
        val title: String,
        val javaCameraId: String, // mapped physical/logical representation
        val cameraId: String,
        val format: Int = ImageFormat.JPEG,
        val cameraType: String,
        val iconResId: Int = 0,
        val isAvailable: Boolean = true,
        val physicalCameraIds: List<String> = emptyList()
    )
 
    @SuppressLint("InlinedApi")
    fun enumerateCameras(): List<CameraInfo> {
        Log.d(TAG, "Starting camera enumeration...")
        val detectedCamerasMap = mutableMapOf<String, CameraInfo>()
        val allCameraIds = try {
            cameraManager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID list", e)
            emptyArray<String>()
        }
        val numberOfActualCameras = allCameraIds.size
 
        Log.i(TAG, "Total camera IDs: $numberOfActualCameras. IDs: ${allCameraIds.joinToString()}")
 
        val frontCameraIds = allCameraIds.filter { id ->
            try {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } catch (e: Exception) {
                false
            }
        }
        val hasMultipleFrontCameras = frontCameraIds.size > 1
        Log.d(TAG, "Device has multiple front cameras: $hasMultipleFrontCameras (Total front cams: ${frontCameraIds.size})")
 
        val ultraWideFocalLengthThreshold = 3.0f
        val telephotoFocalLengthThreshold = 7.0f
 
        allCameraIds.forEach { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
 
                val isLogicalMultiCamera = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                } else false
 
                val physicalCameraIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isLogicalMultiCamera) {
                     characteristics.physicalCameraIds.toList()
                } else emptyList()
 
                Log.i(TAG, "--- Processing Camera ID: $id ---")
                Log.i(TAG, "  Orientation: $orientation")
                Log.i(TAG, "  Is Logical Multi-Camera: $isLogicalMultiCamera")
 
                var determinedType: String? = null
                when (orientation) {
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        determinedType = if (hasMultipleFrontCameras && (id == "1" || (focalLengths != null && focalLengths.any { it < ultraWideFocalLengthThreshold }))) {
                            "Front Ultra Wide Camera"
                        } else {
                            "Front Camera"
                        }
                    }
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        if (isLogicalMultiCamera && physicalCameraIds.isNotEmpty()) {
                            determinedType = when (physicalCameraIds.size) {
                                3 -> "Back Triple Camera"
                                2 -> "Back Dual Camera"
                                else -> "Back Multi-Camera (${physicalCameraIds.size} Lenses)"
                            }
                        } else {
                            determinedType = when {
                                focalLengths?.any { it > telephotoFocalLengthThreshold } == true -> "Back Telephoto Camera"
                                focalLengths?.any { it < ultraWideFocalLengthThreshold } == true -> "Back Ultra Wide Camera"
                                else -> "Back Camera"
                            }
                        }
                    }
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> determinedType = "External Camera"
                }
 
                if (determinedType == null) {
                    determinedType = "Camera $id"
                }
 
                detectedCamerasMap[id] = CameraInfo(
                    title = "$determinedType (ID: $id)",
                    javaCameraId = id,
                    cameraId = id,
                    cameraType = determinedType,
                    isAvailable = true,
                    physicalCameraIds = physicalCameraIds
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing camera ID $id: ${e.message}", e)
            }
        }
 
         return detectedCamerasMap.values.sortedWith(compareBy {
            when (it.cameraType) {
                "Front Camera" -> 0
                "Front Ultra Wide Camera" -> 1
                "Back Camera" -> 2
                "Back Triple Camera" -> 3
                "Back Dual Camera" -> 4
                "Back Dual Wide Camera" -> 5
                "Back Ultra Wide Camera" -> 6
                "Back Telephoto Camera" -> 7
                else -> if (it.cameraType.startsWith("Back Multi-Camera")) 8 else 99
            }
        })
    }
 
    fun getCameraResolutions(cameraId: String): List<Size> {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
 
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
            val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
 
            (jpegSizes + previewSizes)
                .distinctBy { "${it.width}x${it.height}" }
                .filter { Math.min(it.width, it.height) >= 720 }
        } catch(e:Exception) { emptyList() }
    }
 
    private fun getOptimalPreviewSize(sizes: Array<Size>, w: Int, h: Int): Size {
         val aspectRatio = w.toDouble() / h.toDouble()
         return sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
    }
 
    private fun applyCameraQualityAndFocusSettings(builder: CaptureRequest.Builder) {
        val bestAfMode = getBestSupportedAfMode()
        builder.set(CaptureRequest.CONTROL_AF_MODE, bestAfMode)
        
        if (isFrontCamera) {
            val nrModes = characteristics?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            if (nrModes != null) {
                when {
                    nrModes.any { it == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY } -> {
                        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    }
                    nrModes.any { it == CaptureRequest.NOISE_REDUCTION_MODE_FAST } -> {
                        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    }
                }
            }
            
            val edgeModes = characteristics?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            if (edgeModes != null) {
                when {
                    edgeModes.any { it == CaptureRequest.EDGE_MODE_HIGH_QUALITY } -> {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                    edgeModes.any { it == CaptureRequest.EDGE_MODE_FAST } -> {
                        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    }
                }
            }
        }
    }
 
    private fun getBestSupportedAfMode(): Int {
        val charas = characteristics ?: return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val afModes = charas.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        
        return when {
            afModes.any { it == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE } -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            afModes.any { it == CaptureRequest.CONTROL_AF_MODE_AUTO } -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }
 
    override fun close() {
        cameraExecutor.execute {
            closeInternal()
        }
    }
 
    private fun closeInternal() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            previewRequestBuilder = null
            previewSurface?.release()
            previewSurface = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
 
            mainHandler.post {
                this.textureView?.let { tv ->
                    layoutChangeListener?.let { listener ->
                        tv.removeOnLayoutChangeListener(listener)
                    }
                }
                layoutChangeListener = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }
 
    companion object {
        private const val TAG = "Camera2Controller"
    }
}
