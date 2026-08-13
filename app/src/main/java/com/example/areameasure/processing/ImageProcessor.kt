package com.example.areameasure.processing

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.TrackerMIL
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenCV-based image processing pipeline.
 *
 * Detects objects in a camera frame, computes their 3D bounding box
 * dimensions (X, Y, Z) in pixel space, draws orientation axes with
 * measurement values, and supports tap-to-select for choosing which
 * object to measure.
 *
 * Pixel-to-real-world conversion is done externally using a calibration
 * factor (pixels per unit) provided by the user.
 */
class ImageProcessor {

    /**
     * A single detected object in the frame.
     *
     * @param contourIndex   Index into the detectedContours list (for tap selection)
     * @param center         Center point in frame pixel coordinates
     * @param pixelWidth     Width of the bounding box in pixels (X axis)
     * @param pixelHeight    Height of the bounding box in pixels (Y axis)
     * @param pixelDepth     Estimated depth in pixels (Z axis, derived from area)
     * @param angle          Orientation angle in degrees (from minAreaRect)
     * @param area           Contour area in square pixels
     * @param contour        The actual contour points (for hit testing)
     */
    data class DetectedObject(
        val contourIndex: Int,
        val center: Point,
        val pixelWidth: Double,
        val pixelHeight: Double,
        val pixelDepth: Double,
        val angle: Double,
        val area: Double,
        val contour: MatOfPoint
    )

    data class ProcessingResult(
        val annotatedMat: Mat,
        val frameWidth: Int,
        val frameHeight: Int,
        val detectedObjects: List<DetectedObject>,
        val selectedIndex: Int,
        /**
         * Hardware capture timestamp of this frame, in nanoseconds. Used for
         * accurate frame-to-frame dt in speed tracking (more stable than wall
         * clock, which jitters under frame drops). 0 when unavailable.
         */
        val frameTimestampNanos: Long = 0L,
        /** Faces detected in this frame (raw-frame pixel coords). Empty unless [detectFaces]. */
        val detectedFaces: List<Rect> = emptyList(),
        /**
         * SPEED mode: frame-to-frame displacement of the tracked target's
         * bounding-box centre, in raw-frame pixels (0 when not tracking).
         */
        val speedDisplacementPx: Double = 0.0,
        /** SPEED mode: the tracked target's current bounding box (raw-frame coords). */
        val speedTrackRect: Rect? = null,
        /** SPEED mode: true while the visual tracker has a lock on the target. */
        val speedTrackActive: Boolean = false
    ) {
        val selectedObject: DetectedObject?
            get() = detectedObjects.getOrNull(selectedIndex)
    }

    /** Minimum contour area (in pixels) to be considered a real object, not noise. */
    var minContourArea: Double = 5000.0

    /** Canny edge thresholds. */
    var cannyThresholdLow: Double = 50.0
    var cannyThresholdHigh: Double = 150.0

    /** Length of the 3D axis lines in pixels. */
    var axisLength: Double = 80.0

    /** Whether to draw the 3D orientation axes on the selected object. */
    var drawAxes: Boolean = true

    /**
     * When true, each frame is also scanned for frontal faces (PEOPLE mode).
     * Detected faces are returned in [ProcessingResult.detectedFaces] and drawn
     * as green rectangles on the annotated frame.
     */
    var detectFaces: Boolean = false

    /** When true (SPEED mode), the user-selected target is tracked with a visual tracker. */
    var speedTrackingEnabled: Boolean = false

    @Volatile
    private var speedTracker: TrackerMIL? = null

    @Volatile
    private var speedTrackRect: Rect? = null

    @Volatile
    private var speedPrevCenter: Point? = null

    /** Seed box set by the UI thread; consumed by the next frame. */
    @Volatile
    private var pendingSpeedSeed: Rect? = null

    /**
     * (Re)initialise the SPEED tracker from a bounding box (raw-frame coords),
     * or pass null to stop tracking and release the tracker.
     */
    fun setSpeedTargetRect(rect: Rect?) {
        pendingSpeedSeed = rect
        if (rect == null) {
            speedTracker = null
            speedTrackRect = null
            speedPrevCenter = null
        }
    }

