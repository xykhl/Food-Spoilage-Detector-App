package com.example.foodspoilagedetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.model.SessionRecorder
import com.example.foodspoilagedetector.service.MonitoringService
import com.example.foodspoilagedetector.ui.DetectionScreen
import com.example.foodspoilagedetector.ui.HistoryScreen
import com.example.foodspoilagedetector.ui.components.PermissionDialog
import com.example.foodspoilagedetector.ui.theme.FoodSpoilageDetectorTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatDelegate

import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoodSpoilageDetectorTheme {
                FoodSpoilageDetectorApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun FoodSpoilageDetectorApp() {
    val context = LocalContext.current
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.DETECTION) }
    
    // Shared state
    var historyFiles by remember { mutableStateOf(SensorDataParser.getHistoryFiles(context)) }
    var detectionFiles by remember { mutableStateOf(SensorDataParser.getDetectionHistoryFiles(context)) }
    var detectionServerUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:4100/detect") }
    var automaticDetectionUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:4100/stream") }

    // Sensor history for the History screen, pulled from the data server's .jsonl logs
    var sensorHistory by remember { mutableStateOf<List<SensorReading>>(emptyList()) }
    var isHistoryLoading by remember { mutableStateOf(false) }
    var historyRefreshTrigger by remember { mutableIntStateOf(0) }
    var latestAutomaticDetection by remember { mutableStateOf<com.example.foodspoilagedetector.model.SpoilageResult?>(null) }

    // SSE Stream Connection
    DisposableEffect(automaticDetectionUrl) {
        if (automaticDetectionUrl.isBlank()) return@DisposableEffect onDispose {}

        val client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(automaticDetectionUrl)
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                Log.d("SSE", "Stream opened: ${response.code} $automaticDetectionUrl")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d("SSE", "Event received: type='$type', data=$data")
                // Accept the named "detection" event and unnamed/default events (type == null),
                // since not every server labels its SSE events.
                if (type == "detection" || type.isNullOrBlank()) {
                    try {
                        latestAutomaticDetection = SensorDataParser.parseSpoilageResultFromString(data)
                        Log.d("SSE", "Parsed automatic detection OK, isSpoiled=${latestAutomaticDetection?.isSpoiled}")
                    } catch (e: Exception) {
                        Log.e("SSE", "Failed to parse detection event", e)
                    }
                } else {
                    Log.d("SSE", "Ignored event of type '$type'")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("SSE", "Stream closed")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                Log.e("SSE", "Stream failure: code=${response?.code}", t)
            }
        })

        onDispose {
            eventSource.cancel()
        }
    }

    // Buffers live readings and writes them to files/history when the feed drops
    val sessionRecorder = remember { SessionRecorder() }

    fun refreshLocalFiles() {
        historyFiles = SensorDataParser.getHistoryFiles(context)
        detectionFiles = SensorDataParser.getDetectionHistoryFiles(context)
    }

    // Live Data Feed state (data/latest.json + data/latest.jpg over HTTP)
    var dataServerUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:8000") }
    var fridgeImageUri by remember { mutableStateOf<Uri?>(null) }
    // Bumped on every fetch: the frame is written to the same path each poll, so this is
    // what tells the UI (and Coil's cache) that the bytes behind that path are new.
    var fridgeImageVersion by remember { mutableIntStateOf(0) }
    var latestSensorReading by remember { mutableStateOf<SensorReading?>(null) }

    // Live status tracking
    var lastPollSuccess by remember { mutableStateOf(false) }
    val isLive by remember(lastPollSuccess, dataServerUrl) {
        derivedStateOf { lastPollSuccess && dataServerUrl.isNotBlank() }
    }

    // Background Monitoring state
    var isBackgroundMonitoring by rememberSaveable { mutableStateOf(false) }

    // Permission handling
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    
    // Sensor data and camera frames both arrive over HTTP, so notifications are the
    // only runtime permission left.
    val requiredPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            showPermissionDialog = true
        }
    }

    if (showPermissionDialog) {
        PermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onConfirm = {
                showPermissionDialog = false
                permissionLauncher.launch(requiredPermissions.toTypedArray())
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMonitoringService(context, detectionServerUrl, dataServerUrl)
        } else {
            isBackgroundMonitoring = false
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        isBackgroundMonitoring = enabled
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startMonitoringService(context, detectionServerUrl, dataServerUrl)
            }
        } else {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }

    // Persistent Polling Loop - pulls data/latest.json + data/latest.jpg from dataServerUrl.
    // Readings are buffered so a connected stretch can be saved as a session on disconnect.
    LaunchedEffect(dataServerUrl, isBackgroundMonitoring) {
        try {
            while (!isBackgroundMonitoring) {
                if (dataServerUrl.isNotBlank()) {
                    val snapshotResult = SensorDataParser.fetchLatestSnapshot(dataServerUrl)
                    val reading = snapshotResult.getOrNull()?.first
                    
                    if (reading != null) {
                        lastPollSuccess = true
                        latestSensorReading = reading
                        sessionRecorder.record(reading)
                        
                        // Append to memory history so it shows "live" without re-fetching all .jsonl files
                        sensorHistory = (sensorHistory + reading).sortedBy { it.timestamp }.takeLast(2000)
                    } else {
                        lastPollSuccess = false
                        if (sessionRecorder.onFetchFailed()) {
                            // Feed went away - close out the session so it stays inspectable.
                            if (sessionRecorder.flush(context, allowNewSession = true) != null) {
                                refreshLocalFiles()
                            }
                        }
                    }

                    val imageResult = SensorDataParser.fetchLatestImage(context, dataServerUrl)
                    if (imageResult.isSuccess) {
                        fridgeImageUri = imageResult.getOrNull()
                        // The Uri string is identical every poll, so bump the version to
                        // signal the new frame - without it the display never refreshes.
                        fridgeImageVersion++
                    }
                } else {
                    lastPollSuccess = false
                }

                delay(5000)
            }
        } finally {
            // Teardown (URL edited, rotation, language switch, monitoring taking over) is
            // not a disconnect, so these readings may only extend a session already on disk.
            withContext(NonCancellable) { sessionRecorder.flush(context, allowNewSession = false) }
        }
    }

    // Sensor history fetch — re-runs when the server changes or the user hits Refresh
    LaunchedEffect(dataServerUrl, historyRefreshTrigger) {
        if (dataServerUrl.isBlank()) {
            sensorHistory = emptyList()
        } else {
            isHistoryLoading = true
            sensorHistory = SensorDataParser.fetchAllSensorHistory(dataServerUrl)
            isHistoryLoading = false
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            imageVector = it.icon,
                            contentDescription = stringResource(it.labelRes)
                        )
                    },
                    label = { Text(stringResource(it.labelRes)) },
                    selected = it == currentDestination,
                    onClick = {
                        if (it == AppDestinations.HISTORY) {
                            refreshLocalFiles()
                        }
                        currentDestination = it
                    }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.DETECTION -> DetectionScreen(
                    historyFiles = historyFiles,
                    detectionServerUrl = detectionServerUrl,
                    fridgeImageUri = fridgeImageUri,
                    fridgeImageVersion = fridgeImageVersion,
                    liveSensorReading = latestSensorReading,
                    automaticSpoilageResult = latestAutomaticDetection,
                    isLive = isLive,
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.HISTORY -> HistoryScreen(
                    sensorHistory = sensorHistory,
                    isLoading = isHistoryLoading,
                    hasDataServerUrl = dataServerUrl.isNotBlank(),
                    onRefresh = { historyRefreshTrigger++ },
                    onHistoryUpdated = { refreshLocalFiles() },
                    historyFiles = historyFiles,
                    detectionFiles = detectionFiles,
                    isLive = isLive,
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.SETTINGS -> SettingsScreen(
                    serverUrl = detectionServerUrl,
                    onServerUrlChanged = { detectionServerUrl = it },
                    automaticDetectionUrl = automaticDetectionUrl,
                    onAutomaticDetectionUrlChanged = { automaticDetectionUrl = it },
                    dataServerUrl = dataServerUrl,
                    onDataServerUrlChanged = { dataServerUrl = it },
                    isMonitoringEnabled = isBackgroundMonitoring,
                    onMonitoringToggled = { toggleMonitoring(it) },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

private fun startMonitoringService(context: android.content.Context, server: String, dataServerUrl: String) {
    val intent = Intent(context, MonitoringService::class.java).apply {
        putExtra(MonitoringService.EXTRA_SERVER_URL, server)
        putExtra(MonitoringService.EXTRA_DATA_SERVER_URL, dataServerUrl)
    }
    ContextCompat.startForegroundService(context, intent)
}

enum class AppDestinations(
    val labelRes: Int,
    val icon: ImageVector,
) {
    DETECTION(R.string.nav_detect, Icons.Default.Home),
    HISTORY(R.string.nav_history, Icons.Default.History),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings),
}

@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    automaticDetectionUrl: String,
    onAutomaticDetectionUrlChanged: (String) -> Unit,
    dataServerUrl: String,
    onDataServerUrlChanged: (String) -> Unit,
    isMonitoringEnabled: Boolean,
    onMonitoringToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLocale = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"

    val languages = listOf(
        "en" to stringResource(R.string.lang_english),
        "zh" to stringResource(R.string.lang_chinese)
    )

    Column(modifier
        .fillMaxSize()
        // enableEdgeToEdge() disables the window's automatic keyboard resize, so the
        // scroll area must reserve space for the IME itself or the URL fields at the
        // bottom get hidden behind the keyboard while typing.
        .imePadding()
        .padding(16.dp)
        .verticalScroll(rememberScrollState())) {
        
        Text(stringResource(R.string.title_general_settings), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Text(stringResource(R.string.label_language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        languages.forEach { (code, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val appLocale = LocaleListCompat.forLanguageTags(code)
                        AppCompatDelegate.setApplicationLocales(appLocale)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (code == currentLocale), onClick = null)
                Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.title_monitoring_alerts), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.label_background_monitoring), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.desc_background_monitoring),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isMonitoringEnabled,
                onCheckedChange = onMonitoringToggled
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(stringResource(R.string.title_server_config), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text(stringResource(R.string.label_algorithm_server)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = automaticDetectionUrl,
            onValueChange = onAutomaticDetectionUrlChanged,
            label = { Text(stringResource(R.string.label_automatic_detection_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = dataServerUrl,
            onValueChange = onDataServerUrlChanged,
            label = { Text(stringResource(R.string.label_data_server_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text(
            text = stringResource(R.string.desc_ensure_urls),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PlaceholderScreen(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "$name Screen Placeholder")
    }
}
@Preview(showBackground = true)
@Composable
fun FoodSpoilageDetectorAppPreview() {
    FoodSpoilageDetectorTheme {
        FoodSpoilageDetectorApp()
    }
}
