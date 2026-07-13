package com.example.foodspoilagedetector.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.foodspoilagedetector.R
import com.example.foodspoilagedetector.bluetooth.BluetoothService
import com.example.foodspoilagedetector.model.DetectedFood
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.model.SensorRegistry
import com.example.foodspoilagedetector.model.SpoilageResult
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DetectionScreen(
    bluetoothService: BluetoothService? = null,
    historyFiles: List<File> = emptyList(),
    detectionServerUrl: String = "http://localhost:5000/detect",
    modifier: Modifier = Modifier
) {
    val detectionPlaceholder = stringResource(R.string.placeholder_result)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    
    var spoilageResult by remember { mutableStateOf<SpoilageResult?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Sensor states
    var ethanol by remember { mutableStateOf("0.0") }
    var ethylene by remember { mutableStateOf("0.0") }
    var voc by remember { mutableStateOf("0.0") }
    var hydrogenSulfide by remember { mutableStateOf("0.0") }
    var ammonia by remember { mutableStateOf("0.0") }
    var methylMercaptan by remember { mutableStateOf("0.0") }
    var temperature by remember { mutableStateOf("0.0") }
    var humidity by remember { mutableStateOf("0.0") }

    // Store previous values for trend calculation
    var previousValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Bluetooth Integration
    val connectionStatus by bluetoothService?.connectionStatus?.collectAsState() ?: remember { mutableStateOf(BluetoothService.ConnectionStatus.DISCONNECTED) }
    val latestReading by bluetoothService?.latestReading?.collectAsState() ?: remember { mutableStateOf(null) }
    val foundDevices by bluetoothService?.foundDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    
    var showDeviceDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bluetoothService?.startScanning()
            showDeviceDialog = true
        }
    }

    fun updateSensorStatesFromReading(reading: SensorReading) {
        previousValues = mapOf(
            "Ethanol" to ethanol, "Ethylene" to ethylene, "VOC" to voc, "H2S" to hydrogenSulfide,
            "Ammonia (NH3)" to ammonia, "Methanethiol (CH3SH)" to methylMercaptan,
            "DHT11 #1 Temp" to temperature, "DHT11 #1 Humi" to humidity
        )

        reading.values["Ethanol"]?.let { ethanol = it.toString() }
        reading.values["Ethylene"]?.let { ethylene = it.toString() }
        reading.values["VOC"]?.let { voc = it.toString() }
        reading.values["H2S"]?.let { hydrogenSulfide = it.toString() }
        reading.values["Ammonia (NH3)"]?.let { ammonia = it.toString() }
        reading.values["Methanethiol (CH3SH)"]?.let { methylMercaptan = it.toString() }
        reading.values["DHT11 #1 Temp"]?.let { temperature = it.toString() }
        reading.values["DHT11 #1 Humi"]?.let { humidity = it.toString() }
    }

    LaunchedEffect(connectionStatus, historyFiles) {
        if (connectionStatus != BluetoothService.ConnectionStatus.CONNECTED) {
            val latestFile = historyFiles.firstOrNull()
            if (latestFile != null) {
                val readings = loadFileDataSyncInDetection(latestFile)
                readings.lastOrNull()?.let { updateSensorStatesFromReading(it) }
            }
        }
    }

    LaunchedEffect(latestReading) {
        latestReading?.let { updateSensorStatesFromReading(it) }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) { imageUri = uri; spoilageResult = null }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) { imageUri = tempPhotoUri; spoilageResult = null }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bluetooth Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (connectionStatus == BluetoothService.ConnectionStatus.CONNECTED) 
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (connectionStatus == BluetoothService.ConnectionStatus.CONNECTED) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (connectionStatus) {
                            BluetoothService.ConnectionStatus.CONNECTED -> "Connected"
                            BluetoothService.ConnectionStatus.CONNECTING -> "Connecting..."
                            BluetoothService.ConnectionStatus.SCANNING -> "Scanning..."
                            else -> "Sensor Offline"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = {
                    if (connectionStatus == BluetoothService.ConnectionStatus.CONNECTED) bluetoothService?.disconnect()
                    else permissionLauncher.launch(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }) {
                    Text(if (connectionStatus == BluetoothService.ConnectionStatus.CONNECTED) "Disconnect" else "Connect")
                }
            }
        }

        // Image Card
        Card(modifier = Modifier.fillMaxWidth().height(250.dp), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (imageUri != null) {
                    AsyncImage(model = imageUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(painterResource(id = R.drawable.ic_launcher_foreground), null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { val uri = createImageUriInDetection(context); tempPhotoUri = uri; cameraLauncher.launch(uri) }, Modifier.weight(1f)) {
                Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("Take Photo")
            }
            OutlinedButton(onClick = { photoPickerLauncher.launch("image/*") }, Modifier.weight(1f)) {
                Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Gallery")
            }
        }

        HorizontalDivider()

        SensorInputGridInDetection(ethanol, ethylene, voc, hydrogenSulfide, ammonia, methylMercaptan, temperature, humidity, previousValues)

        Button(
            onClick = {
                scope.launch {
                    isDetecting = true; errorMessage = null
                    val currentData = mapOf(
                        "Ethanol" to ethanol, "Ethylene" to ethylene, "VOC" to voc, "H2S" to hydrogenSulfide,
                        "Ammonia (NH3)" to ammonia, "Methanethiol (CH3SH)" to methylMercaptan, "Temp" to temperature, "Humi" to humidity
                    )
                    val result = SensorDataParser.detectSpoilage(context, detectionServerUrl, currentData, imageUri)
                    if (result.isSuccess) spoilageResult = result.getOrNull()
                    else errorMessage = "Detection failed. Check server connection."
                    isDetecting = false
                }
            },
            enabled = !isDetecting && imageUri != null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            if (isDetecting) CircularProgressIndicator(Modifier.size(24.dp), Color.White)
            else Text("Detect Spoilage")
        }

        if (errorMessage != null) Text(errorMessage!!, color = MaterialTheme.colorScheme.error)

        // Multi-Food Result Interface
        spoilageResult?.let { result ->
            Text("Detection Results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            
            // Top Status Card (Summary only, no global percentage)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.isSpoiled) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (result.isSpoiled) "⚠️" else "✅",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (result.isSpoiled) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                    )
                }
            }

            if (result.detectedFoods.isNotEmpty()) {
                result.detectedFoods.forEach { food ->
                    DetectedFoodCard(food = food, originalImageUri = imageUri, context = context)
                }
            }
        }
    }

    if (showDeviceDialog) {
        Dialog(onDismissRequest = { showDeviceDialog = false }) {
            Card(Modifier.fillMaxWidth().height(400.dp).padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Select Sensor", style = MaterialTheme.typography.titleLarge)
                    if (foundDevices.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    else LazyColumn { items(foundDevices) { device -> ListItem(headlineContent = { @SuppressLint("MissingPermission") Text(device.name ?: "Unknown") }, modifier = Modifier.clickable { bluetoothService?.connect(device); showDeviceDialog = false }) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetectedFoodCard(food: DetectedFood, originalImageUri: Uri?, context: Context) {
    val croppedBitmap = remember(food, originalImageUri) {
        if (originalImageUri == null) null
        else try {
            val stream = context.contentResolver.openInputStream(originalImageUri)
            val fullBitmap = BitmapFactory.decodeStream(stream)
            val (x, y, w, h) = food.boundingBox
            val finalX = x.coerceIn(0, (fullBitmap?.width ?: 1) - 1)
            val finalY = y.coerceIn(0, (fullBitmap?.height ?: 1) - 1)
            val finalW = w.coerceAtMost((fullBitmap?.width ?: 0) - finalX).coerceAtLeast(1)
            val finalH = h.coerceAtMost((fullBitmap?.height ?: 0) - finalY).coerceAtLeast(1)
            if (fullBitmap != null) Bitmap.createBitmap(fullBitmap, finalX, finalY, finalW, finalH) else null
        } catch (e: Exception) { null }
    }

    val statusColor = when (food.spoilageLevel.lowercase()) {
        "fresh" -> Color(0xFF2E7D32) // Green
        "unsure" -> Color(0xFFEF6C00) // Orange
        "spoiled" -> Color.Red
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    val containerColor = when (food.spoilageLevel.lowercase()) {
        "fresh" -> Color(0xFFF1F8E9)
        "unsure" -> Color(0xFFFFF3E0)
        "spoiled" -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(Modifier.size(100.dp), shape = MaterialTheme.shapes.small) {
                if (croppedBitmap != null) {
                    Image(bitmap = croppedBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(Icons.Default.PhotoLibrary, null, tint = Color.Gray.copy(alpha = 0.5f)) }
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(Modifier.weight(1f)) {
                Text(food.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = food.spoilageLevel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    color = statusColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Probability: ${String.format(Locale.ENGLISH, "%.1f%%", food.probability * 100)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (food.producedGases.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Produced Gases:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        food.producedGases.forEach { gas ->
                            Card(
                                shape = MaterialTheme.shapes.extraSmall,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = gas,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                if (food.message.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(food.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun loadFileDataSyncInDetection(file: File): List<SensorReading> {
    return try {
        val content = file.readText()
        SensorDataParser.parseDatFile(content)
    } catch (e: Exception) { emptyList() }
}

fun createImageUriInDetection(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun SensorInputGridInDetection(
    ethanol: String, ethylene: String, voc: String, hydrogenSulfide: String,
    ammonia: String, methylMercaptan: String, temperature: String, humidity: String,
    previousValues: Map<String, String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("Ethanol"), value = ethanol, previousValue = previousValues["Ethanol"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("Ethylene"), value = ethylene, previousValue = previousValues["Ethylene"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("VOC"), value = voc, previousValue = previousValues["VOC"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("H2S"), value = hydrogenSulfide, previousValue = previousValues["H2S"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("Ammonia (NH3)"), value = ammonia, previousValue = previousValues["Ammonia (NH3)"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("Methanethiol (CH3SH)"), value = methylMercaptan, previousValue = previousValues["Methanethiol (CH3SH)"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("DHT11 #1 Temp"), value = temperature, previousValue = previousValues["DHT11 #1 Temp"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = SensorRegistry.getDisplayName("DHT11 #1 Humi"), value = humidity, previousValue = previousValues["DHT11 #1 Humi"], modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SensorTextFieldInDetection(label: String, value: String, previousValue: String?, modifier: Modifier = Modifier) {
    val diff = remember(value, previousValue) {
        val cur = value.toDoubleOrNull() ?: 0.0
        val prev = previousValue?.toDoubleOrNull() ?: cur
        cur - prev
    }
    Column(modifier = modifier) {
        if (previousValue != null && diff != 0.0) {
            val isIncrease = diff > 0
            val color = if (isIncrease) Color.Green else Color.Red
            val icon = if (isIncrease) "▲" else "▼"
            Text(text = "$icon ${if (isIncrease) "+" else ""}${String.format(Locale.ENGLISH, "%.2f", diff)}", color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        } else { Text(text = " ", style = MaterialTheme.typography.labelSmall) }
        OutlinedTextField(value = value, onValueChange = { }, label = { Text(label) }, readOnly = true, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

@Preview(showBackground = true, name = "Detection Result Sample")
@Composable
fun DetectionResultPreview() {
    MaterialTheme {
        // Mocking the result you provided
        val mockResult = SpoilageResult(
            isSpoiled = true,
            message = "Detection Complete",
            detectedFoods = listOf(
                DetectedFood(
                    label = "Bread",
                    probability = 0.99f,
                    spoilageLevel = "spoiled",
                    boundingBox = listOf(115, 110, 890, 880),
                    message = "Visible mold detected",
                    producedGases = listOf("VOC")
                )
            )
        )

        // This is a simplified version of the results list for the preview
        Column(Modifier.padding(16.dp)) {
            // ... (Preview UI Code) ...
        }
    }
}
