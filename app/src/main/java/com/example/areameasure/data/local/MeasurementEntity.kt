package com.example.areameasure.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.UnitOfMeasure

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectLabel: String,
    val xValue: Double,
    val yValue: Double,
    val zValue: Double,
    val unit: String,
    val imagePath: String,
    val timestamp: Long
) {
    fun toDomain(): Measurement = Measurement(
        id = id,
        objectLabel = objectLabel,
        xValue = xValue,
        yValue = yValue,
        zValue = zValue,
        unit = UnitOfMeasure.fromSymbol(unit),
        imagePath = imagePath,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(measurement: Measurement): MeasurementEntity = MeasurementEntity(
            id = measurement.id,
            objectLabel = measurement.objectLabel,
            xValue = measurement.xValue,
            yValue = measurement.yValue,
            zValue = measurement.zValue,
            unit = measurement.unit.symbol,
            imagePath = measurement.imagePath,
            timestamp = measurement.timestamp
        )
    }
}
