package com.example.areameasure.processing

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfRect
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.HOGDescriptor
import java.io.File
import kotlin.math.abs

/**
 * Draws race-result labels above detected runners in the auto-captured finish
 * photo. Uses OpenCV's built-in HOG people detector to find every runner, then
 * labels the timed runner (closest to the horizontal centre) with the time and
 * the others with "runner". Returns the annotated photo path, or null if no
 * runners were found (in which case the original photo is kept).
 */
object RunnerPhotoAnnotator {

    private const val TAG = "RunnerPhotoAnnotator"

    fun annotate(photoPath: String, timeText: String): String? {
        return try {
            val src = Imgcodecs.imread(photoPath)
            if (src.empty()) {
                src.release()
                return null
            }

            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

            // Detect on a downscaled copy for speed, then scale boxes back up.
            val longest = maxOf(src.cols(), src.rows())
            val scale = if (longest > MAX_SIDE) MAX_SIDE.toDouble() / longest else 1.0
            val detectMat = if (scale < 1.0) {
                val small = Mat()
                Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
                gray.release()
                small
            } else {
                gray
            }

            val hog = HOGDescriptor()
            val svm = HOGDescriptor.getDefaultPeopleDetector()
            hog.setSVMDetector(svm)
            svm.release()

            val found = MatOfRect()
            val weights = MatOfDouble()
            hog.detectMultiScale(
                detectMat, found, weights,
                /* hitThreshold = */ 0.0,
                /* winStride = */ Size(8.0, 8.0),
                /* padding = */ Size(32.0, 32.0),
                /* scale = */ 1.05,
                /* groupThreshold = */ 2.0,
                /* useMeanshiftGrouping = */ false
            )

            val rects = found.toArray().toMutableList()
            found.release(); weights.release(); detectMat.release()

            if (rects.isEmpty()) {
                src.release()
                return null
            }

            // Scale boxes back to the original image size.
            if (scale < 1.0) {
                val inv = 1.0 / scale
                rects.replaceAll {
                    Rect(
                        (it.x * inv).toInt(),
                        (it.y * inv).toInt(),
                        (it.width * inv).toInt(),
                        (it.height * inv).toInt()
                    )
                }
            }

            val centerX = src.cols() / 2.0
            val timed = rects.minByOrNull { abs(it.x + it.width / 2.0 - centerX) }

            for (r in rects) {
                val label = if (r == timed) timeText else "runner"
                drawLabel(src, r, label, r == timed)
            }

            val outPath = photoPath.removeSuffix(".jpg") + "_annotated.jpg"
            val ok = Imgcodecs.imwrite(outPath, src)
            src.release()

            if (ok && File(outPath).exists()) outPath else null
        } catch (e: Exception) {
            Log.e(TAG, "Photo annotation failed", e)
            null
        }
    }

    private fun drawLabel(img: Mat, r: Rect, text: String, isTimed: Boolean) {
        val barColor = if (isTimed) Scalar(0.0, 255.0, 255.0) else Scalar(0.0, 255.0, 0.0) // BGR
        val top = (r.y - 26).coerceAtLeast(0)

        Imgproc.rectangle(
            img,
            Point(r.x.toDouble(), top.toDouble()),
            Point((r.x + r.width).toDouble(), r.y.toDouble()),
            barColor,
            -1
        )
        Imgproc.putText(
            img,
            text,
            Point(r.x + 4.0, (r.y - 6).toDouble()),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            0.6,
            Scalar(0.0, 0.0, 0.0),
            2,
            Imgproc.LINE_AA,
            false
        )
    }

    private const val MAX_SIDE = 640
}
