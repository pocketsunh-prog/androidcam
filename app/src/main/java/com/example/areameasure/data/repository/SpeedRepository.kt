package com.example.areameasure.data.repository

import com.example.areameasure.data.local.SpeedDao
import com.example.areameasure.data.local.SpeedEntity
import com.example.areameasure.data.model.SpeedMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeedRepository @Inject constructor(
    private val dao: SpeedDao
) {
    fun getAllMeasurements(): Flow<List<SpeedMeasurement>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getMeasurement(id: Long): SpeedMeasurement? =
        dao.getById(id)?.toDomain()

    suspend fun getAllMeasurementsOnce(): List<SpeedMeasurement> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun saveMeasurement(measurement: SpeedMeasurement): Long =
        dao.insert(SpeedEntity.fromDomain(measurement))

    suspend fun deleteMeasurement(measurement: SpeedMeasurement) =
        dao.delete(SpeedEntity.fromDomain(measurement))

    suspend fun deleteAllMeasurements() = dao.deleteAll()
}
