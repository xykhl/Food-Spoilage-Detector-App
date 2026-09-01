package com.example.foodspoilagedetector.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.foodspoilagedetector.R
import com.example.foodspoilagedetector.model.DetectionHistoryEntry
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.model.SessionRecorder
import com.example.foodspoilagedetector.model.SensorRegistry
import com.example.foodspoilagedetector.ui.components.LiveIndicator
import com.example.foodspoilagedetector.ui.components.SensorGraph
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the History screen is currently showing. */
private sealed interface HistorySelection {
    /** The live feed pulled from the data server. */
    data object Live : HistorySelection
    /** A saved .DAT/.jsonl session file. */
    data class Session(val file: File) : HistorySelection
    /** A saved detection result (image + sensors + verdict). */
    data class Detection(val file: File) : HistorySelection
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sensorHistory: List<SensorReading>,
    isLoading: Boolean,
    hasDataServerUrl: Boolean,
    onRefresh: () -> Unit,
    onHistoryUpdated: () -> Unit,
    historyFiles: List<File> = emptyList(),
    detectionFiles: List<File> = emptyList(),
    isLive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var selection by remember { mutableStateOf<HistorySelection>(HistorySelection.Live) }
    var selectedSensor by remember { mutableStateOf<String?>(null) }
    var displayedHistory by remember { mutableStateOf(sensorHistory) }
    var detectionEntry by remember { mutableStateOf<DetectionHistoryEntry?>(null) }
    var isLoadingSelection by remember { mutableStateOf(false) }

    // Resolve the current selection into what the content area renders.
    LaunchedEffect(selection, sensorHistory, historyFiles) {
        when (val current = selection) {
            is HistorySelection.Live -> {
                detectionEntry = null
                displayedHistory = sensorHistory
            }
            is HistorySelection.Session -> {
                // A resumed session is renamed to cover its new end, so the selected
                // file can vanish under us while it is open.
                if (!current.file.exists()) {
                    selection = HistorySelection.Live
                    return@LaunchedEffect
                }
                detectionEntry = null
                isLoadingSelection = true
                displayedHistory = SensorDataParser.loadSensorHistoryFromFile(current.file)
                isLoadingSelection = false
            }
            is HistorySelection.Detection -> {
                displayedHistory = emptyList()
                isLoadingSelection = true
                detectionEntry = SensorDataParser.loadDetectionHistoryEntry(current.file)
                isLoadingSelection = false
            }
        }
    }

    val availableSensors = remember(displayedHistory) {
        displayedHistory.flatMap { it.values.keys }.distinct()
    }

    // A sensor filter is meaningless once the selected source no longer reports it.
    LaunchedEffect(availableSensors) {
        if (selectedSensor != null && selectedSensor !in availableSensors) selectedSensor = null
    }

