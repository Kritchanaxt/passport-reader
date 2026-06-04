package com.tananaev.passportreader
 
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
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
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            recognizer.process(image)
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
 
    companion object {
        private const val TAG = "CaptureActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
