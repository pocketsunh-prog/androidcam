package com.example.areameasure.data.model

/**
 * A saved running-time race result.
 *
 * @param distanceMeters Race distance (e.g. 100, 200, 400, 800 m or custom).
 * @param timeMs         Time from start to finish-line crossing, in milliseconds.
 * @param imagePath      Auto-captured photo of the finish, or null.
 * @param timestamp      When the result was saved.
 */
data class RaceMeasurement(
    val id: Long = 0,
    val distanceMeters: Int,
    val timeMs: Long,
    val imagePath: String?,
    val timestamp: Long
)
