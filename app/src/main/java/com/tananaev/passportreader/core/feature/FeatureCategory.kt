package com.tananaev.passportreader.core.feature

/**
 * Category classification for features.
 * Used by FeatureRegistry to group and filter features.
 */
enum class FeatureCategory(val displayName: String) {
    AI_DETECTION("AI Detection"),
    AI_SEGMENTATION("AI Segmentation"),
    AI_OCR("AI OCR"),
    AI_VERIFICATION("AI Verification"),
    CAMERA_CONTROL("Camera Control"),
    STREAMING("Streaming"),
    SYSTEM("System")
}
