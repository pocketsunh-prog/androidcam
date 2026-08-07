package com.example.areameasure.data.model

/**
 * Domain model representing a single saved 3D measurement.
 *
 * @param xValue Length along X axis (width)
 * @param yValue Length along Y axis (height)
 * @param zValue Length along Z axis (depth)
 */
data class Measurement(
    val id: Long = 0,
    val objectLabel: String,
    val xValue: Double,
    val yValue: Double,
    val zValue: Double,
    val unit: UnitOfMeasure,
    val imagePath: String,
    val timestamp: Long
)
