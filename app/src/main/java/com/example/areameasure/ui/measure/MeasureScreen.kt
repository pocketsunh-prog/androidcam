package com.example.areameasure.ui.measure

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.areameasure.data.model.UnitOfMeasure
import com.example.areameasure.domain.MeasureMode

// ---------------------------------------------------------------- camera preview area

/**
 * The live camera preview with the contour/face overlay, SPEED labels and zoom
 * controls, plus the SPEED/PEOPLE readouts floating at the top. This is the
 * shared "viewfinder" used in both portrait and landscape layouts — only the
 * surrounding controls differ.
 */
@Composable
private fun CameraPreviewArea(
    uiState: MeasureUiState,
    viewModel: MeasureViewModel,
    previewView: PreviewView,
    overlaySize: MutableState<IntSize>
) {
    val isSpeed = uiState.mode == MeasureMode.SPEED
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isSpeed) {
                if (!isSpeed) return@pointerInput
                detectTransformGestures { _, _, zoom, _ ->
                    if (uiState.isCameraRunning) {
                        viewModel.setZoom(uiState.zoomRatio * zoom)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Contour overlay
        if (uiState.isCameraRunning) {
            uiState.contourBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Contour overlay",
                    // Crop-filled to match the PreviewView's FILL_CENTER;
                    // the bitmap is already rotated to the upright orientation.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { overlaySize.value = it }
                        .pointerInput(
                            uiState.mode,
                            uiState.detectedObjects.size,
                            uiState.selectedObjectIndex,
                            uiState.overlayRotationDegrees
                        ) {
                            detectTapGestures { offset ->
                                when (uiState.mode) {
                                    MeasureMode.SIZE -> viewModel.onObjectSelected(
                                        tapX = offset.x,
                                        tapY = offset.y,
                                        viewWidth = overlaySize.value.width,
                                        viewHeight = overlaySize.value.height,
                                        rotationDegrees = uiState.overlayRotationDegrees
                                    )
                                    MeasureMode.SPEED -> viewModel.selectSpeedObject(
                                        tapX = offset.x,
                                        tapY = offset.y,
                                        viewWidth = overlaySize.value.width,
                                        viewHeight = overlaySize.value.height,
                                        rotationDegrees = uiState.overlayRotationDegrees
                                    )
                                    // PEOPLE mode has no tap-to-select interaction.
                                    MeasureMode.PEOPLE -> Unit
                                }
                            }
                        }
                )
            }
        }

        // SPEED: per-object speed labels drawn over the overlay
        if (isSpeed && uiState.isCameraRunning) {
            SpeedObjectLabels(
                objects = uiState.detectedObjects,
                viewWidth = overlaySize.value.width,
                viewHeight = overlaySize.value.height,
                calibrated = uiState.speedUsesMetric,
                viewModel = viewModel
            )
        }

        // SPEED: zoom controls on the right
        if (isSpeed && uiState.isCameraRunning) {
            ZoomControls(
                zoomRatio = uiState.zoomRatio,
                maxZoomRatio = uiState.maxZoomRatio,
                onZoomIn = { viewModel.zoomIn() },
                onZoomOut = { viewModel.zoomOut() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }

        // SPEED: live speed display overlay at the top. When a SIZE-mode
        // plane calibration is live, SPEED reuses it to report m/s + m;
        // otherwise it falls back to raw px/s + px.
        if (isSpeed && uiState.isTracking && uiState.currentSpeed > 0.0) {
            SpeedOverlay(
                speed = uiState.currentSpeed,
                maxSpeed = uiState.maxSpeed,
                distance = uiState.totalDistance,
                elapsedSeconds = uiState.elapsedSeconds,
                calibrated = uiState.speedUsesMetric,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // PEOPLE: live face-count overlay at the top.
        if (uiState.mode == MeasureMode.PEOPLE && uiState.isCameraRunning) {
            PeopleOverlay(
                count = uiState.peopleCount,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

/** Start/Stop + mode-specific controls, as an overlay bar (portrait). */
@Composable
private fun ControlsBar(
    uiState: MeasureUiState,
    viewModel: MeasureViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StartStopButton(uiState, viewModel, lifecycleOwner, previewView)
        Spacer(modifier = Modifier.height(10.dp))
        ModeControls(uiState, viewModel)
    }
}

/** Mode toggle + Start/Stop + mode controls, as a scrollable sidebar (landscape). */
@Composable
private fun ControlsSidebar(
    uiState: MeasureUiState,
    viewModel: MeasureViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.85f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MeasureModeToggle(
            selected = uiState.mode,
            onSelect = { viewModel.selectMode(it) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        StartStopButton(uiState, viewModel, lifecycleOwner, previewView)
        Spacer(modifier = Modifier.height(12.dp))
        ModeControls(uiState, viewModel)
    }
}

/** The Start / Stop button, shared by the portrait bar and landscape sidebar. */
@Composable
private fun StartStopButton(
    uiState: MeasureUiState,
    viewModel: MeasureViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView
) {
    if (!uiState.isCameraRunning) {
        Button(
            onClick = { viewModel.startCamera(lifecycleOwner, previewView.surfaceProvider) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start")
        }
    } else {
        Button(
            onClick = { viewModel.stopCamera() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop")
        }
    }
}

/** The mode-specific controls (Size / Speed / People), shared by both layouts. */
@Composable
private fun ModeControls(uiState: MeasureUiState, viewModel: MeasureViewModel) {
    when (uiState.mode) {
        MeasureMode.SIZE -> SizeControls(uiState = uiState, viewModel = viewModel)
        MeasureMode.SPEED -> SpeedControls(uiState = uiState, viewModel = viewModel)
        MeasureMode.PEOPLE -> PeopleControls(uiState = uiState, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
    viewModel: MeasureViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val overlaySize = remember { mutableStateOf(IntSize.Zero) }
    var showClearMemoryDialog by remember { mutableStateOf(false) }

    // Camera permission
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("3D Measure") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopCamera()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.reset() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset")
                    }
                    IconButton(onClick = { showClearMemoryDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear memory")
                    }
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Wide/landscape whenever the available width exceeds the height.
            val isLandscape = maxWidth > maxHeight

            if (hasCameraPermission) {
                // The PreviewView is created once and shared between the preview
                // area and the Start button (which needs its surfaceProvider).
                val previewView = remember { PreviewView(context) }

                if (isLandscape) {
                    // Landscape: preview fills the left, controls in a sidebar on the right.
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            CameraPreviewArea(
                                uiState = uiState,
                                viewModel = viewModel,
                                previewView = previewView,
                                overlaySize = overlaySize
                            )
                        }
                        ControlsSidebar(
                            uiState = uiState,
                            viewModel = viewModel,
                            lifecycleOwner = lifecycleOwner,
                            previewView = previewView
                        )
                    }
                } else {
                    // Portrait: mode toggle on top, preview in the middle, controls at the bottom.
                    Column(modifier = Modifier.fillMaxSize()) {
                        MeasureModeToggle(
                            selected = uiState.mode,
                            onSelect = { viewModel.selectMode(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreviewArea(
                                uiState = uiState,
                                viewModel = viewModel,
                                previewView = previewView,
                                overlaySize = overlaySize
                            )
                            ControlsBar(
                                uiState = uiState,
                                viewModel = viewModel,
                                lifecycleOwner = lifecycleOwner,
                                previewView = previewView,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            } else {
                // Permission not granted
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Camera permission is required to measure.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }

    // Calibration dialog (SIZE) — a known reference rectangle (width + height, mm)
    // so the plane can be rectified and measurements corrected for tilt/rotation.
    if (uiState.mode == MeasureMode.SIZE && uiState.showCalibrationDialog) {
        CalibrationDialog(
            pixelWidth = uiState.selectedPixelX,
            pixelHeight = uiState.selectedPixelY,
            onDismiss = { viewModel.dismissCalibrationDialog() },
            onCalibrate = { w, h -> viewModel.setSizeCalibration(w, h) }
        )
    }

    // Calibration dialog (SPEED) — a known real-world length of the pinned
    // object, used to derive a pixels-per-metre scale for m/s speed.
    if (uiState.mode == MeasureMode.SPEED && uiState.showSpeedCalibrationDialog) {
        CalibrationDialog(
            pixelWidth = uiState.selectedPixelX,
            pixelHeight = null,
            onDismiss = { viewModel.dismissSpeedCalibrationDialog() },
            onCalibrate = { w, _ -> viewModel.setSpeedCalibration(w) }
        )
    }

    // Label dialog after capture (SIZE mode only — SPEED has no save flow)
    if (uiState.showLabelDialog) {
        LabelDialog(
            onDismiss = { viewModel.dismissLabelDialog() },
            onConfirm = { label -> viewModel.saveMeasurement(label) }
        )
    }

    // Clear memory confirmation
    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryDialog = false },
            title = { Text("Clear all saved measurements?") },
            text = { Text("This permanently deletes every saved size and speed measurement and their images. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearMemoryDialog = false
                    viewModel.clearMemory()
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Error alert
    uiState.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
            }
        )
    }
}

// ---------------------------------------------------------------- mode toggle

@Composable
private fun MeasureModeToggle(
    selected: MeasureMode,
    onSelect: (MeasureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        ModeButton(
            label = "Size",
            icon = Icons.Default.SquareFoot,
            selected = selected == MeasureMode.SIZE,
            onClick = { onSelect(MeasureMode.SIZE) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        ModeButton(
            label = "Speed",
            icon = Icons.Default.Speed,
            selected = selected == MeasureMode.SPEED,
            onClick = { onSelect(MeasureMode.SPEED) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        ModeButton(
            label = "People",
            icon = Icons.Default.Person,
            selected = selected == MeasureMode.PEOPLE,
            onClick = { onSelect(MeasureMode.PEOPLE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        border = if (selected) null else ButtonDefaults.outlinedButtonBorder
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}

// ---------------------------------------------------------------- SIZE controls

@Composable
private fun SizeControls(uiState: MeasureUiState, viewModel: MeasureViewModel) {
    // Unit selector
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        UnitDropdown(
            selected = uiState.unit,
            onSelect = { viewModel.setUnit(it) }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 3D Dimensions display
    val dims = uiState.dimensions
    if (dims != null) {
        val sym = uiState.unit.symbol
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DimensionDisplay(label = "X", value = dims.x, unit = sym, color = Color(0xFFFF5252))
            DimensionDisplay(label = "Y", value = dims.y, unit = sym, color = Color(0xFF69F0AE))
            DimensionDisplay(label = "Z", value = dims.z, unit = sym, color = Color(0xFF448AFF))
        }
    } else if (uiState.isCameraRunning && uiState.selectedObjectIndex >= 0) {
        Text(
            text = if (uiState.isCalibrated) "Calibrated — tap another object"
            else "Tap object, then enter the reference size",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFAB40)
        )
    } else if (uiState.isCameraRunning) {
        Text(
            text = "Tap an object to measure",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
    } else {
        Text(
            text = "Press Start to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Calibrate + Capture
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.isCameraRunning && uiState.selectedObjectIndex >= 0 && !uiState.isCalibrated) {
            Button(
                onClick = { viewModel.showCalibrationDialog() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("Calibrate")
            }
        }

        Button(
            onClick = { viewModel.capture() },
            enabled = !uiState.isCapturing && uiState.dimensions != null && uiState.isCameraRunning,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "Capture",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- SPEED controls

@Composable
private fun SpeedControls(uiState: MeasureUiState, viewModel: MeasureViewModel) {
    Spacer(modifier = Modifier.height(8.dp))

    if (uiState.isCameraRunning && !uiState.isTracking) {
        val movingCount = uiState.detectedObjects.count { it.isMoving }
        Text(
            text = if (movingCount > 0) {
                "$movingCount moving ${if (movingCount == 1) "object" else "objects"} marked — tap one to measure its speed"
            } else {
                "Point at a moving object, then tap it to measure its speed"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
    } else if (uiState.isTracking) {
        Text(
            text = "Pinned — tracking selected object. Tap it again (or empty space) to release.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFD54F)
        )
    } else if (!uiState.isCameraRunning) {
        Text(
            text = "Press Start to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
    }

    // A real-world scale is required to report m/s; offer it once the user has
    // pinned a moving object and no metric scale is available yet.
    if (uiState.isCameraRunning && uiState.isTracking &&
        !uiState.speedUsesMetric && uiState.selectedObjectIndex >= 0
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { viewModel.showSpeedCalibrationDialog() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
        ) {
            Text("Calibrate (m/s)")
        }
    }
}

// ---------------------------------------------------------------- PEOPLE controls

@Composable
private fun PeopleControls(uiState: MeasureUiState, viewModel: MeasureViewModel) {
    // Live face count — large and prominent so it's readable over the camera.
    Text(
        text = "${uiState.peopleCount}",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF4CAF50)
    )
    Text(
        text = if (uiState.peopleCount == 1) "person" else "people",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFAAAAAA)
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = { viewModel.savePeopleCount() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) {
            Text("Save count")
        }
    }
}

@Composable
private fun PeopleOverlay(count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "$count ${if (count == 1) "person" else "people"}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "detected",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF888888)
        )
    }
}

// ---------------------------------------------------------------- shared composables

@Composable
private fun DimensionDisplay(label: String, value: Double, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
        Text(
            text = "${"%.1f".format(value)} $unit",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(
    selected: UnitOfMeasure,
    onSelect: (UnitOfMeasure) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.symbol,
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .width(140.dp),
            colors = OutlinedTextFieldDefaults.colors()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            UnitOfMeasure.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text("${unit.label} (${unit.symbol})") },
                    onClick = { onSelect(unit); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun CalibrationDialog(
    /** Detected width in pixels (always shown). */
    pixelWidth: Double?,
    /**
     * Detected height in pixels. When non-null the dialog asks for both width
     * and height (SIZE mode, for a reference rectangle). When null only the
     * width/length field is shown (SPEED mode).
     */
    pixelHeight: Double?,
    onDismiss: () -> Unit,
    onCalibrate: (widthMm: Double, heightMm: Double) -> Unit
) {
    var widthValue by remember { mutableStateOf("") }
    var heightValue by remember { mutableStateOf("") }

    // A reference rectangle needs both dimensions; a speed target only one.
    val askHeight = pixelHeight != null
    val widthMm = widthValue.toDoubleOrNull()
    val heightMm = heightValue.toDoubleOrNull()
    val canConfirm = widthMm != null && widthMm > 0 &&
        (!askHeight || (heightMm != null && heightMm > 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate Scale") },
        text = {
            Column {
                Text(
                    if (askHeight)
                        "Enter the real-world width and height of the reference " +
                            "object (e.g. a credit card) in millimeters."
                    else
                        "Enter the real-world length of the tracked object in millimeters.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (pixelWidth != null) {
                    Text(
                        "Detected: ${"%.1f".format(pixelWidth)} px" +
                            if (askHeight) " × ${"%.1f".format(pixelHeight)} px" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = widthValue,
                    onValueChange = { widthValue = it },
                    label = { Text(if (askHeight) "Width (mm)" else "Length (mm)") },
                    placeholder = { Text("e.g. 85.6") },
                    singleLine = true
                )
                if (askHeight) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = heightValue,
                        onValueChange = { heightValue = it },
                        label = { Text("Height (mm)") },
                        placeholder = { Text("e.g. 53.9") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // When only width is asked, mirror it to height (square fallback).
                    onCalibrate(widthMm ?: 0.0, if (askHeight) (heightMm ?: 0.0) else (widthMm ?: 0.0))
                },
                enabled = canConfirm
            ) { Text("Calibrate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LabelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this measurement") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text("e.g. Wooden box") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SpeedOverlay(
    speed: Double,
    maxSpeed: Double,
    distance: Double,
    elapsedSeconds: Double,
    /** True when SPEED reuses a SIZE-mode plane calibration for real units. */
    calibrated: Boolean,
    modifier: Modifier = Modifier
) {
    // When a SIZE plane calibration is live, SPEED converts to m/s + m via the
    // homography; otherwise values stay raw pixels (px/s + px).
    val speedUnit = if (calibrated) "m/s" else "px/s"
    val distUnit = if (calibrated) "m" else "px"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = Color(0xFF69F0AE),
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "${"%.1f".format(speed)} $speedUnit",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("Max", "${"%.1f".format(maxSpeed)} $speedUnit")
            StatItem("Dist", "${"%.1f".format(distance)} $distUnit")
            StatItem("Time", "${"%.1f".format(elapsedSeconds)} s")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/**
 * Draws a live speed readout next to each tracked moving object on the contour
 * overlay. Each label is positioned by mapping the object's raw-frame center to
 * the overlay view (accounting for the rotation + crop), so it sits beside the
 * object as it moves.
 */
@Composable
private fun SpeedObjectLabels(
    objects: List<MeasureDetectedObject>,
    viewWidth: Int,
    viewHeight: Int,
    /** True when SPEED reuses a SIZE-mode plane calibration for real units. */
    calibrated: Boolean,
    viewModel: MeasureViewModel
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val unit = if (calibrated) "m/s" else "px/s"
    Box(modifier = Modifier.fillMaxSize()) {
        objects.forEach { obj ->
            // Show the speed readout on the pinned target even while it is
            // momentarily still; auto-marked objects only show it while moving.
            if ((obj.isMoving || obj.isTracked) && obj.speed > 0.0) {
                val center = viewModel.frameToViewCenter(
                    frameX = obj.centerX,
                    frameY = obj.centerY,
                    viewWidth = viewWidth,
                    viewHeight = viewHeight
                )
                if (center != null) {
                    val dpX = (center.first / density).dp
                    val dpY = (center.second / density).dp
                    // The user-pinned object gets an amber ring + "PINNED" tag so
                    // it stands out from auto-tracked objects.
                    val isPinned = obj.contourIndex == viewModel.uiState.value.selectedObjectIndex
                    val labelColor = if (isPinned) Color(0xFFFFD54F) else Color(0xFF69F0AE)
                    val tag = if (isPinned) "★ " else ""
                    Box(
                        modifier = Modifier
                            .offset(x = dpX, y = dpY)
                            .background(
                                Color.Black.copy(alpha = if (isPinned) 0.8f else 0.65f),
                                RoundedCornerShape(6.dp)
                            )
                            .then(
                                if (isPinned) Modifier.border(
                                    1.dp, Color(0xFFFFD54F), RoundedCornerShape(6.dp)
                                ) else Modifier
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$tag${"%.1f".format(obj.speed)} $unit",
                            color = labelColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomControls(
    zoomRatio: Float,
    maxZoomRatio: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${"%.1f".format(zoomRatio)}x",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        IconButton(
            onClick = onZoomIn,
            enabled = zoomRatio < maxZoomRatio,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Zoom in",
                tint = if (zoomRatio < maxZoomRatio) Color.White else Color.Gray
            )
        }
        IconButton(
            onClick = onZoomOut,
            enabled = zoomRatio > 1.0f,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Zoom out",
                tint = if (zoomRatio > 1.0f) Color.White else Color.Gray
            )
        }
    }
}
