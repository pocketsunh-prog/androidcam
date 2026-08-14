package com.example.areameasure.data.model

import com.example.areameasure.domain.RunType

/**
 * A saved running-posture capture.
 *
 * @param runType           Long-distance or sprint.
 * @param isCorrectPosture  True = good form (yellow), false = bad form (red).
 * @param trunkLeanDegrees  Estimated forward trunk lean in degrees (positive = lean forward).
 * @param imagePath         Saved photo snapshot, or null.
 * @param videoPath         Saved short video clip, or null.
 * @param timestamp         When the capture was saved.
 */
data class RunningPostureMeasurement(
    val id: Long = 0,
    val runType: RunType,
    val isCorrectPosture: Boolean,
    val trunkLeanDegrees: Double,
    val imagePath: String?,
    val videoPath: String?,
    val timestamp: Long
)
