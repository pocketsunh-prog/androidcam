package com.example.areameasure.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.PeopleCountMeasurement
import com.example.areameasure.data.model.RaceMeasurement
import com.example.areameasure.data.model.SpeedMeasurement
import com.example.areameasure.data.repository.MeasurementRepository
import com.example.areameasure.data.repository.PeopleCountRepository
import com.example.areameasure.data.repository.RaceRepository
import com.example.areameasure.data.repository.SpeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    measurementRepository: MeasurementRepository,
    speedRepository: SpeedRepository,
    peopleCountRepository: PeopleCountRepository,
    raceRepository: RaceRepository
) : ViewModel() {

    val measurements: StateFlow<List<Measurement>> = measurementRepository
        .getAllMeasurements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val speedMeasurements: StateFlow<List<SpeedMeasurement>> = speedRepository
        .getAllMeasurements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val peopleMeasurements: StateFlow<List<PeopleCountMeasurement>> = peopleCountRepository
        .getAllMeasurements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val raceMeasurements: StateFlow<List<RaceMeasurement>> = raceRepository
        .getAllMeasurements()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
}
