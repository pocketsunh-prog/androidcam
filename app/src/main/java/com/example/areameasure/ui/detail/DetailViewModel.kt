package com.example.areameasure.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.SpeedMeasurement
import com.example.areameasure.data.repository.MeasurementRepository
import com.example.areameasure.data.repository.SpeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The kind of measurement being viewed, chosen via the detail route. */
enum class DetailType {
    SIZE,
    SPEED;

    companion object {
        fun fromRoute(value: String?): DetailType =
            if (value == "speed") SPEED else SIZE
    }
}

/** A detail measurement: either a 3D size reading or a speed tracking session. */
sealed interface DetailMeasurement {
    val id: Long
    val objectLabel: String
    val timestamp: Long

    data class Size(
        val measurement: Measurement
    ) : DetailMeasurement {
        override val id: Long get() = measurement.id
        override val objectLabel: String get() = measurement.objectLabel
        override val timestamp: Long get() = measurement.timestamp
    }

    data class Speed(
        val measurement: SpeedMeasurement
    ) : DetailMeasurement {
        override val id: Long get() = measurement.id
        override val objectLabel: String get() = measurement.objectLabel
        override val timestamp: Long get() = measurement.timestamp
    }
}

data class DetailUiState(
    val type: DetailType = DetailType.SIZE,
    val measurement: DetailMeasurement? = null,
    val isLoading: Boolean = true,
    val deleted: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val measurementRepository: MeasurementRepository,
    private val speedRepository: SpeedRepository
) : ViewModel() {

    private val type: DetailType =
        DetailType.fromRoute(savedStateHandle.get<String>("type"))
    private val measurementId: Long = savedStateHandle.get<Long>("measurementId") ?: 0L

    private val _uiState = MutableStateFlow(DetailUiState(type = type))
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        loadMeasurement()
    }

    private fun loadMeasurement() {
        viewModelScope.launch {
            val measurement: DetailMeasurement? = when (type) {
                DetailType.SIZE -> measurementRepository.getMeasurement(measurementId)
                    ?.let { DetailMeasurement.Size(it) }
                DetailType.SPEED -> speedRepository.getMeasurement(measurementId)
                    ?.let { DetailMeasurement.Speed(it) }
            }
            _uiState.update {
                it.copy(measurement = measurement, isLoading = false)
            }
        }
    }

    fun deleteMeasurement() {
        val current = _uiState.value.measurement ?: return
        viewModelScope.launch {
            when (current) {
                is DetailMeasurement.Size -> {
                    measurementRepository.deleteMeasurement(current.measurement)
                    try {
                        java.io.File(current.measurement.imagePath).delete()
                    } catch (_: Exception) {
                    }
                }
                is DetailMeasurement.Speed -> {
                    speedRepository.deleteMeasurement(current.measurement)
                    current.measurement.imagePath?.let { path ->
                        try {
                            java.io.File(path).delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            _uiState.update { it.copy(deleted = true) }
        }
    }
}
