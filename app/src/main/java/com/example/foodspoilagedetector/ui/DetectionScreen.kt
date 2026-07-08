package com.example.foodspoilagedetector.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.foodspoilagedetector.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DetectionScreen(
    modifier: Modifier = Modifier
) {
    val detectionPlaceholder = stringResource(R.string.placeholder_result)
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var resultText by remember { mutableStateOf("") }

    // Sensor states
    var ethanol by remember { mutableStateOf("") }
    var ethylene by remember { mutableStateOf("") }
    var voc by remember { mutableStateOf("") }
    var hydrogenSulfide by remember { mutableStateOf("") }
    var ammonia by remember { mutableStateOf("") }
    var methylMercaptan by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var humidity by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) imageUri = uri
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) imageUri = tempPhotoUri
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.title_detection),
            style = MaterialTheme.typography.headlineMedium
        )

        // Image Preview
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (imageUri != null) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Selected Food Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Placeholder",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val uri = createImageUri(context)
                    tempPhotoUri = uri
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AddAPhoto, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.btn_take_photo))
            }
            OutlinedButton(
                onClick = { photoPickerLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.btn_gallery))
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.title_sensor_data),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Start)
        )

        SensorInputGrid(
            ethanol = ethanol, onEthanolChange = { ethanol = it },
            ethylene = ethylene, onEthyleneChange = { ethylene = it },
            voc = voc, onVocChange = { voc = it },
            hydrogenSulfide = hydrogenSulfide, onHydrogenSulfideChange = { hydrogenSulfide = it },
            ammonia = ammonia, onAmmoniaChange = { ammonia = it },
            methylMercaptan = methylMercaptan, onMethylMercaptanChange = { methylMercaptan = it },
            temperature = temperature, onTemperatureChange = { temperature = it },
            humidity = humidity, onHumidityChange = { humidity = it }
        )

        Button(
            onClick = {
                resultText = detectionPlaceholder
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Text(stringResource(R.string.btn_detect))
        }

        if (resultText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = resultText,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

fun createImageUri(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

@Composable
fun SensorInputGrid(
    ethanol: String, onEthanolChange: (String) -> Unit,
    ethylene: String, onEthyleneChange: (String) -> Unit,
    voc: String, onVocChange: (String) -> Unit,
    hydrogenSulfide: String, onHydrogenSulfideChange: (String) -> Unit,
    ammonia: String, onAmmoniaChange: (String) -> Unit,
    methylMercaptan: String, onMethylMercaptanChange: (String) -> Unit,
    temperature: String, onTemperatureChange: (String) -> Unit,
    humidity: String, onHumidityChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextField(label = stringResource(R.string.label_ethanol), value = ethanol, onValueChange = onEthanolChange, modifier = Modifier.weight(1f))
            SensorTextField(label = stringResource(R.string.label_ethylene), value = ethylene, onValueChange = onEthyleneChange, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextField(label = stringResource(R.string.label_voc), value = voc, onValueChange = onVocChange, modifier = Modifier.weight(1f))
            SensorTextField(label = stringResource(R.string.label_h2s), value = hydrogenSulfide, onValueChange = onHydrogenSulfideChange, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextField(label = stringResource(R.string.label_ammonia), value = ammonia, onValueChange = onAmmoniaChange, modifier = Modifier.weight(1f))
            SensorTextField(label = stringResource(R.string.label_ch3sh), value = methylMercaptan, onValueChange = onMethylMercaptanChange, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextField(label = stringResource(R.string.label_temp), value = temperature, onValueChange = onTemperatureChange, modifier = Modifier.weight(1f))
            SensorTextField(label = stringResource(R.string.label_humidity), value = humidity, onValueChange = onHumidityChange, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SensorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true
    )
}

@Preview(showBackground = true)
@Composable
fun DetectionScreenPreview() {
    MaterialTheme {
        DetectionScreen()
    }
}
