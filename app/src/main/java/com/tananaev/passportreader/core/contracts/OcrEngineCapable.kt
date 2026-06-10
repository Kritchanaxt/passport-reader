package com.tananaev.passportreader.core.contracts

import android.content.Context
import android.graphics.Bitmap

/**
 * Capability interface for processors that expose their underlying OCR engine.
 * Used primarily for benchmarking scenarios where direct engine access is needed.
 *
 * This allows the UI layer to check `proc is OcrEngineCapable` instead of
 * casting to OCRProcessor and accessing its `paddleOCR` field directly.
 */
interface OcrEngineCapable {
    /**
     * Check if the engine can run inference (e.g., model files are present).
     * @return Pair of (canRun, errorMessage). errorMessage is null if canRun is true.
     */
    fun canRunInference(context: Context): Pair<Boolean, String?>

    /**
     * Run raw detection and return the result as a JSON string.
     * This bypasses the AIResult conversion for benchmark/direct-use scenarios.
     */
    fun detectRaw(bitmap: Bitmap): String?
}
