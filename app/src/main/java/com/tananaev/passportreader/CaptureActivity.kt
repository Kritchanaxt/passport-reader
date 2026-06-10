package com.tananaev.passportreader
 
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import com.tananaev.passportreader.AppLog as Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tananaev.passportreader.core.AIDetectedItem
import com.tananaev.passportreader.core.AiMode
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.view.components.camera.CameraPreviewScreen
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
 
class CaptureActivity : AppCompatActivity() {
 
    private var mrzResult: MRZParser.ParsedMRZ? = null
    private var rawOcrTextStore: String = ""

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun getPaddleOcrProcessor(): com.tananaev.passportreader.features.ocr_paddle.OCRProcessor {
        if (com.tananaev.passportreader.core.AIManager.getActiveProcessor() !is com.tananaev.passportreader.features.ocr_paddle.OCRProcessor) {
            com.tananaev.passportreader.core.feature.FeatureRegistry.enable(
                this,
                "paddle_ocr",
                com.tananaev.passportreader.core.feature.FeatureConfig(useGpu = false, threads = 4)
            )
        }
        return com.tananaev.passportreader.core.AIManager.getActiveProcessor() as com.tananaev.passportreader.features.ocr_paddle.OCRProcessor
    }

    private fun getTextRecognitionProcessor(): com.tananaev.passportreader.features.ocr_mlkit.TextRecognitionProcessor {
        if (com.tananaev.passportreader.core.AIManager.getActiveProcessor() !is com.tananaev.passportreader.features.ocr_mlkit.TextRecognitionProcessor) {
            com.tananaev.passportreader.core.feature.FeatureRegistry.enable(
                this,
                "text_recognition"
            )
        }
        return com.tananaev.passportreader.core.AIManager.getActiveProcessor() as com.tananaev.passportreader.features.ocr_mlkit.TextRecognitionProcessor
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        LogOverlayHelper.attach(this)
    }
 
