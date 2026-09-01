package com.example.foodspoilagedetector.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONArray
import org.json.JSONObject

data class SensorReading(
    val timestamp: Long,
    val values: Map<String, Float>
)

data class DetectedFood(
    val label: String,
    val probability: Float,    // CONFIDENCE in the food type (e.g. "Shrimp")
    val spoilageScore: Float? = null, // New: Raw score from server
    val spoilageLevel: String, // "fresh", "unsure", "spoiled"
    val boundingBox: List<Int>, // [x, y, w, h]
    val message: String,
    val producedGases: List<String> = emptyList()
)

data class SpoilageResult(
    val isSpoiled: Boolean,
    val message: String,
    val detectedFoods: List<DetectedFood> = emptyList(),
    val generatedAt: String? = null,
    val nextRunInSeconds: Long? = null,
    val trigger: String? = null,      // "manual", "scheduled", or null if unknown
    val ageSeconds: Long? = null
)

data class DetectionHistoryEntry(
    val timestamp: Long,
    val sensorData: Map<String, String>,
    val imageUri: Uri,
    val result: SpoilageResult
)

data class SensorMetadata(
    val displayName: String,
    val unit: String
)

data class CameraSnapshot(
    val device: String,
    val deviceId: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val path: String,
    val historyFile: String
)

object SensorRegistry {
    private val metadataMap = mapOf(
        "C2H4" to SensorMetadata("Ethylene", "ppm"),
        "C2H5OH" to SensorMetadata("Ethanol", "%LEL"),
        "DHT1_T" to SensorMetadata("Temp 1", "°C"),
        "DHT1_H" to SensorMetadata("Humi 1", "%RH"),
        "DHT2_T" to SensorMetadata("Temp 2", "°C"),
        "DHT2_H" to SensorMetadata("Humi 2", "%RH"),
        "CH3SH" to SensorMetadata("CH3SH", "ppm"),
        "H2S" to SensorMetadata("H2S", "ppm"),
        "NH3" to SensorMetadata("Ammonia", "ppm"),
        "VOC" to SensorMetadata("VOC", "ppm")
    )

    fun getDisplayName(key: String): String = metadataMap[key]?.displayName ?: key
    fun getUnit(key: String): String = metadataMap[key]?.unit ?: ""
}

object SensorDataParser {
    private const val TAG = "SensorDataParser"

    /** Sensor key -> the gateway subfolder that logs its .jsonl history. */
    private val SENSOR_JSONL_LOCATIONS = mapOf(
        "C2H4" to "esp32_env", "C2H5OH" to "esp32_env",
        "DHT1_T" to "esp32_env", "DHT1_H" to "esp32_env",
        "DHT2_T" to "esp32_env", "DHT2_H" to "esp32_env",
        "CH3SH" to "esp32_gas", "H2S" to "esp32_gas",
        "NH3" to "esp32_gas", "VOC" to "esp32_gas"
    )

