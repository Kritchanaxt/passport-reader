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
            var selectedAspectRatio by remember { mutableStateOf(UiAspectRatio.RATIO_3_4) }
            var horizontalFlip by remember { mutableStateOf(false) }
            var verticalFlip by remember { mutableStateOf(false) }
            var autoFramingEnabled by remember { mutableStateOf(false) }

            var showConfirmationScreen by remember { mutableStateOf(false) }
            var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
            var rawOcrText by remember { mutableStateOf("") }
            var isOcrTextExpanded by remember { mutableStateOf(false) }
 
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
                val result = mrzResult
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isWideScreen = configuration.screenWidthDp >= 600

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF0F6FF) // Premium Light Blue-White Background
                ) {
                    if (isWideScreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left Pane (Header + Crop Preview + Parsed Fields)
                            Column(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxHeight()
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ConfirmationHeader(alignStart = true)
                                CropPreviewCard(capturedBitmap)
                                ParsedDetailsCard(result)
                            }

                            // Right Pane (Raw OCR Results + Action Buttons)
                            Column(
                                modifier = Modifier
                                    .weight(0.9f)
                                    .fillMaxHeight()
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                RawOcrCard(rawOcrText, height = 240.dp) // Taller OCR box on tablet
                                Spacer(modifier = Modifier.weight(1f))
                                ActionButtons(
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
                                    },
                                    isConfirmEnabled = (result != null)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ConfirmationHeader(alignStart = false)
                            CropPreviewCard(capturedBitmap)
                            ParsedDetailsCard(result)
                            RawOcrCard(rawOcrText, height = 130.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            ActionButtons(
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
                                },
                                isConfirmEnabled = (result != null)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfirmationHeader(alignStart: Boolean) {
        Column(
            horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "ตรวจสอบข้อมูลการสแกน",
                color = Color(0xFF0F172A),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (alignStart) TextAlign.Start else TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "กรุณาตรวจสอบรูปภาพที่ถูกตัดเฉพาะส่วน MRZ และค่าที่ตรวจจับได้",
                color = Color(0xFF475569),
                fontSize = 13.sp,
                textAlign = if (alignStart) TextAlign.Start else TextAlign.Center
            )
        }
    }

    @Composable
    private fun CropPreviewCard(bmp: Bitmap?) {
        bmp?.let {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFBFDBFE)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Cropped MRZ Zone",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    @Composable
    private fun ParsedDetailsCard(result: MRZParser.ParsedMRZ?) {
        if (result != null) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DataRow(label = "Passport Number", value = result.documentNumber)
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    
                    val formattedExpiry = formatMrzDate(result.expirationDate)
                    val formattedDob = formatMrzDate(result.dateOfBirth)
                    
                    DataRow(label = "Expiration Date", value = formattedExpiry)
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    DataRow(label = "Date of Birth", value = formattedDob)
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ไม่สามารถถอดรหัสรูปแบบ MRZ ได้สมบูรณ์",
                        color = Color(0xFFEF4444),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun RawOcrCard(rawOcrText: String, height: androidx.compose.ui.unit.Dp) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "ผลลัพธ์ข้อความ OCR ดิบ",
                        color = Color(0xFF0F172A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = Color(0xFFE2E8F0))
                
                Text(
                    text = if (rawOcrText.trim().isNotEmpty()) rawOcrText else "ไม่มีข้อความตรวจจับ",
                    color = Color(0xFF334155),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }

    @Composable
    private fun ActionButtons(
        onRescan: () -> Unit,
        onConfirm: () -> Unit,
        isConfirmEnabled: Boolean
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRescan,
                shape = RoundedCornerShape(100.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFEF4444)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    text = "สแกนใหม่",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Button(
                onClick = onConfirm,
                enabled = isConfirmEnabled,
                shape = RoundedCornerShape(100.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFCBD5E1),
                    disabledContentColor = Color(0xFF94A3B8)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    text = "ยืนยัน",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }

    @Composable
    private fun DataRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFF475569),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                color = Color(0xFF0F172A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    private fun formatMrzDate(mrzDate: String): String {
        if (mrzDate.length != 6) return mrzDate
        val clean = mrzDate.replace("I", "1")
            .replace("L", "1")
            .replace("D", "0")
            .replace("O", "0")
            .replace("S", "5")
            .replace("G", "6")
        
        val yy = clean.substring(0, 2)
        val mm = clean.substring(2, 4)
        val dd = clean.substring(4, 6)
        
        // Return standard YY-MM-DD
        return "$yy-$mm-$dd"
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
