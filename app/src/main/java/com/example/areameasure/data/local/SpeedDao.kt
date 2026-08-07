package com.example.areameasure.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedDao {

    @Query("SELECT * FROM speed_measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SpeedEntity>>

    @Query("SELECT * FROM speed_measurements ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<SpeedEntity>

    @Query("SELECT * FROM speed_measurements WHERE id = :id")
    suspend fun getById(id: Long): SpeedEntity?

    @Insert
    suspend fun insert(entity: SpeedEntity): Long

    @Delete
    suspend fun delete(entity: SpeedEntity)

    @Query("DELETE FROM speed_measurements")
    suspend fun deleteAll()
}
