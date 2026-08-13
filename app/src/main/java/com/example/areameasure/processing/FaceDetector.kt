package com.example.areameasure.processing

import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.objdetect.CascadeClassifier
import java.io.File

/**
 * Wraps an OpenCV [CascadeClassifier] to detect frontal faces in a grayscale
 * frame.
 *
 * The Haar cascade XML shipped with the OpenCV SDK is NOT packaged into the APK
 * (the :opencv module only bundles java/src, java/res, native/libs). So the XML
 * is shipped as an app asset and materialized to internal storage at startup
 * ([init]) before the classifier is constructed — [CascadeClassifier] needs a
 * real filesystem path, not an asset stream.
 *
 * [init] is idempotent and must be called once (from the [Application]) before
 * [detect] is used.
 */
object FaceDetector {

    private const val TAG = "FaceDetector"
    private const val ASSET_NAME = "haarcascade_frontalface_default.xml"
    private const val CACHED_FILE_NAME = "haarcascade_frontalface_default.xml"

    /** Smallest face side (px) to detect, as a fraction of the frame min side. */
    private const val MIN_FACE_FRACTION = 0.05

    /**
     * Cascade pyramid scale factor. A finer pyramid (lower value) catches
     * smaller / more distant faces at the cost of more classifier evaluations.
     */
    private const val SCALE_FACTOR = 1.05

    /** IoU threshold above which two detections are considered the same face. */
    private const val NMS_IOU_THRESHOLD = 0.3

    @Volatile
    private var classifier: CascadeClassifier? = null

    /** True once the classifier has been loaded from the materialized asset. */
    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * Materialize the cascade asset to internal storage and load the classifier.
     * Safe to call multiple times — subsequent calls are a no-op once initialized.
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val cascadeFile = File(context.filesDir, CACHED_FILE_NAME)
                if (!cascadeFile.exists()) {
                    context.assets.open(ASSET_NAME).use { input ->
                        cascadeFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val cc = CascadeClassifier(cascadeFile.absolutePath)
                if (cc.empty()) {
                    Log.e(TAG, "CascadeClassifier loaded but is empty — detection will not work")
                    return
                }
                classifier = cc
                isInitialized = true
                Log.d(TAG, "FaceDetector initialized from ${cascadeFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FaceDetector", e)
            }
        }
    }

    /**
     * Detect frontal faces in a grayscale frame.
     *
     * For performance the frame is downscaled so its smaller side is ~480px
     * before classification; the returned rectangles are scaled back to the
     * original frame's coordinate space.
     *
     * @return Face rectangles in the original frame's pixel coordinates. Empty
     *         when [init] has not been called or the classifier failed to load.
     */
    fun detect(gray: Mat): List<Rect> {
        val cc = classifier ?: return emptyList()
        if (!isInitialized) return emptyList()

        return try {
            val minSide = minOf(gray.width(), gray.height())
            val targetSmallSide = 480.0
            val scale = if (minSide > targetSmallSide) targetSmallSide / minSide else 1.0

            val detectionMat: Mat
            val detectionMinSide: Int
            if (scale < 1.0) {
                val small = Mat()
                ImgprocResize(gray, small, scale)
                detectionMat = small
                detectionMinSide = minOf(small.width(), small.height())
            } else {
                detectionMat = gray
                detectionMinSide = minSide
            }

            // minSize must be relative to the image actually passed to the
            // classifier. Computing it from the original frame while classifying
            // a downscaled image made the minimum face too large and missed
            // distant/small faces.
            val minFaceSide = (detectionMinSide * MIN_FACE_FRACTION).coerceAtLeast(20.0)
            val rawFaces = detectAtScale(cc, detectionMat, minFaceSide)
            if (scale < 1.0) detectionMat.release()

            // Haar often fires several overlapping boxes on one face; merge them
            // so the count reflects distinct faces, not duplicate detections.
            val faces = nonMaxSuppression(rawFaces)

            if (scale < 1.0) {
                // Map rectangles back to the original frame coordinates.
                val inv = 1.0 / scale
                faces.map { r ->
                    Rect(
                        (r.x * inv).toInt(),
                        (r.y * inv).toInt(),
                        (r.width * inv).toInt(),
                        (r.height * inv).toInt()
                    )
                }
            } else {
                faces
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            emptyList()
        }
    }

    private fun detectAtScale(cc: CascadeClassifier, gray: Mat, minSide: Double): List<Rect> {
        val faces = MatOfRect()
        cc.detectMultiScale(
            gray,
            faces,
            /* scaleFactor = */ SCALE_FACTOR,
            /* minNeighbors = */ 4,
            /* flags = */ 0,
            /* minSize = */ Size(minSide, minSide),
            /* maxSize = */ Size()
        )
        val result = faces.toList()
        faces.release()
        return result
    }

    /** Greedy IoU-based non-maximum suppression, largest boxes first. */
    private fun nonMaxSuppression(rects: List<Rect>): List<Rect> {
        if (rects.size <= 1) return rects

        val sorted = rects.sortedByDescending { it.width.toLong() * it.height }
        val kept = mutableListOf<Rect>()
        for (r in sorted) {
            val overlaps = kept.any { intersectionOverUnion(r, it) > NMS_IOU_THRESHOLD }
            if (!overlaps) kept.add(r)
        }
        return kept
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Double {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.width, b.x + b.width)
        val y2 = minOf(a.y + a.height, b.y + b.height)
        val interW = (x2 - x1).coerceAtLeast(0)
        val interH = (y2 - y1).coerceAtLeast(0)
        val inter = interW.toLong() * interH
        val areaA = a.width.toLong() * a.height
        val areaB = b.width.toLong() * b.height
        val union = areaA + areaB - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    private fun ImgprocResize(src: Mat, dst: Mat, scale: Double) {
        org.opencv.imgproc.Imgproc.resize(
            src, dst, org.opencv.core.Size(), scale, scale,
            org.opencv.imgproc.Imgproc.INTER_LINEAR
        )
    }
}
