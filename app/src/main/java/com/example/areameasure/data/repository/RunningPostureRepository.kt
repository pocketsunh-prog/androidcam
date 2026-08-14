package com.example.areameasure.data.repository

import com.example.areameasure.data.local.RunningPostureDao
import com.example.areameasure.data.local.RunningPostureEntity
import com.example.areameasure.data.model.RunningPostureMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunningPostureRepository @Inject constructor(
    private val dao: RunningPostureDao
) {
    fun getAllMeasurements(): Flow<List<RunningPostureMeasurement>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getMeasurement(id: Long): RunningPostureMeasurement? =
        dao.getById(id)?.toDomain()

    suspend fun getAllMeasurementsOnce(): List<RunningPostureMeasurement> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun saveMeasurement(measurement: RunningPostureMeasurement): Long =
        dao.insert(RunningPostureEntity.fromDomain(measurement))

    suspend fun deleteMeasurement(measurement: RunningPostureMeasurement) =
        dao.delete(RunningPostureEntity.fromDomain(measurement))

    suspend fun deleteAllMeasurements() = dao.deleteAll()
}
