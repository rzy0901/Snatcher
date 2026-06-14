package com.example.snatchapp.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.snatchapp.model.BleDevice
import com.example.snatchapp.model.ImuData
import com.example.snatchapp.model.RssiDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class LogManager(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    companion object {
        private const val LOG_DIR = "SnatchAPP_Logs"
        private const val BLE_LOG_PREFIX = "ble_scan_"
        private const val IMU_LOG_PREFIX = "imu_data_"
        private const val NAV_RSSI_LOG_PREFIX = "NavRSSI_"
        private const val AUDIO_LEFT_PREFIX = "audio_left_"
        private const val AUDIO_RIGHT_PREFIX = "audio_right_"
        private const val LOG_EXTENSION = ".csv"
        private const val AUDIO_EXTENSION = ".pcm"
        private const val METADATA_EXTENSION = ".txt"
    }

    // Get the log directory
    private fun getLogDirectory(): File {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val logDir = File(externalDir, LOG_DIR)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }

    // Generate the default file name
    fun generateDefaultFileName(type: String): String {
        val timestamp = dateFormat.format(Date())
        return when (type.lowercase()) {
            "ble" -> "$BLE_LOG_PREFIX$timestamp$LOG_EXTENSION"
            "imu" -> "$IMU_LOG_PREFIX$timestamp$LOG_EXTENSION"
            "lost_device" -> "lost_device_$timestamp$LOG_EXTENSION"
            "nav_rssi" -> "$NAV_RSSI_LOG_PREFIX$timestamp$LOG_EXTENSION"
            else -> "data_$timestamp$LOG_EXTENSION"
        }
    }

    // Create the BLE log file
    suspend fun createBleLogFile(fileName: String): File = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        if (!file.exists()) {
            file.createNewFile()
            // Write the CSV header
            FileWriter(file, true).use { writer ->
                writer.append("Timestamp,MAC_Address,RSSI,Device_Name,Payload_Hex,Payload_Length,Connectable\n")
            }
        }
        file
    }

    // Create the Lost Device log file
    suspend fun createLostDeviceLogFile(fileName: String): File = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        if (!file.exists()) {
            file.createNewFile()
            FileWriter(file, true).use { writer ->
                writer.append("Timestamp,MAC_Address,RSSI,Device_Name,Payload_Hex,Payload_Length,Connectable\n")
            }
        }
        file
    }

    // Create the IMU log file
    suspend fun createImuLogFile(fileName: String): File = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        if (!file.exists()) {
            file.createNewFile()
            // Write the CSV header
            FileWriter(file, true).use { writer ->
                writer.append("Timestamp,Accelerometer_X,Accelerometer_Y,Accelerometer_Z,Gyroscope_X,Gyroscope_Y,Gyroscope_Z,Magnetometer_X,Magnetometer_Y,Magnetometer_Z,Gravity_X,Gravity_Y,Gravity_Z,Orientation_Azimuth,Position_X,Position_Y,Linear_Acceleration_X,Linear_Acceleration_Y,Linear_Acceleration_Z,Step_Count\n")
            }
        }
        file
    }

    // Create the navigation RSSI log file
    suspend fun createNavRssiLogFile(fileName: String): File = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        if (!file.exists()) {
            file.createNewFile()
            // Write the CSV header - Phase column marks the logging phase
            FileWriter(file, true).use { writer ->
                writer.append("Timestamp,MAC_Address,RSSI,Phone_Direction,Position_X,Position_Y,Step_Count,Target_Direction,Cluster,Ground_Truth,Phase\n")
            }
        }
        file
    }

    // Write BLE data
    suspend fun logBleDevice(fileName: String, device: BleDevice) = withContext(Dispatchers.IO) {
        try {
            val file = File(getLogDirectory(), fileName)
            FileWriter(file, true).use { writer ->
                val timestamp = timestampFormat.format(Date(device.timestamp))
                val deviceName = device.deviceName?.replace(",", ";") ?: "Unknown"
                val macAddress = device.macAddress
                val rssi = device.rssi
                val payload = device.payloadHex
//                val payloadLength = device.payloadHex.length / 2 // Assume it is a hex string
                val payloadLength = device.payloadLength
                val connectable = if (device.isConnectable) "true" else "false"
                Log.d("LogManager", "Logging BLE device at timestamp: $timestamp, MAC: $macAddress, RSSI: $rssi, Name: $deviceName, Payload: $payload, Length: $payloadLength, Connectable: $connectable")
                writer.append("$timestamp,$macAddress,$rssi,$deviceName,$payload,$payloadLength,$connectable\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Write Lost Device data
    suspend fun logLostDevice(fileName: String, device: BleDevice) = withContext(Dispatchers.IO) {
        try {
            val file = File(getLogDirectory(), fileName)
            FileWriter(file, true).use { writer ->
                val timestamp = timestampFormat.format(Date(device.timestamp))
                val deviceName = device.deviceName?.replace(",", ";") ?: "Unknown"
                val macAddress = device.macAddress
                val rssi = device.rssi
                val payload = device.payloadHex
                val payloadLength = device.payloadLength
                val connectable = if (device.isConnectable) "true" else "false"

                writer.append("$timestamp,$macAddress,$rssi,$deviceName,$payload,$payloadLength,$connectable\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Write IMU data
    suspend fun logImuData(fileName: String, imuData: ImuData) = withContext(Dispatchers.IO) {
        try {
            val file = File(getLogDirectory(), fileName)
            FileWriter(file, true).use { writer ->
                val timestamp = timestampFormat.format(Date(imuData.timestamp))
                writer.append("$timestamp,${imuData.accelerometerX},${imuData.accelerometerY},${imuData.accelerometerZ},${imuData.gyroscopeX},${imuData.gyroscopeY},${imuData.gyroscopeZ},${imuData.magnetometerX},${imuData.magnetometerY},${imuData.magnetometerZ},${imuData.gravityX},${imuData.gravityY},${imuData.gravityZ},${imuData.orientationAzimuth},${imuData.positionX},${imuData.positionY},${imuData.linearAccelerationX},${imuData.linearAccelerationY},${imuData.linearAccelerationZ},${imuData.stepCount}\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Write navigation RSSI data
    // phase: free-form label for the logging session (kept for log-format compatibility)
    suspend fun logNavRssiData(fileName: String, rssiDataPoint: RssiDataPoint, clusterId: Int? = null, groundTruth: Int? = null, phase: String = "nav") = withContext(Dispatchers.IO) {
        try {
            val file = File(getLogDirectory(), fileName)
            FileWriter(file, true).use { writer ->
                val timestamp = timestampFormat.format(Date(rssiDataPoint.timestamp))
                val phoneDirection = rssiDataPoint.phoneDirection ?: 0f
                val positionX = rssiDataPoint.userPosition?.first ?: 0f
                val positionY = rssiDataPoint.userPosition?.second ?: 0f
                val stepCount = rssiDataPoint.stepCount ?: 0
                val targetDirection = rssiDataPoint.targetDirection

                // Cluster and Ground Truth columns (empty if not applicable)
                val cluster = clusterId?.toString() ?: ""
                val groundTruthStr = groundTruth?.toString() ?: ""

                writer.append("$timestamp,${rssiDataPoint.macAddress},${rssiDataPoint.rssi},$phoneDirection,$positionX,$positionY,$stepCount,$targetDirection,$cluster,$groundTruthStr,$phase\n")
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to log Nav RSSI data", e)
            e.printStackTrace()
        }
    }

    // Get all log files (including audio files)
    fun getAllLogFiles(): List<LogFile> {
        val logDir = getLogDirectory()
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

        val logFiles = mutableListOf<LogFile>()

        // Add CSV log files
        logDir.listFiles()?.filter { it.name.endsWith(LOG_EXTENSION) }?.forEach { file ->
            logFiles.add(
                LogFile(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    type = when {
                        file.name.startsWith(BLE_LOG_PREFIX) -> "BLE"
                        file.name.startsWith(IMU_LOG_PREFIX) -> "IMU"
                        file.name.startsWith(NAV_RSSI_LOG_PREFIX) -> "Nav RSSI"
                        file.name.startsWith("lost_device_") -> "Lost Device"
                        else -> "Unknown"
                    }
                )
            )
        }

        // Add audio files (from the Music directory)
        musicDir?.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(AUDIO_EXTENSION) -> {
                    logFiles.add(
                        LogFile(
                            name = file.name,
                            path = file.absolutePath,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            type = when {
                                file.name.startsWith(AUDIO_LEFT_PREFIX) -> "Audio Left"
                                file.name.startsWith(AUDIO_RIGHT_PREFIX) -> "Audio Right"
                                else -> "Audio"
                            }
                        )
                    )
                }
                file.name.endsWith(METADATA_EXTENSION) -> {
                    // Identify all .txt metadata files, whether or not they start with an audio prefix
                    logFiles.add(
                        LogFile(
                            name = file.name,
                            path = file.absolutePath,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            type = when {
                                file.name.startsWith(AUDIO_LEFT_PREFIX) -> "Audio Left Metadata"
                                file.name.startsWith(AUDIO_RIGHT_PREFIX) -> "Audio Right Metadata"
                                else -> "Audio Metadata"
                            }
                        )
                    )
                }
            }
        }

        return logFiles.sortedByDescending { it.lastModified }
    }

    // Updated function for reading the BLE log file
    suspend fun readBleLogFile(fileName: String): List<BleDevice> = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        val devices = mutableListOf<BleDevice>()

        try {
            file.bufferedReader().use { reader ->
                reader.readLine()
                reader.lineSequence().forEach { line ->
                    val parts = line.split(",")
                    if (parts.size >= 7) {
                        try {
                            val timestamp = timestampFormat.parse(parts[0])?.time ?: System.currentTimeMillis()
                            val macAddress = parts[1]
                            val rssi = parts[2].toInt()
                            val deviceName = if (parts[3] == "Unknown") null else parts[3]
                            val payloadHex = parts[4]

                            // Handle payloadLength (which may or may not be present)
                            val payloadLength = if (parts.size >= 6) {
                                try {
                                    parts[5].toInt()
                                } catch (e: NumberFormatException) {
                                    payloadHex.length / 2 // Calculate from the hex length
                                }
                            } else {
                                payloadHex.length / 2 // Calculate from the hex length
                            }

                            // Rebuild the byte array from the hex string
                            val payloadBytes = hexStringToBytes(payloadHex)
                            val isConnectable = if (parts.size >= 7) {
                                parts[6].toBoolean()
                            } else {
                                false // Default value
                            }

                            val device = BleDevice(
                                macAddress = macAddress,
                                rssi = rssi,
                                deviceName = deviceName,
                                payloadBytes = payloadBytes,
                                payloadHex = payloadHex,
                                payloadLength = payloadLength,
                                timestamp = timestamp,
                                isConnectable = isConnectable
                            )
                            devices.add(device)
                        } catch (e: Exception) {
                            // Skip invalid lines
                            Log.w("LogManager", "Skipping invalid line: $line, error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Error reading BLE log file: ${e.message}", e)
            e.printStackTrace()
        }

        devices
    }

    // Read the IMU log file
    suspend fun readImuLogFile(fileName: String): List<ImuData> = withContext(Dispatchers.IO) {
        val file = File(getLogDirectory(), fileName)
        val imuDataList = mutableListOf<ImuData>()

        try {
            file.bufferedReader().use { reader ->
                reader.readLine() // Skip the header
                reader.lineSequence().forEach { line ->
                    val parts = line.split(",")
                    if (parts.size >= 20) {
                        try {
                            val timestamp = timestampFormat.parse(parts[0])?.time ?: System.currentTimeMillis()
                            val imuData = ImuData(
                                timestamp = timestamp,
                                accelerometerX = parts[1].toFloat(),
                                accelerometerY = parts[2].toFloat(),
                                accelerometerZ = parts[3].toFloat(),
                                gyroscopeX = parts[4].toFloat(),
                                gyroscopeY = parts[5].toFloat(),
                                gyroscopeZ = parts[6].toFloat(),
                                magnetometerX = parts[7].toFloat(),
                                magnetometerY = parts[8].toFloat(),
                                magnetometerZ = parts[9].toFloat(),
                                gravityX = parts[10].toFloat(),
                                gravityY = parts[11].toFloat(),
                                gravityZ = parts[12].toFloat(),
                                orientationAzimuth = parts[13].toFloat(),
                                positionX = parts[14].toFloat(),
                                positionY = parts[15].toFloat(),
                                linearAccelerationX = parts[16].toFloat(),
                                linearAccelerationY = parts[17].toFloat(),
                                linearAccelerationZ = parts[18].toFloat(),
                                stepCount = parts[19].toInt()
                            )
                            imuDataList.add(imuData)
                        } catch (e: Exception) {
                            // Skip invalid lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        imuDataList
    }

    /**
     * Convert hex string to byte array
     */
    private fun hexStringToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return byteArrayOf()

        return try {
            val cleanHex = hex.replace(" ", "").uppercase()
            if (cleanHex.length % 2 != 0) {
                // If the length is odd, pad a 0 at the front
                ("0$cleanHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
        } catch (e: Exception) {
            Log.w("LogManager", "Error converting hex string to bytes: $hex, error: ${e.message}")
            byteArrayOf()
        }
    }

    // Delete a log file (supports both CSV and audio files)
    fun deleteLogFile(fileName: String): Boolean {
        // First try to delete from the log directory
        val logFile = File(getLogDirectory(), fileName)
        if (logFile.exists() && logFile.delete()) {
            return true
        }

        // If it is an audio file, delete it from the Music directory
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (musicDir != null) {
            val audioFile = File(musicDir, fileName)
            if (audioFile.exists() && audioFile.delete()) {
                return true
            }
        }

        return false
    }

    fun deleteAllLogFiles(): Boolean {
        val logDir = getLogDirectory()
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

        var allDeleted = true

        // Delete all CSV log files
        logDir.listFiles()?.forEach { file ->
            if (!file.delete()) {
                allDeleted = false
            }
        }

        // Delete all audio files and metadata files
        musicDir?.listFiles()?.forEach { file ->
            // Delete all .pcm audio files and all .txt metadata files
            if (file.name.endsWith(AUDIO_EXTENSION) || file.name.endsWith(METADATA_EXTENSION)) {
                if (!file.delete()) {
                    allDeleted = false
                }
            }
        }

        return allDeleted
    }

    /**
     * Get audio recording session information
     * Returns all audio recording sessions (each session contains the left channel, right channel, and their metadata)
     */
    fun getAudioRecordingSessions(): List<AudioSession> {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return emptyList()
        val sessions = mutableMapOf<String, AudioSession>()

        musicDir.listFiles()?.forEach { file ->
            // Extract the session identifier (timestamp and MAC address)
            val sessionKey = extractSessionKey(file.name)
            if (sessionKey != null) {
                val session = sessions.getOrPut(sessionKey) {
                    AudioSession(
                        sessionId = sessionKey,
                        timestamp = file.lastModified(),
                        deviceMac = extractMacAddress(file.name)
                    )
                }

                when {
                    file.name.startsWith(AUDIO_LEFT_PREFIX) && file.name.endsWith(AUDIO_EXTENSION) -> {
                        session.leftChannelFile = file
                    }
                    file.name.startsWith(AUDIO_RIGHT_PREFIX) && file.name.endsWith(AUDIO_EXTENSION) -> {
                        session.rightChannelFile = file
                    }
                    file.name.startsWith(AUDIO_LEFT_PREFIX) && file.name.endsWith(METADATA_EXTENSION) -> {
                        session.leftMetadataFile = file
                    }
                    file.name.startsWith(AUDIO_RIGHT_PREFIX) && file.name.endsWith(METADATA_EXTENSION) -> {
                        session.rightMetadataFile = file
                    }
                }
            }
        }

        return sessions.values.sortedByDescending { it.timestamp }
    }

    /**
     * Extract the session identifier (based on MAC address and timestamp)
     */
    private fun extractSessionKey(fileName: String): String? {
        // File name format: audio_left_MACADDRESS_angleXXX_TIMESTAMP.pcm or audio_right_MACADDRESS_angleXXX_TIMESTAMP.pcm
        // Or the old format: audio_left_MACADDRESS_TIMESTAMP.pcm or audio_right_MACADDRESS_TIMESTAMP.pcm
        // New format (includes angle)
        val patternWithAngle = "audio_(left|right)_([A-F0-9]+)_angle\\d+_(\\d{8}_\\d{6})".toRegex()
        val matchWithAngle = patternWithAngle.find(fileName)
        if (matchWithAngle != null) {
            return "${matchWithAngle.groupValues[2]}_${matchWithAngle.groupValues[3]}"
        }

        // Old format (does not include angle)
        val patternOld = "audio_(left|right)_([A-F0-9]+)_(\\d{8}_\\d{6})".toRegex()
        val matchOld = patternOld.find(fileName)
        return matchOld?.let { "${it.groupValues[2]}_${it.groupValues[3]}" }
    }

    /**
     * Extract the MAC address
     */
    private fun extractMacAddress(fileName: String): String {
        // New format (includes angle)
        val patternWithAngle = "audio_(left|right)_([A-F0-9]+)_angle\\d+_\\d{8}_\\d{6}".toRegex()
        val matchWithAngle = patternWithAngle.find(fileName)
        if (matchWithAngle != null) {
            return matchWithAngle.groupValues[2].let { mac ->
                // Convert the contiguous MAC address into colon-separated format
                mac.chunked(2).joinToString(":")
            }
        }

        // Old format (does not include angle)
        val patternOld = "audio_(left|right)_([A-F0-9]+)_\\d{8}_\\d{6}".toRegex()
        val matchOld = patternOld.find(fileName)
        return matchOld?.groupValues?.get(2)?.let { mac ->
            // Convert the contiguous MAC address into colon-separated format
            mac.chunked(2).joinToString(":")
        } ?: "Unknown"
    }
}

data class LogFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val type: String
)

/**
 * Audio recording session
 * Contains a pair of stereo recording files (left channel and right channel) and their metadata
 */
data class AudioSession(
    val sessionId: String,
    val timestamp: Long,
    val deviceMac: String,
    var leftChannelFile: File? = null,
    var rightChannelFile: File? = null,
    var leftMetadataFile: File? = null,
    var rightMetadataFile: File? = null
) {
    val isComplete: Boolean
        get() = leftChannelFile != null && rightChannelFile != null

    val totalSize: Long
        get() = (leftChannelFile?.length() ?: 0L) + (rightChannelFile?.length() ?: 0L)

    val hasMetadata: Boolean
        get() = leftMetadataFile != null || rightMetadataFile != null
}
