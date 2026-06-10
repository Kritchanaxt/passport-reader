package com.tananaev.passportreader.core.contracts

import android.graphics.Bitmap

/**
 * Capability interface for processors that can return raw JSON results.
 * Used by OCR processors (PaddleOCR, Tesseract) for direct JSON output
 * without going through the standardized AIResult pipeline.
 *
 * This allows the UI layer to check `proc is RawJsonCapable` instead of
 * casting to concrete processor types like OCRProcessor or TesseractOCRProcessor.
 */
interface RawJsonCapable {
    fun getRawJson(bitmap: Bitmap): String?
}
