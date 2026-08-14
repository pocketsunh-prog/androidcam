package com.example.areameasure.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RaceDao {

    @Query("SELECT * FROM race_measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<RaceEntity>>

    @Query("SELECT * FROM race_measurements ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<RaceEntity>

    @Query("SELECT * FROM race_measurements WHERE id = :id")
    suspend fun getById(id: Long): RaceEntity?

    @Insert
    suspend fun insert(entity: RaceEntity): Long

    @Delete
    suspend fun delete(entity: RaceEntity)

    @Query("DELETE FROM race_measurements")
    suspend fun deleteAll()
}
