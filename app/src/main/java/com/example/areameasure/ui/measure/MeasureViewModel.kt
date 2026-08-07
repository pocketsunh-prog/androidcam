package com.example.areameasure.ui.measure

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.areameasure.camera.MeasureCameraManager
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.SpeedMeasurement
import com.example.areameasure.data.model.UnitOfMeasure
import com.example.areameasure.data.repository.MeasurementRepository
import com.example.areameasure.data.repository.SpeedRepository
import com.example.areameasure.domain.AreaCalculator
import com.example.areameasure.domain.MeasureMode
import com.example.areameasure.processing.ImageProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Point
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

/**
 * Live tracking state for one moving object (SPEED mode). Position/speed are
 * accumulated frame-to-frame; [contourIndex] is refreshed each frame from the
 * nearest detected contour so a track survives index reassignments.
 */
private data class TrackedObject(
    val id: Int,
    var contourIndex: Int,
    var lastPosition: Point,
    var smoothedSpeed: Double,
    var lastFrameTimeMs: Long,
    var lastMovementTimeMs: Long,
    var totalDistance: Double
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
    /** Calibration: pixels per millimeter. null = not calibrated yet. */
    val pixelsPerMm: Double? = null,
    val dimensions: Dimensions3D? = null,
    val selectedPixelX: Double? = null,
    val selectedPixelY: Double? = null,
    val selectedPixelZ: Double? = null,
    // --- SPEED mode ---
    val isTracking: Boolean = false,
    val currentSpeed: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val totalDistance: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val zoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    /**
     * Clockwise rotation (a multiple of 90°) applied to the raw analysis frame
     * so the contour overlay matches the upright preview. The raw frame is
     * landscape; after rotating by this many degrees the overlay is portrait and
     * lines up with the crop-filled PreviewView.
     */
    val overlayRotationDegrees: Int = 0
)

