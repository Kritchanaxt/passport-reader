package com.tananaev.passportreader.view.components.camera
 
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import com.tananaev.passportreader.core.AiMode
 
@Composable
fun BoxScope.CameraControls(
    zoomScale: Float,
    onZoomScaleChange: (Float) -> Unit,
    horizontalFlip: Boolean,
    onHorizontalFlipChange: (Boolean) -> Unit,
    verticalFlip: Boolean,
    onVerticalFlipChange: (Boolean) -> Unit,
    autoFramingEnabled: Boolean,
    onAutoFramingEnabledChange: (Boolean) -> Unit,
    context: Context,
    onShowSettingsDialogChange: (Boolean) -> Unit,
    isCapturing: Boolean,
    stableTime: Long,
    isProcessingBusy: Boolean,
    useCropMode: Boolean,
    onUseCropModeChange: (Boolean) -> Unit,
    onShowAiModeSheetChange: (Boolean) -> Unit,
    aiMode: AiMode,
    onBackClick: () -> Unit
) {
    val composeState = com.tananaev.passportreader.core.AiStateManager.state.collectAsState()
    val isAutoScan = composeState.value.isAutoScan
    val isManualScanTriggered = composeState.value.isManualScanTriggered

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopStart)
            .padding(top = 10.dp, end = 12.dp, bottom = 8.dp, start = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(40.dp)
                .background(Color.White.copy(alpha = 0.2f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var showScaleMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showScaleMenu = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    val displayZoom = if (zoomScale % 1.0f == 0.0f) "${zoomScale.toInt()}x" else "${zoomScale}x"
                    Text(
                        text = displayZoom,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(
                    expanded = showScaleMenu,
                    onDismissRequest = { showScaleMenu = false }
                ) {
                    listOf(1.0f, 1.2f, 1.5f, 2.0f, 3.0f).forEach { scale ->
                        DropdownMenuItem(
                            text = { Text("${scale}x") },
                            onClick = {
                                onZoomScaleChange(scale)
                                showScaleMenu = false
                            }
                        )
                    }
                }
            }
 
            IconButton(
                onClick = { onHorizontalFlipChange(!horizontalFlip) },
                modifier = Modifier
                    .size(40.dp)
                    .background(if (horizontalFlip) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = "Flip Horizontal",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
 
            IconButton(
                onClick = { onVerticalFlipChange(!verticalFlip) },
                modifier = Modifier
                    .size(40.dp)
                    .background(if (verticalFlip) Color(0xFF4CAF50).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = "Flip Vertical",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
 
            IconButton(
                onClick = {
                    val newState = !autoFramingEnabled
                    onAutoFramingEnabledChange(newState)
                    val msg = if (newState) "Auto-framing ON" else "Auto-framing OFF"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (autoFramingEnabled) Color(0xFF7C4DFF).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Auto-framing (Center Stage)",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
 
            IconButton(
                onClick = { onShowSettingsDialogChange(true) },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
 
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(100.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(100.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    val newMode = !isAutoScan
                    com.tananaev.passportreader.core.AiStateManager.updateState { it.copy(isAutoScan = newMode) }
                }
            ) {
                Switch(
                    checked = isAutoScan,
                    onCheckedChange = { newMode ->
                        com.tananaev.passportreader.core.AiStateManager.updateState { it.copy(isAutoScan = newMode) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2563EB),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.scale(0.8f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "AUTO-SCAN",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
 
            Surface(
                onClick = { onUseCropModeChange(!useCropMode) },
                color = if (useCropMode) Color(0xFF007AFF) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(100.dp),
                modifier = Modifier.height(26.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (useCropMode) "CROP IMAGE" else "FULL IMAGE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
 
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = { onShowAiModeSheetChange(true) },
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(100.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val displayName = when (aiMode) {
                        AiMode.OCR -> "MRZ Passport Scanner"
                        AiMode.TEXT_RECOGNITION -> "Text Recognition"
                    }
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (!isAutoScan) {
        val isManualScanning = isManualScanTriggered
        Box(
            modifier = with(this@CameraControls) {
                Modifier.align(Alignment.BottomCenter)
            }.padding(bottom = 100.dp)
        ) {
            Button(
                onClick = {
                    if (!isManualScanning && !isCapturing && !isProcessingBusy) {
                        com.tananaev.passportreader.core.AiStateManager.updateState { it.copy(isManualScanTriggered = true) }
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isManualScanning) Color(0xFF2563EB).copy(alpha = 0.6f) else Color(0xFF2563EB),
                    disabledContainerColor = Color.Gray
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(72.dp)
                    .border(4.dp, Color.White, CircleShape)
            ) {
                if (isManualScanning) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Scan Now",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}
