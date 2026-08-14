package com.example.areameasure.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.areameasure.data.model.RaceMeasurement

@Entity(tableName = "race_measurements")
data class RaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val distanceMeters: Int,
    val timeMs: Long,
    val imagePath: String?,
    val timestamp: Long
) {
    fun toDomain(): RaceMeasurement = RaceMeasurement(
        id = id,
        distanceMeters = distanceMeters,
        timeMs = timeMs,
        imagePath = imagePath,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(measurement: RaceMeasurement): RaceEntity = RaceEntity(
            id = measurement.id,
            distanceMeters = measurement.distanceMeters,
            timeMs = measurement.timeMs,
            imagePath = measurement.imagePath,
            timestamp = measurement.timestamp
        )
    }
}