@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val cameraManager: MeasureCameraManager,
    private val measurementRepository: MeasurementRepository,
    private val speedRepository: SpeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeasureUiState())
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    private var processingCollectionJob: Job? = null

    // --- SPEED tracking state ---

    /**
     * Per-object speed tracking, keyed by a stable track id (not the frame-local
     * contour index, which can change between frames). Each entry accumulates
     * displacement and speed for one independently moving object.
     */
    private val trackedObjects = mutableMapOf<Int, TrackedObject>()

    /** Monotonic id source for new tracks. */
    private var nextTrackId: Int = 0

    /** When the first object in the current tracking burst started moving. */
    private var trackingStartTimeMs: Long = 0L

    private var prevObjects: List<ImageProcessor.DetectedObject> = emptyList()
    /** Timestamp when the camera most recently started (for the settle delay). */
    private var cameraStartedAtMs: Long = 0L

    /** Switch between Size and Speed measurement. Stops the camera if running. */
    fun selectMode(mode: MeasureMode) {
        if (mode == _uiState.value.mode) return
        if (_uiState.value.isCameraRunning) stopCamera()
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
                pixelsPerMm = null,
                dimensions = null,
                selectedPixelX = null,
                selectedPixelY = null,
                selectedPixelZ = null,
                showCalibrationDialog = false,
                isCapturing = false
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
        prevObjects = emptyList()
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
                frameHeight = 0
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
        _uiState.update {
            it.copy(
                selectedObjectIndex = -1,
                pixelsPerMm = null,
                dimensions = null,
                selectedPixelX = null,
                selectedPixelY = null,
                selectedPixelZ = null,
                isTracking = false,
                currentSpeed = 0.0,
                maxSpeed = 0.0,
                totalDistance = 0.0,
                elapsedSeconds = 0.0,
                showCalibrationDialog = false,
                isCapturing = false,
                lastCapturedImagePath = null
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

            measurementRepository.deleteAllMeasurements()
            speedRepository.deleteAllMeasurements()
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

                if (mode == MeasureMode.SPEED) {
                    handleSpeedFrame(result)
                }

                // Map each detected contour to its live tracking session (if any)
                // so the overlay can show a per-object speed and highlight it.
                val trackByContour: Map<Int, TrackedObject> =
                    trackedObjects.values.associateBy { it.contourIndex }

                val selectableObjects = result.detectedObjects.map { obj ->
                    val track = trackByContour[obj.contourIndex]
                    MeasureDetectedObject(
                        contourIndex = obj.contourIndex,
                        centerX = obj.center.x.toFloat(),
                        centerY = obj.center.y.toFloat(),
                        pixelWidth = obj.pixelWidth.toFloat(),
                        pixelHeight = obj.pixelHeight.toFloat(),
                        pixelDepth = obj.pixelDepth.toFloat(),
                        angle = obj.angle.toFloat(),
                        isSelected = obj.contourIndex == _uiState.value.selectedObjectIndex,
                        isMoving = track != null,
                        isTracked = track != null,
                        speed = track?.smoothedSpeed ?: 0.0
                    )
                }

                // SIZE: compute real-world dimensions once calibrated.
                val selectedObj = result.selectedObject
                val pixelsPerMm = _uiState.value.pixelsPerMm
                val dimensions: Dimensions3D?
                if (mode == MeasureMode.SIZE && selectedObj != null && pixelsPerMm != null && pixelsPerMm > 0) {
                    val (dx, dy, dz) = AreaCalculator.calculateDimensions(
                        pixelX = selectedObj.pixelWidth,
                        pixelY = selectedObj.pixelHeight,
                        pixelZ = selectedObj.pixelDepth,
                        pixelsPerMm = pixelsPerMm,
                        unit = _uiState.value.unit
                    )
                    dimensions = Dimensions3D(dx, dy, dz)
                } else {
                    dimensions = null
                }

                _uiState.update {
                    it.copy(
                        contourBitmap = bitmap ?: it.contourBitmap,
                        detectedObjects = selectableObjects,
                        frameWidth = result.frameWidth,
                        frameHeight = result.frameHeight,
                        selectedPixelX = selectedObj?.pixelWidth,
                        selectedPixelY = selectedObj?.pixelHeight,
                        selectedPixelZ = selectedObj?.pixelDepth,
                        dimensions = dimensions
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
            if (state.pixelsPerMm == null) {
                _uiState.update { it.copy(showCalibrationDialog = true) }
            }
        }
    }

    fun setCalibration(knownLengthMm: Double) {
        val pixelX = _uiState.value.selectedPixelX ?: return
        if (knownLengthMm <= 0 || pixelX <= 0) return
        _uiState.update {
            it.copy(
                pixelsPerMm = pixelX / knownLengthMm,
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
        val now = System.currentTimeMillis()

        // Ignore motion for a short window after the camera starts so handshake
        // jitter doesn't get mistaken for a moving object. This lets the user
        // steady the phone before tracking engages.
        val settled = now - cameraStartedAtMs >= SETTLE_DELAY_MS

        if (settled) {
            updateMultiObjectTracking(result, now)
        }

        updateAggregateSpeedState(now)
        prevObjects = result.detectedObjects
    }

    /**
     * Track several moving objects at once. Each frame, every object that moved
     * clearly more than the background is greedily matched to the nearest
     * existing track (or spawns a new one); tracks that stop moving time out.
     */
    private fun updateMultiObjectTracking(
        result: ImageProcessor.ProcessingResult,
        now: Long
    ) {
        val currentObjects = result.detectedObjects
        val moving = detectMovingObjects(currentObjects)

        // Greedy nearest match of existing tracks to moving detections so two
        // tracks never claim the same object.
        val matchedTrackIds = mutableSetOf<Int>()
        val matchedMoving = mutableSetOf<Int>() // indices into [moving]
        val candidates = mutableListOf<Triple<Double, Int, Int>>() // (dist, trackId, movingIndex)
        for ((id, track) in trackedObjects) {
            moving.forEachIndexed { i, m ->
                candidates.add(
                    Triple(
                        distance(track.lastPosition.x, track.lastPosition.y, m.obj.center.x, m.obj.center.y),
                        id,
                        i
                    )
                )
            }
        }
        candidates.sortBy { it.first }
        for ((dist, id, mi) in candidates) {
            if (dist > MAX_DISPLACEMENT_PX) break
            if (id in matchedTrackIds || mi in matchedMoving) continue
            matchedTrackIds.add(id)
            matchedMoving.add(mi)
            updateTrack(trackedObjects[id]!!, moving[mi].obj, now)
        }

        // Spawn a fresh track for each moving detection that went unmatched.
        moving.forEachIndexed { i, m ->
            if (i in matchedMoving) return@forEachIndexed
            if (trackedObjects.isEmpty()) trackingStartTimeMs = now
            val id = nextTrackId++
            trackedObjects[id] = TrackedObject(
                id = id,
                contourIndex = m.obj.contourIndex,
                lastPosition = m.obj.center,
                smoothedSpeed = 0.0,
                lastFrameTimeMs = now,
                lastMovementTimeMs = now,
                totalDistance = 0.0
            )
        }

        // Tracks not matched to a moving object: follow the object if it's still
        // nearby (so the label sticks to it), otherwise let it time out.
        val stale = mutableListOf<Int>()
        for ((id, track) in trackedObjects) {
            if (id in matchedTrackIds) continue
            val nearest = currentObjects.minByOrNull {
                distance(track.lastPosition.x, track.lastPosition.y, it.center.x, it.center.y)
            }
            val followDist = nearest?.let {
                distance(track.lastPosition.x, track.lastPosition.y, it.center.x, it.center.y)
            } ?: Double.MAX_VALUE
            if (followDist <= MAX_DISPLACEMENT_PX) {
                // Still present but below motion threshold — follow without gaining speed.
                track.contourIndex = nearest!!.contourIndex
                track.lastPosition = nearest.center
                track.smoothedSpeed = 0.0
            }
            if (now - track.lastMovementTimeMs > SPEED_RESET_TIMEOUT_MS) {
                stale.add(id)
            }
        }
        stale.forEach { trackedObjects.remove(it) }

        // Highlight the fastest currently-moving object in the contour overlay.
        val primary = trackedObjects.values.maxByOrNull { it.smoothedSpeed }
        cameraManager.setTargetContourIndex(primary?.contourIndex ?: -1)
    }

    /** A current detection paired with its frame-to-frame displacement. */
    private data class MovingDetection(
        val obj: ImageProcessor.DetectedObject,
        val displacement: Double
    )

    /**
     * Return every current object whose displacement from its nearest previous
     * counterpart exceeds the background (median) motion by the threshold.
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

    /** Accumulate displacement/speed for one matched track against its object. */
    private fun updateTrack(track: TrackedObject, obj: ImageProcessor.DetectedObject, now: Long) {
        val displacement = distance(track.lastPosition.x, track.lastPosition.y, obj.center.x, obj.center.y)
        track.contourIndex = obj.contourIndex
        track.lastPosition = obj.center
        track.lastFrameTimeMs = now
        if (displacement in MOVING_THRESHOLD_PX..MAX_DISPLACEMENT_PX) {
            val timeDelta = now - track.lastMovementTimeMs
            val instantSpeed = if (timeDelta > 0) displacement / (timeDelta / 1000.0) else 0.0
            track.smoothedSpeed = smoothSpeed(track.smoothedSpeed, instantSpeed)
            track.totalDistance += displacement
            track.lastMovementTimeMs = now
        }
    }

    /**
     * Fold all live tracks into the aggregate speed stats shown in the top
     * overlay: current speed is the fastest active object, distance is summed.
     */
    private fun updateAggregateSpeedState(now: Long) {
        if (trackedObjects.isEmpty()) {
            if (_uiState.value.isTracking) {
                _uiState.update { it.copy(isTracking = false, currentSpeed = 0.0) }
            }
            return
        }

        val fastest = trackedObjects.values.maxOf { it.smoothedSpeed }
        val totalDistance = trackedObjects.values.sumOf { it.totalDistance }
        val elapsed = if (trackingStartTimeMs > 0L) (now - trackingStartTimeMs) / 1000.0 else 0.0

        _uiState.update {
            it.copy(
                isTracking = true,
                currentSpeed = fastest,
                maxSpeed = maxOf(it.maxSpeed, fastest),
                totalDistance = totalDistance,
                elapsedSeconds = elapsed
            )
        }
    }

    fun stopTracking() {
        trackedObjects.clear()
        trackingStartTimeMs = 0L
        _uiState.update { it.copy(isTracking = false, currentSpeed = 0.0) }
    }

    fun stopTrackingAndSave() {
        trackedObjects.clear()
        trackingStartTimeMs = 0L
        _uiState.update { it.copy(isTracking = false, currentSpeed = 0.0, showLabelDialog = true) }
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

    fun saveSpeedMeasurement(label: String) {
        val state = _uiState.value
        val avgSpeed = if (state.elapsedSeconds > 0.0) {
            state.totalDistance / state.elapsedSeconds
        } else {
            state.maxSpeed
        }

        val measurement = SpeedMeasurement(
            objectLabel = label.ifBlank { "Unnamed" },
            maxSpeedMps = state.maxSpeed,
            avgSpeedMps = avgSpeed,
            distanceMeters = state.totalDistance,
            durationSeconds = state.elapsedSeconds,
            imagePath = null,
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            speedRepository.saveMeasurement(measurement)
            _uiState.update {
                it.copy(
                    showLabelDialog = false,
                    isTracking = false,
                    currentSpeed = 0.0,
                    maxSpeed = 0.0,
                    totalDistance = 0.0,
                    elapsedSeconds = 0.0
                )
            }
        }
    }

    /** Save the completed measurement for whichever mode is active. */
    fun saveMeasurement(label: String) {
        if (_uiState.value.mode == MeasureMode.SIZE) {
            saveSizeMeasurement(label)
        } else {
            saveSpeedMeasurement(label)
        }
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
        cameraManager.shutdown()
        processingCollectionJob?.cancel()
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
        private const val SPEED_RESET_TIMEOUT_MS = 800L
        /** Motion is ignored for this long after the camera starts, to let the user steady the phone. */
        private const val SETTLE_DELAY_MS = 1500L

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
