package com.tananaev.passportreader.core.contracts

import android.graphics.RectF

/**
 * Data classes for AI processing results.
 * These are the standardized output shapes that all AIProcessor implementations return.
 */

data class AIConfig(
    val useGpu: Boolean = true,
    val threads: Int = 4,
    val options: Map<String, Any> = emptyMap()
)

data class AIResult(
    val success: Boolean,
    val items: List<AIDetectedItem>,
    val processTimeMs: Long,
    val errorMessage: String? = null
)

data class AIDetectedItem(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val extra: Map<String, Any> = emptyMap()
)
