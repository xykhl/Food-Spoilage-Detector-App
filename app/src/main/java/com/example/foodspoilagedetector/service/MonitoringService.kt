package com.example.foodspoilagedetector.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.foodspoilagedetector.MainActivity
import com.example.foodspoilagedetector.R
import com.example.foodspoilagedetector.model.SensorDataParser
import kotlinx.coroutines.*

class MonitoringService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null

    companion object {
        const val CHANNEL_ID = "SensorMonitoringChannel"
        const val ALERT_CHANNEL_ID = "SpoilageAlertChannel"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2

        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_DATA_SERVER_URL = "extra_data_server_url"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: "http://10.0.2.2:5000/detect"
        val dataServerUrl = intent?.getStringExtra(EXTRA_DATA_SERVER_URL)

        startForegroundService()
        startMonitoring(serverUrl, dataServerUrl)

        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_monitor_title))
            .setContentText(getString(R.string.notification_monitor_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoring(serverUrl: String, dataServerUrl: String?) {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                if (!dataServerUrl.isNullOrBlank()) {
                    try {
                        val snapshotResult = SensorDataParser.fetchLatestSnapshot(dataServerUrl)
                        val reading = snapshotResult.getOrNull()?.first
                        val imageUri = SensorDataParser.fetchLatestImage(this@MonitoringService, dataServerUrl).getOrNull()

                        if (reading != null) {
                            val sensorData = reading.values.mapValues { it.value.toString() }
                            val result = SensorDataParser.detectSpoilage(
                                this@MonitoringService,
                                serverUrl,
                                sensorData,
                                imageUri
                            )

                            if (result.isSuccess) {
                                val spoilage = result.getOrNull()
                                if (spoilage != null && spoilage.isSpoiled) {
                                    sendAlertNotification(spoilage.message)
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }

                delay(10000)
            }
        }
    }

    private fun sendAlertNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_alert_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_monitor_title),
                NotificationManager.IMPORTANCE_LOW
            )

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.notification_alert_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
