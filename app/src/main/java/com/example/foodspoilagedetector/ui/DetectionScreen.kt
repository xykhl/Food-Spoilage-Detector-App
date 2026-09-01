package com.example.foodspoilagedetector.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.foodspoilagedetector.R
import com.example.foodspoilagedetector.model.DetectedFood
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import com.example.foodspoilagedetector.model.SpoilageResult
import com.example.foodspoilagedetector.ui.components.LiveIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun DetectionScreen(
    historyFiles: List<File> = emptyList(),
    detectionServerUrl: String = "http://localhost:5000/detect",
    fridgeImageUri: Uri? = null,
    fridgeImageVersion: Int = 0,
    liveSensorReading: SensorReading? = null,
    automaticSpoilageResult: SpoilageResult? = null,
    isLive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manualSpoilageResult by remember { mutableStateOf<SpoilageResult?>(null) }
    var isDetecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Arrival timestamps so we can show whichever result is newer. A manual tap stamps
    // manualResultTime; each new automatic (SSE) result stamps automaticResultTime.
    var manualResultTime by remember { mutableLongStateOf(0L) }
    var automaticResultTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(automaticSpoilageResult) {
        if (automaticSpoilageResult != null) automaticResultTime = System.currentTimeMillis()
    }

    // Show whichever result arrived most recently; ties fall to the manual result.
    val showAutomatic = automaticSpoilageResult != null &&
        (manualSpoilageResult == null || automaticResultTime > manualResultTime)
    val activeResult = if (showAutomatic) automaticSpoilageResult else manualSpoilageResult
    // Prefer the server's own label ("scheduled" = automatic, "manual" = manual);
    // fall back to which channel it arrived on if the trigger is missing/unknown.
    val isAutomaticResult = when (activeResult?.trigger?.lowercase()) {
        "scheduled", "automatic" -> true
        "manual" -> false
        else -> showAutomatic
    }

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

    fun updateSensorStatesFromReading(reading: SensorReading) {
        previousValues = mapOf(
            "C2H5OH" to ethanol, "C2H4" to ethylene, "VOC" to voc, "H2S" to hydrogenSulfide,
            "NH3" to ammonia, "CH3SH" to methylMercaptan,
            "DHT1_T" to temperature, "DHT1_H" to humidity
        )

        reading.values["C2H5OH"]?.let { ethanol = it.toString() }
        reading.values["C2H4"]?.let { ethylene = it.toString() }
        reading.values["VOC"]?.let { voc = it.toString() }
        reading.values["H2S"]?.let { hydrogenSulfide = it.toString() }
        reading.values["NH3"]?.let { ammonia = it.toString() }
        reading.values["CH3SH"]?.let { methylMercaptan = it.toString() }
        reading.values["DHT1_T"]?.let { temperature = it.toString() }
        reading.values["DHT1_H"]?.let { humidity = it.toString() }
    }

    // Until the data server sends something, fall back to the last recorded session.
    LaunchedEffect(historyFiles, liveSensorReading) {
        if (liveSensorReading == null) {
            historyFiles.firstOrNull()?.let { file ->
                SensorDataParser.loadSensorHistoryFromFile(file).lastOrNull()
                    ?.let { updateSensorStatesFromReading(it) }
            }
        }
    }

    LaunchedEffect(liveSensorReading) {
        liveSensorReading?.let { updateSensorStatesFromReading(it) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Fridge camera feed
        Card(modifier = Modifier.fillMaxWidth().height(250.dp), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (fridgeImageUri != null) {
                    // The frame lives at a fixed path (…/live/latest.jpg) overwritten each
                    // poll, so a plain Uri would be served from Coil's cache and never
                    // refresh. Keying the request on the version forces a fresh decode.
                    val liveModel = remember(fridgeImageUri, fridgeImageVersion) {
                        ImageRequest.Builder(context)
                            .data(fridgeImageUri)
                            .memoryCacheKey("fridge_$fridgeImageVersion")
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    }
                    AsyncImage(model = liveModel, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (isLive) {
                        LiveIndicator(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Text(stringResource(R.string.label_live_camera), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(id = R.drawable.ic_launcher_foreground), null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text(
                            stringResource(R.string.msg_waiting_for_camera),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.title_sensor_data),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Start)
        )

        SensorInputGridInDetection(ethanol, ethylene, voc, hydrogenSulfide, ammonia, methylMercaptan, temperature, humidity, previousValues)

        Button(
            onClick = {
                scope.launch {
                    isDetecting = true; errorMessage = null
                    val currentData = mapOf(
                        "C2H5OH" to ethanol, "C2H4" to ethylene, "VOC" to voc, "H2S" to hydrogenSulfide,
                        "NH3" to ammonia, "CH3SH" to methylMercaptan, "DHT1_T" to temperature, "DHT1_H" to humidity
                    )
                    val result = SensorDataParser.detectSpoilage(context, detectionServerUrl, currentData, fridgeImageUri)
                    if (result.isSuccess) {
                        val spoilage = result.getOrNull()
                        manualSpoilageResult = spoilage
                        manualResultTime = System.currentTimeMillis()
                        if (spoilage != null) {
                            SensorDataParser.saveDetectionHistory(context, currentData, fridgeImageUri, spoilage)
                        }
                    } else errorMessage = context.getString(R.string.msg_detection_failed)
                    isDetecting = false
                }
            },
            enabled = !isDetecting && fridgeImageUri != null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            if (isDetecting) CircularProgressIndicator(Modifier.size(24.dp), Color.White)
            else Text(stringResource(R.string.btn_detect))
        }

        if (errorMessage != null) Text(errorMessage!!, color = MaterialTheme.colorScheme.error)

        // Result Interface
        activeResult?.let { result ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Start)) {
                Text(stringResource(R.string.title_detection_results), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (isAutomaticResult) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "LATEST AUTOMATIC CHECK",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (result.generatedAt != null) {
                Text(
                    text = "Generated at: ${result.generatedAt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 4.dp)
                )
            }

            // Top Status Card
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
                    DetectedFoodCard(food = food, originalImageUri = fridgeImageUri, context = context)
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
        "unsure", "spoiling" -> Color(0xFFEF6C00) // Orange
        "spoiled" -> Color.Red
        else -> MaterialTheme.colorScheme.onSurface
    }

    val containerColor = when (food.spoilageLevel.lowercase()) {
        "fresh" -> Color(0xFFF1F8E9)
        "unsure", "spoiling" -> Color(0xFFFFF3E0)
        "spoiled" -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val aspectRatio = if (food.boundingBox.size >= 4 && food.boundingBox[3] > 0) {
                food.boundingBox[2].toFloat() / food.boundingBox[3].toFloat()
            } else 1f

            Card(
                Modifier
                    .height(100.dp)
                    .aspectRatio(aspectRatio)
                    .widthIn(max = 150.dp),
                shape = MaterialTheme.shapes.small
            ) {
                if (croppedBitmap != null) {
                    Image(bitmap = croppedBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
                    text = stringResource(R.string.label_probability, food.probability * 100),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (food.spoilageScore != null) {
                    Text(
                        text = "Spoilage Score: ${String.format(Locale.getDefault(), "%.4f", food.spoilageScore)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }

                if (food.producedGases.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.label_produced_gases), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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

@Composable
fun SensorInputGridInDetection(
    ethanol: String, ethylene: String, voc: String, hydrogenSulfide: String,
    ammonia: String, methylMercaptan: String, temperature: String, humidity: String,
    previousValues: Map<String, String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = stringResource(R.string.label_ethanol), value = ethanol, previousValue = previousValues["C2H5OH"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = stringResource(R.string.label_ethylene), value = ethylene, previousValue = previousValues["C2H4"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = stringResource(R.string.label_voc), value = voc, previousValue = previousValues["VOC"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = stringResource(R.string.label_h2s), value = hydrogenSulfide, previousValue = previousValues["H2S"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = stringResource(R.string.label_ammonia), value = ammonia, previousValue = previousValues["NH3"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = stringResource(R.string.label_ch3sh), value = methylMercaptan, previousValue = previousValues["CH3SH"], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorTextFieldInDetection(label = stringResource(R.string.label_temp), value = temperature, previousValue = previousValues["DHT1_T"], modifier = Modifier.weight(1f))
            SensorTextFieldInDetection(label = stringResource(R.string.label_humidity), value = humidity, previousValue = previousValues["DHT1_H"], modifier = Modifier.weight(1f))
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

        Column(Modifier.padding(16.dp)) {
            mockResult.detectedFoods.forEach { food ->
                Text(food.label, fontWeight = FontWeight.Bold)
                Text(food.message)
            }
        }
    }
}
