package com.example.snatchapp.utils

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.example.snatchapp.model.BleDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // Configure SharedFlow with buffer to prevent data loss
    private val _scanResults = MutableSharedFlow<BleDevice>(
        replay = 1,              // Replay last 1 value for new subscribers
        extraBufferCapacity = 1000  // Extra buffer capacity
    )
    val scanResults: SharedFlow<BleDevice> = _scanResults

    private val _scanErrors = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val scanErrors: SharedFlow<String> = _scanErrors

    init {
        Log.d(TAG, "Initializing BleManager")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        Log.d(TAG, "Bluetooth adapter available: ${bluetoothAdapter != null}")
        Log.d(TAG, "BLE scanner available: ${bluetoothLeScanner != null}")
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresApi(Build.VERSION_CODES.O)
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            try {
                val device = result.device
                val rssi = result.rssi
                val scanRecord = result.scanRecord
                val timestamp = System.currentTimeMillis()

                val isConnectable = result.isConnectable

                // Process payload data
                val (payloadBytes, payloadHex, payloadLength) = processScanRecord(scanRecord)

                val bleDevice = BleDevice(
                    macAddress = device.address ?: "Unknown",
                    rssi = rssi,
                    deviceName = if (hasBluetoothPermission()) {
                        device.name ?: "Unknown Device"
                    } else {
                        "Unknown Device"
                    },
                    payloadBytes = payloadBytes,
                    payloadHex = payloadHex,
                    payloadLength = payloadLength,
                    isConnectable = isConnectable,
                    timestamp = timestamp
                )

                Log.d(TAG, "Device found: MAC=${bleDevice.macAddress}, RSSI=${rssi}, Name=${bleDevice.deviceName}, Payload=${bleDevice.payloadHex}, Timestamp=${bleDevice.timestamp}")

                // Use tryEmit and check result
                val emitSuccess = _scanResults.tryEmit(bleDevice)
                Log.d(TAG, "Data emission result: $emitSuccess")

                if (!emitSuccess) {
                    Log.w(TAG, "Data emission failed, buffer might be full")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing scan result: ${e.message}", e)
                _scanErrors.tryEmit("Scan result processing error: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan not supported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Out of hardware resources"
                else -> "Unknown scan error: $errorCode"
            }
            Log.e(TAG, "Scan failed: $errorMessage")
            _scanErrors.tryEmit(errorMessage)
            isScanning = false
        }
    }

    /**
     * Process scan record, remove trailing zeros and get actual length
     */
    private fun processScanRecord(scanRecord: android.bluetooth.le.ScanRecord?): Triple<ByteArray, String, Int> {
        if (scanRecord?.bytes == null) {
            return Triple(byteArrayOf(), "", 0)
        }

        val originalBytes = scanRecord.bytes
        var actualLength = 0

        // Parse AD structures to determine actual length
        var index = 0
        while (index < originalBytes.size) {
            if (originalBytes[index] == 0.toByte()) {
                // Found AD structure with length 0, padding follows
                break
            }

            val length = originalBytes[index].toInt() and 0xFF
            if (length == 0) break

            // Check for buffer overflow
            if (index + length + 1 > originalBytes.size) break

            actualLength = index + length + 1
            index += length + 1
        }

        // If no valid AD structure found, use original length
        if (actualLength == 0) {
            actualLength = originalBytes.size
        }

        // Extract effective bytes
        val effectiveBytes = originalBytes.copyOfRange(0, actualLength)
        val hexString = bytesToHex(effectiveBytes)

        Log.d(TAG, "Original payload length: ${originalBytes.size}, Effective length: $actualLength")

        return Triple(effectiveBytes, hexString, actualLength)
    }

    /**
     * Start BLE scanning
     * @return true if scan started successfully, false otherwise
     */
    fun startScan(): Boolean {
        Log.d(TAG, "Starting BLE scan...")

        try {
            if (!isBluetoothEnabled()) {
                Log.e(TAG, "Bluetooth is not enabled")
                _scanErrors.tryEmit("Bluetooth is not enabled")
                return false
            }

            if (!hasBluetoothPermission()) {
                Log.e(TAG, "Missing Bluetooth permissions")
                _scanErrors.tryEmit("Missing bluetooth permissions")
                return false
            }

            if (isScanning) {
                Log.w(TAG, "Scan already in progress")
                _scanErrors.tryEmit("Scan already in progress")
                return false
            }

            Log.d(TAG, "Bluetooth is enabled and permissions are granted, starting scan...")

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true

            Log.d(TAG, "BLE scan started successfully")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}", e)
            _scanErrors.tryEmit("Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan: ${e.message}", e)
            _scanErrors.tryEmit("Failed to start scan: ${e.message}")
            return false
        }
    }

    /**
     * Stop BLE scanning
     */
    fun stopScan() {
        Log.d(TAG, "Stopping BLE scan")

        try {
            if (isScanning && hasBluetoothPermission()) {
                bluetoothLeScanner?.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "BLE scan stopped successfully")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping scan: ${e.message}", e)
            _scanErrors.tryEmit("Permission denied when stopping scan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan: ${e.message}", e)
            _scanErrors.tryEmit("Failed to stop scan: ${e.message}")
        }
    }

    /**
     * Check if currently scanning
     */
    fun isScanning(): Boolean = isScanning

    /**
     * Check if Bluetooth is enabled
     */
    private fun isBluetoothEnabled(): Boolean {
        val enabled = bluetoothAdapter?.isEnabled == true
        Log.d(TAG, "Bluetooth enabled status: $enabled")
        return enabled
    }

    /**
     * Check if app has necessary Bluetooth permissions
     */
    private fun hasBluetoothPermission(): Boolean {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Bluetooth permission status: $hasPermission")
        return hasPermission
    }

    /**
     * Convert byte array to hex string
     */
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * Get debug information for troubleshooting
     */
    fun getDebugInfo(): String {
        return """
            Bluetooth Adapter: ${bluetoothAdapter != null}
            Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}
            BLE Scanner: ${bluetoothLeScanner != null}
            Is Scanning: $isScanning
            Has Permission: ${hasBluetoothPermission()}
            Android Version: ${Build.VERSION.SDK_INT}
        """.trimIndent()
    }

    /**
     * Test method: manually emit a test device for debugging
     */
    fun emitTestDevice() {
        Log.d(TAG, "Emitting test device")
        val testDevice = BleDevice(
            macAddress = "00:11:22:33:44:55",
            rssi = -50,
            deviceName = "Test Device",
            payloadHex = "AABBCCDD",
            payloadBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            payloadLength = 4,
            isConnectable = true,
            timestamp = System.currentTimeMillis()
        )

        val success = _scanResults.tryEmit(testDevice)
        Log.d(TAG, "Test device emission result: $success")
    }

    /**
     * Get current scan results buffer size for monitoring
     */
    fun getScanResultsBufferInfo(): String {
        return "Scan results buffer - Subscription count: ${_scanResults.subscriptionCount.value}"
    }

    /**
     * Force clear any buffered scan results
     */
    fun clearBuffer() {
        // Note: There's no direct way to clear MutableSharedFlow buffer
        // This is mainly for future reference if needed
        Log.d(TAG, "Buffer clear requested - recreating flows")
    }
}