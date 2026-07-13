package com.example.foodspoilagedetector.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.example.foodspoilagedetector.model.SensorDataParser
import com.example.foodspoilagedetector.model.SensorReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.*

@SuppressLint("MissingPermission")
class BluetoothService(private val context: Context) {
    private val TAG = "BluetoothService"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val scanner = adapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    
    private val _latestReading = MutableStateFlow<SensorReading?>(null)
    val latestReading: StateFlow<SensorReading?> = _latestReading

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices

    private var currentSessionFile: File? = null

    enum class ConnectionStatus {
        DISCONNECTED, SCANNING, CONNECTING, CONNECTED
    }

    fun startScanning() {
        if (_connectionStatus.value == ConnectionStatus.SCANNING) return
        
        _foundDevices.value = emptyList()
        _connectionStatus.value = ConnectionStatus.SCANNING
        
        scanner.startScan(scanCallback)
        
        // Stop scanning after 10 seconds automatically
        Timer().schedule(object : TimerTask() {
            override fun run() {
                stopScanning()
            }
        }, 10000)
    }

    fun stopScanning() {
        if (_connectionStatus.value == ConnectionStatus.SCANNING) {
            scanner.stopScan(scanCallback)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != null && !_foundDevices.value.contains(device)) {
                _foundDevices.value = _foundDevices.value + device
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScanning()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        currentSessionFile = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                gatt.discoverServices()
                
                // Prepare session file
                val historyDir = File(context.filesDir, "history").apply { if (!exists()) mkdirs() }
                val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                currentSessionFile = File(historyDir, "Live_Session_$timeStamp.DAT")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                currentSessionFile = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // In a real scenario, we'd search for specific UUIDs. 
                // For this implementation, we'll try to find any characteristic that supports NOTIFY
                gatt.services.forEach { service ->
                    service.characteristics.forEach { characteristic ->
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                            enableNotifications(gatt, characteristic)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.getStringValue(0)
            if (data != null) {
                processReceivedData(data)
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun processReceivedData(data: String) {
        // Log raw data to session file
        currentSessionFile?.let { file ->
            try {
                file.appendText(data + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to session file", e)
            }
        }

        // Parse latest reading for the UI
        val readings = SensorDataParser.parseDatFile(data)
        if (readings.isNotEmpty()) {
            _latestReading.value = readings.last()
        }
    }
}
