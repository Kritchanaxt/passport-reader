package com.tananaev.passportreader.view.components.camera
 
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.tananaev.passportreader.core.AiMode
import com.tananaev.passportreader.core.AIDetectedItem
 
@Composable
fun OverlayRenderer(
    aiMode: AiMode,
    stableTime: Long,
    isCapturing: Boolean,
    useCropMode: Boolean,
    bitmapWidth: Float,
    bitmapHeight: Float,
    latestDetections: List<AIDetectedItem>,
    selectedResolution: Size?
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val frameColor =
            if (stableTime >= 250L || isCapturing) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.WHITE
        val strokeW = 8f
        val cw = size.width
        val ch = size.height
 
        val frameW = {
            val maxW = cw * 0.9f
            val idealH = ch * 0.6f
            if (idealH * 1.58f > maxW) maxW else idealH * 1.58f
        }()
        val frameH = frameW / 1.58f
 
        val left = (cw - frameW) / 2
        val top = (ch - frameH) / 2
        val right = left + frameW
        val bottom = top + frameH
 
        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            color = frameColor
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 36f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
 
        val rect = android.graphics.RectF(left, top, right, bottom)
        drawContext.canvas.nativeCanvas.drawRoundRect(rect, 40f, 40f, paint)
 
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, top - 60f)
        val topText =
            if (stableTime >= 250L || isCapturing) "พบบัตรแล้ว กำลังทำการบันทึกภาพ..." else "กรุณาวางหน้าหนังสือเดินทางในกรอบเพื่อสแกนอัตโนมัติ"
        val topTextWidth = textPaint.measureText(topText)
        drawContext.canvas.nativeCanvas.drawText(topText, -topTextWidth / 2f, 0f, textPaint)
        drawContext.canvas.nativeCanvas.restore()
 
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.translate(left + frameW / 2f, bottom + 80f)
        val bottomText = "สแกนหน้าหนังสือเดินทาง (MRZ)"
        val bottomTextWidth = textPaint.measureText(bottomText)
        drawContext.canvas.nativeCanvas.drawText(bottomText, -bottomTextWidth / 2f, 0f, textPaint)
        drawContext.canvas.nativeCanvas.restore()
 
        val cropW = if (useCropMode) frameW else size.width
        val cropH = if (useCropMode) frameH else size.height
 
        val leftOffset = if (useCropMode) (size.width - cropW) / 2f else 0f
        val topOffset = if (useCropMode) (size.height - cropH) / 2f else 0f
 
        val boxScaleX = if (bitmapWidth > 0) cropW / bitmapWidth else 1f
        val boxScaleY = if (bitmapHeight > 0) cropH / bitmapHeight else 1f
 
        val ocrPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            alpha = 200
            isAntiAlias = true
        }
 
        latestDetections.forEach { item ->
            val r = item.boundingBox
            val mappedRect = android.graphics.RectF(
                leftOffset + r.left * boxScaleX, topOffset + r.top * boxScaleY,
                leftOffset + r.right * boxScaleX, topOffset + r.bottom * boxScaleY
            )
            drawContext.canvas.nativeCanvas.drawRect(mappedRect, ocrPaint)
            
            val text = item.label.trim()
            if (text.isNotEmpty()) {
                val ocrTextPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }
                val ocrBgPaint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    alpha = 80
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
 
                val displayText = if (text.length > 30) text.take(27) + "..." else text
                val padding = 8f
                val textWidth = ocrTextPaint.measureText(displayText)
                val fontMetrics = ocrTextPaint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent
 
                val bgRect = RectF(
                    mappedRect.left,
                    mappedRect.top - textHeight - (padding * 2),
                    mappedRect.left + textWidth + (padding * 2),
                    mappedRect.top
                )
 
                if (bgRect.top < 0) {
                    bgRect.offsetTo(bgRect.left, mappedRect.bottom)
                }
 
                drawContext.canvas.nativeCanvas.drawRoundRect(bgRect, 4f, 4f, ocrBgPaint)
                drawContext.canvas.nativeCanvas.drawText(
                    displayText,
                    bgRect.left + padding,
                    bgRect.bottom - padding - fontMetrics.descent,
                    ocrTextPaint
                )
            }
        }
    }
}
