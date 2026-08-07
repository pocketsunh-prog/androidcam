package com.example.areameasure.camera

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.areameasure.domain.MeasureMode
import com.example.areameasure.processing.ImageProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the CameraX lifecycle for the unified measure screen.
 *
 * Supports both [MeasureMode.SIZE] (preview + analysis + image capture) and
 * [MeasureMode.SPEED] (preview + analysis only). [ImageCapture] is bound only in
 * SIZE mode — many devices only support 2 concurrent use cases, so binding it
 * during SPEED tracking avoids `takePicture()` crashes.
 *
 * Set [measureMode] before calling [startCamera]; the axis overlay is drawn only
 * in SIZE mode via the shared [ImageProcessor].
 */
@Singleton
class MeasureCameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessor
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    private val analyzer = CameraFrameAnalyzer(imageProcessor) { result ->
        _processingResults.value = result
    }

    private val _processingResults = MutableStateFlow<ImageProcessor.ProcessingResult?>(null)
    val processingResults: StateFlow<ImageProcessor.ProcessingResult?> = _processingResults.asStateFlow()

    /**
     * Single-thread executor for frame analysis (off the main thread).
     *
     * Kept as a (lazily recreated) `var` rather than a one-shot `val` because
     * [shutdown] terminates the executor. Restarting the camera after a stop
     * would otherwise submit the analyzer to a dead executor and freeze.
     */
    private var analysisExecutor = Executors.newSingleThreadExecutor()

    var measureMode: MeasureMode = MeasureMode.SIZE

    /**
     * Clockwise rotation (a multiple of 90°) to apply to the analysis frame so
     * the contour overlay matches the upright, crop-filled preview. Computed from
     * the back camera's sensor orientation vs. the current display rotation.
     */
    var overlayRotationDegrees: Int = 0

    fun setTargetContourIndex(index: Int) {
        analyzer.setTargetContourIndex(index)
    }

    fun getCamera(): Camera? = camera

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ): Result<Camera> {
        return try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider

            // Recreate the executor if a prior shutdown terminated it, otherwise
            // setAnalyzer() silently no-ops against a dead executor and the
            // preview freezes with no frames.
            if (analysisExecutor.isShutdown) {
                analysisExecutor = Executors.newSingleThreadExecutor()
            }

            // Draw the 3D X/Y/Z axes only while measuring size.
            imageProcessor.drawAxes = measureMode == MeasureMode.SIZE

            // Align the preview to the display rotation so it isn't shown
            // sideways in a portrait UI (the camera sensor is landscape).
            val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation

            preview = Preview.Builder()
                .setTargetRotation(displayRotation)
                .build()
                .also { it.setSurfaceProvider(surfaceProvider) }

            // Rotation the raw analysis frame needs so the contour overlay lines
            // up with the upright preview. sensorOrientation comes from the back
            // camera; the delta to the display rotation is what the preview applies.
            val sensorOrientation = backCameraSensorOrientation()
            overlayRotationDegrees = (sensorOrientation - rotationDegrees(displayRotation) + 360) % 360

            // Analysis stays unrotated — detection coordinates must match the
            // raw sensor frame, not the rotated preview.

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            // Image capture is only needed to photograph the measured object.
            if (measureMode == MeasureMode.SIZE) {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            } else {
                imageCapture = null
            }

            provider.unbindAll()

            val useCases = mutableListOf(preview, imageAnalysis).apply {
                imageCapture?.let { add(it) }
            }

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases.toTypedArray()
            )

            Result.success(camera!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun captureImage(
        onSaved: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not initialized"))
            return
        }

        // Unbind preview and image analysis before capturing. Many devices only
        // support 2 concurrent camera use cases; running Preview + ImageAnalysis
        // + ImageCapture simultaneously causes a crash on takePicture().
        preview?.let { cameraProvider?.unbind(it) }
        imageAnalysis?.let { cameraProvider?.unbind(it) }

        val file = File(context.filesDir, "measure_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(file.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    fun shutdown() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    /** Sensor orientation (clockwise degrees) of the back camera, or 0 if unknown. */
    private fun backCameraSensorOrientation(): Int {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val backId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return 0
            cameraManager.getCameraCharacteristics(backId)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
