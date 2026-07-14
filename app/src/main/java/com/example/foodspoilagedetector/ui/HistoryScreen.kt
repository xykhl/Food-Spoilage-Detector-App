package com.example.foodspoilagedetector.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.foodspoilagedetector.R
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.model.SensorRegistry
import com.example.foodspoilagedetector.ui.components.SensorGraph
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyFiles: List<File>,
    dataServerUrl: String,
    onHistoryUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var selectedFile by remember { mutableStateOf<File?>(null) } // null means combined view
    var isLiveHistory by remember { mutableStateOf(false) }
    var liveHistoryRefreshTrigger by remember { mutableStateOf(0) }
    var isLoadingLiveHistory by remember { mutableStateOf(false) }
    var liveHistoryError by remember { mutableStateOf<String?>(null) }

    var sensorReadings by remember { mutableStateOf<List<SensorReading>>(emptyList()) }
    var availableSensors by remember { mutableStateOf<List<String>>(emptyList()) }

    var showSyncDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("http://localhost:8000/") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val savedName = SensorDataParser.saveFileToHistory(context, it)
            if (savedName != null) {
                onHistoryUpdated()
            }
        }
    }

    // Load data based on the selected view mode
    LaunchedEffect(selectedFile, historyFiles, isLiveHistory, liveHistoryRefreshTrigger) {
        if (isLiveHistory) {
            liveHistoryError = null
            if (dataServerUrl.isBlank()) {
                sensorReadings = emptyList()
                liveHistoryError = context.getString(R.string.msg_set_data_server_first)
            } else {
                isLoadingLiveHistory = true
                sensorReadings = SensorDataParser.fetchAllSensorHistory(dataServerUrl)
                isLoadingLiveHistory = false
                if (sensorReadings.isEmpty()) {
                    liveHistoryError = context.getString(R.string.msg_no_live_history_found)
                }
            }
        } else if (selectedFile != null) {
            sensorReadings = loadFileDataSync(selectedFile!!)
        } else {
            // Aggregated View
            sensorReadings = historyFiles.flatMap { loadFileDataSync(it) }.sortedBy { it.timestamp }
        }
        availableSensors = sensorReadings.flatMap { it.values.keys }.distinct()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.title_sensor_history), 
                    modifier = Modifier.padding(16.dp), 
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                
                Text(
                    stringResource(R.string.label_view_mode), 
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), 
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.label_dashboard_combined)) },
                    selected = selectedFile == null && !isLiveHistory,
                    onClick = {
                        selectedFile = null
                        isLiveHistory = false
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.label_live_sensor_history)) },
                    selected = isLiveHistory,
                    onClick = {
                        selectedFile = null
                        isLiveHistory = true
                        liveHistoryRefreshTrigger++
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()

                Text(
                    stringResource(R.string.label_saved_files), 
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), 
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(historyFiles) { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                        ) {
                            NavigationDrawerItem(
                                label = { Text(file.name) },
                                selected = selectedFile == file && !isLiveHistory,
                                onClick = {
                                    selectedFile = file
                                    isLiveHistory = false
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.weight(1f).padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            IconButton(onClick = {
                                SensorDataParser.deleteHistoryFile(file)
                                onHistoryUpdated()
                                if (selectedFile == file) selectedFile = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_upload_dat))
                }
                
                OutlinedButton(
                    onClick = {
                        showSyncDialog = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_sync_computer))
                }
            }
        }
    ) {
        if (showSyncDialog) {
            AlertDialog(
                onDismissRequest = { showSyncDialog = false },
                title = { Text(stringResource(R.string.title_sync_dialog)) },
                text = {
                    Column {
                        Text(stringResource(R.string.label_enter_url))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text(stringResource(R.string.label_server_url)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            val result = SensorDataParser.downloadFileFromServer(context, serverUrl)
                            if (result.isSuccess) {
                                onHistoryUpdated()
                            }
                            showSyncDialog = false
                        }
                    }) {
                        Text(stringResource(R.string.btn_download_once))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSyncDialog = false }) {
                        Text(stringResource(R.string.btn_close))
                    }
                }
            )
        }

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                isLiveHistory -> stringResource(R.string.label_live_sensor_history)
                                selectedFile == null -> stringResource(R.string.title_sensor_dashboard)
                                else -> selectedFile!!.name
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (isLiveHistory) {
                            IconButton(onClick = { liveHistoryRefreshTrigger++ }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_refresh))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            if (isLoadingLiveHistory) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (sensorReadings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(liveHistoryError ?: stringResource(R.string.label_no_data_found))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    availableSensors.forEach { sensorKey ->
                        SensorGraphCard(
                            sensorKey = sensorKey,
                            readings = sensorReadings
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
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

fun loadFileDataSync(file: File): List<SensorReading> {
    return try {
        val content = file.readText()
        SensorDataParser.parseDatFile(content)
    } catch (e: Exception) {
        emptyList()
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(java.util.Locale.ENGLISH, this)
