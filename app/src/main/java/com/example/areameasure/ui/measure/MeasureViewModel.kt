package com.example.areameasure.ui.measure

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.areameasure.camera.MeasureCameraManager
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.PeopleCountMeasurement
import com.example.areameasure.data.model.RunningPostureMeasurement
import com.example.areameasure.data.model.UnitOfMeasure
import com.example.areameasure.data.repository.MeasurementRepository
import com.example.areameasure.data.repository.PeopleCountRepository
import com.example.areameasure.data.repository.RunningPostureRepository
import com.example.areameasure.data.repository.SpeedRepository
import com.example.areameasure.domain.AreaCalculator
import com.example.areameasure.domain.MeasureMode
import com.example.areameasure.domain.PerspectiveCalibration
import com.example.areameasure.domain.RunType
import com.example.areameasure.processing.ImageProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * A detected object shown on the live contour overlay.
 *
 * In SIZE mode [isSelected] is relevant; in SPEED mode [isTracked] / [isMoving] are.
 */
data class MeasureDetectedObject(
    val contourIndex: Int,
    val centerX: Float,
    val centerY: Float,
    val pixelWidth: Float,
    val pixelHeight: Float,
    val pixelDepth: Float,
    val angle: Float,
    val isSelected: Boolean,
    /** True while this object has an active speed-tracking session. */
    val isMoving: Boolean,
    val isTracked: Boolean,
    /** Current smoothed speed of this object in px/s (0 when not tracked). */
    val speed: Double = 0.0
)

/** Real-world dimensions for the selected object (SIZE mode). */
data class Dimensions3D(
    val x: Double,
    val y: Double,
    val z: Double
)

data class MeasureUiState(
    val mode: MeasureMode = MeasureMode.SIZE,
    // --- shared ---
    val isCameraRunning: Boolean = false,
    val contourBitmap: Bitmap? = null,
    val detectedObjects: List<MeasureDetectedObject> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val showLabelDialog: Boolean = false,
    val errorMessage: String? = null,
    // --- SIZE mode ---
    val unit: UnitOfMeasure = UnitOfMeasure.MM,
    val isCapturing: Boolean = false,
    val showCalibrationDialog: Boolean = false,
    val lastCapturedImagePath: String? = null,
    val selectedObjectIndex: Int = -1,
    /**
     * True once SIZE mode has a perspective (homography) calibration from a known
     * reference rectangle. Replaces the old single scalar `pixelsPerMm`, which
     * couldn't correct for perspective tilt or in-plane rotation.
     */
    val isCalibrated: Boolean = false,
    /** Real width/height (mm) of the reference rectangle used for calibration (for display). */
    val calibrationWidthMm: Double? = null,
    val calibrationHeightMm: Double? = null,
    val dimensions: Dimensions3D? = null,
    /** Pixel-space dimensions of the currently selected object (shown in the calibration dialog). */
    val selectedPixelX: Double? = null,
    val selectedPixelY: Double? = null,
    val selectedPixelZ: Double? = null,
    // --- SPEED mode ---
    val isTracking: Boolean = false,
    /**
     * Fastest active object's speed. When a SIZE-mode plane calibration is
     * available and the tracked object sits on that plane, this is in **m/s**
     * (see [speedUsesMetric]); otherwise it is reported in raw **px/s**.
     */
    val currentSpeed: Double = 0.0,
    val maxSpeed: Double = 0.0,
    /**
     * Total distance covered by the tracked object. Metres when
     * [speedUsesMetric] is true, pixels otherwise.
     */
    val totalDistance: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val zoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    /**
     * True while SPEED mode can convert pixels to real-world units, either via
     * the active SIZE-mode plane calibration (homography) or via a SPEED-mode
     * reference-length calibration (pixels per metre). When true, speed and
     * distance are reported in m/s and m; when false they fall back to px/s
     * and px.
     */
    val speedUsesMetric: Boolean = false,
    /** True while the SPEED reference-length calibration dialog is shown. */
    val showSpeedCalibrationDialog: Boolean = false,
    /**
     * Clockwise rotation (a multiple of 90°) applied to the raw analysis frame
     * so the contour overlay matches the upright preview. The raw frame is
     * landscape; after rotating by this many degrees the overlay is portrait and
     * lines up with the crop-filled PreviewView.
     */
    val overlayRotationDegrees: Int = 0,
    // --- PEOPLE mode ---
    /** Number of faces detected in the most recent frame (live count). */
    val peopleCount: Int = 0,
    // --- RUNNING mode ---
    /** Long-distance or sprint session type. */
    val runType: RunType = RunType.LONG,
    /** null = no pose detected; true = good (yellow); false = bad (red). */
    val postureCorrect: Boolean? = null,
    /** Absolute torso lean from vertical in degrees (null when no pose). */
    val trunkLeanDegrees: Double? = null,
    /** True while a running clip is being recorded. */
    val isRecording: Boolean = false,
    /** Non-null shows a "saved" confirmation dialog. */
    val runningSavedMessage: String? = null,
    // --- FAN mode ---
    /** Number of blades on the fan (affects RPM conversion). */
    val bladeCount: Int = 3,
    /** Estimated blade-pass frequency in Hz (null until a period is detected). */
    val fanPassFrequencyHz: Double? = null,
    /** Estimated rotation speed in RPM (null until a period is detected). */
    val fanRpm: Double? = null,
    /** Normalised motion energy of the detected region (0..1). */
    val fanEnergy: Double = 0.0,
    /** Number of samples accumulated by the fan analyzer. */
    val fanSampleCount: Int = 0
)

