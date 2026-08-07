package com.example.areameasure.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.areameasure.data.model.SpeedMeasurement

@Entity(tableName = "speed_measurements")
data class SpeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectLabel: String,
    val maxSpeedMps: Double,
    val avgSpeedMps: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val imagePath: String?,
    val timestamp: Long
) {
    fun toDomain(): SpeedMeasurement = SpeedMeasurement(
        id = id,
        objectLabel = objectLabel,
        maxSpeedMps = maxSpeedMps,
        avgSpeedMps = avgSpeedMps,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        imagePath = imagePath,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(measurement: SpeedMeasurement): SpeedEntity = SpeedEntity(
            id = measurement.id,
            objectLabel = measurement.objectLabel,
            maxSpeedMps = measurement.maxSpeedMps,
            avgSpeedMps = measurement.avgSpeedMps,
            distanceMeters = measurement.distanceMeters,
            durationSeconds = measurement.durationSeconds,
            imagePath = measurement.imagePath,
            timestamp = measurement.timestamp
        )
    }
}
