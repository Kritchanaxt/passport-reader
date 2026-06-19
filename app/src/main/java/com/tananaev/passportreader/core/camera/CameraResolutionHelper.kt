package com.tananaev.passportreader.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import com.tananaev.passportreader.features.monitor_logging.AppLog as Log
import android.util.Size
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.utils.ResolutionItem
import com.tananaev.passportreader.utils.predefinedResolutionsByRatio
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CameraResolutionHelper {
    private const val TAG = "CameraResolutionHelper"

    fun isCroppingNeeded(context: Context, aspectRatio: UiAspectRatio): Boolean {
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return !isLandscape && (aspectRatio == UiAspectRatio.RATIO_16_9 || aspectRatio == UiAspectRatio.RATIO_4_3)
    }

    fun getActiveAspectRatio(context: Context, aspectRatio: UiAspectRatio): UiAspectRatio {
        return if (isCroppingNeeded(context, aspectRatio)) {
            when (aspectRatio) {
                UiAspectRatio.RATIO_16_9 -> UiAspectRatio.RATIO_9_16
                UiAspectRatio.RATIO_4_3 -> UiAspectRatio.RATIO_3_4
                else -> aspectRatio
            }
        } else {
            aspectRatio
        }
    }

    fun getActiveResolution(context: Context, aspectRatio: UiAspectRatio, selectedRes: Size?): Size? {
        if (selectedRes == null) return null
        return if (isCroppingNeeded(context, aspectRatio)) {
            Size(min(selectedRes.width, selectedRes.height), max(selectedRes.width, selectedRes.height))
        } else {
            selectedRes
        }
    }

    fun getResolutionsForAspectRatio(
        characteristics: CameraCharacteristics?,
        isFrontCamera: Boolean,
        maxSensorProcessingSize: Size,
        aspectRatio: UiAspectRatio
    ): List<ResolutionItem> {
        if (characteristics == null) return emptyList()

        val resolutionItems = mutableListOf<ResolutionItem>()
        val sourceW = maxSensorProcessingSize.width
        val sourceH = maxSensorProcessingSize.height
        val targetAR = aspectRatio.value ?: (sourceW.toFloat() / sourceH.toFloat())

        var maxFinalW: Int
        var maxFinalH: Int

        if (aspectRatio.isPortraitDefault) {
            val canvasW = min(sourceW, sourceH)
            val canvasH = max(sourceW, sourceH)
            val canvasAR = canvasW.toFloat() / canvasH.toFloat()

            if (canvasAR > targetAR) {
                maxFinalH = canvasH
                maxFinalW = (canvasH * targetAR).roundToInt()
            } else {
                maxFinalW = canvasW
                maxFinalH = (canvasW / targetAR).roundToInt()
            }
        } else {
            maxFinalW = min(sourceW, sourceH)
            maxFinalH = (maxFinalW / targetAR).roundToInt()
        }

        if (maxFinalW >= 720 && maxFinalH >= 720) {
            val maxForArText = "Max for AR (${maxFinalW}x${maxFinalH})"
            resolutionItems.add(ResolutionItem(null, maxForArText))
        }

        val resolutionStrings = predefinedResolutionsByRatio[aspectRatio] ?: emptyList()

        resolutionStrings.forEach { resString ->
            try {
                val parts = resString.split("x")
                if (parts.size == 2) {
                    val width = parts[0].toInt()
                    val height = parts[1].toInt()
                    val candidateSize = Size(width, height)

                    if (candidateSize.width < 720 || candidateSize.height < 720) return@forEach

                    val isSupported = candidateSize.width <= maxFinalW && candidateSize.height <= maxFinalH
                    val isWithinFrontCamLimit = if (isFrontCamera) {
                        (candidateSize.width * candidateSize.height) <= (2160L * 2160L)
                    } else {
                        true
                    }

                    if (isSupported && isWithinFrontCamLimit) {
                        resolutionItems.add(ResolutionItem(candidateSize, resString))
                    }
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Could not parse resolution string: $resString", e)
            }
        }

        return resolutionItems.distinctBy { it.displayText }
    }

    fun getOptimalPreviewSize(sizes: Array<Size>, targetSize: Size): Size {
        val targetW = targetSize.width
        val targetH = targetSize.height
        val targetRatio = max(targetW, targetH).toDouble() / min(targetW, targetH).toDouble()

        val tolerance = 0.05
        val matchedAspect = sizes.filter {
            val ratio = max(it.width, it.height).toDouble() / min(it.width, it.height).toDouble()
            Math.abs(ratio - targetRatio) < tolerance
        }

        if (matchedAspect.isNotEmpty()) {
            return matchedAspect.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width * it.height }
                ?: matchedAspect.maxByOrNull { it.width * it.height }!!
        }

        return sizes.minByOrNull {
            val ratio = max(it.width, it.height).toDouble() / min(it.width, it.height).toDouble()
            Math.abs(ratio - targetRatio)
        } ?: sizes[0]
    }
}
