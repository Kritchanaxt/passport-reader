package com.tananaev.passportreader.features.ocr_mlkit

import com.tananaev.passportreader.core.AIManager
import com.tananaev.passportreader.core.AiMode
import android.content.Context
import com.tananaev.passportreader.core.feature.Feature
import com.tananaev.passportreader.core.feature.FeatureCategory
import com.tananaev.passportreader.core.feature.FeatureConfig

class TextRecognitionFeature : Feature {
    override val aiMode = AiMode.TEXT_RECOGNITION
    override val id = "text_recognition"
    override val displayName = "ML Kit Text Recognition"
    override val category = FeatureCategory.AI_OCR

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(TextRecognitionProcessor(), context, config)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
