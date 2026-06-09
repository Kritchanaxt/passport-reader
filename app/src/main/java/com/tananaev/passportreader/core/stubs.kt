package com.tananaev.passportreader.core
 
import android.graphics.Bitmap
import android.util.Size
import com.tananaev.passportreader.utils.UiAspectRatio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
 
enum class AiMode {
    TEXT_RECOGNITION,
    OCR
}
 
data class AIDetectedItem(
    val label: String,
    val confidence: Float,
    val boundingBox: android.graphics.RectF,
    val extra: Map<String, Any> = emptyMap()
)
 
data class AiState(
    val currentAiMode: AiMode = AiMode.OCR,
    val isProcessing: Boolean = false,
    val showAiModeSheet: Boolean = false,
    val currentImage: Bitmap? = null,
    val ocrResultJson: String = "[]",
    val ocrTimeMs: Long = 0L,
    val zoomScale: Float = 1.0f,
    val useCropMode: Boolean = true,
    val selectedResolution: Size? = null,
    val availableResolutions: List<Size> = emptyList(),
    val selectedCameraId: String = "0",
    val selectedAspectRatio: UiAspectRatio = UiAspectRatio.RATIO_1_1,
    val cropImage: Bitmap? = null,
    val processingResultMsg: String? = null,
    val horizontalFlip: Boolean = false,
    val verticalFlip: Boolean = false,
    val selectedOcrModel: String = "",
    val hasPermission: Boolean = false,
    val maxFps: Int = 30,
    val isThrottled: Boolean = false,
    val isIdCardMode: Boolean = true,
    val fps: Int = 0,
    val detectorLatency: Long = 0L,
    val frameLatency: Long = 0L,
    val ramUsed: Long = 0L,
    val ramTotal: Long = 0L,
    val freeRamMb: Long = 1000L,
    val cpuUsage: String = "0.0%",
    val isCapturing: Boolean = false,
    val isFrontCamera: Boolean = false,
    val autoFramingEnabled: Boolean = false,
    val isAutoScan: Boolean = !android.os.Build.MANUFACTURER.equals("SUNMI", ignoreCase = true),
    val isManualScanTriggered: Boolean = false
)
 
object AiStateManager {
    private val _state = MutableStateFlow(AiState())
    val state = _state.asStateFlow()
 
    fun updateState(transform: (AiState) -> AiState) {
        _state.update(transform)
    }
}

 
data class ResourceUsage(
    val cpuUsage: String,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val ramFreeMb: Long,
    val batteryLevel: Int,
    val batteryTemp: Float
)
 
object SystemMonitor {
    fun isUltraLowRAM(context: android.content.Context): Boolean {
        val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return totalRamGb <= 2.5 || actManager.isLowRamDevice
    }
 
    fun getCurrentResourceUsage(context: android.content.Context): ResourceUsage {
        val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val ramUsed = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val ramTotal = memInfo.totalMem / (1024 * 1024)
        return ResourceUsage(
            cpuUsage = "0.0%",
            ramUsedMb = ramUsed,
            ramTotalMb = ramTotal,
            ramFreeMb = ramTotal - ramUsed,
            batteryLevel = 100,
            batteryTemp = 30.0f
        )
    }
}
 
object AIManager {
    private val isBusyFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    fun isBusy(): Boolean = isBusyFlag.get()
    fun setBusy(busy: Boolean) = isBusyFlag.set(busy)
}
