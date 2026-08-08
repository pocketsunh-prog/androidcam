package com.example.areameasure.data.repository

import com.example.areameasure.data.local.PeopleCountDao
import com.example.areameasure.data.local.PeopleCountEntity
import com.example.areameasure.data.model.PeopleCountMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeopleCountRepository @Inject constructor(
    private val dao: PeopleCountDao
) {
    fun getAllMeasurements(): Flow<List<PeopleCountMeasurement>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getMeasurement(id: Long): PeopleCountMeasurement? =
        dao.getById(id)?.toDomain()

    suspend fun getAllMeasurementsOnce(): List<PeopleCountMeasurement> =
        dao.getAllOnce().map { it.toDomain() }

    suspend fun saveMeasurement(measurement: PeopleCountMeasurement): Long =
        dao.insert(PeopleCountEntity.fromDomain(measurement))

    suspend fun deleteMeasurement(measurement: PeopleCountMeasurement) =
        dao.delete(PeopleCountEntity.fromDomain(measurement))

    suspend fun deleteAllMeasurements() = dao.deleteAll()
}
