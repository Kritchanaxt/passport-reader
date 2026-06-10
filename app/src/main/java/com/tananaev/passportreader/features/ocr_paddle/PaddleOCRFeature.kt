package com.tananaev.passportreader.features.ocr_paddle

import com.tananaev.passportreader.core.AIManager
import com.tananaev.passportreader.core.AiMode
import android.content.Context
import com.tananaev.passportreader.core.feature.Feature
import com.tananaev.passportreader.core.feature.FeatureCategory
import com.tananaev.passportreader.core.feature.FeatureConfig

class PaddleOCRFeature : Feature {
    override val aiMode = AiMode.PADDLE_OCR
    override val id = "paddle_ocr"
    override val displayName = "PaddleOCR (Thai+English)"
    override val category = FeatureCategory.AI_OCR

    override fun onEnable(context: Context, config: FeatureConfig) {
        AIManager.switchProcessor(OCRProcessor(), context, config)
    }

    override fun onDisable(context: Context) {
        AIManager.release()
    }
}
