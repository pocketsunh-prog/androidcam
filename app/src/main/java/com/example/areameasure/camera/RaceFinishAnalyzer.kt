package com.example.areameasure.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.areameasure.processing.PoseDetector
import com.google.mlkit.vision.common.InputImage

/**
 * CameraX analyzer for RACE mode. Feeds each frame to ML Kit pose and reports
 * the runner's torso centre x (normalised 0..1) so the ViewModel can detect
 * when it crosses the finish line.
 */
class RaceFinishAnalyzer(
    private val poseDetector: PoseDetector,
    private val onResult: (PoseDetector.TorsoResult) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val mediaImage = image.image ?: return
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                image.imageInfo.rotationDegrees
            )
            onResult(poseDetector.detectTorsoCenterX(inputImage))
        } finally {
            image.close()
        }
    }
}
