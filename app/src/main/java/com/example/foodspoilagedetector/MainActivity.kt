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
import com.example.foodspoilagedetector.ui.DetectionScreen
import com.example.foodspoilagedetector.ui.theme.FoodSpoilageDetectorTheme
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*

class MainActivity : ComponentActivity() {
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
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.DETECTION) }

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
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.DETECTION -> DetectionScreen(modifier = Modifier.padding(innerPadding))
                AppDestinations.HISTORY -> PlaceholderScreen(stringResource(R.string.nav_history), modifier = Modifier.padding(innerPadding))
                AppDestinations.SETTINGS -> SettingsScreen(modifier = Modifier.padding(innerPadding))
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
fun SettingsScreen(modifier: Modifier = Modifier) {
    // Get current language (default to English "en")
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
                        // Change the app's language
                        val appLocale = LocaleListCompat.forLanguageTags(code)
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (code == currentLocale),
                    onClick = null // Handled by Row clickable
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
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
