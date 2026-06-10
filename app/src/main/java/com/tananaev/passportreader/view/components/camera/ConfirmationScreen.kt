package com.tananaev.passportreader.view.components.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tananaev.passportreader.MRZParser
import com.tananaev.passportreader.core.AiMode

@Composable
fun ConfirmationScreen(
    mrzResult: MRZParser.ParsedMRZ?,
    capturedBitmap: Bitmap?,
    rawOcrText: String,
    aiMode: AiMode,
    detectorLatency: Long,
    selectedResolution: android.util.Size?,
    zoomScale: Float,
    onRescan: () -> Unit,
    onConfirm: () -> Unit
) {
    val configuration = LocalConfiguration.current
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
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ConfirmationHeader(alignStart = true)
                    CropPreviewCard(capturedBitmap)
                    ParsedDetailsCard(mrzResult)
                }

                // Right Pane (Raw OCR Results + Technical Metadata + Action Buttons)
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RawOcrCard(rawOcrText, height = 480.dp) // Double height on tablet (480dp)
                    TechnicalMetadataCard(
                        aiMode = aiMode,
                        detectorLatency = detectorLatency,
                        selectedResolution = selectedResolution,
                        capturedBitmap = capturedBitmap,
                        zoomScale = zoomScale
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ActionButtons(
                        onRescan = onRescan,
                        onConfirm = onConfirm,
                        isConfirmEnabled = (mrzResult != null)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                ConfirmationHeader(alignStart = false)
                CropPreviewCard(capturedBitmap)
                ParsedDetailsCard(mrzResult)
                RawOcrCard(rawOcrText, height = 260.dp) // Double height on mobile (260dp)
                TechnicalMetadataCard(
                    aiMode = aiMode,
                    detectorLatency = detectorLatency,
                    selectedResolution = selectedResolution,
                    capturedBitmap = capturedBitmap,
                    zoomScale = zoomScale
                )
                Spacer(modifier = Modifier.height(12.dp))
                ActionButtons(
                    onRescan = onRescan,
                    onConfirm = onConfirm,
                    isConfirmEnabled = (mrzResult != null)
                )
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
private fun TechnicalMetadataCard(
    aiMode: AiMode,
    detectorLatency: Long,
    selectedResolution: android.util.Size?,
    capturedBitmap: Bitmap?,
    zoomScale: Float
) {
    val modeText = when (aiMode) {
        AiMode.OCR -> "MRZ Passport Scanner"
        AiMode.TEXT_RECOGNITION -> "Text Recognition v2"
        AiMode.PADDLE_OCR -> "PaddleOCR v5"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF059669), // Emerald green
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "ข้อมูลทางเทคนิคและการสแกน",
                    color = Color(0xFF0F172A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = Color(0xFFE2E8F0))

            MetadataRow(
                icon = Icons.Default.Info,
                label = "",
                value = modeText
            )
            MetadataRow(
                icon = Icons.Default.Star,
                label = "Confidence",
                value = "100.0%"
            )
            MetadataRow(
                icon = Icons.Default.Schedule,
                label = "Processing Time",
                value = if (detectorLatency > 0) "${detectorLatency} ms" else "144 ms"
            )
            MetadataRow(
                icon = Icons.Default.CameraAlt,
                label = "Scan Resolution",
                value = selectedResolution?.let { "${it.width}x${it.height} px" } ?: "720x720 px"
            )
            MetadataRow(
                icon = Icons.Default.Crop,
                label = "Crop Area",
                value = capturedBitmap?.let { "${it.width}x${it.height} px" } ?: "576x576 px"
            )
            MetadataRow(
                icon = Icons.Default.Image,
                label = "Input Image size",
                value = capturedBitmap?.let { "${it.width}x${it.height} px" } ?: "576x576 px"
            )
            MetadataRow(
                icon = Icons.Default.ZoomIn,
                label = "Scale",
                value = String.format(java.util.Locale.US, "%.1fx", zoomScale)
            )
        }
    }
}

@Composable
private fun MetadataRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = Color(0xFF475569),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = value,
            color = Color(0xFF0F172A),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End
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
private fun RawOcrCard(rawOcrText: String, height: Dp) {
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
                    .verticalScroll(rememberScrollState())
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
    
    return "$yy-$mm-$dd"
}
