package com.tananaev.passportreader.core.camera
 
import com.tananaev.passportreader.utils.safeCreateScaledBitmap
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.utils.ResolutionItem
import com.tananaev.passportreader.utils.predefinedResolutionsByRatio
import com.tananaev.passportreader.features.camera.SoftwareAutoFramingController
import com.tananaev.passportreader.core.camera.CameraMetadataHelper
import com.tananaev.passportreader.core.camera.CameraResolutionHelper
import com.tananaev.passportreader.core.camera.CameraInfo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.tananaev.passportreader.features.monitor_logging.AppLog as Log
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
        return CameraResolutionHelper.isCroppingNeeded(context, aspectRatio)
    }
 
    fun getActiveAspectRatio(): UiAspectRatio {
        return CameraResolutionHelper.getActiveAspectRatio(context, aspectRatio)
    }
 
    fun getActiveResolution(selectedRes: Size?): Size? {
        return CameraResolutionHelper.getActiveResolution(context, aspectRatio, selectedRes)
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
            var targetCameraId = cameraIdToOpen
            val available = getAvailableCameras()
            if (available.isNotEmpty() && !available.contains(targetCameraId)) {
                val fallbackId = available.first()
                Log.w(TAG, "Camera ID $targetCameraId not found. Falling back to first available: $fallbackId")
                targetCameraId = fallbackId
            }

            val activeRes = getActiveResolution(desiredResolution)
            val activeRatio = getActiveAspectRatio()
            if (cameraDevice != null && cameraId == targetCameraId && 
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
            this.cameraId = targetCameraId
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
            var availableJpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.sortedByDescending { size -> size.width * size.height } ?: emptyList()

            if (availableJpegSizes.isEmpty()) {
                Log.w(TAG, "No JPEG output sizes supported by this camera! Falling back to SurfaceTexture sizes.")
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (!previewSizes.isNullOrEmpty()) {
                    availableJpegSizes = previewSizes.sortedByDescending { it.width * it.height }
                }
            }

            if (availableJpegSizes.isEmpty()) {
                Log.e(TAG, "Camera supports no output sizes. Aborting camera open.")
                cameraOpenCloseLock.release()
                return
            }
 
            maxSensorProcessingSize = availableJpegSizes.firstOrNull()
                ?: characteristics!!.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Size(it.width(), it.height()) }
                        ?: Size(4000, 3000)
 
            Log.d(TAG, "Max sensor processing size: $maxSensorProcessingSize. Is Front Camera: $isFrontCamera")
 
            val activeResolution = getActiveResolution(targetResolution)
            val finalCaptureSize = if (activeResolution != null) {
                val targetRatio = max(activeResolution.width, activeResolution.height).toFloat() /
                                  min(activeResolution.width, activeResolution.height).toFloat()

                val tolerance = 0.05
                val matched = availableJpegSizes.filter {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    Math.abs(ratio - targetRatio) < tolerance
                }
                if (matched.isNotEmpty()) {
                    // Always select the largest matching resolution to prevent sensor mode crop/zoom
                    matched.maxByOrNull { it.width * it.height }!!
                } else {
                    // Fallback to size with closest aspect ratio
                    availableJpegSizes.minByOrNull { size ->
                        val ratio = size.width.toFloat() / size.height.toFloat()
                        Math.abs(ratio - targetRatio)
                    } ?: availableJpegSizes.first()
                }
            } else {
                val activeAR = getActiveAspectRatio()
                val targetRatio = activeAR.value ?: (4f / 3f)
                val targetRatioNormalized = if (targetRatio < 1f) 1f / targetRatio else targetRatio

                val tolerance = 0.05
                val matched = availableJpegSizes.filter {
                    val ratio = it.width.toFloat() / it.height.toFloat()
                    Math.abs(ratio - targetRatioNormalized) < tolerance
                }
                matched.maxByOrNull { it.width * it.height } ?: availableJpegSizes.first()
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
 
            Log.d(TAG, "Selected preview size: $previewSize (Target requested: ${targetResolution?.width}x${targetResolution?.height})")
 
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
        return CameraResolutionHelper.getResolutionsForAspectRatio(
            characteristics,
            isFrontCamera,
            maxSensorProcessingSize,
            aspectRatio
        )
    }
 
    private fun getOptimalPreviewSize(sizes: Array<Size>, targetSize: Size): Size {
        return CameraResolutionHelper.getOptimalPreviewSize(sizes, targetSize)
    }
 
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val textureView = this.textureView ?: return
        val previewSize = this.previewSize ?: return
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = windowManager?.defaultDisplay?.rotation ?: 0
        val matrix = android.graphics.Matrix()
        
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayRotation = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        
        // Calculate the relative rotation of the sensor compared to the display
        val relativeRotation = if (isFrontCamera) {
            (sensorOrientation + displayRotation) % 360
        } else {
            (sensorOrientation - displayRotation + 360) % 360
        }
        
        // Determine if we need to swap width/height based on relative rotation
        val swapDimensions = relativeRotation == 90 || relativeRotation == 270
        val pWidth = if (swapDimensions) previewSize.height.toFloat() else previewSize.width.toFloat()
        val pHeight = if (swapDimensions) previewSize.width.toFloat() else previewSize.height.toFloat()
        
        Log.d(TAG, "configureTransform: viewSize=${viewWidth}x${viewHeight}, previewSize=${previewSize.width}x${previewSize.height}, rotation=$rotation, sensorOrientation=$sensorOrientation, relativeRotation=$relativeRotation, swapDimensions=$swapDimensions")

        // 1. Calculate scale factor to fill the TextureView (Center Crop) without distortion
        val scaleX = viewWidth.toFloat() / pWidth
        val scaleY = viewHeight.toFloat() / pHeight
        val targetScale = max(scaleX, scaleY)
        
        val scaleXRelative = (targetScale * pWidth) / viewWidth
        val scaleYRelative = (targetScale * pHeight) / viewHeight
        
        if (swapDimensions) {
            matrix.setScale(scaleYRelative, scaleXRelative, centerX, centerY)
        } else {
            matrix.setScale(scaleXRelative, scaleYRelative, centerX, centerY)
        }
        
        // 2. Rotate the preview content to compensate for display rotation.
        if (displayRotation != 0) {
            val rotationDegrees = when (displayRotation) {
                90 -> 270f
                180 -> 180f
                270 -> 90f
                else -> 0f
            }
            matrix.postRotate(rotationDegrees, centerX, centerY)
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
                        Log.e(TAG, "Camera capture session configuration failed! This resolution may not be supported.")
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
        return CameraMetadataHelper.getAvailableCameras(cameraManager)
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
