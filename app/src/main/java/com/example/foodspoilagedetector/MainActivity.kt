package com.example.foodspoilagedetector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.example.foodspoilagedetector.bluetooth.BluetoothService
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.ui.DetectionScreen
import com.example.foodspoilagedetector.ui.HistoryScreen
import com.example.foodspoilagedetector.ui.theme.FoodSpoilageDetectorTheme
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
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
    var activeLiveUrl by remember { mutableStateOf<String?>(null) }
    var detectionServerUrl by rememberSaveable { mutableStateOf("http://10.0.2.2:5000/detect") }

    // Persistent Polling Loop
    LaunchedEffect(activeLiveUrl) {
        if (activeLiveUrl != null) {
            while (true) {
                val result = SensorDataParser.downloadFileFromServer(context, activeLiveUrl!!)
                if (result.isSuccess) {
                    historyFiles = SensorDataParser.getHistoryFiles(context)
                }
                delay(5000)
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            imageVector = it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
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
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.HISTORY -> HistoryScreen(
                    historyFiles = historyFiles,
                    activeLiveUrl = activeLiveUrl,
                    onLiveUrlChanged = { activeLiveUrl = it },
                    onHistoryUpdated = { historyFiles = SensorDataParser.getHistoryFiles(context) },
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.SETTINGS -> SettingsScreen(
                    serverUrl = detectionServerUrl,
                    onServerUrlChanged = { detectionServerUrl = it },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    DETECTION("Detect", Icons.Default.Home),
    HISTORY("History", Icons.Default.History),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun SettingsScreen(
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().get(0)?.language ?: "en"

    val languages = listOf(
        "en" to stringResource(R.string.lang_english),
        "zh" to stringResource(R.string.lang_chinese)
    )

    Column(modifier
        .fillMaxSize()
        .padding(16.dp)) {
        Text(
            text = stringResource(R.string.label_language),
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        languages.forEach { (code, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val appLocale = LocaleListCompat.forLanguageTags(code)
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (code == currentLocale),
                    onClick = null
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text("Detection Server Configuration", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text("Server URL (e.g., http://192.168.1.5:5000/detect)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Text(
            text = "Update this URL if your computer's IP address changes on a new WiFi network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
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
