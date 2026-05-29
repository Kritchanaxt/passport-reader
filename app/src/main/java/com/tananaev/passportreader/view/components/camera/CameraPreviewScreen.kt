package com.tananaev.passportreader.view.components.camera
 
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tananaev.passportreader.core.AiMode
import com.tananaev.passportreader.core.AIDetectedItem
import com.tananaev.passportreader.core.AiState
import com.tananaev.passportreader.core.AiStateManager
import com.tananaev.passportreader.core.SystemMonitor
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.utils.ResolutionItem
import com.tananaev.passportreader.utils.predefinedResolutionsByRatio
import com.tananaev.passportreader.core.camera.Camera2Controller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
 
class CameraStateWrapper(private val composeState: State<AiState>) {
    var fps: Int
        get() = composeState.value.fps
        set(value) { AiStateManager.updateState { it.copy(fps = value) } }
    var detectorLatency: Long
        get() = composeState.value.detectorLatency
        set(value) { AiStateManager.updateState { it.copy(detectorLatency = value) } }
    var frameLatency: Long
        get() = composeState.value.frameLatency
        set(value) { AiStateManager.updateState { it.copy(frameLatency = value) } }
    var ramUsed: Long
        get() = composeState.value.ramUsed
        set(value) { AiStateManager.updateState { it.copy(ramUsed = value) } }
    var ramTotal: Long
        get() = composeState.value.ramTotal
        set(value) { AiStateManager.updateState { it.copy(ramTotal = value) } }
    var freeRamMb: Long
        get() = composeState.value.freeRamMb
        set(value) { AiStateManager.updateState { it.copy(freeRamMb = value) } }
    var cpuUsage: String
        get() = composeState.value.cpuUsage
        set(value) { AiStateManager.updateState { it.copy(cpuUsage = value) } }
    var isCapturing: Boolean
        get() = composeState.value.isCapturing
        set(value) { AiStateManager.updateState { it.copy(isCapturing = value) } }
    var isFrontCamera: Boolean
        get() = composeState.value.isFrontCamera
        set(value) { AiStateManager.updateState { it.copy(isFrontCamera = value) } }
}
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    aiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    zoomScale: Float,
    onZoomScaleChange: (Float) -> Unit,
    useCropMode: Boolean,
    onUseCropModeChange: (Boolean) -> Unit,
    selectedResolution: android.util.Size?,
    onResolutionChange: (android.util.Size) -> Unit,
    availableResolutions: List<android.util.Size>,
    onAvailableResolutionsChange: (List<android.util.Size>) -> Unit,
    onStableDetection: suspend (Bitmap, Boolean) -> Pair<Boolean, List<AIDetectedItem>>,
    onImageCaptured: (Bitmap, Boolean) -> Unit,
    isProcessingBusy: Boolean = false,
    selectedCameraId: String,
    onCameraIdChange: (String) -> Unit,
    selectedAspectRatio: UiAspectRatio,
    onAspectRatioChange: (UiAspectRatio) -> Unit,
    horizontalFlip: Boolean = false,
    onHorizontalFlipChange: (Boolean) -> Unit = {},
    verticalFlip: Boolean = false,
    onVerticalFlipChange: (Boolean) -> Unit = {},
    autoFramingEnabled: Boolean = false,
    onAutoFramingEnabledChange: (Boolean) -> Unit = {},
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraController by remember { mutableStateOf<Camera2Controller?>(null) }
 
    val availableCameras = remember {
        val tempController = Camera2Controller(context) { _, _ -> }
        val list = tempController.enumerateCameras()
        tempController.close()
        list
    }
 
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAiModeSheet by remember { mutableStateOf(false) }
    val isPreviewPausedState = remember { mutableStateOf(false) }
    var isPreviewPaused by isPreviewPausedState
    val stableTimeState = remember { mutableStateOf(0L) }
    val stableTime by stableTimeState
    val faceBuffer2sState = remember { mutableStateOf<Bitmap?>(null) }
 
    val latestItemsOcr = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestDetectionsState = remember { mutableStateOf<List<AIDetectedItem>>(emptyList()) }
    val latestDetections by latestDetectionsState
    val bitmapWidthState = remember { mutableStateOf(720f) }
    val bitmapWidth by bitmapWidthState
    val bitmapHeightState = remember { mutableStateOf(1280f) }
    val bitmapHeight by bitmapHeightState
 
    val composeState = AiStateManager.state.collectAsState()
    val wrapper = remember(composeState) { CameraStateWrapper(composeState) }
 
    with(wrapper) {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val usage = SystemMonitor.getCurrentResourceUsage(context)
                    withContext(Dispatchers.Main) {
                        freeRamMb = usage.ramFreeMb
                        ramUsed = usage.ramUsedMb
                        ramTotal = usage.ramTotalMb
                        cpuUsage = usage.cpuUsage
                    }
                    delay(2000L)
                }
            }
        }
 
        val currentOnStableDetection = rememberUpdatedState(onStableDetection)
        val currentOnImageCaptured = rememberUpdatedState(onImageCaptured)
 
        LaunchedEffect(selectedCameraId, selectedAspectRatio, aiMode) {
            if (cameraController == null) {
                cameraController = Camera2Controller(context) { bitmap, isFront ->
                    currentOnImageCaptured.value(
                        bitmap,
                        isFront
                    )
                }
            }
 
            val hardwareSizes = cameraController!!.getCameraResolutions(selectedCameraId)
            val allSizes = hardwareSizes.toMutableList()
 
            if (selectedAspectRatio == UiAspectRatio.RATIO_1_1) {
                allSizes.add(android.util.Size(720, 720))
                allSizes.add(android.util.Size(1080, 1080))
                allSizes.add(android.util.Size(1440, 1440))
                allSizes.add(android.util.Size(1920, 1920))
                allSizes.add(android.util.Size(2160, 2160))
            }
 
            val targetRatio = selectedAspectRatio.value
            val tolerance = 0.05f
 
            val filtered = if (targetRatio != null) {
                allSizes.filter { size ->
                    val ratio = size.width.toFloat() / size.height.toFloat()
                    val invRatio = size.height.toFloat() / size.width.toFloat()
                    abs(ratio - targetRatio) < tolerance || abs(invRatio - targetRatio) < tolerance
                }
            } else {
                allSizes
            }
 
            val isPortraitRatio = selectedAspectRatio.isPortraitDefault
            val normalized = filtered.map { size ->
                when {
                    isPortraitRatio && size.width > size.height ->
                        android.util.Size(size.height, size.width)
                    !isPortraitRatio && selectedAspectRatio != UiAspectRatio.RATIO_1_1 && size.height > size.width ->
                        android.util.Size(size.height, size.width)
                    else -> size
                }
            }
 
            val cleanSizes = normalized.filter { size ->
                size.width % 8 == 0 && size.height % 8 == 0 &&
                size.width != 1088 && size.height != 1088
            }
            val finalResolutions = cleanSizes.distinct().sortedByDescending { it.width * it.height }
            onAvailableResolutionsChange(finalResolutions)
 
            if (selectedResolution == null || !finalResolutions.contains(selectedResolution)) {
                finalResolutions.minByOrNull { it.width * it.height }?.let { onResolutionChange(it) }
            }
 
            cameraController?.aspectRatio = selectedAspectRatio
        }
 
        LaunchedEffect(zoomScale) {
            cameraController?.setZoom(zoomScale)
        }
 
        var showAutoFramingMsg by remember { mutableStateOf(false) }
 
        LaunchedEffect(autoFramingEnabled) {
            cameraController?.setAutoFraming(autoFramingEnabled)
            if (autoFramingEnabled) {
                showAutoFramingMsg = true
                delay(3000)
                showAutoFramingMsg = false
            }
        }
 
        LaunchedEffect(isPreviewPaused, isProcessingBusy, cameraController) {
            if (isPreviewPaused || isProcessingBusy) {
                cameraController?.pausePreview()
            } else {
                cameraController?.resumePreview()
            }
        }
 
        val cameraKey =
            "$selectedCameraId-${selectedResolution?.width}x${selectedResolution?.height}-${selectedAspectRatio.name}"
 
        CameraFrameProcessor(
            cameraKey = cameraKey,
            aiMode = aiMode,
            isProcessingBusy = isProcessingBusy,
            useCropMode = useCropMode,
            horizontalFlip = horizontalFlip,
            verticalFlip = verticalFlip,
            autoFramingEnabled = autoFramingEnabled,
            stableTimeState = stableTimeState,
            bitmapWidthState = bitmapWidthState,
            bitmapHeightState = bitmapHeightState,
            cameraController = cameraController,
            currentOnStableDetection = currentOnStableDetection,
            currentOnImageCaptured = currentOnImageCaptured,
            latestItemsOcr = latestItemsOcr,
            latestDetectionsState = latestDetectionsState,
            faceBuffer2sState = faceBuffer2sState,
            isPreviewPausedState = isPreviewPausedState,
            context = context,
            wrapper = wrapper
        )
 
        DisposableEffect(Unit) {
            onDispose {
                cameraController?.close()
            }
        }
 
        Scaffold(
            containerColor = Color.Black
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(top = paddingValues.calculateTopPadding())) {
                val ratioVal = if (selectedResolution != null && selectedResolution.width == selectedResolution.height) {
                    1.0f
                } else {
                    selectedAspectRatio.value
                }
 
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (ratioVal != null) it.aspectRatio(ratioVal) else it.fillMaxHeight() }
                        .align(Alignment.Center)
                        .clip(RectangleShape)
                        .background(Color.Black)
                ) {
                    CameraPreviewView(
                        selectedAspectRatio = selectedAspectRatio,
                        selectedCameraId = selectedCameraId,
                        selectedResolution = selectedResolution,
                        cameraController = cameraController
                    )
 
                    OverlayRenderer(
                        aiMode = aiMode,
                        stableTime = stableTime,
                        isCapturing = isCapturing,
                        useCropMode = useCropMode,
                        bitmapWidth = bitmapWidth,
                        bitmapHeight = bitmapHeight,
                        latestDetections = latestDetections,
                        selectedResolution = selectedResolution
                    )
 
                    AnimatedVisibility(
                        visible = showAutoFramingMsg,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "ถือโทรศัพท์ให้นิ่งขณะใช้การจัดเฟรมอัตโนมัติ",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
 
                CameraControls(
                    zoomScale = zoomScale,
                    onZoomScaleChange = onZoomScaleChange,
                    horizontalFlip = horizontalFlip,
                    onHorizontalFlipChange = onHorizontalFlipChange,
                    verticalFlip = verticalFlip,
                    onVerticalFlipChange = onVerticalFlipChange,
                    autoFramingEnabled = autoFramingEnabled,
                    onAutoFramingEnabledChange = onAutoFramingEnabledChange,
                    context = context,
                    onShowSettingsDialogChange = { showSettingsDialog = it },
                    isCapturing = isCapturing,
                    stableTime = stableTime,
                    isProcessingBusy = isProcessingBusy,
                    useCropMode = useCropMode,
                    onUseCropModeChange = onUseCropModeChange,
                    onShowAiModeSheetChange = { showAiModeSheet = it },
                    aiMode = aiMode,
                    onBackClick = onBackClick
                )
            }
        }
 
        if (showSettingsDialog) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = Color(0xFFF0F6FF),
                tonalElevation = 8.dp
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Camera Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                    }
 
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    Spacer(modifier = Modifier.height(20.dp))
 
                    Text(
                        "Select Camera",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D4ED8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        availableCameras.forEachIndexed { index, cameraInfo ->
                            val id = cameraInfo.cameraId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCameraIdChange(id) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (id == selectedCameraId),
                                    onClick = { onCameraIdChange(id) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF2563EB),
                                        unselectedColor = Color(0xFF94A3B8)
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val titleString = cameraInfo.title
                                    val idIndex = titleString.lastIndexOf("(ID:")
                                    if (idIndex != -1) {
                                        val mainName = titleString.substring(0, idIndex).trim()
                                        val idPart = titleString.substring(idIndex).trim()
                                        Text(
                                            text = mainName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF0F172A)
                                        )
                                        Text(
                                            text = idPart,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF64748B)
                                        )
                                    } else {
                                        Text(
                                            text = titleString,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF0F172A)
                                        )
                                    }
                                }
                            }
                            if (index < availableCameras.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = Color(0xFFF1F5F9)
                                )
                            }
                        }
                    }
 
                    Spacer(modifier = Modifier.height(24.dp))
 
                    Text(
                        "Aspect Ratio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D4ED8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
 
                    val supportedRatios = listOf(
                        UiAspectRatio.RATIO_3_4,
                        UiAspectRatio.RATIO_9_16,
                        UiAspectRatio.RATIO_1_1,
                        UiAspectRatio.RATIO_4_3,
                        UiAspectRatio.RATIO_16_9
                    )
 
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            supportedRatios.take(3).forEach { ratio ->
                                AspectRatioTile(
                                    ratio = ratio,
                                    isSelected = (ratio == selectedAspectRatio),
                                    onClick = { onAspectRatioChange(ratio) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            supportedRatios.drop(3).forEach { ratio ->
                                AspectRatioTile(
                                    ratio = ratio,
                                    isSelected = (ratio == selectedAspectRatio),
                                    onClick = { onAspectRatioChange(ratio) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
 
                    Spacer(modifier = Modifier.height(24.dp))
 
                    Text(
                        "Resolution (JPEG)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D4ED8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
 
                    val recommendedSize = availableResolutions.minByOrNull { it.width * it.height }
                    val sortedResolutions = if (recommendedSize != null) {
                        listOf(recommendedSize) + availableResolutions.filter { it != recommendedSize }.sortedBy { it.width * it.height }
                    } else {
                        availableResolutions.sortedBy { it.width * it.height }
                    }
 
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sortedResolutions.forEach { size ->
                            val isSelected = (size == selectedResolution)
                            val isRecommended = (size == recommendedSize)
                            val mp = (size.width * size.height) / 1_000_000f
                            val qualityLabel = when {
                                mp >= 8f -> "Ultra HD"
                                mp >= 3.5f -> "Quad HD"
                                mp >= 2f -> "Full HD"
                                mp >= 0.9f -> "HD"
                                else -> "Standard"
                            }
                            val qualityColor = when {
                                mp >= 8f -> Color(0xFF7C3AED)
                                mp >= 3.5f -> Color(0xFF2563EB)
                                mp >= 2f -> Color(0xFF0891B2)
                                mp >= 0.9f -> Color(0xFF059669)
                                else -> Color(0xFF64748B)
                            }
 
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isSelected && isRecommended -> Color(0xFFDCFCE7)
                                        isSelected -> Color(0xFFDBEAFE)
                                        isRecommended -> Color(0xFFF0FDF4)
                                        else -> Color.White
                                    }
                                ),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 2.dp else 1.dp,
                                    when {
                                        isSelected && isRecommended -> Color(0xFF16A34A)
                                        isSelected -> Color(0xFF2563EB)
                                        else -> Color(0xFFE2E8F0)
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onResolutionChange(size) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(qualityColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
 
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "${size.width} × ${size.height}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = Color(0xFF0F172A)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                color = qualityColor.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    qualityLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = qualityColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            String.format(Locale.US, "%.1f MP", mp),
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
 
                                    Spacer(modifier = Modifier.width(8.dp))
 
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(
                                                    if (isRecommended) Color(0xFF16A34A) else Color(0xFF2563EB),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .border(1.5.dp, Color(0xFFCBD5E1), CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
 
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
 
        if (showAiModeSheet) {
            AiModeBottomSheet(
                currentAiMode = aiMode,
                onAiModeChange = onAiModeChange,
                onDismiss = { showAiModeSheet = false }
            )
        }
    }
}
 
@Composable
private fun AspectRatioTile(
    ratio: UiAspectRatio,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) Color(0xFFDBEAFE) else Color.White
    val borderColor = if (isSelected) Color(0xFF2563EB) else Color(0xFFE2E8F0)
    val textColor = if (isSelected) Color(0xFF1E40AF) else Color(0xFF0F172A)
 
    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .padding(bottom = 4.dp)
            ) {
                val boxWidth: Float
                val boxHeight: Float
                when (ratio) {
                    UiAspectRatio.RATIO_1_1 -> { boxWidth = 22f; boxHeight = 22f }
                    UiAspectRatio.RATIO_16_9 -> { boxWidth = 30f; boxHeight = 17f }
                    UiAspectRatio.RATIO_4_3 -> { boxWidth = 26f; boxHeight = 19.5f }
                    UiAspectRatio.RATIO_9_16 -> { boxWidth = 17f; boxHeight = 30f }
                    UiAspectRatio.RATIO_3_4 -> { boxWidth = 19.5f; boxHeight = 26f }
                    else -> { boxWidth = 26f; boxHeight = 19.5f }
                }
                
                Box(
                    modifier = Modifier
                        .size(boxWidth.dp, boxHeight.dp)
                        .background(
                            if (isSelected) Color(0xFF93C5FD) else Color(0xFFF1F5F9),
                            RoundedCornerShape(3.dp)
                        )
                        .border(
                            1.5.dp,
                            if (isSelected) Color(0xFF2563EB) else Color(0xFF94A3B8),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
            
            Text(
                text = ratio.displayName,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = textColor,
                textAlign = TextAlign.Center
            )
            
            val label = when (ratio) {
                UiAspectRatio.RATIO_16_9, UiAspectRatio.RATIO_4_3 -> "Landscape"
                UiAspectRatio.RATIO_9_16, UiAspectRatio.RATIO_3_4 -> "Portrait"
                UiAspectRatio.RATIO_1_1 -> "Square"
                else -> "Auto"
            }
            
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) Color(0xFF2563EB) else Color(0xFF64748B),
                textAlign = TextAlign.Center
            )
        }
    }
}