    private fun startCamera() {
        setContent {
            var aiMode by remember { mutableStateOf(AiMode.OCR) }
            var zoomScale by remember { mutableStateOf(1.0f) }
            var useCropMode by remember { mutableStateOf(true) }
            var selectedResolution by remember { mutableStateOf<android.util.Size?>(null) }
            var availableResolutions by remember { mutableStateOf<List<android.util.Size>>(emptyList()) }
            var selectedCameraId by remember { mutableStateOf("0") }
            var selectedAspectRatio by remember { mutableStateOf(UiAspectRatio.RATIO_1_1) }
            var horizontalFlip by remember { mutableStateOf(false) }
            var verticalFlip by remember { mutableStateOf(false) }
            var autoFramingEnabled by remember { mutableStateOf(false) }

            var showConfirmationScreen by remember { mutableStateOf(false) }
            var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var rawOcrText by remember { mutableStateOf("") }
            var isOcrTextExpanded by remember { mutableStateOf(false) }

            val aiState by com.tananaev.passportreader.core.AiStateManager.state.collectAsState()
            val detectorLatency = aiState.detectorLatency
 
            if (!showConfirmationScreen) {
                CameraPreviewScreen(
                    aiMode = aiMode,
                    onAiModeChange = { aiMode = it },
                    zoomScale = zoomScale,
                    onZoomScaleChange = { zoomScale = it },
                    useCropMode = useCropMode,
                    onUseCropModeChange = { useCropMode = it },
                    selectedResolution = selectedResolution,
                    onResolutionChange = { selectedResolution = it },
                    availableResolutions = availableResolutions,
                    onAvailableResolutionsChange = { availableResolutions = it },
                    selectedCameraId = selectedCameraId,
                    onCameraIdChange = { selectedCameraId = it },
                    selectedAspectRatio = selectedAspectRatio,
                    onAspectRatioChange = { selectedAspectRatio = it },
                    horizontalFlip = horizontalFlip,
                    onHorizontalFlipChange = { horizontalFlip = it },
                    verticalFlip = verticalFlip,
                    onVerticalFlipChange = { verticalFlip = it },
                    autoFramingEnabled = autoFramingEnabled,
                    onAutoFramingEnabledChange = { autoFramingEnabled = it },
                    onStableDetection = { bitmap, isFront ->
                        processFrame(bitmap, aiMode)
                    },
                    onImageCaptured = { bitmap, isFront ->
                        capturedBitmap = bitmap
                        rawOcrText = rawOcrTextStore
                        showConfirmationScreen = true
                    },
                    onBackClick = {
                        finish()
                    }
                )
            } else {
                com.tananaev.passportreader.view.components.camera.ConfirmationScreen(
                    mrzResult = mrzResult,
                    capturedBitmap = capturedBitmap,
                    rawOcrText = rawOcrText,
                    aiMode = aiMode,
                    detectorLatency = detectorLatency,
                    selectedResolution = selectedResolution,
                    zoomScale = zoomScale,
                    onRescan = {
                        mrzResult = null
                        rawOcrTextStore = ""
                        capturedBitmap = null
                        com.tananaev.passportreader.core.AiStateManager.updateState { 
                            it.copy(isCapturing = false) 
                        }
                        showConfirmationScreen = false
                    },
                    onConfirm = {
                        if (capturedBitmap != null) {
                            handleImageCaptured(capturedBitmap!!, false)
                        } else {
                            finish()
                        }
                    }
                )
            }
        }
    }


 
    private suspend fun processFrame(bitmap: Bitmap, aiMode: AiMode): Pair<Boolean, List<AIDetectedItem>> {
        if (aiMode == AiMode.PADDLE_OCR) {
            val ocr = getPaddleOcrProcessor()
            val jsonResult = ocr.getRawJson(bitmap) ?: "[]"
            
            val mergedLines = MRZParser.getMergedRawLinesFromPaddle(jsonResult)
            rawOcrTextStore = mergedLines.joinToString("\n")
            
            val parsedResult = MRZParser.parseFromPaddleJson(jsonResult)
            val detectedItems = mutableListOf<AIDetectedItem>()
            
            try {
                val array = org.json.JSONArray(jsonResult)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val label = obj.optString("label", "")
                    val prob = obj.optDouble("prob", 0.0).toFloat()
                    
                    val x0 = obj.optDouble("x0", 0.0).toFloat()
                    val y0 = obj.optDouble("y0", 0.0).toFloat()
                    val x1 = obj.optDouble("x1", 0.0).toFloat()
                    val y1 = obj.optDouble("y1", 0.0).toFloat()
                    val x2 = obj.optDouble("x2", 0.0).toFloat()
                    val y2 = obj.optDouble("y2", 0.0).toFloat()
                    val x3 = obj.optDouble("x3", 0.0).toFloat()
                    val y3 = obj.optDouble("y3", 0.0).toFloat()
                    
                    val minX = minOf(minOf(x0, x1), minOf(x2, x3))
                    val maxX = maxOf(maxOf(x0, x1), maxOf(x2, x3))
                    val minY = minOf(minOf(y0, y1), minOf(y2, y3))
                    val maxY = maxOf(maxOf(y0, y1), maxOf(y2, y3))
                    
                    detectedItems.add(
                        AIDetectedItem(
                            label = label,
                            confidence = prob,
                            boundingBox = android.graphics.RectF(minX, minY, maxX, maxY)
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("CaptureActivity", "Error parsing paddle items", e)
            }
            
            return if (parsedResult != null) {
                mrzResult = parsedResult
                Pair(true, detectedItems)
            } else {
                Pair(false, detectedItems)
            }
        }

        if (aiMode == AiMode.TEXT_RECOGNITION) {
            val processor = getTextRecognitionProcessor()
            val result = processor.process(bitmap)
            
            val detectedItems = result.items.map { item ->
                AIDetectedItem(
                    label = item.label,
                    confidence = item.confidence,
                    boundingBox = android.graphics.RectF(item.boundingBox)
                )
            }
            
            rawOcrTextStore = detectedItems.joinToString("\n") { it.label }
            
            val mergedLines = detectedItems.map { it.label }
            val parsedResult = MRZParser.parseGeneralText(mergedLines)
            
            return if (parsedResult != null) {
                mrzResult = parsedResult
                Pair(true, detectedItems)
            } else {
                Pair(false, detectedItems)
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Reconstruct continuous lines by merging horizontal segments based on Y coordinates
                    val mergedLines = MRZParser.getMergedRawLines(visionText)
                    rawOcrTextStore = mergedLines.joinToString("\n")

                    val parsedResult = if (aiMode == AiMode.OCR) {
                        MRZParser.parse(visionText)
                    } else {
                        MRZParser.parseGeneralText(mergedLines)
                    }

                    if (parsedResult != null) {
                        val detectedItems = mutableListOf<AIDetectedItem>()
                        for (block in visionText.textBlocks) {
                            detectedItems.add(
                                AIDetectedItem(
                                    label = block.text,
                                    confidence = 0.9f,
                                    boundingBox = android.graphics.RectF(block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0))
                                )
                            )
                        }
                        mrzResult = parsedResult
                        continuation.resume(Pair(true, detectedItems))
                    } else {
                        val detectedItems = mutableListOf<AIDetectedItem>()
                        for (block in visionText.textBlocks) {
                            detectedItems.add(
                                AIDetectedItem(
                                    label = block.text,
                                    confidence = 0.5f,
                                    boundingBox = android.graphics.RectF(block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0))
                                )
                            )
                        }
                        continuation.resume(Pair(false, detectedItems))
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    continuation.resume(Pair(false, emptyList()))
                }
        }
    }
 
    private fun handleImageCaptured(bitmap: Bitmap, isFront: Boolean) {
        val result = mrzResult
        if (result != null) {
            val intent = Intent()
            intent.putExtra("documentNumber", result.documentNumber)
            intent.putExtra("dateOfBirth", result.dateOfBirth)
            intent.putExtra("dateOfExpiry", result.expirationDate)
            setResult(RESULT_OK, intent)
            finish()
        }
    }
 
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }
 
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
 
    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
        com.tananaev.passportreader.core.AIManager.release()
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
