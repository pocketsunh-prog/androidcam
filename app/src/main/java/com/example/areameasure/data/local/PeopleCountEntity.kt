package com.example.areameasure.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.areameasure.data.model.PeopleCountMeasurement

@Entity(tableName = "people_counts")
data class PeopleCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val count: Int,
    val imagePath: String?,
    val timestamp: Long
) {
    fun toDomain(): PeopleCountMeasurement = PeopleCountMeasurement(
        id = id,
        count = count,
        imagePath = imagePath,
        timestamp = timestamp
    )

    companion object {
        fun fromDomain(measurement: PeopleCountMeasurement): PeopleCountEntity = PeopleCountEntity(
            id = measurement.id,
            count = measurement.count,
            imagePath = measurement.imagePath,
            timestamp = measurement.timestamp
        )
    }
}
