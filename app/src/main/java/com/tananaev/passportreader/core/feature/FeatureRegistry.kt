package com.tananaev.passportreader.core.feature

import android.content.Context
import android.util.Log

/**
 * Central registry that manages all available features.
 *
 * This is the "plugin system" — features register themselves here,
 * and the UI/logic layers query the registry to discover what's available.
 *
 * Usage:
 *   // At app startup (RelayApplication.onCreate or lazy init)
 *   FeatureRegistry.register(FaceDetectionFeature())
 *   FeatureRegistry.register(AutoFramingFeature())
 *
 *   // In UI layer
 *   val aiFeatures = FeatureRegistry.getByCategory(FeatureCategory.AI_DETECTION)
 *   val supported = FeatureRegistry.getSupportedFeatures(context)
 */
object FeatureRegistry {
    private const val TAG = "FeatureRegistry"

    /** All registered features indexed by id */
    private val features = LinkedHashMap<String, Feature>()

    /** Currently enabled feature ids (for toggle-style features) */
    private val enabledFeatures = mutableSetOf<String>()

    // ─── Registration ───

    /**
     * Register a feature. If a feature with the same id already exists,
     * it will be replaced (useful for swapping implementations).
     */
    fun register(feature: Feature) {
        features[feature.id] = feature
        Log.d(TAG, "Registered feature: ${feature.id} (${feature.category})")
    }

    /** Register multiple features at once */
    fun registerAll(vararg featureList: Feature) {
        featureList.forEach { register(it) }
    }

    /** Unregister a feature by id */
    fun unregister(id: String) {
        features.remove(id)
        enabledFeatures.remove(id)
        Log.d(TAG, "Unregistered feature: $id")
    }

    // ─── Queries ───

    /** Get all registered features */
    fun getAll(): List<Feature> = features.values.toList()

    /** Get a specific feature by id */
    fun get(id: String): Feature? = features[id]

    /** Get a specific feature by AiMode */
    fun getByAiMode(mode: com.tananaev.passportreader.core.AiMode): Feature? =
        features.values.firstOrNull { it.aiMode == mode }

    /** Get all features in a specific category */
    fun getByCategory(category: FeatureCategory): List<Feature> =
        features.values.filter { it.category == category }

    /** Get all features that are supported on this device */
    fun getSupportedFeatures(context: Context): List<Feature> =
        features.values.filter { it.isSupported(context) }

    /** Get supported features filtered by category */
    fun getSupportedByCategory(context: Context, category: FeatureCategory): List<Feature> =
        getByCategory(category).filter { it.isSupported(context) }

    // ─── Enable / Disable ───

    /** Enable a feature. Returns false if unsupported or not registered. */
    fun enable(context: Context, featureId: String, config: FeatureConfig = FeatureConfig()): Boolean {
        val feature = features[featureId] ?: run {
            Log.w(TAG, "Feature not found: $featureId")
            return false
        }
        if (!feature.isSupported(context)) {
            Log.w(TAG, "Feature not supported: $featureId")
            return false
        }
        try {
            feature.onEnable(context, config)
            enabledFeatures.add(featureId)
            Log.d(TAG, "Enabled feature: $featureId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable feature: $featureId", e)
            return false
        }
    }

    /** Disable a feature */
    fun disable(context: Context, featureId: String) {
        val feature = features[featureId] ?: return
        try {
            feature.onDisable(context)
            enabledFeatures.remove(featureId)
            Log.d(TAG, "Disabled feature: $featureId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable feature: $featureId", e)
        }
    }

    /** Check if a feature is currently enabled */
    fun isEnabled(featureId: String): Boolean = featureId in enabledFeatures

    /** Get all currently enabled features */
    fun getEnabledFeatures(): List<Feature> =
        enabledFeatures.mapNotNull { features[it] }

    // ─── Utilities ───

    /** Clear all registrations (for testing or app teardown) */
    fun clear() {
        features.clear()
        enabledFeatures.clear()
    }

    /** Debug: print all registered features */
    fun dump() {
        Log.d(TAG, "=== Feature Registry Dump ===")
        FeatureCategory.entries.forEach { cat ->
            val catFeatures = getByCategory(cat)
            if (catFeatures.isNotEmpty()) {
                Log.d(TAG, "[$cat] ${catFeatures.joinToString { "${it.id}(${if (isEnabled(it.id)) "ON" else "OFF"})" }}")
            }
        }
        Log.d(TAG, "=============================")
    }
}
