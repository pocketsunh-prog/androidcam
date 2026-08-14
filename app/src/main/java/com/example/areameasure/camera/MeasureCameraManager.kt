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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.areameasure.domain.MeasureMode
import com.example.areameasure.processing.ImageProcessor
import com.example.areameasure.processing.PoseDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import org.opencv.core.Rect
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
 * SIZE/SPEED/PEOPLE use the OpenCV [CameraFrameAnalyzer]. RUNNING uses
 * [RunningPoseAnalyzer] (ML Kit pose) plus photo and video capture.
 */
@Singleton
class MeasureCameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessor
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private var activeRecording: Recording? = null
    private var pendingVideoCallback: ((String) -> Unit)? = null

    private val analyzer = CameraFrameAnalyzer(imageProcessor) { result ->
        _processingResults.value = result
    }

    private val poseDetector = PoseDetector()
    private val poseAnalyzer = RunningPoseAnalyzer(poseDetector) { result ->
        _runningPoseResults.value = result
    }

    private val fanAnalyzer = FanSpeedAnalyzer { result ->
        _fanResults.value = result
    }

    private val raceAnalyzer = RaceFinishAnalyzer(poseDetector) { result ->
        _raceResults.value = result
    }

    private val _processingResults = MutableStateFlow<ImageProcessor.ProcessingResult?>(null)
    val processingResults: StateFlow<ImageProcessor.ProcessingResult?> = _processingResults.asStateFlow()

    private val _runningPoseResults = MutableStateFlow<PoseDetector.Result?>(null)
    val runningPoseResults: StateFlow<PoseDetector.Result?> = _runningPoseResults.asStateFlow()

    private val _fanResults = MutableStateFlow<FanSpeedAnalyzer.FanSpeedResult?>(null)
    val fanResults: StateFlow<FanSpeedAnalyzer.FanSpeedResult?> = _fanResults.asStateFlow()

    private val _raceResults = MutableStateFlow<PoseDetector.TorsoResult?>(null)
    val raceResults: StateFlow<PoseDetector.TorsoResult?> = _raceResults.asStateFlow()

    private var analysisExecutor = Executors.newSingleThreadExecutor()

    var measureMode: MeasureMode = MeasureMode.SIZE

    /**
     * Clockwise rotation (a multiple of 90°) to apply to the analysis frame so
     * the contour overlay matches the upright, crop-filled preview.
     */
    var overlayRotationDegrees: Int = 0

    fun setTargetContourIndex(index: Int) {
        analyzer.setTargetContourIndex(index)
    }

    /** Restrict overlay contour marking to these indices (SPEED: moving objects only). */
    fun setMarkedContourIndices(indices: Set<Int>?) {
        analyzer.setMarkedContourIndices(indices)
    }

    /** Seed (or clear) the SPEED visual tracker from a raw-frame bounding box. */
    fun setSpeedTargetRect(rect: Rect?) {
        imageProcessor.setSpeedTargetRect(rect)
    }

    fun getCamera(): Camera? = camera

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ): Result<Camera> {
        return try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider
            this.lifecycleOwner = lifecycleOwner

            // Recreate the executor if a prior shutdown terminated it.
            if (analysisExecutor.isShutdown) {
                analysisExecutor = Executors.newSingleThreadExecutor()
            }

            val isRunning = measureMode == MeasureMode.RUNNING
            val isFan = measureMode == MeasureMode.FAN
            val isRace = measureMode == MeasureMode.RACE

            // OpenCV processing flags.
            imageProcessor.drawAxes = measureMode == MeasureMode.SIZE
            imageProcessor.detectFaces = measureMode == MeasureMode.PEOPLE
            imageProcessor.speedTrackingEnabled = measureMode == MeasureMode.SPEED
            analyzer.setMarkedContourIndices(
                if (measureMode == MeasureMode.SPEED) emptySet() else null
            )

            val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation

            preview = Preview.Builder()
                .setTargetRotation(displayRotation)
                .build()
                .also { it.setSurfaceProvider(surfaceProvider) }

            val sensorOrientation = backCameraSensorOrientation()
            overlayRotationDegrees = (sensorOrientation - rotationDegrees(displayRotation) + 360) % 360

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            if (isRunning) {
                imageAnalysis!!.setAnalyzer(analysisExecutor, poseAnalyzer)
            } else if (isFan) {
                fanAnalyzer.reset()
                imageAnalysis!!.setAnalyzer(analysisExecutor, fanAnalyzer)
            } else if (isRace) {
                imageAnalysis!!.setAnalyzer(analysisExecutor, raceAnalyzer)
            } else {
                imageAnalysis!!.setAnalyzer(analysisExecutor, analyzer)
            }

            imageCapture = if (measureMode == MeasureMode.SIZE || isRunning || isRace) {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            } else {
                null
            }

            videoCapture = if (isRunning) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                VideoCapture.withOutput(recorder)
            } else {
                null
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

    /** Capture a photo without unbinding preview/analysis (3-use-case modes). */
    fun capturePhoto(
        prefix: String,
        onSaved: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not initialized"))
            return
        }
        val file = File(context.filesDir, "${prefix}_${System.currentTimeMillis()}.jpg")
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

    /** Start recording a short running clip (rebinds analysis→video to stay ≤3 use cases). */
    fun startRunningRecording(
        onStarted: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val vc = videoCapture ?: run {
            onError(IllegalStateException("Video capture not available"))
            return
        }

        try {
            rebindForVideo(recordingActive = true)
        } catch (e: Exception) {
            onError(e)
            return
        }

        val file = File(context.filesDir, "running_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(file).build()
        pendingVideoCallback = null

        try {
            val recording = vc.output.prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        activeRecording = null
                        rebindForVideo(recordingActive = false)
                        val cb = pendingVideoCallback
                        pendingVideoCallback = null
                        cb?.invoke(file.absolutePath)
                    }
                }
            activeRecording = recording
            onStarted()
        } catch (e: Exception) {
            rebindForVideo(recordingActive = false)
            onError(e)
        }
    }

    /** Stop the active recording; [onSaved] fires once the clip is finalized. */
    fun stopRunningRecording(onSaved: (String) -> Unit) {
        val recording = activeRecording ?: return
        pendingVideoCallback = onSaved
        recording.stop()
    }

    fun isRecording(): Boolean = activeRecording != null

    private fun rebindForVideo(recordingActive: Boolean) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        provider.unbindAll()
        val useCases = mutableListOf(preview, imageAnalysis).apply {
            if (recordingActive) videoCapture?.let { add(it) }
            else imageCapture?.let { add(it) }
        }
        camera = provider.bindToLifecycle(
            owner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
    }

    /** SIZE-mode capture: unbind preview/analysis first to respect 2-use-case devices. */
    fun captureImage(
        onSaved: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera not initialized"))
            return
        }

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
        activeRecording?.stop()
        activeRecording = null
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
