package com.tananaev.passportreader.core.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.tananaev.passportreader.utils.logging.AppLog as Log
import android.util.Size

object CameraMetadataHelper {
    private const val TAG = "CameraMetadataHelper"

    fun getAvailableCameras(cameraManager: CameraManager): List<String> {
        return try {
            cameraManager.cameraIdList.toList()
        } catch(e: Exception) { emptyList() }
    }

    @SuppressLint("InlinedApi")
    fun enumerateCameras(cameraManager: CameraManager): List<CameraInfo> {
        Log.d(TAG, "Starting camera enumeration...")
        val detectedCamerasMap = mutableMapOf<String, CameraInfo>()
        val allCameraIds = try {
            cameraManager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID list", e)
            emptyArray<String>()
        }
        val numberOfActualCameras = allCameraIds.size

        Log.i(TAG, "Total camera IDs: $numberOfActualCameras. IDs: ${allCameraIds.joinToString()}")

        // Check if there are multiple front cameras on this device
        val frontCameraIds = allCameraIds.filter { id ->
            try {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } catch (e: Exception) {
                false
            }
        }
        val hasMultipleFrontCameras = frontCameraIds.size > 1
        Log.d(TAG, "Device has multiple front cameras: $hasMultipleFrontCameras (Total front cams: ${frontCameraIds.size})")

        val ultraWideFocalLengthThreshold = 3.0f
        val telephotoFocalLengthThreshold = 7.0f

        allCameraIds.forEach { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = characteristics.get(CameraCharacteristics.LENS_FACING)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

                val isLogicalMultiCamera = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                } else false

                val physicalCameraIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isLogicalMultiCamera) {
                     characteristics.physicalCameraIds.toList()
                } else emptyList()

                Log.i(TAG, "--- Processing Camera ID: $id ---")
                Log.i(TAG, "  Orientation: $orientation")
                Log.i(TAG, "  Is Logical Multi-Camera: $isLogicalMultiCamera")

                var determinedType: String? = null
                when (orientation) {
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        determinedType = if (hasMultipleFrontCameras && (id == "1" || (focalLengths != null && focalLengths.any { it < ultraWideFocalLengthThreshold }))) {
                            "Front Ultra Wide Camera"
                        } else {
                            "Front Camera"
                        }
                    }
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        if (isLogicalMultiCamera && physicalCameraIds.isNotEmpty()) {
                            determinedType = when (physicalCameraIds.size) {
                                3 -> "Back Triple Camera"
                                2 -> "Back Dual Camera"
                                else -> "Back Multi-Camera (${physicalCameraIds.size} Lenses)"
                            }
                        } else {
                            determinedType = when {
                                focalLengths?.any { it > telephotoFocalLengthThreshold } == true -> "Back Telephoto Camera"
                                focalLengths?.any { it < ultraWideFocalLengthThreshold } == true -> "Back Ultra Wide Camera"
                                else -> "Back Camera"
                            }
                        }
                    }
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> determinedType = "External Camera"
                }

                if (determinedType == null) {
                    determinedType = "Camera $id"
                }

                detectedCamerasMap[id] = CameraInfo(
                    title = "$determinedType (ID: $id)",
                    cameraId = id,
                    cameraType = determinedType!!,
                    isAvailable = true,
                    physicalCameraIds = physicalCameraIds
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing camera ID $id: ${e.message}", e)
            }
        }

        return detectedCamerasMap.values.sortedWith(compareBy {
            when (it.cameraType) {
                "Front Camera" -> 0
                "Front Ultra Wide Camera" -> 1
                "Back Camera" -> 2
                "Back Triple Camera" -> 3
                "Back Dual Camera" -> 4
                "Back Dual Wide Camera" -> 5
                "Back Ultra Wide Camera" -> 6
                "Back Telephoto Camera" -> 7
                else -> if (it.cameraType.startsWith("Back Multi-Camera")) 8 else 99
            }
        })
    }

    fun getCameraResolutions(cameraManager: CameraManager, cameraId: String): List<Size> {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
            val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()

            (jpegSizes + previewSizes)
                .distinctBy { "${it.width}x${it.height}" }
                .filter { Math.min(it.width, it.height) >= 720 }
        } catch(e: Exception) { emptyList() }
    }
}
