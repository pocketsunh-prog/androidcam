package com.example.areameasure.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeopleCountDao {

    @Query("SELECT * FROM people_counts ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PeopleCountEntity>>

    @Query("SELECT * FROM people_counts ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<PeopleCountEntity>

    @Query("SELECT * FROM people_counts WHERE id = :id")
    suspend fun getById(id: Long): PeopleCountEntity?

    @Insert
    suspend fun insert(entity: PeopleCountEntity): Long

    @Delete
    suspend fun delete(entity: PeopleCountEntity)

    @Query("DELETE FROM people_counts")
    suspend fun deleteAll()
}
