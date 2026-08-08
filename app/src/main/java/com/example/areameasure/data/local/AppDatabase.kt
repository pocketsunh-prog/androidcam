package com.example.areameasure.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MeasurementEntity::class, SpeedEntity::class, PeopleCountEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun speedDao(): SpeedDao
    abstract fun peopleCountDao(): PeopleCountDao
}
