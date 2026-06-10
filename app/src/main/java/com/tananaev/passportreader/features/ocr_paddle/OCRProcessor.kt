package com.tananaev.passportreader.features.ocr_paddle

import com.tananaev.passportreader.core.contracts.AIProcessor
import com.tananaev.passportreader.core.contracts.AIConfig
import com.tananaev.passportreader.core.contracts.AIResult
import com.tananaev.passportreader.core.contracts.AIDetectedItem
import com.tananaev.passportreader.core.contracts.RawJsonCapable
import com.tananaev.passportreader.core.contracts.OcrEngineCapable
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

class OCRProcessor : AIProcessor, RawJsonCapable, OcrEngineCapable {
    private var appContext: android.content.Context? = null
    private var appThreads: Int = 4
    private var appGpu: Boolean = false
    var paddleOCR: PaddleOCR? = null // Made public for benchmarking
        private set
    override val name: String = "PaddleOCRv5"

    override fun init(context: android.content.Context, config: AIConfig): Boolean {
        appContext = context.applicationContext
        appThreads = config.threads
        appGpu = config.useGpu // Respect compute mode config!
        
        try {
            if (paddleOCR == null) {
                paddleOCR = PaddleOCR()
            }
            var isInit = paddleOCR?.initModel(appContext!!, appThreads, appGpu) ?: false
            if (!isInit && appGpu) {
                Log.w("OCR", "Failed to initialize PaddleOCR with GPU, falling back to CPU...")
                appGpu = false
                isInit = paddleOCR?.initModel(appContext!!, appThreads, false) ?: false
            }
            if (!isInit) {
                paddleOCR?.release()
                paddleOCR = null
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e("OCR", "Init error", e)
            paddleOCR?.release()
            paddleOCR = null
            return false
        }
    }

    override fun getRawJson(bitmap: Bitmap): String? {
        return paddleOCR?.detect(bitmap)
    }

    override fun canRunInference(context: android.content.Context): Pair<Boolean, String?> {
        return paddleOCR?.canRunInference(context) ?: Pair(false, "PaddleOCR not initialized")
    }

    override fun detectRaw(bitmap: Bitmap): String? {
        return paddleOCR?.detect(bitmap)
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        if (appContext == null || paddleOCR == null) return AIResult(false, emptyList(), 0, "OCR Not initialized")
        
        val start = System.currentTimeMillis()
        val results = mutableListOf<AIDetectedItem>()
        
        try {
            val jsonResult = paddleOCR?.detect(bitmap)
            if (!jsonResult.isNullOrBlank() && jsonResult != "[]") {
                val array = JSONArray(jsonResult)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val label = obj.optString("label", "")
                    val prob = obj.optDouble("prob", 0.0).toFloat()
                    
                    // JSON coordinates (x0, y0, ..., x3, y3)
                    val x0 = obj.optDouble("x0", 0.0).toFloat()
                    val y0 = obj.optDouble("y0", 0.0).toFloat()
                    val x1 = obj.optDouble("x1", 0.0).toFloat()
                    val y1 = obj.optDouble("y1", 0.0).toFloat()
                    val x2 = obj.optDouble("x2", 0.0).toFloat()
                    val y2 = obj.optDouble("y2", 0.0).toFloat()
                    val x3 = obj.optDouble("x3", 0.0).toFloat()
                    val y3 = obj.optDouble("y3", 0.0).toFloat()

                    // Bounding Box
                    val minX = min(min(x0, x1), min(x2, x3))
                    val maxX = max(max(x0, x1), max(x2, x3))
                    val minY = min(min(y0, y1), min(y2, y3))
                    val maxY = max(max(y0, y1), max(y2, y3))

                    if (label.isNotBlank()) {
                        results.add(
                            AIDetectedItem(
                                label = label,
                                confidence = prob,
                                boundingBox = RectF(minX, minY, maxX, maxY),
                                extra = mapOf("raw_json" to obj.toString())
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OCR", "Process error", e)
        }
        
        val end = System.currentTimeMillis()
        
        return AIResult(
            success = true,
            items = results, 
            processTimeMs = end - start
        )
    }

    override fun release() {
        paddleOCR?.release()
        paddleOCR = null
        appContext = null
    }
}
