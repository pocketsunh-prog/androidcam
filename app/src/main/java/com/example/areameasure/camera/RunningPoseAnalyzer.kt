package com.example.areameasure.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.areameasure.processing.PoseDetector
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX [ImageAnalysis.Analyzer] for RUNNING mode. Converts each camera frame
 * straight into an ML Kit [InputImage] (no OpenCV round-trip) and reports the
 * running-posture verdict via callback.
 */
class RunningPoseAnalyzer(
    private val poseDetector: PoseDetector,
    private val onResult: (PoseDetector.Result) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val mediaImage = image.image ?: return
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                image.imageInfo.rotationDegrees
            )
            onResult(poseDetector.detect(inputImage))
        } finally {
            image.close()
        }
    }
}
