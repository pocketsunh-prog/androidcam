package com.example.areameasure.processing

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

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
        val frameTimestampNanos: Long = 0L
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
     * Process a camera frame.
     *
     * @param inputMat           The camera frame as RGBA Mat.
     * @param selectedTargetIndex User-selected target contour index, or -1 for auto (largest).
     */
    fun processFrame(
        inputMat: Mat,
        selectedTargetIndex: Int = -1
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

        // Release intermediate mats
        gray.release(); blurred.release(); edges.release(); hierarchy.release(); kernel.release()

        return ProcessingResult(
            annotatedMat = annotated,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            detectedObjects = detectedObjects,
            selectedIndex = selectedIdx
        )
    }

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
