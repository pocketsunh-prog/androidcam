package com.example.areameasure.data.repository

import com.example.areameasure.data.local.RaceDao
import com.example.areameasure.data.local.RaceEntity
import com.example.areameasure.data.model.RaceMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RaceRepository @Inject constructor(
    private val dao: RaceDao
) {
    fun getAllMeasurements(): Flow<List<RaceMeasurement>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getMeasurement(id: Long): RaceMeasurement? =
        dao.getById(id)?.toDomain()

    suspend fun getAllMeasurementsOnce(): List<RaceMeasurement> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun saveMeasurement(measurement: RaceMeasurement): Long =
        dao.insert(RaceEntity.fromDomain(measurement))

    suspend fun deleteMeasurement(measurement: RaceMeasurement) =
        dao.delete(RaceEntity.fromDomain(measurement))

    suspend fun deleteAllMeasurements() = dao.deleteAll()
}
