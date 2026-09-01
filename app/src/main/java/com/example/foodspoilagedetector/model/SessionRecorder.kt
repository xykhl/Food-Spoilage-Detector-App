package com.example.foodspoilagedetector.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Buffers the readings polled from the data server and writes them out as a session
 * file once the server stops responding, so a connected period stays inspectable in
 * History after the live feed is gone.
 *
 * Sessions are written as .jsonl in the same shape the gateway uses, so
 * [SensorDataParser.loadSensorHistoryFromFile] reads them back without a special case.
 *
 * Three rules keep an unstable feed from fragmenting into a pile of entries:
 *  - a stretch shorter than [MIN_DURATION_MS] / [MIN_READINGS] is not worth a file;
 *  - a stretch that resumes within [RESUME_WINDOW_MS] of the last one is appended to it
 *    rather than starting a sibling;
 *  - only a real disconnect may start a *new* file. Teardown (URL edited, screen
 *    rotated, language switched) can only extend an existing one.
 *
 * Not thread-safe: it is driven from a single polling coroutine.
 */
class SessionRecorder {
    private val buffer = mutableListOf<SensorReading>()
    private var consecutiveFailures = 0

    /** Buffers a reading, skipping polls where the gateway had nothing new to publish. */
    fun record(reading: SensorReading) {
        consecutiveFailures = 0
        val last = buffer.lastOrNull()
        if (last != null && reading.timestamp > 0L && last.timestamp == reading.timestamp) return
        // A snapshot with an unparseable updated_at still happened now, so stamp it.
        buffer += if (reading.timestamp > 0L) reading
        else reading.copy(timestamp = System.currentTimeMillis())
    }

    /**
     * Counts a failed poll. Returns true exactly once per outage — on the poll where the
     * streak crosses the disconnect threshold. The counter is only reset by [record], so
     * a long outage does not re-trigger.
     */
    fun onFetchFailed(): Boolean {
        consecutiveFailures++
        return consecutiveFailures == DISCONNECT_AFTER_FAILURES
    }

    /**
     * Writes the buffered readings out and clears the buffer.
     *
     * @param allowNewSession true only for a genuine disconnect. When false the readings
     *   can extend a recent session but will never create a new one, so incidental
     *   teardown does not litter History with stray entries.
     * @return the file written or extended, or null if nothing was kept.
     */
    suspend fun flush(context: Context, allowNewSession: Boolean): File? {
        val readings = buffer.toList()
        buffer.clear()
        if (readings.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "history").apply { if (!exists()) mkdirs() }
                val start = readings.first().timestamp
                val end = readings.last().timestamp

                resumableSession(dir, start)?.let { (file, range) ->
                    file.appendText(renderJsonl(readings))
                    val merged = File(dir, "session_${range.first}_${maxOf(range.second, end)}.jsonl")
                    return@withContext if (file.renameTo(merged)) merged else file
                }

                val tooShort = readings.size < MIN_READINGS || end - start < MIN_DURATION_MS
                if (!allowNewSession || tooShort) return@withContext null

                File(dir, "session_${start}_${end}.jsonl")
                    .apply { writeText(renderJsonl(readings)) }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing session file", e)
                null
            }
        }
    }

    /** The most recent session close enough in time that these readings continue it. */
    private fun resumableSession(dir: File, start: Long): Pair<File, Pair<Long, Long>>? {
        return dir.listFiles()
            ?.mapNotNull { file -> parseSessionRange(file)?.let { file to it } }
            ?.maxByOrNull { it.second.second }
            ?.takeIf { start - it.second.second <= RESUME_WINDOW_MS }
    }

    private fun renderJsonl(readings: List<SensorReading>): String {
        val iso = SimpleDateFormat(ISO_PATTERN, Locale.US)
        return buildString {
            readings.forEach { reading ->
                val stamp = iso.format(Date(reading.timestamp))
                reading.values.forEach { (sensor, value) ->
                    val line = JSONObject()
                        .put("timestamp", stamp)
                        .put("sensor", sensor)
                        // via String so 3.4f does not land as 3.4000000953674316
                        .put("value", value.toString().toDouble())
                    append(line.toString()).append('\n')
                }
            }
        }
    }

    companion object {
        private const val TAG = "SessionRecorder"
        private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

        /** Consecutive failed polls before the feed counts as disconnected (~15s at a 5s poll). */
        private const val DISCONNECT_AFTER_FAILURES = 3

        /** Below these a stretch is a blip, not a recording worth its own entry. */
        private const val MIN_READINGS = 10
        private const val MIN_DURATION_MS = 60_000L

        /** Reconnect inside this window continues the previous session instead of starting one. */
        private const val RESUME_WINDOW_MS = 2 * 60_000L

        private val NAME_REGEX = Regex("""session_(\d+)_(\d+)\.jsonl""")

        /** The start/end millis encoded in a session filename, or null for any other file. */
        fun parseSessionRange(file: File): Pair<Long, Long>? =
            NAME_REGEX.matchEntire(file.name)?.let {
                it.groupValues[1].toLongOrNull()?.let { start ->
                    it.groupValues[2].toLongOrNull()?.let { end -> start to end }
                }
            }
    }
}
