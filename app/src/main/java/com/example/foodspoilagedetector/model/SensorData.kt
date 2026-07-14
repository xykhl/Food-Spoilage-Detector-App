package com.example.foodspoilagedetector.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class SensorReading(
    val timestamp: Long,
    val values: Map<String, Float>
)

data class DetectedFood(
    val label: String,
    val probability: Float,    // 0-1 from server
    val spoilageLevel: String, // "fresh", "unsure", "spoiled"
    val boundingBox: List<Int>, // [x, y, w, h]
    val message: String,
    val producedGases: List<String> = emptyList()
)

data class SpoilageResult(
    val isSpoiled: Boolean,
    val message: String,
    val detectedFoods: List<DetectedFood> = emptyList()
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

    fun saveFileToHistory(context: Context, uri: Uri): String? {
        return try {
            val contentResolver = context.contentResolver
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "upload_${System.currentTimeMillis()}.DAT"

            val historyDir = File(context.filesDir, "history").apply { if (!exists()) mkdirs() }
            val destFile = File(historyDir, fileName)
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to history", e)
            null
        }
    }

    fun getHistoryFiles(context: Context): List<File> {
        val historyDir = File(context.filesDir, "history")
        return historyDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteHistoryFile(file: File): Boolean {
        return file.delete()
    }

    suspend fun downloadFileFromServer(context: Context, urlString: String): Result<String> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val fileName = urlString.substringAfterLast("/")
                    val historyDir = File(context.filesDir, "history").apply { if (!exists()) mkdirs() }
                    val destFile = File(historyDir, fileName)
                    
                    connection.inputStream.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Result.success(fileName)
                } else {
                    Result.failure(Exception("Server returned: ${connection.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
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
        val obj = org.json.JSONObject(json)
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
                conn.readTimeout = 10000
                conn.doOutput = true

                val requestJson = org.json.JSONObject()
                requestJson.put("sensors", org.json.JSONObject(sensorData))
                
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

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    
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

                            detectedFoods.add(DetectedFood(
                                label = food.getString("label"),
                                probability = food.getDouble("probability").toFloat(),
                                spoilageLevel = food.getString("spoilageLevel").lowercase(),
                                boundingBox = bbox,
                                message = food.optString("message", ""),
                                producedGases = producedGases
                            ))
                        }
                    }

                    Result.success(SpoilageResult(
                        isSpoiled = json.getBoolean("isSpoiled"),
                        message = json.getString("message"),
                        detectedFoods = detectedFoods
                    ))
                } else {
                    Result.failure(Exception("Server Error: ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
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
