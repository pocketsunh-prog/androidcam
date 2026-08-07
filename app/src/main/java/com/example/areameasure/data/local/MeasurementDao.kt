package com.example.areameasure.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun getById(id: Long): MeasurementEntity?

    @Insert
    suspend fun insert(entity: MeasurementEntity): Long

    @Delete
    suspend fun delete(entity: MeasurementEntity)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
}
