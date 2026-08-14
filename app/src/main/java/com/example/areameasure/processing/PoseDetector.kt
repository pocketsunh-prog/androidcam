package com.example.areameasure.processing

import android.graphics.PointF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Wraps ML Kit's on-device pose detector and turns each frame into a simple
 * "good / bad running posture" verdict.
 *
 * The verdict is based on torso lean: the angle between the shoulder-mid →
 * hip-mid line and the vertical. A fairly upright torso (a slight forward lean
 * is normal while running) is considered correct; a large lean is flagged as
 * wrong. This is a heuristic — it detects grossly off-balance posture, not
 * every nuance of running form.
 */
class PoseDetector {

    data class Result(
        val hasPose: Boolean,
        val isCorrect: Boolean,
        /** Absolute torso lean from vertical, in degrees (null when no pose). */
        val trunkLeanDegrees: Double?
    )

    /** Torso centre position, used for finish-line crossing detection. */
    data class TorsoResult(
        val hasPose: Boolean,
        /** Torso centre x, normalised to 0..1 across the image width. */
        val centerXNormalized: Float?
    )

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
    )

    /**
     * Runs pose detection synchronously (call on a background thread, e.g. the
     * camera analysis executor) and evaluates the running posture.
     */
    fun detect(image: InputImage): Result {
        return try {
            val pose = Tasks.await(detector.process(image))
            evaluate(pose)
        } catch (e: Exception) {
            Log.e(TAG, "Pose detection failed", e)
            Result(hasPose = false, isCorrect = false, trunkLeanDegrees = null)
        }
    }

    /**
     * Runs pose detection and returns the torso centre x, normalised to the
     * image width (0..1). Used by the race timer to detect a finish-line cross.
     */
    fun detectTorsoCenterX(image: InputImage): TorsoResult {
        return try {
            val pose = Tasks.await(detector.process(image))
            torsoCenter(pose, image.width)
        } catch (e: Exception) {
            Log.e(TAG, "Pose detection failed", e)
            TorsoResult(hasPose = false, centerXNormalized = null)
        }
    }

    fun close() {
        detector.close()
    }

    private fun torsoCenter(pose: Pose, imageWidth: Int): TorsoResult {
        val landmarks = pose.allPoseLandmarks
        val shoulder = midpoint(landmarks, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
        val hip = midpoint(landmarks, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
        if (shoulder == null || hip == null || imageWidth <= 0) {
            return TorsoResult(hasPose = false, centerXNormalized = null)
        }
        val cx = (shoulder.x + hip.x) / 2f
        return TorsoResult(
            hasPose = true,
            centerXNormalized = (cx / imageWidth).coerceIn(0f, 1f)
        )
    }

    private fun evaluate(pose: Pose): Result {
        val landmarks = pose.allPoseLandmarks
        val shoulder = midpoint(landmarks, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
        val hip = midpoint(landmarks, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP)
        if (shoulder == null || hip == null) {
            return Result(hasPose = false, isCorrect = false, trunkLeanDegrees = null)
        }

        val dx = hip.x - shoulder.x
        val dy = hip.y - shoulder.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) {
            return Result(hasPose = false, isCorrect = false, trunkLeanDegrees = null)
        }

        // Angle from vertical. Image y grows downward, so a perfectly vertical
        // torso has dy > 0 and dx = 0 → angle 0. A forward/backward lean tilts dx.
        val leanDegrees = abs(Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())))
        val isCorrect = leanDegrees <= GOOD_LEAN_MAX_DEGREES
        return Result(hasPose = true, isCorrect = isCorrect, trunkLeanDegrees = leanDegrees)
    }

    private fun midpoint(landmarks: List<PoseLandmark>, typeA: Int, typeB: Int): PointF? {
        val a = landmarks.firstOrNull { it.landmarkType == typeA } ?: return null
        val b = landmarks.firstOrNull { it.landmarkType == typeB } ?: return null
        return PointF((a.position.x + b.position.x) / 2f, (a.position.y + b.position.y) / 2f)
    }

    companion object {
        private const val TAG = "PoseDetector"

        /** Torso lean (deg from vertical) still considered good running form. */
        private const val GOOD_LEAN_MAX_DEGREES = 25.0
    }
}