    /**
     * Process a camera frame.
     *
     * @param inputMat           The camera frame as RGBA Mat.
     * @param selectedTargetIndex User-selected target contour index, or -1 for auto (largest).
     * @param markContourIndices When non-null, only these contour indices are drawn as
     *        overlays (used by SPEED mode so only moving/tracked objects are marked).
     *        When null, every detected contour is drawn.
     */
    fun processFrame(
        inputMat: Mat,
        selectedTargetIndex: Int = -1,
        markContourIndices: Set<Int>? = null
    ): ProcessingResult {
        val frameWidth = inputMat.width()
        val frameHeight = inputMat.height()

        // 1. Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(inputMat, gray, Imgproc.COLOR_RGBA2GRAY)

        // 2. Reduce noise with Gaussian blur
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        // 3. Edge detection
        val edges = Mat()
        Imgproc.Canny(blurred, edges, cannyThresholdLow, cannyThresholdHigh)

        // 4. Dilate to close small gaps in edges
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)

        // 5. Find external contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        // 6. Filter out noise, sort largest first
        val validContours = contours
            .filter { Imgproc.contourArea(it) > minContourArea }
            .sortedByDescending { Imgproc.contourArea(it) }

        if (validContours.isEmpty()) {
            gray.release(); blurred.release(); edges.release(); hierarchy.release(); kernel.release()
            return ProcessingResult(inputMat, frameWidth, frameHeight, emptyList(), -1)
        }

        // 7. Determine which object is selected (user tap or auto largest)
        val selectedIdx = if (selectedTargetIndex in validContours.indices) {
            selectedTargetIndex
        } else {
            0 // largest contour by default
        }

        // 8. Build detected objects list with 3D dimension info
        val detectedObjects = mutableListOf<DetectedObject>()
        validContours.forEachIndexed { index, contour ->
            val moments = Imgproc.moments(contour)
            val cx = if (moments.m00 != 0.0) moments.m10 / moments.m00 else 0.0
            val cy = if (moments.m00 != 0.0) moments.m01 / moments.m00 else 0.0
            val mat2f = MatOfPoint2f(*contour.toArray())
            val minRect = Imgproc.minAreaRect(mat2f)
            mat2f.release()

            val rectWidth = minRect.size.width
            val rectHeight = minRect.size.height
            val area = Imgproc.contourArea(contour)

            // X = longer edge of the rotated rect, Y = shorter edge
            val pixelX = maxOf(rectWidth, rectHeight)
            val pixelY = minOf(rectWidth, rectHeight)
            // Z (depth) estimated from area assuming roughly cubic proportions
            val pixelZ = kotlin.math.sqrt(area / (pixelX / pixelY.coerceAtLeast(1.0)))

            detectedObjects.add(
                DetectedObject(
                    contourIndex = index,
                    center = Point(cx, cy),
                    pixelWidth = pixelX,
                    pixelHeight = pixelY,
                    pixelDepth = pixelZ,
                    angle = minRect.angle,
                    area = area,
                    contour = contour
                )
            )
        }

        // 9. Draw annotations
        val annotated = inputMat.clone()

        detectedObjects.forEach { obj ->
            // SPEED mode supplies an explicit mark set so only moving/tracked
            // objects are outlined; other modes mark every detected contour.
            val marked = markContourIndices?.contains(obj.contourIndex) ?: true
            if (!marked) return@forEach
            // Draw contours: selected = red, others = cyan
            val color = if (obj.contourIndex == selectedIdx) Scalar(255.0, 0.0, 0.0)
                       else Scalar(255.0, 255.0, 0.0)
            val thickness = if (obj.contourIndex == selectedIdx) 4 else 2
            Imgproc.drawContours(annotated, listOf(obj.contour), -1, color, thickness)
        }

        // 10. Draw 3D axes on the selected object with dimension values
        if (drawAxes) {
            detectedObjects.getOrNull(selectedIdx)?.let { obj ->
                draw3DAxes(annotated, obj)
            }
        }

        // 11. Detect faces (PEOPLE mode) and draw them as green rectangles.
        // Runs on the grayscale frame produced in step 1; the detector downscales
        // internally for speed and returns rects in the original frame's coords.
        val faces = if (detectFaces) {
            val detected = com.example.areameasure.processing.FaceDetector.detect(gray)
            detected.forEach { face ->
                Imgproc.rectangle(
                    annotated,
                    Point(face.x.toDouble(), face.y.toDouble()),
                    Point((face.x + face.width).toDouble(), (face.y + face.height).toDouble()),
                    Scalar(0.0, 255.0, 0.0),
                    3
                )
            }
            detected
        } else {
            emptyList()
        }

