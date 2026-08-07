package com.example.areameasure.domain

import kotlin.math.sqrt

/**
 * Pure speed-calculation logic for tracking a moving object across camera frames.
 *
 * Converts pixel displacement to real-world speed using a calibration factor
 * (pixels per meter) and applies exponential moving average smoothing to
 * reduce jitter from detection noise.
 */
object SpeedTracker {

    /** Smoothing factor for the exponential moving average (0..1). Higher = more responsive, less smooth. */
    const val EMA_ALPHA = 0.3

    /**
     * Maximum pixel displacement per frame. Readings above this threshold are
     * treated as tracking jumps (object lost or mis-match) and ignored.
     */
    const val MAX_DISPLACEMENT_PX = 200.0

    /**
     * Calculate instantaneous speed from pixel displacement.
     *
     * @param pixelDisplacement Distance moved in pixels between two frames
     * @param pixelsPerMeter    Calibration: how many pixels equal one meter
     * @param timeDeltaMs       Elapsed time between frames in milliseconds
     * @return Speed in meters per second, or 0 if inputs are invalid
     */
    fun calculateSpeedMps(
        pixelDisplacement: Double,
        pixelsPerMeter: Double,
        timeDeltaMs: Long
    ): Double {
        if (pixelsPerMeter <= 0.0 || timeDeltaMs <= 0L) return 0.0
        val distanceMeters = pixelDisplacement / pixelsPerMeter
        val timeSeconds = timeDeltaMs / 1000.0
        return distanceMeters / timeSeconds
    }

    /**
     * Apply exponential moving average to smooth speed readings.
     *
     * @param previous The previous smoothed value
     * @param current  The new instantaneous reading
     * @return New smoothed value
     */
    fun smoothSpeed(previous: Double, current: Double): Double {
        if (previous == 0.0) return current
        return EMA_ALPHA * current + (1.0 - EMA_ALPHA) * previous
    }

    /**
     * Convert meters per second to kilometers per hour.
     */
    fun toKmh(mps: Double): Double = mps * 3.6

    /**
     * Euclidean distance between two points.
     */
    fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
