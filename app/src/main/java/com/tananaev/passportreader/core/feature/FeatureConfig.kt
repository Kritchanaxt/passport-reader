package com.tananaev.passportreader.core.feature

/**
 * Configuration passed to a feature during enable/init.
 * Replaces ad-hoc Map<String, Any> options with a structured config.
 */
data class FeatureConfig(
    val useGpu: Boolean = true,
    val threads: Int = 4,
    val options: Map<String, Any> = emptyMap()
)