    fun getHistoryFiles(context: Context): List<File> {
        val historyDir = File(context.filesDir, "history")
        return historyDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteHistoryFile(file: File): Boolean {
        return file.delete()
    }

    /** Parses an ISO-8601 timestamp with numeric offset, e.g. "2026-07-14T15:05:26.969+08:00". */
    fun parseIsoTimestamp(iso: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            format.parse(iso)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Parses a data/latest.json snapshot. The "sensors" key set varies run to run
     * depending on which gateways last reported, so keys are read dynamically
     * rather than assumed fixed.
     */
    fun parseLatestJson(json: String): Pair<SensorReading, CameraSnapshot?> {
        val obj = JSONObject(json)
        val timestamp = parseIsoTimestamp(obj.optString("updated_at"))

        val values = mutableMapOf<String, Float>()
        obj.optJSONObject("sensors")?.let { sensorsObj ->
            sensorsObj.keys().forEach { key ->
                val value = sensorsObj.optJSONObject(key)?.optDouble("value", Double.NaN)
                if (value != null && !value.isNaN()) {
                    values[key] = value.toFloat()
                }
            }
        }

        val camera = obj.optJSONObject("camera")?.let {
            CameraSnapshot(
                device = it.optString("device"),
                deviceId = it.optString("device_id"),
                timestamp = parseIsoTimestamp(it.optString("timestamp")),
                width = it.optInt("width"),
                height = it.optInt("height"),
                path = it.optString("path"),
                historyFile = it.optString("history_file")
            )
        }

        return SensorReading(timestamp, values) to camera
    }

    /** Fetches and parses {dataServerUrl}/latest.json. */
    suspend fun fetchLatestSnapshot(dataServerUrl: String): Result<Pair<SensorReading, CameraSnapshot?>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("${dataServerUrl.trimEnd('/')}/latest.json")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(parseLatestJson(body))
                } else {
                    Result.failure(Exception("Server returned: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Fetches {dataServerUrl}/latest.jpg, overwriting the same local file each call. */
    suspend fun fetchLatestImage(context: Context, dataServerUrl: String): Result<Uri> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("${dataServerUrl.trimEnd('/')}/latest.jpg")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val liveDir = File(context.filesDir, "live").apply { if (!exists()) mkdirs() }
                    val destFile = File(liveDir, "latest.jpg")
                    connection.inputStream.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Result.success(Uri.fromFile(destFile))
                } else {
                    Result.failure(Exception("Server returned: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Parses one line of a per-sensor .jsonl log, e.g. {"timestamp":"...","sensor":"VOC","value":0.0,...}. */
    fun parseJsonlLine(line: String): SensorReading? {
        if (line.isBlank()) return null
        return try {
            val obj = JSONObject(line)
            val sensorKey = obj.optString("sensor")
            val value = obj.optDouble("value", Double.NaN)
            if (sensorKey.isEmpty() || value.isNaN()) return null
            SensorReading(
                timestamp = parseIsoTimestamp(obj.optString("timestamp")),
                values = mapOf(sensorKey to value.toFloat())
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Parses every line of a per-sensor .jsonl log into readings, skipping malformed lines. */
    fun parseJsonlContent(content: String): List<SensorReading> {
        return content.lines().mapNotNull { parseJsonlLine(it) }
    }

    /** Fetches and parses one sensor's {dataServerUrl}/{subfolder}/{sensorKey}.jsonl history log. */
    suspend fun fetchSensorHistory(dataServerUrl: String, sensorKey: String, subfolder: String): Result<List<SensorReading>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("${dataServerUrl.trimEnd('/')}/$subfolder/$sensorKey.jsonl")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    Result.success(parseJsonlContent(body))
                } else {
                    Result.failure(Exception("Server returned: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Fetches every known sensor's .jsonl history in parallel and merges them into one
     * timestamp-sorted list. Each SensorReading only carries its own sensor's key, so
     * this plugs directly into the existing SensorGraphCard/SensorGraph without needing
     * to merge readings by timestamp across sensors. Sensors whose file is missing or
     * fails to fetch are silently skipped rather than failing the whole history.
     */
    suspend fun fetchAllSensorHistory(dataServerUrl: String): List<SensorReading> {
        return kotlinx.coroutines.coroutineScope {
            SENSOR_JSONL_LOCATIONS.map { (sensorKey, subfolder) ->
                async { fetchSensorHistory(dataServerUrl, sensorKey, subfolder).getOrNull() }
            }.awaitAll().filterNotNull().flatten().sortedBy { it.timestamp }
        }
    }

    suspend fun fetchAutomaticDetection(url: String): Result<SpoilageResult> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val urlObj = java.net.URL(url)
                val conn = urlObj.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    Result.success(parseSpoilageResultFromJson(json))
                } else {
                    Result.failure(Exception("Server returned: ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun parseSpoilageResultFromString(json: String): SpoilageResult {
        return parseSpoilageResultFromJson(JSONObject(json))
    }

    suspend fun detectSpoilage(
        context: Context,
        serverUrl: String, 
        sensorData: Map<String, String>,
        imageUri: Uri?
    ): Result<SpoilageResult> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL(serverUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 120000
                conn.doOutput = true

                val requestJson = JSONObject()
                requestJson.put("sensors", JSONObject(sensorData))
                
                imageUri?.let { uri ->
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { 
                        BitmapFactory.decodeStream(it)
                    }
                    bitmap?.let {
                        val outputStream = ByteArrayOutputStream()
                        it.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
                        requestJson.put("image", base64Image)
                    }
                }

                conn.outputStream.use { it.write(requestJson.toString().toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Detection raw response: $response")
                    val json = JSONObject(response)
                    val parsed = parseSpoilageResultFromJson(json)
                    Log.d(TAG, "Detection parsed: isSpoiled=${parsed.isSpoiled}, " +
                        "message='${parsed.message}', foods=${parsed.detectedFoods.size}")
                    Result.success(parsed)
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e(TAG, "Detection failed with code: ${conn.responseCode}, body: $err")
                    Result.failure(Exception("Server Error: ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection exception", e)
                Result.failure(e)
            }
        }
    }

    fun parseSpoilageResultFromJson(json: JSONObject): SpoilageResult {
        val foodsJson = json.optJSONArray("detectedFoods")
        val detectedFoods = mutableListOf<DetectedFood>()
        if (foodsJson != null) {
            for (i in 0 until foodsJson.length()) {
                val food = foodsJson.getJSONObject(i)
                val bboxJson = food.getJSONArray("boundingBox")
                val bbox = listOf(bboxJson.getInt(0), bboxJson.getInt(1), bboxJson.getInt(2), bboxJson.getInt(3))
                
                val gasesJson = food.optJSONArray("producedGases")
                val producedGases = mutableListOf<String>()
                if (gasesJson != null) {
                    for (j in 0 until gasesJson.length()) {
                        producedGases.add(gasesJson.getString(j))
                    }
                }

                // Support new type_probability and spoilageScore
                val prob = if (food.has("type_probability") && !food.isNull("type_probability"))
                              food.getDouble("type_probability").toFloat()
                          else food.optDouble("probability", 0.0).toFloat()
                val score = if (food.has("spoilageScore") && !food.isNull("spoilageScore"))
                    food.getDouble("spoilageScore").toFloat() else null

                detectedFoods.add(DetectedFood(
                    label = food.getString("label"),
                    probability = prob,
                    spoilageScore = score,
                    spoilageLevel = food.getString("spoilageLevel").lowercase(),
                    boundingBox = bbox,
                    message = food.optString("message", ""),
                    producedGases = producedGases
                ))
            }
        }

        return SpoilageResult(
            isSpoiled = json.optBoolean("isSpoiled"),
            message = json.optString("message", ""),
            detectedFoods = detectedFoods,
            generatedAt = if (json.isNull("generated_at")) null else json.optString("generated_at"),
            nextRunInSeconds = if (json.isNull("next_run_in_seconds")) null else json.optLong("next_run_in_seconds"),
            trigger = if (json.has("trigger") && !json.isNull("trigger")) json.optString("trigger") else null,
            ageSeconds = if (json.has("age_seconds") && !json.isNull("age_seconds")) json.optLong("age_seconds") else null
        )
    }

    suspend fun saveDetectionHistory(context: Context, sensorData: Map<String, String>, imageUri: Uri?, result: SpoilageResult) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val detectionsDir = File(context.filesDir, "detections")
            if (!detectionsDir.exists()) detectionsDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val baseName = "detection_$timestamp"

            // Save Image if present
            var savedImageUri = Uri.EMPTY
            imageUri?.let { uri ->
                val destFile = File(detectionsDir, "$baseName.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                savedImageUri = Uri.fromFile(destFile)
            }

            // Save Metadata JSON
            val metaObj = JSONObject()
            metaObj.put("t", timestamp)
            metaObj.put("image", savedImageUri.toString())
            
            val sensorsObj = JSONObject()
            sensorData.forEach { (k, v) -> sensorsObj.put(k, v) }
            metaObj.put("sensors", sensorsObj)

            val resultObj = JSONObject()
            resultObj.put("isSpoiled", result.isSpoiled)
            resultObj.put("message", result.message)
            result.generatedAt?.let { resultObj.put("generated_at", it) }
            result.nextRunInSeconds?.let { resultObj.put("next_run_in_seconds", it) }
            result.trigger?.let { resultObj.put("trigger", it) }
            result.ageSeconds?.let { resultObj.put("age_seconds", it) }

            val foodsArr = JSONArray()
            result.detectedFoods.forEach { food ->
                val foodObj = JSONObject()
                foodObj.put("label", food.label)
                foodObj.put("type_probability", food.probability.toDouble())
                food.spoilageScore?.let { foodObj.put("spoilageScore", it.toDouble()) }
                foodObj.put("spoilageLevel", food.spoilageLevel)
                foodObj.put("message", food.message)
                val bboxArr = JSONArray()
                food.boundingBox.forEach { bboxArr.put(it) }
                foodObj.put("boundingBox", bboxArr)
                val gasesArr = JSONArray()
                food.producedGases.forEach { gasesArr.put(it) }
                foodObj.put("producedGases", gasesArr)
                foodsArr.put(foodObj)
            }
            resultObj.put("detectedFoods", foodsArr)
            metaObj.put("result", resultObj)

            File(detectionsDir, "$baseName.json").writeText(metaObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving detection history", e)
        }
        }
    }

    /**
     * Loads one saved session file into readings. Files arrive either as the legacy
     * serial-log .DAT format or as the gateway's per-sensor .jsonl format, so the
     * parser is picked by extension.
     */
    suspend fun loadSensorHistoryFromFile(file: File): List<SensorReading> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val content = file.readText()
                if (file.name.endsWith(".jsonl", ignoreCase = true)) parseJsonlContent(content)
                else parseDatFile(content)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading sensor history from ${file.name}", e)
                emptyList()
            }
        }
    }

    fun getDetectionHistoryFiles(context: Context): List<File> {
        val detectionsDir = File(context.filesDir, "detections")
        if (!detectionsDir.exists()) return emptyList()
        return (detectionsDir.listFiles()?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    suspend fun loadDetectionHistoryEntry(file: File): DetectionHistoryEntry? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            parseDetectionHistoryEntry(file)
        }
    }

    private fun parseDetectionHistoryEntry(file: File): DetectionHistoryEntry? {
        return try {
            val obj = JSONObject(file.readText())
            val timestamp = obj.getLong("t")
            val imageUriString = obj.optString("image", "")
            val imageUri = if (imageUriString.isNotEmpty()) Uri.parse(imageUriString) else Uri.EMPTY
            
            val sensors = mutableMapOf<String, String>()
            val sensorsObj = obj.getJSONObject("sensors")
            sensorsObj.keys().forEach { sensors[it] = sensorsObj.getString(it) }

            val resObj = obj.getJSONObject("result")
            val detectedFoods = mutableListOf<DetectedFood>()
            val foodsArr = resObj.optJSONArray("detectedFoods")
            if (foodsArr != null) {
                for (i in 0 until foodsArr.length()) {
                    val food = foodsArr.getJSONObject(i)
                    val bboxArr = food.getJSONArray("boundingBox")
                    val bbox = listOf(bboxArr.getInt(0), bboxArr.getInt(1), bboxArr.getInt(2), bboxArr.getInt(3))
                    val gasesArr = food.optJSONArray("producedGases")
                    val gases = mutableListOf<String>()
                    if (gasesArr != null) {
                        for (j in 0 until gasesArr.length()) gases.add(gasesArr.getString(j))
                    }

                    // Handle new probability/score fields
                    val prob = if (food.has("type_probability") && !food.isNull("type_probability"))
                                  food.getDouble("type_probability").toFloat()
                              else food.optDouble("probability", 0.0).toFloat()
                    val score = if (food.has("spoilageScore") && !food.isNull("spoilageScore"))
                        food.getDouble("spoilageScore").toFloat() else null

                    detectedFoods.add(DetectedFood(
                        label = food.getString("label"),
                        probability = prob,
                        spoilageScore = score,
                        spoilageLevel = food.getString("spoilageLevel"),
                        boundingBox = bbox,
                        message = food.optString("message", ""),
                        producedGases = gases
                    ))
                }
            }

            DetectionHistoryEntry(
                timestamp = timestamp,
                sensorData = sensors,
                imageUri = imageUri,
                result = SpoilageResult(
                    isSpoiled = resObj.optBoolean("isSpoiled"),
                    message = resObj.optString("message", ""),
                    detectedFoods = detectedFoods,
                    generatedAt = if (resObj.isNull("generated_at")) null else resObj.optString("generated_at"),
                    nextRunInSeconds = if (resObj.isNull("next_run_in_seconds")) null else resObj.optLong("next_run_in_seconds"),
                    trigger = if (resObj.has("trigger") && !resObj.isNull("trigger")) resObj.optString("trigger") else null,
                    ageSeconds = if (resObj.has("age_seconds") && !resObj.isNull("age_seconds")) resObj.optLong("age_seconds") else null
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseDatFile(content: String): List<SensorReading> {
        val readings = mutableListOf<SensorReading>()
        val lines = content.lines()
        
        var currentTimestamp: Long = 0
        val currentMap = mutableMapOf<String, Float>()
        var lastSensorName: String? = null

        val headerRegex = Regex("""\[(\d{2}:\d{2}:\d{2}\.\d{3})\]\s+\[(.*?)\]""")
        val concentrationRegex = Regex("""Concentration:\s+([\d.]+)""")
        val dhtRegex = Regex("""DHT11\s+#(\d+)\s+(Temp|Humi):\s+([\d.]+)""")

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("---")) {
                return@forEach
            }

            headerRegex.find(trimmed)?.let { match ->
                val time = parseTimestamp(match.groupValues[1])
                // If timestamp jumps significantly (> 500ms), it's likely a new set of data points
                if (currentMap.isNotEmpty() && time > currentTimestamp + 500) {
                    readings.add(SensorReading(currentTimestamp, currentMap.toMap()))
                    currentMap.clear()
                }
                currentTimestamp = time
                lastSensorName = match.groupValues[2]
                return@forEach
            }

            concentrationRegex.find(trimmed)?.let { match ->
                val value = match.groupValues[1].toFloatOrNull()
                if (value != null && lastSensorName != null) {
                    currentMap[lastSensorName!!] = value
                }
                return@forEach
            }

            dhtRegex.find(trimmed)?.let { match ->
                val id = match.groupValues[1]
                val type = match.groupValues[2]
                val value = match.groupValues[3].toFloatOrNull()
                if (value != null) {
                    currentMap["DHT11 #$id $type"] = value
                }
                return@forEach
            }
            
            // Fallback for simple single-line formats if any
            if (trimmed.contains(":") && !trimmed.contains("[")) {
                 // If we have existing data from a block, save it first if we are switching to single line mode
                 // or just treat each line as a new reading.
                 val lineMap = mutableMapOf<String, Float>()
                 val parts = trimmed.split(",")
                 parts.forEach { part ->
                     val kv = part.split(":")
                     if (kv.size == 2) {
                         val name = kv[0].trim()
                         val value = kv[1].trim().split(" ")[0].toFloatOrNull()
                         if (value != null) {
                             lineMap[name] = value
                         }
                     }
                 }
                 if (lineMap.isNotEmpty()) {
                     readings.add(SensorReading(currentTimestamp, lineMap))
                     currentTimestamp += 1000 // Increment for subsequent single lines
                 }
                 return@forEach
            }
        }
        
        if (currentMap.isNotEmpty()) {
            readings.add(SensorReading(currentTimestamp, currentMap))
        }

        return readings
    }

    private fun parseTimestamp(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":", ".")
            if (parts.size == 4) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toLong()
                val ms = parts[3].toLong()
                (h * 3600 + m * 60 + s) * 1000 + ms
            } else 0
        } catch (e: Exception) {
            0
        }
    }
}
