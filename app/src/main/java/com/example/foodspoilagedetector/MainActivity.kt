package com.example.foodspoilagedetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.foodspoilagedetector.bluetooth.BluetoothService
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.service.MonitoringService
import com.example.foodspoilagedetector.ui.DetectionScreen
import com.example.foodspoilagedetector.ui.HistoryScreen
import com.example.foodspoilagedetector.ui.components.PermissionDialog
import com.example.foodspoilagedetector.ui.theme.FoodSpoilageDetectorTheme
import kotlinx.coroutines.delay
import androidx.appcompat.app.AppCompatDelegate

import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothService = BluetoothService(this)
        enableEdgeToEdge()
        setContent {
            FoodSpoilageDetectorTheme {
                FoodSpoilageDetectorApp(bluetoothService)
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun FoodSpoilageDetectorApp(bluetoothService: BluetoothService? = null) {
    val context = LocalContext.current
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.DETECTION) }
    
    // Shared state
    var historyFiles by remember { mutableStateOf(SensorDataParser.getHistoryFiles(context)) }
    var detectionServerUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:5000/detect") }

    // Live Data Feed state (data/latest.json + data/latest.jpg over HTTP)
    var dataServerUrl by rememberSaveable { mutableStateOf("") }
    var fridgeImageUri by remember { mutableStateOf<Uri?>(null) }
    var latestSensorReading by remember { mutableStateOf<SensorReading?>(null) }

    // Background Monitoring state
    var isBackgroundMonitoring by rememberSaveable { mutableStateOf(false) }

    // Permission handling
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    
    val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
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

    // Persistent Polling Loop - pulls data/latest.json + data/latest.jpg from dataServerUrl
    LaunchedEffect(dataServerUrl, isBackgroundMonitoring) {
        while (!isBackgroundMonitoring) {
            if (dataServerUrl.isNotBlank()) {
                val snapshotResult = SensorDataParser.fetchLatestSnapshot(dataServerUrl)
                if (snapshotResult.isSuccess) {
                    latestSensorReading = snapshotResult.getOrNull()?.first
                }

                val imageResult = SensorDataParser.fetchLatestImage(context, dataServerUrl)
                if (imageResult.isSuccess) {
                    fridgeImageUri = imageResult.getOrNull()
                }
            }

            delay(5000)
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
                            historyFiles = SensorDataParser.getHistoryFiles(context)
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
                    bluetoothService = bluetoothService,
                    historyFiles = historyFiles,
                    detectionServerUrl = detectionServerUrl,
                    fridgeImageUri = fridgeImageUri,
                    liveSensorReading = latestSensorReading,
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.HISTORY -> HistoryScreen(
                    historyFiles = historyFiles,
                    onHistoryUpdated = { historyFiles = SensorDataParser.getHistoryFiles(context) },
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.SETTINGS -> SettingsScreen(
                    serverUrl = detectionServerUrl,
                    onServerUrlChanged = { detectionServerUrl = it },
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
