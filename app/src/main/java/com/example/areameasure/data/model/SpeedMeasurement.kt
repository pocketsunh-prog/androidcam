package com.example.areameasure.data.model

/**
 * Domain model representing a single saved speed measurement.
 *
 * @param id              Auto-generated primary key
 * @param objectLabel     User-given name for the measured object
 * @param maxSpeedMps     Maximum speed observed during tracking (meters/second)
 * @param avgSpeedMps     Average speed over the tracking session (meters/second)
 * @param distanceMeters  Total real-world distance traveled (meters)
 * @param durationSeconds Total tracking duration (seconds)
 * @param imagePath       Path to the captured snapshot, or null
 * @param timestamp       When the measurement was saved
 */
data class SpeedMeasurement(
    val id: Long = 0,
    val objectLabel: String,
    val maxSpeedMps: Double,
    val avgSpeedMps: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val imagePath: String?,
    val timestamp: Long
)
