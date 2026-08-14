package com.example.areameasure.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.areameasure.data.model.RunningPostureMeasurement
import com.example.areameasure.domain.RunType

@Entity(tableName = "running_posture_measurements")
data class RunningPostureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runType: String,
    val isCorrectPosture: Boolean,
    val trunkLeanDegrees: Double,
    val imagePath: String?,
    val videoPath: String?,
    val timestamp: Long
) {
    fun toDomain(): RunningPostureMeasurement = RunningPostureMeasurement(
        id = id,
        runType = RunType.fromName(runType),
        isCorrectPosture = isCorrectPosture,
        trunkLeanDegrees = trunkLeanDegrees,
        imagePath = imagePath,
        videoPath = videoPath,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(measurement: RunningPostureMeasurement): RunningPostureEntity =
            RunningPostureEntity(
                id = measurement.id,
                runType = measurement.runType.name,
                isCorrectPosture = measurement.isCorrectPosture,
                trunkLeanDegrees = measurement.trunkLeanDegrees,
                imagePath = measurement.imagePath,
                videoPath = measurement.videoPath,
                timestamp = measurement.timestamp
            )
    }
}