    val drawerDateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val sessionEndFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(12.dp))

                    // ---- Which sensor's graph to show ----
                    Text(
                        text = stringResource(R.string.label_sensor_selection),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider()

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.label_all_sensors)) },
                        selected = selectedSensor == null,
                        onClick = {
                            selectedSensor = null
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    availableSensors.forEach { sensorKey ->
                        NavigationDrawerItem(
                            label = { Text(SensorRegistry.getDisplayName(sensorKey)) },
                            selected = selectedSensor == sensorKey,
                            onClick = {
                                selectedSensor = sensorKey
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }

                    // ---- Which data source to graph ----
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.label_historical_sessions),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider()

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.label_live_current_feed)) },
                        selected = selection is HistorySelection.Live,
                        onClick = {
                            selection = HistorySelection.Live
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    if (historyFiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.msg_no_sessions_yet),
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    historyFiles.forEach { file ->
                        val sessionLabel = sessionLabel(file, drawerDateFormat, sessionEndFormat)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                        ) {
                            NavigationDrawerItem(
                                label = { Text(sessionLabel) },
                                selected = (selection as? HistorySelection.Session)?.file == file,
                                onClick = {
                                    selection = HistorySelection.Session(file)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            IconButton(onClick = {
                                SensorDataParser.deleteHistoryFile(file)
                                if ((selection as? HistorySelection.Session)?.file == file) {
                                    selection = HistorySelection.Live
                                }
                                onHistoryUpdated()
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.desc_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // ---- Past detection results ----
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.title_detection_history),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider()

                    if (detectionFiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.msg_no_detections_yet),
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        detectionFiles.forEach { file ->
                            val dateStr = drawerDateFormat.format(Date(file.lastModified()))
                            NavigationDrawerItem(
                                label = { Text(stringResource(R.string.label_detection_at, dateStr)) },
                                selected = (selection as? HistorySelection.Detection)?.file == file,
                                onClick = {
                                    selection = HistorySelection.Detection(file)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val title = when (val current = selection) {
                                is HistorySelection.Live ->
                                    stringResource(R.string.label_live_sensor_history)
                                is HistorySelection.Session ->
                                    sessionLabel(current.file, drawerDateFormat, sessionEndFormat)
                                is HistorySelection.Detection -> stringResource(
                                    R.string.label_detection_at,
                                    drawerDateFormat.format(
                                        Date(detectionEntry?.timestamp ?: current.file.lastModified())
                                    )
                                )
                            }
                            Text(title)
                            if (isLive && selection is HistorySelection.Live) {
                                Spacer(Modifier.width(8.dp))
                                LiveIndicator()
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.desc_open_history_menu)
                            )
                        }
                    },
                    actions = {
                        if (selection is HistorySelection.Live) {
                            IconButton(onClick = onRefresh) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.btn_refresh)
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                if (selection is HistorySelection.Detection) {
                    when {
                        isLoadingSelection -> LoadingBox()
                        detectionEntry != null -> DetectionHistoryDetail(
                            entry = detectionEntry!!,
                            context = context
                        )
                        else -> MessageBox(stringResource(R.string.msg_detection_load_failed))
                    }
                } else {
                    HistoryContent(
                        isLoading = isLoading || isLoadingSelection,
                        displayedHistory = displayedHistory,
                        hasDataServerUrl = hasDataServerUrl,
                        isSessionFile = selection is HistorySelection.Session,
                        selectedSensor = selectedSensor,
                        availableSensors = availableSensors
                    )
                }
            }
        }
    }
}

/**
 * "Session Jul 20, 15:05-15:06" for recorded sessions, falling back to the raw
 * filename for anything else that ends up in the history directory.
 */
@Composable
private fun sessionLabel(
    file: File,
    startFormat: SimpleDateFormat,
    endFormat: SimpleDateFormat
): String {
    val range = SessionRecorder.parseSessionRange(file) ?: return file.name
    return stringResource(
        R.string.label_session_range,
        startFormat.format(Date(range.first)),
        endFormat.format(Date(range.second))
    )
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageBox(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message)
    }
}

@Composable
fun HistoryContent(
    isLoading: Boolean,
    displayedHistory: List<SensorReading>,
    hasDataServerUrl: Boolean,
    isSessionFile: Boolean,
    selectedSensor: String?,
    availableSensors: List<String>
) {
    when {
        isLoading && displayedHistory.isEmpty() -> LoadingBox()

        displayedHistory.isEmpty() -> MessageBox(
            when {
                isSessionFile -> stringResource(R.string.msg_no_data_in_session)
                hasDataServerUrl -> stringResource(R.string.msg_no_live_history_found)
                else -> stringResource(R.string.msg_set_data_server_first)
            }
        )

        else -> Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            val sensorsToShow = selectedSensor?.let { listOf(it) } ?: availableSensors
            sensorsToShow.forEach { sensorKey ->
                SensorGraphCard(sensorKey = sensorKey, readings = displayedHistory)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun DetectionHistoryDetail(entry: DetectionHistoryEntry, context: android.content.Context) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            if (entry.imageUri != Uri.EMPTY) {
                AsyncImage(
                    model = entry.imageUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.msg_no_photo_saved))
                }
            }
        }

        Text(stringResource(R.string.title_sensor_data), style = MaterialTheme.typography.titleLarge)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sensors = entry.sensorData.entries.toList()
            for (i in sensors.indices step 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensorItem(
                        SensorRegistry.getDisplayName(sensors[i].key),
                        sensors[i].value,
                        Modifier.weight(1f)
                    )
                    if (i + 1 < sensors.size) {
                        SensorItem(
                            SensorRegistry.getDisplayName(sensors[i + 1].key),
                            sensors[i + 1].value,
                            Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.title_spoilage_results), style = MaterialTheme.typography.titleLarge)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (entry.result.isSpoiled) MaterialTheme.colorScheme.errorContainer
                else Color(0xFFE8F5E9)
            )
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.result.isSpoiled) "⚠️" else "✅",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry.result.message,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.result.isSpoiled) MaterialTheme.colorScheme.error
                    else Color(0xFF2E7D32)
                )
            }
        }

        entry.result.detectedFoods.forEach { food ->
            DetectedFoodCard(
                food = food,
                originalImageUri = entry.imageUri.takeIf { it != Uri.EMPTY },
                context = context
            )
        }
    }
}

@Composable
fun SensorItem(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        modifier = modifier,
        singleLine = true
    )
}

@Composable
fun SensorGraphCard(sensorKey: String, readings: List<SensorReading>) {
    val displayName = SensorRegistry.getDisplayName(sensorKey)
    val unit = SensorRegistry.getUnit(sensorKey)
    val values = readings.mapNotNull { it.values[sensorKey] }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (values.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.label_avg, values.average(), unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SensorGraph(
                readings = readings,
                sensorKey = sensorKey,
                unit = unit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (values.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_min, values.minOrNull() ?: 0f, unit),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.label_max, values.maxOrNull() ?: 0f, unit),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
