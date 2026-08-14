package com.example.areameasure.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RunningPostureDao {

    @Query("SELECT * FROM running_posture_measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<RunningPostureEntity>>

    @Query("SELECT * FROM running_posture_measurements ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<RunningPostureEntity>

    @Query("SELECT * FROM running_posture_measurements WHERE id = :id")
    suspend fun getById(id: Long): RunningPostureEntity?

    @Insert
    suspend fun insert(entity: RunningPostureEntity): Long

    @Delete
    suspend fun delete(entity: RunningPostureEntity)

    @Query("DELETE FROM running_posture_measurements")
    suspend fun deleteAll()
}