@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val cameraManager: MeasureCameraManager,
    private val measurementRepository: MeasurementRepository,
    private val speedRepository: SpeedRepository,
    private val peopleCountRepository: PeopleCountRepository,
    private val runningPostureRepository: RunningPostureRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeasureUiState())
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    private var processingCollectionJob: Job? = null
    private var poseCollectionJob: Job? = null
    private var fanCollectionJob: Job? = null

    // --- SIZE calibration state ---

    /**
     * Active perspective calibration for SIZE mode, mapping image pixels to
     * millimetres on the reference plane. null = not calibrated. Stored outside
     * the UI state because it holds a native OpenCV Mat.
     */
    private var sizeCalibration: PerspectiveCalibration.PlaneCalibration? = null

    /**
     * Contour of the currently selected object, retained so the calibration step
     * can extract its four corners. ImageProcessor does not release detected
     * contours, so this reference stays valid until the next detection pass.
     */
    private var selectedContour: org.opencv.core.MatOfPoint? = null

    /** Release the native homography Mat and drop the SIZE calibration. */
    private fun clearSizeCalibration() {
        sizeCalibration?.homography?.release()
        sizeCalibration = null
        selectedContour = null
    }

    /**
     * SPEED-mode scale factor: how many pixels equal one metre, derived from a
     * user-entered reference length. null until the user calibrates. When the
     * SIZE plane calibration is also present, that homography takes precedence
     * (it corrects for perspective); this simple factor is the fallback.
     */
    private var speedPixelsPerMeter: Double? = null

    /** True when SPEED can convert pixel displacement to real-world metres. */
    private fun hasMetricScale(): Boolean = sizeCalibration != null || speedPixelsPerMeter != null

    // --- SPEED tracking state ---

    /**
     * Contour index currently measured for speed (-1 = none). Auto-picked from
     * the largest moving object unless the user taps a specific one. Speed uses
     * a visual tracker (ImageProcessor.speedTracker) whose bounding-box centre
     * is far more stable than raw contour centroids.
     */
    private var trackedContourIndex: Int = -1

    /** True once the user has manually tapped a target (overrides auto-pick). */
    private var userPinned: Boolean = false

    /** Contour indices currently detected as moving (used only to mark them). */
    private var movingContourIndices: Set<Int> = emptySet()

    /** Smoothed speed of the tracked target (px/s or m/s depending on calibration). */
    private var pinnedSpeed: Double = 0.0

    /** Accumulated distance of the tracked target in the current unit. */
    private var pinnedDistance: Double = 0.0

    /** Sensor timestamp of the last frame that contributed to the tracked speed. */
    private var lastPinnedTimeMs: Long = 0L

    /** Previous ground-contact point (pixels) for the plane-homography metric path. */
    private var lastPinnedGroundPointPx: Point? = null

    /** When the current tracking session started (for elapsed time display). */
    private var trackingStartTimeMs: Long = 0L

    private var prevObjects: List<ImageProcessor.DetectedObject> = emptyList()
    /** Timestamp when the camera most recently started (for the settle delay). */
    private var cameraStartedAtMs: Long = 0L

    /** Rolling window of recent raw face counts, used to stabilise the live count. */
    private val peopleCountHistory = mutableListOf<Int>()

    /** Switch between Size and Speed measurement. Stops the camera if running. */
    fun selectMode(mode: MeasureMode) {
        if (mode == _uiState.value.mode) return
        // Clear speed tracking state on any mode switch — tracks and the user pin
        // are specific to SPEED mode and must not leak into SIZE mode.
        clearSpeedPin()
        peopleCountHistory.clear()
        if (_uiState.value.isCameraRunning) stopCamera()
        // The SIZE plane calibration (homography) is NOT dropped on mode switch:
        // SPEED mode reuses it to convert pixels → metres. Only reset()/stopCamera()
        // clear it. [isCalibrated] reflects whether that calibration still exists.
        // The SPEED-only reference-length scale is mode-specific and is dropped.
        speedPixelsPerMeter = null
        cameraManager.measureMode = mode
        _uiState.update {
            it.copy(
                mode = mode,
                // Reset mode-specific state on switch.
                isTracking = false,
                currentSpeed = 0.0,
                maxSpeed = 0.0,
                totalDistance = 0.0,
                elapsedSeconds = 0.0,
                selectedObjectIndex = -1,
                isCalibrated = sizeCalibration != null,
                calibrationWidthMm = sizeCalibration?.referenceWidthMm,
                calibrationHeightMm = sizeCalibration?.referenceHeightMm,
                speedUsesMetric = hasMetricScale(),
                dimensions = null,
                selectedPixelX = null,
                selectedPixelY = null,
                selectedPixelZ = null,
                showCalibrationDialog = false,
                showSpeedCalibrationDialog = false,
                isCapturing = false,
                peopleCount = 0,
                postureCorrect = null,
                trunkLeanDegrees = null,
                isRecording = false,
                runningSavedMessage = null,
                fanPassFrequencyHz = null,
                fanRpm = null,
                fanEnergy = 0.0,
                fanSampleCount = 0
            )
        }
    }

    fun startCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner, surfaceProvider: androidx.camera.core.Preview.SurfaceProvider) {
        if (_uiState.value.isCameraRunning) return

        cameraManager.measureMode = _uiState.value.mode
        viewModelScope.launch {
            cameraManager.startCamera(lifecycleOwner, surfaceProvider)
                .onSuccess { camera ->
                    val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f
                    cameraStartedAtMs = System.currentTimeMillis()
                    peopleCountHistory.clear()
                    _uiState.update {
                        it.copy(
                            isCameraRunning = true,
                            maxZoomRatio = maxZoom,
                            overlayRotationDegrees = cameraManager.overlayRotationDegrees
                        )
                    }
                    startProcessingCollection()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = "Camera error: ${e.message}") }
                }
        }
    }

    fun stopCamera() {
        stopTracking()
        cameraManager.shutdown()
        processingCollectionJob?.cancel()
        processingCollectionJob = null
        poseCollectionJob?.cancel()
        poseCollectionJob = null
        fanCollectionJob?.cancel()
        fanCollectionJob = null
        prevObjects = emptyList()
        movingContourIndices = emptySet()
        cameraStartedAtMs = 0L
        _uiState.update {
            it.copy(
                isCameraRunning = false,
                contourBitmap = null,
                detectedObjects = emptyList(),
                dimensions = null,
                selectedObjectIndex = -1,
                selectedPixelX = null,
                selectedPixelY = null,
                selectedPixelZ = null,
                frameWidth = 0,
                frameHeight = 0,
                peopleCount = 0,
                postureCorrect = null,
                trunkLeanDegrees = null,
                isRecording = false,
                fanPassFrequencyHz = null,
                fanRpm = null,
                fanEnergy = 0.0,
                fanSampleCount = 0
            )
        }
    }

    /**
     * Reset the current in-progress measurement: stop the camera and clear the
     * selected object, calibration, dimensions and speed stats. Non-destructive —
     * saved measurements are untouched.
     */
    fun reset() {
        stopCamera()
        clearSizeCalibration()
        speedPixelsPerMeter = null
        _uiState.update {
            it.copy(
                selectedObjectIndex = -1,
                isCalibrated = false,
                calibrationWidthMm = null,
                calibrationHeightMm = null,
                dimensions = null,
                selectedPixelX = null,
                selectedPixelY = null,
                selectedPixelZ = null,
                isTracking = false,
                currentSpeed = 0.0,
                maxSpeed = 0.0,
                totalDistance = 0.0,
                elapsedSeconds = 0.0,
                speedUsesMetric = false,
                showCalibrationDialog = false,
                showSpeedCalibrationDialog = false,
                isCapturing = false,
                lastCapturedImagePath = null,
                peopleCount = 0,
                postureCorrect = null,
                trunkLeanDegrees = null,
                isRecording = false,
                runningSavedMessage = null,
                fanPassFrequencyHz = null,
                fanRpm = null,
                fanEnergy = 0.0,
                fanSampleCount = 0
            )
        }
    }

    /**
     * Delete ALL saved size and speed measurements from the database, along with
     * their image files. Destructive — callers should confirm before invoking.
     */
    fun clearMemory(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val sizeMeasurements = measurementRepository.getAllMeasurementsOnce()
            val speedMeasurements = speedRepository.getAllMeasurementsOnce()
            val peopleMeasurements = peopleCountRepository.getAllMeasurementsOnce()
            val runningMeasurements = runningPostureRepository.getAllMeasurementsOnce()

            sizeMeasurements.forEach { m ->
                try {
                    java.io.File(m.imagePath).delete()
                } catch (_: Exception) {
                }
            }
            speedMeasurements.forEach { m ->
                m.imagePath?.let { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (_: Exception) {
                    }
                }
            }
            peopleMeasurements.forEach { m ->
                m.imagePath?.let { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (_: Exception) {
                    }
                }
            }
            runningMeasurements.forEach { m ->
                listOfNotNull(m.imagePath, m.videoPath).forEach { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (_: Exception) {
                    }
                }
            }

            measurementRepository.deleteAllMeasurements()
            speedRepository.deleteAllMeasurements()
            peopleCountRepository.deleteAllMeasurements()
            runningPostureRepository.deleteAllMeasurements()
            onDone()
        }
    }

    private fun startProcessingCollection() {
        processingCollectionJob?.cancel()
        processingCollectionJob = viewModelScope.launch {
            cameraManager.processingResults.collect { result ->
                if (result == null) return@collect

                val bitmap = matToBitmap(result.annotatedMat)
                val mode = _uiState.value.mode

                when (mode) {
                    MeasureMode.SPEED -> handleSpeedFrame(result)
                    MeasureMode.PEOPLE -> handlePeopleFrame(result)
                    MeasureMode.SIZE -> { /* dimensions computed below */ }
                    MeasureMode.RUNNING -> { /* pose handled by the pose collector */ }
                    MeasureMode.FAN -> { /* fan speed handled by the fan collector */ }
                }

                // Map each detected contour to its live status so the overlay can
                // mark moving objects and show the tracked target's speed.
                val selectableObjects = result.detectedObjects.map { obj ->
                    val isTracked = obj.contourIndex == trackedContourIndex
                    MeasureDetectedObject(
                        contourIndex = obj.contourIndex,
                        centerX = obj.center.x.toFloat(),
                        centerY = obj.center.y.toFloat(),
                        pixelWidth = obj.pixelWidth.toFloat(),
                        pixelHeight = obj.pixelHeight.toFloat(),
                        pixelDepth = obj.pixelDepth.toFloat(),
                        angle = obj.angle.toFloat(),
                        isSelected = isTracked && userPinned,
                        isMoving = obj.contourIndex in movingContourIndices,
                        isTracked = isTracked,
                        speed = if (isTracked) pinnedSpeed else 0.0
                    )
                }

                // SIZE: compute real-world dimensions once a plane calibration exists.
                val selectedObj = result.selectedObject
                val calibration = sizeCalibration
                val dimensions: Dimensions3D?
                if (mode == MeasureMode.SIZE && selectedObj != null && calibration != null) {
                    // Rectify the selected object's four bounding corners through the
                    // homography to get its real side lengths (mm), correcting for
                    // the phone's tilt and rotation over the surface.
                    val corners = PerspectiveCalibration.cornersOf(selectedObj.contour)
                    val (widthMm, heightMm) = PerspectiveCalibration.measure(
                        calibration, corners
                    )
                    val (dx, dy, dz) = AreaCalculator.fromPlaneMm(
                        widthMm = widthMm,
                        heightMm = heightMm,
                        unit = _uiState.value.unit
                    )
                    dimensions = Dimensions3D(dx, dy, dz)
                } else {
                    dimensions = null
                }

                // Retain the selected object's contour so calibration can extract
                // its four corners. Cleared via clearSizeCalibration() on reset.
                if (mode == MeasureMode.SIZE) selectedContour = selectedObj?.contour

                // SPEED reuses the SIZE plane calibration: flag the UI so it can
                // show m/s + m when a calibration is live, px/s + px otherwise.
                _uiState.update {
                    it.copy(
                        contourBitmap = bitmap ?: it.contourBitmap,
                        detectedObjects = selectableObjects,
                        frameWidth = result.frameWidth,
                        frameHeight = result.frameHeight,
                        selectedPixelX = selectedObj?.pixelWidth,
                        selectedPixelY = selectedObj?.pixelHeight,
                        selectedPixelZ = selectedObj?.pixelDepth,
                        dimensions = dimensions,
                        isCalibrated = sizeCalibration != null,
                        calibrationWidthMm = sizeCalibration?.referenceWidthMm,
                        calibrationHeightMm = sizeCalibration?.referenceHeightMm,
                        speedUsesMetric = hasMetricScale()
                    )
                }
            }
        }

        // RUNNING mode posture stream (separate from the OpenCV pipeline).
        poseCollectionJob?.cancel()
        poseCollectionJob = viewModelScope.launch {
            cameraManager.runningPoseResults.collect { result ->
                if (_uiState.value.mode != MeasureMode.RUNNING) return@collect
                if (result == null) return@collect
                _uiState.update {
                    it.copy(
                        postureCorrect = if (result.hasPose) result.isCorrect else null,
                        trunkLeanDegrees = result.trunkLeanDegrees
                    )
                }
            }
        }

        // FAN mode blade-speed stream.
        fanCollectionJob?.cancel()
        fanCollectionJob = viewModelScope.launch {
            cameraManager.fanResults.collect { result ->
                if (_uiState.value.mode != MeasureMode.FAN) return@collect
                if (result == null) return@collect
                val blades = _uiState.value.bladeCount
                val rpm = result.passFrequencyHz?.let { it * 60.0 / blades }
                _uiState.update {
                    it.copy(
                        fanPassFrequencyHz = result.passFrequencyHz,
                        fanRpm = rpm,
                        fanEnergy = result.energy,
                        fanSampleCount = result.sampleCount
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- SIZE mode

    /**
     * Handle a tap on the camera preview to select an object to measure.
     *
     * The overlay is the raw sensor frame rotated clockwise by [rotationDegrees]
     * (a multiple of 90°) and shown with [ContentScale.Crop], so a screen tap must
     * be inverse-mapped: view → cropped rotated-bitmap → raw frame. Detected
     * objects live in raw-frame coordinates, so the tap is compared there.
     */
    fun onObjectSelected(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Int = 0
    ) {
        if (_uiState.value.mode != MeasureMode.SIZE) return
        val state = _uiState.value
        if (state.detectedObjects.isEmpty() || state.frameWidth == 0 || state.frameHeight == 0) return

        val (frameX, frameY) = viewTapToFrame(
            tapX = tapX,
            tapY = tapY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            frameWidth = state.frameWidth,
            frameHeight = state.frameHeight,
            rotationDegrees = rotationDegrees
        ) ?: return

        var closestIndex = -1
        var closestDistance = Double.MAX_VALUE

        state.detectedObjects.forEach { obj ->
            val dx = frameX - obj.centerX
            val dy = frameY - obj.centerY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            val maxDim = kotlin.math.max(obj.pixelWidth, obj.pixelHeight)

            if (dist < maxDim && dist < closestDistance) {
                closestDistance = dist
                closestIndex = obj.contourIndex
            }
        }

        if (closestIndex >= 0) {
            _uiState.update { it.copy(selectedObjectIndex = closestIndex) }
            cameraManager.setTargetContourIndex(closestIndex)
            if (!state.isCalibrated) {
                _uiState.update { it.copy(showCalibrationDialog = true) }
            }
        }
    }

    /**
     * Handle a tap on the camera preview in SPEED mode: pin the nearest MOVING
     * object so its speed is tracked and shown on demand. Only objects that are
     * currently moving can be selected — tapping a stationary object is ignored.
     * Tapping the same pinned object again (or empty space) clears the pin.
     *
     * Once pinned, the object is tracked with a visual tracker so its speed is
     * measured from a stable bounding-box centre rather than a jittery contour
     * centroid.
     */
    fun selectSpeedObject(
        tapX: Float,
        tapY: Float,
        viewWidth: Int,
        viewHeight: Int,
        rotationDegrees: Int = 0
    ) {
        if (_uiState.value.mode != MeasureMode.SPEED) return
        val state = _uiState.value
        if (state.detectedObjects.isEmpty() || state.frameWidth == 0 || state.frameHeight == 0) return

        val (frameX, frameY) = viewTapToFrame(
            tapX = tapX,
            tapY = tapY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            frameWidth = state.frameWidth,
            frameHeight = state.frameHeight,
            rotationDegrees = rotationDegrees
        ) ?: return

        var closestIndex = -1
        var closestDistance = Double.MAX_VALUE

        // Only moving objects are selectable for speed.
        state.detectedObjects.forEach { obj ->
            if (!obj.isMoving) return@forEach
            val dx = frameX - obj.centerX
            val dy = frameY - obj.centerY
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            val maxDim = kotlin.math.max(obj.pixelWidth, obj.pixelHeight)
            if (dist < maxDim && dist < closestDistance) {
                closestDistance = dist
                closestIndex = obj.contourIndex
            }
        }

        // Toggle: tapping the currently-pinned object again clears it (back to auto).
        if (closestIndex >= 0 && userPinned && closestIndex == trackedContourIndex) {
            clearSpeedPin()
            return
        }

        if (closestIndex >= 0) {
            pinSpeedTarget(closestIndex)
        } else {
            // Tapped empty space (or a stationary object) — clear and go back to auto.
            clearSpeedPin()
        }
    }

    /** Pin [contourIndex] and seed the visual tracker from its bounding box. */
    private fun pinSpeedTarget(contourIndex: Int) {
        // Seed the tracker with the detected object's upright bounding box.
        // If the object is no longer in the last frame, abort rather than pin
        // with no tracker.
        val targetObj = prevObjects.firstOrNull { it.contourIndex == contourIndex }
            ?: return
        val seedRect = Imgproc.boundingRect(targetObj.contour)

        trackedContourIndex = contourIndex
        userPinned = true
        pinnedSpeed = 0.0
        pinnedDistance = 0.0
        lastPinnedTimeMs = 0L
        lastPinnedGroundPointPx = null
        trackingStartTimeMs = 0L

        cameraManager.setTargetContourIndex(contourIndex)
        cameraManager.setSpeedTargetRect(seedRect)
        _uiState.update {
            it.copy(
                selectedObjectIndex = contourIndex,
                isTracking = true,
                currentSpeed = 0.0,
                maxSpeed = 0.0,
                totalDistance = 0.0,
                elapsedSeconds = 0.0
            )
        }
    }

    /** Auto-pick [obj] and (re)seed the tracker without marking it user-pinned. */
    private fun seedTracker(obj: ImageProcessor.DetectedObject) {
        val seedRect = Imgproc.boundingRect(obj.contour)

        trackedContourIndex = obj.contourIndex
        pinnedSpeed = 0.0
        pinnedDistance = 0.0
        lastPinnedTimeMs = 0L
        lastPinnedGroundPointPx = null
        trackingStartTimeMs = 0L

        cameraManager.setTargetContourIndex(obj.contourIndex)
        cameraManager.setSpeedTargetRect(seedRect)
        _uiState.update {
            it.copy(
                isTracking = true,
                currentSpeed = 0.0,
                maxSpeed = 0.0,
                totalDistance = 0.0,
                elapsedSeconds = 0.0
            )
        }
    }

    /** Clear the target (and manual pin), its tracker and the live readout. */
    private fun clearSpeedPin() {
        trackedContourIndex = -1
        userPinned = false
        pinnedSpeed = 0.0
        pinnedDistance = 0.0
        lastPinnedTimeMs = 0L
        lastPinnedGroundPointPx = null
        trackingStartTimeMs = 0L

        cameraManager.setSpeedTargetRect(null)
        cameraManager.setTargetContourIndex(-1)
        _uiState.update {
            it.copy(selectedObjectIndex = -1, isTracking = false, currentSpeed = 0.0)
        }
    }

    /**
     * Calibrate SIZE mode from a known reference rectangle (e.g. a credit card)
     * resting on the surface. Rectifies the selected object's four bounding
     * corners to compute a homography to millimetres on that plane, so later
     * measurements correct for the phone's tilt and rotation.
     */
    fun setSizeCalibration(widthMm: Double, heightMm: Double) {
        val contour = selectedContour ?: return
        if (widthMm <= 0 || heightMm <= 0) return
        val imageCorners = PerspectiveCalibration.cornersOf(contour)
        // Replace any prior calibration, releasing its native Mat.
        clearSizeCalibration()
        sizeCalibration = PerspectiveCalibration.calibrate(
            imageCorners, widthMm, heightMm
        )
        _uiState.update {
            it.copy(
                isCalibrated = true,
                calibrationWidthMm = widthMm,
                calibrationHeightMm = heightMm,
                showCalibrationDialog = false
            )
        }
    }

    fun dismissCalibrationDialog() {
        _uiState.update { it.copy(showCalibrationDialog = false) }
    }

    fun showCalibrationDialog() {
        _uiState.update { it.copy(showCalibrationDialog = true) }
    }

    fun showSpeedCalibrationDialog() {
        _uiState.update { it.copy(showSpeedCalibrationDialog = true) }
    }

    fun dismissSpeedCalibrationDialog() {
        _uiState.update { it.copy(showSpeedCalibrationDialog = false) }
    }

    /**
     * Calibrate SPEED mode from the pinned object's real-world length. Uses the
     * object's longer bounding-box edge (in pixels) as the reference, producing a
     * pixels-per-metre scale so speed/distance are reported in m/s and m.
     */
    fun setSpeedCalibration(referenceLengthMm: Double) {
        if (referenceLengthMm <= 0.0) return
        val target = prevObjects.firstOrNull { it.contourIndex == trackedContourIndex }
            ?: prevObjects.maxByOrNull { it.area }
            ?: return
        val pixelLength = target.pixelWidth
        if (pixelLength <= 0.0) return

        speedPixelsPerMeter = pixelLength / (referenceLengthMm / 1000.0)
        _uiState.update {
            it.copy(showSpeedCalibrationDialog = false, speedUsesMetric = true)
        }
    }

    fun setUnit(unit: UnitOfMeasure) {
        _uiState.update { it.copy(unit = unit) }
    }

    fun capture() {
        if (_uiState.value.mode != MeasureMode.SIZE) return
        _uiState.update { it.copy(isCapturing = true, errorMessage = null) }
        processingCollectionJob?.cancel()
        processingCollectionJob = null
        cameraManager.captureImage(
            onSaved = { path ->
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        lastCapturedImagePath = path,
                        showLabelDialog = true,
                        isCameraRunning = false,
                        contourBitmap = null,
                        detectedObjects = emptyList()
                    )
                }
            },
            onError = { e ->
                _uiState.update {
                    it.copy(isCapturing = false, errorMessage = "Capture failed: ${e.message}")
                }
            }
        )
    }

    fun saveSizeMeasurement(label: String) {
        val state = _uiState.value
        val dims = state.dimensions
        val imagePath = state.lastCapturedImagePath

        if (dims == null || imagePath == null) {
            _uiState.update { it.copy(errorMessage = "No measurement to save", showLabelDialog = false) }
            return
        }

        val measurement = Measurement(
            objectLabel = label.ifBlank { "Unnamed" },
            xValue = dims.x,
            yValue = dims.y,
            zValue = dims.z,
            unit = state.unit,
            imagePath = imagePath,
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            measurementRepository.saveMeasurement(measurement)
            _uiState.update {
                it.copy(
                    showLabelDialog = false,
                    lastCapturedImagePath = null
                )
            }
        }
    }

    // ---------------------------------------------------------------- SPEED mode

    private fun handleSpeedFrame(result: ImageProcessor.ProcessingResult) {
        // Prefer the sensor-capture timestamp for frame-to-frame dt: it reflects
        // when the frame was actually exposed and is immune to wall-clock jitter
        // under frame drops. Fall back to the wall clock when unavailable.
        val now = if (result.frameTimestampNanos > 0L) {
            result.frameTimestampNanos / 1_000_000L
        } else {
            System.currentTimeMillis()
        }

        // Ignore motion for a short window after the camera starts so handshake
        // jitter doesn't get mistaken for a moving object.
        val settled = now - cameraStartedAtMs >= SETTLE_DELAY_MS

        movingContourIndices = if (settled) {
            detectMovingObjects(result.detectedObjects).map { it.obj.contourIndex }.toSet()
        } else {
            emptySet()
        }

        // Auto-pick the largest moving object when the user has not manually
        // tapped one. This is the "just point the camera" simple path.
        if (settled && !userPinned) {
            val auto = result.detectedObjects
                .filter { it.contourIndex in movingContourIndices }
                .maxByOrNull { it.area }
            if (auto == null) {
                if (trackedContourIndex >= 0) clearSpeedPin()
            } else if (auto.contourIndex != trackedContourIndex) {
                seedTracker(auto)
            }
        }

        // Speed comes from the visual tracker (see ImageProcessor), whose
        // bounding-box centre is far more stable than a raw contour centroid.
        if (settled && trackedContourIndex >= 0 && result.speedTrackActive) {
            updatePinnedSpeed(result.speedDisplacementPx, result.speedTrackRect, now)
        }

        // Mark only moving contours plus the tracked target.
        val marked = movingContourIndices.toMutableSet()
        if (trackedContourIndex >= 0) marked.add(trackedContourIndex)
        cameraManager.setMarkedContourIndices(marked)
        cameraManager.setTargetContourIndex(if (trackedContourIndex >= 0) trackedContourIndex else -1)

        updateAggregateSpeedState(now)
        prevObjects = result.detectedObjects
    }

    /** A current detection paired with its frame-to-frame displacement. */
    private data class MovingDetection(
        val obj: ImageProcessor.DetectedObject,
        val displacement: Double
    )

    /**
     * Return every current object whose displacement from its nearest previous
     * counterpart exceeds the background (median) motion by the threshold. Used
     * only to mark moving objects — not to measure speed.
     */
    private fun detectMovingObjects(currentObjects: List<ImageProcessor.DetectedObject>): List<MovingDetection> {
        if (prevObjects.isEmpty()) return emptyList()

        val matches = currentObjects.mapNotNull { current ->
            val nearest = prevObjects.minByOrNull { prev ->
                distance(prev.center.x, prev.center.y, current.center.x, current.center.y)
            } ?: return@mapNotNull null
            MovingDetection(current, distance(nearest.center.x, nearest.center.y, current.center.x, current.center.y))
        }
        if (matches.isEmpty()) return emptyList()

        // Median displacement = global background motion (camera pan / handshake),
        // which shifts every object together. A genuinely moving object must move
        // clearly more than this background.
        val backgroundMotion = matches.map { it.displacement }.sorted()
            .let { it[it.size / 2] }
            .coerceAtLeast(MOVING_THRESHOLD_PX)

        val cutoff = backgroundMotion + MOVING_THRESHOLD_PX
        return matches.filter { it.displacement in cutoff..MAX_DISPLACEMENT_PX }
    }

    /** Convert the tracker's pixel displacement into real units and update the pinned speed. */
    private fun updatePinnedSpeed(displacementPx: Double, trackRect: Rect?, now: Long) {
        // Reject implausible tracker jumps; still advance the clock so a single
        // bad frame doesn't turn into a huge dt on the next good frame.
        if (displacementPx <= 0.0 || displacementPx > MAX_DISPLACEMENT_PX) {
            lastPinnedTimeMs = now
            return
        }
        if (lastPinnedTimeMs <= 0L) {
            lastPinnedTimeMs = now
            return
        }
        val timeDelta = now - lastPinnedTimeMs
        if (timeDelta <= 0) return

        val displacementM = when {
            sizeCalibration != null -> {
                val cal = sizeCalibration!!
                val curGround = trackRect?.let { rectBottomCenter(it) }
                val prevGround = lastPinnedGroundPointPx
                val d = if (curGround != null && prevGround != null) {
                    val prevMm = PerspectiveCalibration.transformPoint(cal, prevGround)
                    val curMm = PerspectiveCalibration.transformPoint(cal, curGround)
                    distance(prevMm.x, prevMm.y, curMm.x, curMm.y) / 1000.0
                } else null
                if (curGround != null) lastPinnedGroundPointPx = curGround
                d ?: speedPixelsPerMeter?.let { displacementPx / it } ?: displacementPx
            }
            speedPixelsPerMeter != null -> displacementPx / speedPixelsPerMeter!!
            else -> displacementPx
        }

        val instantSpeed = displacementM / (timeDelta / 1000.0)
        pinnedSpeed = smoothSpeed(pinnedSpeed, instantSpeed)
        pinnedDistance += displacementM
        lastPinnedTimeMs = now
    }

    /** Bottom-centre of a tracker box — the approximate ground-contact point. */
    private fun rectBottomCenter(r: Rect): Point =
        Point(r.x + r.width / 2.0, (r.y + r.height).toDouble())

    /** Fold the tracked target's stats into the top overlay readout. */
    private fun updateAggregateSpeedState(now: Long) {
        if (trackedContourIndex < 0) {
            if (_uiState.value.isTracking) {
                _uiState.update { it.copy(isTracking = false, currentSpeed = 0.0) }
            }
            return
        }

        if (trackingStartTimeMs == 0L) trackingStartTimeMs = now
        val elapsed = (now - trackingStartTimeMs) / 1000.0

        _uiState.update {
            it.copy(
                isTracking = true,
                currentSpeed = pinnedSpeed,
                maxSpeed = maxOf(it.maxSpeed, pinnedSpeed),
                totalDistance = pinnedDistance,
                elapsedSeconds = elapsed
            )
        }
    }

    fun stopTracking() {
        clearSpeedPin()
    }

    // ---------------------------------------------------------------- PEOPLE mode

    /**
     * Update the live face count from the most recent frame. PEOPLE mode has no
     * calibration and no object selection — it simply reports how many faces are
     * visible right now.
     */
    private fun handlePeopleFrame(result: ImageProcessor.ProcessingResult) {
        val count = result.detectedFaces.size

        // Median over a short window: resists single-frame false positives and
        // missed detections so the live count doesn't flicker between frames.
        peopleCountHistory.add(count)
        if (peopleCountHistory.size > PEOPLE_COUNT_WINDOW) {
            peopleCountHistory.removeAt(0)
        }
        val stable = peopleCountHistory.sorted().let { it[it.size / 2] }

        if (_uiState.value.peopleCount != stable) {
            _uiState.update { it.copy(peopleCount = stable) }
        }
    }

    /**
     * Save the current face count as a history snapshot. PEOPLE mode does not
     * bind ImageCapture (the 2-use-case limit), so the snapshot is saved without
     * an image.
     */
    fun savePeopleCount() {
        val count = _uiState.value.peopleCount
        viewModelScope.launch {
            peopleCountRepository.saveMeasurement(
                PeopleCountMeasurement(
                    count = count,
                    imagePath = null,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // ---------------------------------------------------------------- RUNNING mode

    fun selectRunType(type: RunType) {
        _uiState.update { it.copy(runType = type) }
    }

    fun captureRunningPhoto() {
        if (_uiState.value.mode != MeasureMode.RUNNING) return
        cameraManager.captureRunningPhoto(
            onSaved = { path -> saveRunningCapture(imagePath = path, videoPath = null) },
            onError = { e ->
                _uiState.update { it.copy(errorMessage = "Photo failed: ${e.message}") }
            }
        )
    }

    fun startRunningRecording() {
        if (_uiState.value.mode != MeasureMode.RUNNING) return
        cameraManager.startRunningRecording(
            onStarted = { _uiState.update { it.copy(isRecording = true) } },
            onError = { e ->
                _uiState.update { it.copy(errorMessage = "Recording failed: ${e.message}") }
            }
        )
    }

    fun stopRunningRecording() {
        if (_uiState.value.mode != MeasureMode.RUNNING) return
        cameraManager.stopRunningRecording { path ->
            _uiState.update { it.copy(isRecording = false) }
            saveRunningCapture(imagePath = null, videoPath = path)
        }
    }

    private fun saveRunningCapture(imagePath: String?, videoPath: String?) {
        val state = _uiState.value
        val measurement = RunningPostureMeasurement(
            runType = state.runType,
            isCorrectPosture = state.postureCorrect ?: false,
            trunkLeanDegrees = state.trunkLeanDegrees ?: 0.0,
            imagePath = imagePath,
            videoPath = videoPath,
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch {
            runningPostureRepository.saveMeasurement(measurement)
            _uiState.update {
                it.copy(
                    runningSavedMessage = if (videoPath != null) "Video saved" else "Photo saved"
                )
            }
        }
    }

    fun dismissRunningSavedDialog() {
        _uiState.update { it.copy(runningSavedMessage = null) }
    }

    // ---------------------------------------------------------------- FAN mode

    /** Set the fan blade count; RPM is recomputed on the next analyzer result. */
    fun selectBladeCount(count: Int) {
        if (count !in 2..6) return
        val passHz = _uiState.value.fanPassFrequencyHz
        _uiState.update {
            it.copy(
                bladeCount = count,
                fanRpm = passHz?.let { hz -> hz * 60.0 / count }
            )
        }
    }

    fun zoomIn() {
        val current = _uiState.value.zoomRatio
        val max = _uiState.value.maxZoomRatio
        applyZoom((current + ZOOM_STEP).coerceAtMost(max))
    }

    fun zoomOut() {
        val current = _uiState.value.zoomRatio
        applyZoom((current - ZOOM_STEP).coerceAtLeast(1.0f))
    }

    fun setZoom(ratio: Float) {
        val max = _uiState.value.maxZoomRatio
        applyZoom(ratio.coerceIn(1.0f, max))
    }

    private fun applyZoom(ratio: Float) {
        cameraManager.getCamera()?.cameraControl?.setZoomRatio(ratio)
        _uiState.update { it.copy(zoomRatio = ratio) }
    }

    /**
     * Save the completed measurement. SPEED mode has no save flow (it reports
     * live speed/distance in px only, decoupled from area/size), so this always
     * delegates to the SIZE saver.
     */
    fun saveMeasurement(label: String) {
        saveSizeMeasurement(label)
    }

    fun dismissLabelDialog() {
        _uiState.update { it.copy(showLabelDialog = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Map a raw-frame point to an offset (in pixels) within the overlay view, so a
     * speed label can be drawn next to its object. This is the forward direction
     * of [viewTapToFrame]: raw frame → rotated bitmap → cropped view. Returns null
     * if the frame/view dimensions aren't known yet.
     */
    fun frameToViewCenter(
        frameX: Float,
        frameY: Float,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Float, Float>? {
        val state = _uiState.value
        val frameWidth = state.frameWidth
        val frameHeight = state.frameHeight
        if (frameWidth == 0 || frameHeight == 0 || viewWidth == 0 || viewHeight == 0) return null

        val w = frameWidth.toFloat()
        val h = frameHeight.toFloat()
        val rot = state.overlayRotationDegrees

        // Normalized position in the raw sensor frame.
        val nx = (frameX / w).coerceIn(0f, 1f)
        val ny = (frameY / h).coerceIn(0f, 1f)

        // Forward clockwise rotation onto the rotated-bitmap normalized coords.
        val (nxr, nyr) = when (rot) {
            90 -> Pair(1f - ny, nx)
            180 -> Pair(1f - nx, 1f - ny)
            270 -> Pair(ny, 1f - nx)
            else -> Pair(nx, ny)
        }

        // Rotated bitmap dimensions.
        val rotW = if (rot == 90 || rot == 270) h else w
        val rotH = if (rot == 90 || rot == 270) w else h

        // ContentScale.Crop: uniform fill then center-crop onto the view.
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val scale = maxOf(vw / rotW, vh / rotH)
        val cropX = (rotW * scale - vw) / 2f
        val cropY = (rotH * scale - vh) / 2f

        val viewX = nxr * rotW * scale - cropX
        val viewY = nyr * rotH * scale - cropY
        return Pair(viewX, viewY)
    }

    override fun onCleared() {
        super.onCleared()
        clearSizeCalibration()
        cameraManager.shutdown()
        processingCollectionJob?.cancel()
        poseCollectionJob?.cancel()
        fanCollectionJob?.cancel()
    }

    private fun matToBitmap(mat: org.opencv.core.Mat): Bitmap? {
        return try {
            val rgbMat = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, rgbMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB)

            // Rotate the landscape sensor frame so the contour overlay matches the
            // upright, crop-filled preview. overlayRotationDegrees is a multiple
            // of 90° computed from the sensor orientation vs. display rotation.
            val rotated = rotateMat(rgbMat, _uiState.value.overlayRotationDegrees)
            val out = if (rotated === rgbMat) rgbMat else {
                rgbMat.release()
                rotated
            }

            val bitmap = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            org.opencv.android.Utils.matToBitmap(out, bitmap)
            out.release()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /** Rotate a Mat by a clockwise multiple of 90°. Returns the same Mat for 0°. */
    private fun rotateMat(mat: org.opencv.core.Mat, degrees: Int): org.opencv.core.Mat {
        if (degrees == 0) return mat
        val code = when (degrees) {
            90 -> org.opencv.core.Core.ROTATE_90_CLOCKWISE
            180 -> org.opencv.core.Core.ROTATE_180
            270 -> org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val dst = org.opencv.core.Mat()
        org.opencv.core.Core.rotate(mat, dst, code)
        return dst
    }

    companion object {
        /**
         * Minimum pixel displacement per frame to consider an object "moving".
         * Kept above typical handshake jitter so a steady hand doesn't trigger
         * tracking on static objects.
         */
        private const val MOVING_THRESHOLD_PX = 8.0
        private const val MAX_DISPLACEMENT_PX = 200.0
        private const val ZOOM_STEP = 0.5f
        /** Motion is ignored for this long after the camera starts, to let the user steady the phone. */
        private const val SETTLE_DELAY_MS = 1500L

        /** Number of recent frames used to stabilise the PEOPLE face count. */
        private const val PEOPLE_COUNT_WINDOW = 5

        private fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1
            val dy = y2 - y1
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private fun smoothSpeed(previous: Double, current: Double): Double {
            val alpha = 0.3
            return alpha * current + (1 - alpha) * previous
        }

        /**
         * Map a tap in overlay-view coordinates back to raw sensor-frame coordinates.
         *
         * The overlay shows the raw frame rotated clockwise by [rotationDegrees]
         * (a multiple of 90°) and displayed with [ContentScale.Crop]. This reverses
         * that transform: view → cropped rotated-bitmap → raw frame, so the tap can
         * be compared against detected objects (which live in raw-frame space).
         * Returns null if the tap falls outside the visible (cropped) region.
         */
        private fun viewTapToFrame(
            tapX: Float,
            tapY: Float,
            viewWidth: Int,
            viewHeight: Int,
            frameWidth: Int,
            frameHeight: Int,
            rotationDegrees: Int
        ): Pair<Float, Float>? {
            val w = frameWidth.toFloat()
            val h = frameHeight.toFloat()

            // Dimensions of the rotated overlay bitmap.
            val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) h else w
            val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) w else h

            // ContentScale.Crop: uniform scale to fill, then center-crop.
            val viewW = viewWidth.toFloat()
            val viewH = viewHeight.toFloat()
            val scale = maxOf(viewW / rotW, viewH / rotH)
            val cropX = (rotW * scale - viewW) / 2f
            val cropY = (rotH * scale - viewH) / 2f

            // View coords → rotated-bitmap coords.
            val rx = (tapX + cropX) / scale
            val ry = (tapY + cropY) / scale
            if (rx < 0 || ry < 0 || rx >= rotW || ry >= rotH) return null

            // Normalized position within the rotated bitmap.
            val nx = rx / rotW
            val ny = ry / rotH

            // Inverse of the clockwise rotation, back to normalized raw frame.
            // Forward 90° CW maps raw (nx,ny) → rotated (1−ny, nx), so the
            // inverse maps rotated (nx,ny) → raw (ny, 1−nx). Derived the same
            // way for 180° and 270°.
            val (nnx, nny) = when (rotationDegrees) {
                90 -> Pair(ny, 1f - nx)
                180 -> Pair(1f - nx, 1f - ny)
                270 -> Pair(1f - ny, nx)
                else -> Pair(nx, ny)
            }

            return Pair(nnx * w, nny * h)
        }
    }
}
