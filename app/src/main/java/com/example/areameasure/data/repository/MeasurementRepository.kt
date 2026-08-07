package com.example.areameasure.data.repository

import com.example.areameasure.data.local.MeasurementDao
import com.example.areameasure.data.local.MeasurementEntity
import com.example.areameasure.data.model.Measurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeasurementRepository @Inject constructor(
    private val dao: MeasurementDao
) {
    fun getAllMeasurements(): Flow<List<Measurement>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getMeasurement(id: Long): Measurement? =
        dao.getById(id)?.toDomain()

    suspend fun getAllMeasurementsOnce(): List<Measurement> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun saveMeasurement(measurement: Measurement): Long =
        dao.insert(MeasurementEntity.fromDomain(measurement))

    suspend fun deleteMeasurement(measurement: Measurement) =
        dao.delete(MeasurementEntity.fromDomain(measurement))

    suspend fun deleteAllMeasurements() = dao.deleteAll()
}
