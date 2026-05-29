package com.tananaev.passportreader.view.components.camera
 
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tananaev.passportreader.utils.UiAspectRatio
import com.tananaev.passportreader.core.camera.Camera2Controller
 
@Composable
fun CameraPreviewView(
    selectedAspectRatio: UiAspectRatio,
    selectedCameraId: String,
    selectedResolution: android.util.Size?,
    cameraController: Camera2Controller?
) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx)
        },
        modifier = Modifier.fillMaxSize(),
        update = { tv ->
            if (cameraController != null) {
                try {
                    cameraController.aspectRatio = selectedAspectRatio
                    cameraController.openCamera(tv, selectedCameraId, selectedResolution)
                } catch (e: Exception) {
                    Log.e("CameraPreviewView", "Error opening camera in update", e)
                }
            }
        }
    )
}
