package com.tananaev.passportreader.features.ocr_mlkit

import com.tananaev.passportreader.core.contracts.AIProcessor
import com.tananaev.passportreader.core.contracts.AIConfig
import com.tananaev.passportreader.core.contracts.AIResult
import com.tananaev.passportreader.core.contracts.AIDetectedItem
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.tananaev.passportreader.features.ocr_mlkit.constants.TextRecognitionConfig
import com.tananaev.passportreader.features.ocr_mlkit.models.TextRecognitionResult

class TextRecognitionProcessor : AIProcessor {
    override val name: String = "ML Kit Text Recognition"
    private var recognizer: TextRecognizer? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            // Use default options for Latin script (standard text recognition)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val currentRecognizer = recognizer ?: return AIResult(false, emptyList(), 0, "Not initialized")
        val startTime = System.currentTimeMillis()

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = currentRecognizer.process(image)
            
            // Wait for task completion (blocking call for AIProcessor interface)
            val visionText = Tasks.await(task)
            val duration = System.currentTimeMillis() - startTime

            val items = mutableListOf<AIDetectedItem>()
            
            for (block in visionText.textBlocks) {
                val res = TextRecognitionResult(
                    text = block.text,
                    confidence = TextRecognitionConfig.DEFAULT_CONFIDENCE,
                    boundingBox = RectF(block.boundingBox),
                    language = block.recognizedLanguage ?: "unknown",
                    linesCount = block.lines.size
                )
                items.add(
                    AIDetectedItem(
                        label = res.text,
                        confidence = res.confidence,
                        boundingBox = res.boundingBox,
                        extra = mapOf(
                            "type" to TextRecognitionConfig.EXTRA_TYPE,
                            "language" to res.language,
                            "lines_count" to res.linesCount
                        )
                    )
                )
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            e.printStackTrace()
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        recognizer?.close()
        recognizer = null
    }
}
