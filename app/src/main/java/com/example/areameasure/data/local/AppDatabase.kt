package com.example.areameasure.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MeasurementEntity::class, SpeedEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun speedDao(): SpeedDao
}
