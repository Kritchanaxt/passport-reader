package com.tananaev.passportreader.core.feature

import com.tananaev.passportreader.features.ocr_paddle.PaddleOCRFeature
import com.tananaev.passportreader.features.ocr_mlkit.TextRecognitionFeature

/**
 * Convenience function to register all built-in features.
 */
object FeatureInitializer {

    fun registerAll() {
        FeatureRegistry.registerAll(
            PaddleOCRFeature(),
            TextRecognitionFeature()
        )

        FeatureRegistry.dump()
    }
}

