package com.tananaev.passportreader.features.ocr_mlkit.models

import android.graphics.RectF

data class TextRecognitionResult(
    val text: String,
    val confidence: Float,
    val boundingBox: RectF,
    val language: String,
    val linesCount: Int
)
