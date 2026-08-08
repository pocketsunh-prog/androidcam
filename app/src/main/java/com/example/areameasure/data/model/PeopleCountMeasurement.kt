package com.example.areameasure.data.model

/**
 * Domain model representing a single saved people-count snapshot.
 *
 * @param id        Auto-generated primary key
 * @param count     Number of faces detected in this snapshot
 * @param imagePath Path to a captured snapshot image, or null (PEOPLE mode does
 *                  not bind ImageCapture, so snapshots are saved without an image)
 * @param timestamp When the snapshot was saved
 */
data class PeopleCountMeasurement(
    val id: Long = 0,
    val count: Int,
    val imagePath: String?,
    val timestamp: Long
)
