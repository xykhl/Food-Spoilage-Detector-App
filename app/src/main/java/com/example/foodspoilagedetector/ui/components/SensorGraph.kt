package com.example.foodspoilagedetector.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.foodspoilagedetector.model.SensorReading
import kotlin.math.roundToInt

@Composable
fun SensorGraph(
    readings: List<SensorReading>,
    sensorKey: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    val sensorData = readings.mapNotNull { it.values[sensorKey] }
    val timeLabels = readings.map { formatTime(it.timestamp) }
    
    if (sensorData.isEmpty()) return

    val maxVal = sensorData.maxOrNull() ?: 1f
    val minVal = sensorData.minOrNull() ?: 0f
    val range = if (maxVal == minVal) 1f else maxVal - minVal
    
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val density = LocalDensity.current
    val textPaint = android.graphics.Paint().apply {
        color = labelColor.hashCode()
        textSize = with(density) { 10.sp.toPx() }
        textAlign = android.graphics.Paint.Align.CENTER
    }

    Box(modifier = modifier.padding(bottom = 24.dp, start = 40.dp, end = 16.dp, top = 24.dp)) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val width = size.width
            val height = size.height
            val stepX = width / (sensorData.size - 1).coerceAtLeast(1)

            // Draw Grid/Axes
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(0f, 0f),
                end = Offset(0f, height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )

            // Draw Data Path
            val path = Path().apply {
                sensorData.forEachIndexed { index, value ->
                    val x = index * stepX
                    val y = height - ((value - minVal) / range * height)
                    if (index == 0) {
                        moveTo(x, y)
                    } else {
                        lineTo(x, y)
                    }
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw Y-axis labels (Min/Max)
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.2f", maxVal),
                -10f,
                10f,
                textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT }
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.2f", minVal),
                -10f,
                height,
                textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT }
            )

            // Draw X-axis labels (Start/End time)
            if (timeLabels.isNotEmpty()) {
                drawContext.canvas.nativeCanvas.drawText(
                    timeLabels.first(),
                    0f,
                    height + 30f,
                    textPaint.apply { textAlign = android.graphics.Paint.Align.LEFT }
                )
                drawContext.canvas.nativeCanvas.drawText(
                    timeLabels.last(),
                    width,
                    height + 30f,
                    textPaint.apply { textAlign = android.graphics.Paint.Align.RIGHT }
                )
            }
        }
        
        // Axis Titles
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-35).dp, y = (-20).dp)
        )
        Text(
            text = "Time",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 0.dp, y = 15.dp)
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}
