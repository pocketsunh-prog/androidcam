package com.example.areameasure.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.areameasure.data.model.Measurement
import com.example.areameasure.data.model.PeopleCountMeasurement
import com.example.areameasure.data.model.RaceMeasurement
import com.example.areameasure.data.model.SpeedMeasurement
import com.example.areameasure.domain.SpeedTracker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToDetail: (type: String, id: Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val measurements by viewModel.measurements.collectAsState()
    val speedMeasurements by viewModel.speedMeasurements.collectAsState()
    val peopleMeasurements by viewModel.peopleMeasurements.collectAsState()
    val raceMeasurements by viewModel.raceMeasurements.collectAsState()
    val selectedTab = remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab.intValue) {
                Tab(
                    selected = selectedTab.intValue == 0,
                    onClick = { selectedTab.intValue = 0 },
                    text = { Text("Size") },
                    icon = { Icon(Icons.Default.SquareFoot, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab.intValue == 1,
                    onClick = { selectedTab.intValue = 1 },
                    text = { Text("Speed") },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab.intValue == 2,
                    onClick = { selectedTab.intValue = 2 },
                    text = { Text("People") },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab.intValue == 3,
                    onClick = { selectedTab.intValue = 3 },
                    text = { Text("Race") },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) }
                )
            }

            // Tab content
            when (selectedTab.intValue) {
                0 -> SizeMeasurementList(
                    measurements = measurements,
                    onItemClick = { id -> onNavigateToDetail("size", id) }
                )
                1 -> SpeedMeasurementList(
                    measurements = speedMeasurements,
                    onItemClick = { id -> onNavigateToDetail("speed", id) }
                )
                2 -> PeopleCountList(
                    measurements = peopleMeasurements,
                    onItemClick = { id -> onNavigateToDetail("people", id) }
                )
                3 -> RaceMeasurementList(
                    measurements = raceMeasurements,
                    onItemClick = { id -> onNavigateToDetail("race", id) }
                )
            }
        }
    }
}

/**
 * List of size measurements (existing functionality).
 */
@Composable
private fun SizeMeasurementList(
    measurements: List<Measurement>,
    onItemClick: (Long) -> Unit
) {
    if (measurements.isEmpty()) {
        EmptyState(message = "No size measurements yet")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            items(measurements, key = { it.id }) { measurement ->
                SizeMeasurementCard(
                    measurement = measurement,
                    onClick = { onItemClick(measurement.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

/**
 * List of speed measurements (new).
 */
@Composable
private fun SpeedMeasurementList(
    measurements: List<SpeedMeasurement>,
    onItemClick: (Long) -> Unit
) {
    if (measurements.isEmpty()) {
        EmptyState(message = "No speed measurements yet")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            items(measurements, key = { it.id }) { measurement ->
                SpeedMeasurementCard(
                    measurement = measurement,
                    onClick = { onItemClick(measurement.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

/**
 * List of people-count snapshots (new).
 */
@Composable
private fun PeopleCountCard(
    measurement: PeopleCountMeasurement,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Person icon placeholder
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${measurement.count} ${if (measurement.count == 1) "person" else "people"}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Count snapshot",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50)
                )
                Text(
                    text = dateFormat.format(Date(measurement.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PeopleCountList(
    measurements: List<PeopleCountMeasurement>,
    onItemClick: (Long) -> Unit
) {
    if (measurements.isEmpty()) {
        EmptyState(message = "No people counts yet")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            items(measurements, key = { it.id }) { measurement ->
                PeopleCountCard(
                    measurement = measurement,
                    onClick = { onItemClick(measurement.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

/**
 * List of race-time results.
 */
@Composable
private fun RaceMeasurementList(
    measurements: List<RaceMeasurement>,
    onItemClick: (Long) -> Unit
) {
    if (measurements.isEmpty()) {
        EmptyState(message = "No race results yet")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            items(measurements, key = { it.id }) { measurement ->
                RaceMeasurementCard(
                    measurement = measurement,
                    onClick = { onItemClick(measurement.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun RaceMeasurementCard(
    measurement: RaceMeasurement,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color(0xFFFFC107)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatRaceTime(measurement.timeMs),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${measurement.distanceMeters} m race",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFC107)
                )
                Text(
                    text = dateFormat.format(Date(measurement.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRaceTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val minutes = total / 60_000L
    val seconds = (total % 60_000L) / 1_000L
    val millis = total % 1_000L
    return "%02d.%02d.%03d".format(minutes, seconds, millis)
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SizeMeasurementCard(
    measurement: Measurement,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = File(measurement.imagePath),
                contentDescription = measurement.objectLabel,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = measurement.objectLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "X:${"%.1f".format(measurement.xValue)} Y:${"%.1f".format(measurement.yValue)} Z:${"%.1f".format(measurement.zValue)} ${measurement.unit.symbol}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateFormat.format(Date(measurement.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpeedMeasurementCard(
    measurement: SpeedMeasurement,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }
    val maxKmh = SpeedTracker.toKmh(measurement.maxSpeedMps)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed icon placeholder
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color(0xFF2196F3)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = measurement.objectLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${"%.1f".format(measurement.maxSpeedMps)} m/s (${"%.1f".format(maxKmh)} km/h)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2196F3)
                )
                Text(
                    text = "${"%.2f".format(measurement.distanceMeters)} m in ${"%.1f".format(measurement.durationSeconds)} s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = dateFormat.format(Date(measurement.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
