package com.tananaev.passportreader.view.components.camera
 
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tananaev.passportreader.core.AiMode
 
@Composable
fun AiModeSelector(
    showAiModeSheet: Boolean,
    currentAiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (showAiModeSheet) {
        AiModeBottomSheet(
            currentAiMode = currentAiMode,
            onAiModeChange = onAiModeChange,
            onDismiss = onDismiss
        )
    }
}
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModeBottomSheet(
    currentAiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val orderedModes = listOf(
                AiMode.OCR,
                AiMode.TEXT_RECOGNITION
            )
 
            orderedModes.forEach { mode ->
                val label = when (mode) {
                    AiMode.OCR -> "MRZ Passport Scanner"
                    AiMode.TEXT_RECOGNITION -> "Text Recognition"
                    else -> mode.name
                }
 
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAiModeChange(mode)
                            onDismiss()
                        }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .border(3.dp, if (currentAiMode == mode) Color(0xFF007AFF) else Color.Gray, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentAiMode == mode) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(Color(0xFF007AFF), CircleShape)
                            )
                        }
                    }
 
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
    }
}
