package com.example.areameasure.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.areameasure.domain.SpeedTracker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    @Suppress("UNUSED_PARAMETER") measurementId: Long,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.measurement?.objectLabel ?: "Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.measurement != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Text(
                "Loading...",
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            uiState.measurement?.let { measurement ->
                when (measurement) {
                    is DetailMeasurement.Size -> SizeDetailContent(
                        measurement = measurement.measurement,
                        modifier = Modifier.padding(padding)
                    )
                    is DetailMeasurement.Speed -> SpeedDetailContent(
                        measurement = measurement.measurement,
                        modifier = Modifier.padding(padding)
                    )
                }
            } ?: Text(
                "Measurement not found",
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete measurement?") },
            text = { Text("This will permanently delete this measurement and its image.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMeasurement()
                    showDeleteDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SizeDetailContent(
    measurement: com.example.areameasure.data.model.Measurement,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember {
        SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        AsyncImage(
            model = File(measurement.imagePath),
            contentDescription = measurement.objectLabel,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(16.dp))

        val sym = measurement.unit.symbol
        Text(
            text = "X: ${"%.1f".format(measurement.xValue)} $sym",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFFF5252)
        )
        Text(
            text = "Y: ${"%.1f".format(measurement.yValue)} $sym",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF69F0AE)
        )
        Text(
            text = "Z: ${"%.1f".format(measurement.zValue)} $sym",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF448AFF)
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        DetailRow("Object name", measurement.objectLabel)
        DetailRow("Measured on", dateFormat.format(Date(measurement.timestamp)))
        DetailRow("Unit", measurement.unit.label)
    }
}

@Composable
private fun SpeedDetailContent(
    measurement: com.example.areameasure.data.model.SpeedMeasurement,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember {
        SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
    }
    val maxKmh = SpeedTracker.toKmh(measurement.maxSpeedMps)
    val avgKmh = SpeedTracker.toKmh(measurement.avgSpeedMps)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        measurement.imagePath?.let { path ->
            AsyncImage(
                model = File(path),
                contentDescription = measurement.objectLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "Max Speed",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${"%.1f".format(measurement.maxSpeedMps)} m/s",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFFF5252)
        )
        Text(
            text = "${"%.1f".format(maxKmh)} km/h",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFFF8A80)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Avg Speed",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${"%.1f".format(measurement.avgSpeedMps)} m/s",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF448AFF)
        )
        Text(
            text = "${"%.1f".format(avgKmh)} km/h",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF82B1FF)
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        DetailRow("Object name", measurement.objectLabel)
        DetailRow("Distance", "${"%.2f".format(measurement.distanceMeters)} m")
        DetailRow("Duration", "${"%.1f".format(measurement.durationSeconds)} s")
        DetailRow("Measured on", dateFormat.format(Date(measurement.timestamp)))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
