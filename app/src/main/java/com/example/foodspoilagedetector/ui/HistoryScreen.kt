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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    activeLiveUrl: String?,
    onLiveUrlChanged: (String?) -> Unit,
    onHistoryUpdated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var selectedFile by remember { mutableStateOf<File?>(null) } // null means combined view
    
    var sensorReadings by remember { mutableStateOf<List<SensorReading>>(emptyList()) }
    var availableSensors by remember { mutableStateOf<List<String>>(emptyList()) }

    var showSyncDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("http://localhost:8000/") }
    var isLiveSync by remember { mutableStateOf(false) }

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

    // Load data based on selectedFile
    LaunchedEffect(selectedFile, historyFiles) {
        if (selectedFile != null) {
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
                    "Sensor History", 
                    modifier = Modifier.padding(16.dp), 
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                
                Text(
                    "View Mode", 
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp), 
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                NavigationDrawerItem(
                    label = { Text("Dashboard (Combined All)") },
                    selected = selectedFile == null,
                    onClick = {
                        selectedFile = null
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                
                Text(
                    "Saved Files", 
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
                                selected = selectedFile == file,
                                onClick = {
                                    selectedFile = file
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
                    Text("Upload New .DAT")
                }
                
                OutlinedButton(
                    onClick = { 
                        showSyncDialog = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                ) {
                    Icon(if (activeLiveUrl != null) Icons.Default.Sync else Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (activeLiveUrl != null) "Stop Live Sync" else "Sync from Computer")
                }
            }
        }
    ) {
        if (showSyncDialog) {
            AlertDialog(
                onDismissRequest = { showSyncDialog = false },
                title = { Text(if (activeLiveUrl != null) "Active Sync" else "Sync from Computer") },
                text = {
                    Column {
                        if (activeLiveUrl == null) {
                            Text("Enter the file URL (e.g., http://localhost:8000/data.DAT)")
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("Server URL") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isLiveSync,
                                    onCheckedChange = { isLiveSync = it }
                                )
                                Text("Stay Synced (Live Polling)")
                            }
                        } else {
                            Text("Currently syncing from: $activeLiveUrl")
                        }
                    }
                },
                confirmButton = {
                    if (activeLiveUrl == null) {
                        Button(onClick = {
                            scope.launch {
                                val result = SensorDataParser.downloadFileFromServer(context, serverUrl)
                                if (result.isSuccess) {
                                    onHistoryUpdated()
                                    if (isLiveSync) {
                                        onLiveUrlChanged(serverUrl)
                                    }
                                }
                                showSyncDialog = false
                            }
                        }) {
                            Text(if (isLiveSync) "Start Live Sync" else "Download Once")
                        }
                    } else {
                        Button(onClick = {
                            onLiveUrlChanged(null)
                            showSyncDialog = false
                        }) {
                            Text("Stop Sync")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSyncDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selectedFile == null) "Sensor Dashboard" else selectedFile!!.name)
                            if (activeLiveUrl != null) {
                                Spacer(Modifier.width(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Text(
                                        "● LIVE", 
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { padding ->
            if (sensorReadings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding), 
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data found. Open the side menu to upload files.")
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
                        text = "Avg: ${values.average().format(2)} $unit",
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
                        text = "Min: ${values.minOrNull()} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Max: ${values.maxOrNull()} $unit",
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
