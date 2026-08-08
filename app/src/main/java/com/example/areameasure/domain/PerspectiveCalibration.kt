package com.example.areameasure.domain

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Perspective-aware (homography) calibration for the SIZE measurement plane.
 *
 * A single scalar "pixels per mm" can't capture perspective tilt or in-plane
 * rotation: if the camera isn't perpendicular to the object, scale varies
 * across the image, and measured width/height follow the image axes rather than
 * the object's real axes. Instead we calibrate with a known rectangle (e.g. a
 * credit card) placed on the surface, map its four image corners to its real
 * dimensions via a homography, and rectify every later measurement through that
 * same transform. Anything resting on the reference plane then measures correctly
 * regardless of how the phone is tilted or rotated.
 */
object PerspectiveCalibration {

    /**
     * A calibrated mapping from image pixels to millimetres on the reference
     * plane. [homography] is a 3x3 perspective transform such that for an image
     * point p, `perspectiveTransform(p, homography)` yields the corresponding
     * point in millimetres on the plane.
     */
    data class PlaneCalibration(
        val homography: Mat,
        val referenceWidthMm: Double,
        val referenceHeightMm: Double
    )

    /**
     * Extract the four corners of a contour's minimum-area rotated rectangle as
     * an ordered [MatOfPoint2f] (ordered by polar angle around the centroid) so
     * caller and callee agree on corner correspondence.
     */
    fun cornersOf(contour: MatOfPoint): MatOfPoint2f {
        val mat2f = MatOfPoint2f(*contour.toArray())
        val rect: RotatedRect = Imgproc.minAreaRect(mat2f)
        mat2f.release()
        val pts = arrayOf(Point(), Point(), Point(), Point())
        rect.points(pts)
        return orderedCorners(MatOfPoint2f(*pts))
    }

    /**
     * Order four corners deterministically by polar angle around their centroid.
     * A homography preserves the cyclic order of a convex quadrilateral, so
     * sorting both the source corners and the destination rectangle the same
     * way guarantees matching correspondence for `getPerspectiveTransform`.
     */
    fun orderedCorners(corners: MatOfPoint2f): MatOfPoint2f {
        val pts = corners.toArray()
        var cx = 0.0
        var cy = 0.0
        for (p in pts) {
            cx += p.x; cy += p.y
        }
        cx /= pts.size
        cy /= pts.size
        val sorted = pts.sortedBy { atan2(it.y - cy, it.x - cx) }.toTypedArray()
        return MatOfPoint2f(*sorted)
    }

    /**
     * Build a [PlaneCalibration] from the four image corners of a reference
     * rectangle of known [widthMm] × [heightMm]. The destination rectangle is
     * anchored at the origin in millimetres.
     */
    fun calibrate(
        imageCorners: MatOfPoint2f,
        widthMm: Double,
        heightMm: Double
    ): PlaneCalibration {
        val src = orderedCorners(imageCorners)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(widthMm, 0.0),
            Point(widthMm, heightMm),
            Point(0.0, heightMm)
        )
        val homography = Imgproc.getPerspectiveTransform(src, dst)
        src.release(); dst.release()
        return PlaneCalibration(homography, widthMm, heightMm)
    }

    /**
     * Measure a detected object's real side lengths (mm) on the calibrated plane
     * by rectifying its four bounding corners. Returns `(longer, shorter)` so the
     * longer edge maps to X — matching the existing width=X / height=Y convention.
     */
    fun measure(
        calibration: PlaneCalibration,
        objectCorners: MatOfPoint2f
    ): Pair<Double, Double> {
        val src = orderedCorners(objectCorners)
        val dst = MatOfPoint2f()
        org.opencv.core.Core.perspectiveTransform(src, dst, calibration.homography)
        val p = dst.toArray()
        src.release(); dst.release()
        // Adjacent rectified corners give the two side lengths in mm.
        val d01 = hypot(p[0].x - p[1].x, p[0].y - p[1].y)
        val d12 = hypot(p[1].x - p[2].x, p[1].y - p[2].y)
        val longer = maxOf(d01, d12)
        val shorter = minOf(d01, d12)
        return Pair(longer, shorter)
    }

    /**
     * Map a single image point to its position in millimetres on the calibrated
     * plane. Used by SPEED mode to convert a tracked object's frame-to-frame
     * displacement from pixels into real-world metres.
     */
    fun transformPoint(calibration: PlaneCalibration, point: Point): Point {
        val src = MatOfPoint2f(point)
        val dst = MatOfPoint2f()
        Core.perspectiveTransform(src, dst, calibration.homography)
        val out = dst.toArray()[0]
        src.release(); dst.release()
        return out
    }
}