        // 12. SPEED: track the user-selected target with a MIL visual tracker and
        // measure its frame-to-frame displacement. A tracker bounding-box centre
        // is far more stable than raw contour centroids, so speed reads smoother.
        var speedDisplacementPx = 0.0
        var trackRectOut: Rect? = null
        var speedTrackActive = false
        if (speedTrackingEnabled) {
            val seed = pendingSpeedSeed
            if (seed != null) {
                pendingSpeedSeed = null
                speedTracker = TrackerMIL.create()
                speedTracker?.init(gray, seed)
                speedTrackRect = seed
                speedPrevCenter = centerOf(seed)
                trackRectOut = seed
                speedTrackActive = true
            } else {
                val tracker = speedTracker
                val rect = speedTrackRect
                if (tracker != null && rect != null) {
                    val updated = Rect(rect.x, rect.y, rect.width, rect.height)
                    if (tracker.update(gray, updated)) {
                        val c = centerOf(updated)
                        val prev = speedPrevCenter
                        if (prev != null) {
                            val dx = c.x - prev.x
                            val dy = c.y - prev.y
                            speedDisplacementPx = sqrt(dx * dx + dy * dy)
                        }
                        speedPrevCenter = c
                        speedTrackRect = updated
                        trackRectOut = updated
                        speedTrackActive = true
                    }
                }
            }

            // Draw the tracked box in amber so the user sees what is measured.
            trackRectOut?.let { r ->
                Imgproc.rectangle(
                    annotated,
                    Point(r.x.toDouble(), r.y.toDouble()),
                    Point((r.x + r.width).toDouble(), (r.y + r.height).toDouble()),
                    Scalar(255.0, 213.0, 79.0),
                    3
                )
            }
        }

        // Release intermediate mats
        gray.release(); blurred.release(); edges.release(); hierarchy.release(); kernel.release()

        return ProcessingResult(
            annotatedMat = annotated,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            detectedObjects = detectedObjects,
            selectedIndex = selectedIdx,
            detectedFaces = faces,
            speedDisplacementPx = speedDisplacementPx,
            speedTrackRect = trackRectOut,
            speedTrackActive = speedTrackActive
        )
    }

    private fun centerOf(r: Rect): Point =
        Point(r.x + r.width / 2.0, r.y + r.height / 2.0)

    /**
     * Draw 3D orientation axes (X, Y, Z) on a detected object.
     *
     * X axis = red, Y axis = green, Z axis = blue.
     * The X-Y plane follows the object's 2D orientation (from minAreaRect).
     * The Z axis is drawn perpendicular to the plane (pointing "up").
     */
    private fun draw3DAxes(mat: Mat, obj: DetectedObject) {
        val center = obj.center
        val angleRad = Math.toRadians(obj.angle)
        val len = axisLength

        // X axis (red) - along the object's orientation
        val xEnd = Point(center.x + len * cos(angleRad), center.y + len * sin(angleRad))
        Imgproc.line(mat, center, xEnd, Scalar(255.0, 0.0, 0.0), 3, Imgproc.LINE_AA, 0)

        // Y axis (green) - perpendicular to X in the plane
        val yEnd = Point(center.x - len * sin(angleRad), center.y + len * cos(angleRad))
        Imgproc.line(mat, center, yEnd, Scalar(0.0, 255.0, 0.0), 3, Imgproc.LINE_AA, 0)

        // Z axis (blue) - pointing "up" from the plane
        val zEnd = Point(center.x, center.y - len * 0.7)
        Imgproc.line(mat, center, zEnd, Scalar(0.0, 0.0, 255.0), 3, Imgproc.LINE_AA, 0)

        // Axis labels with dimension values
        val labelOffset = len + 18
        Imgproc.putText(
            mat, "X",
            Point(center.x + labelOffset * cos(angleRad) - 8, center.y + labelOffset * sin(angleRad) + 8),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, Scalar(255.0, 0.0, 0.0), 2
        )
        Imgproc.putText(
            mat, "Y",
            Point(center.x - labelOffset * sin(angleRad) - 8, center.y + labelOffset * cos(angleRad) + 8),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, Scalar(0.0, 255.0, 0.0), 2
        )
        Imgproc.putText(
            mat, "Z",
            Point(center.x + 8, center.y - labelOffset * 0.7),
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, Scalar(0.0, 0.0, 255.0), 2
        )

        // Center dot
        Imgproc.circle(mat, center, 5, Scalar(255.0, 255.0, 255.0), -1)
    }
}
