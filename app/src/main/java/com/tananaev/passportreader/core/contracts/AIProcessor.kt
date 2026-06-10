package com.tananaev.passportreader.core.contracts

import android.graphics.Bitmap

/**
 * Interface for AI Processors to allow switching and lifecycle management.
 *
 * This is the core contract that all AI features must implement.
 * Features in features/ package implement this interface and register
 * themselves via FeatureRegistry.
 */
interface AIProcessor {
    val name: String
    fun init(context: android.content.Context, config: AIConfig): Boolean
    fun process(bitmap: Bitmap, options: Map<String, Any> = emptyMap()): AIResult
    fun release()
}
