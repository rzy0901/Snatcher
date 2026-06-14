package com.example.snatchapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.snatchapp.model.BleDevice
import com.example.snatchapp.model.RssiDataPoint
import com.example.snatchapp.utils.BleManager
import com.example.snatchapp.utils.LogFile
import com.example.snatchapp.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class SharedDataViewModel(application: Application) : AndroidViewModel(application) {

    private val logManager = LogManager(application)
    private val bleManager = BleManager(application)

    // RSSI history per Apple lost device (used by the detail screen's RSSI History chart)
    private val rssiHistoryMap = mutableMapOf<String, MutableList<RssiDataPoint>>()
    private val rssiHistoryLiveDataMap = mutableMapOf<String, MutableLiveData<List<RssiDataPoint>>>()

    companion object {
        private const val MAX_RSSI_HISTORY_SIZE = 500
    }

    private val _allBleDevices = mutableMapOf<String, BleDevice>()

    private val _displayBleDevices = MutableLiveData<List<BleDevice>>(emptyList())
    val bleDevices: LiveData<List<BleDevice>> = _displayBleDevices

    fun getAllBleDevices(): List<BleDevice> = _allBleDevices.values.toList()
    fun getBleDeviceCount(): Int = _allBleDevices.size

    private var lastUiUpdateTime = 0L
    private val uiUpdateInterval = 500L
    private val maxDisplayDevices = 100

    private val dataProcessingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pendingUiUpdate = false

    private val _lostDevices = MutableLiveData<List<BleDevice>>(emptyList())
    val lostDevices: LiveData<List<BleDevice>> = _lostDevices

    private val _currentLostDeviceLogFile = MutableLiveData<String>("")
    val currentLostDeviceLogFile: LiveData<String> = _currentLostDeviceLogFile

    private val _isCollecting = MutableLiveData<Boolean>(false)
    val isCollecting: LiveData<Boolean> = _isCollecting

    private val _isLogging = MutableLiveData<Boolean>(false)
    val isLogging: LiveData<Boolean> = _isLogging

    private val _currentBleLogFile = MutableLiveData<String>("")
    val currentBleLogFile: LiveData<String> = _currentBleLogFile

    private val _logFiles = MutableLiveData<List<LogFile>>(emptyList())
    val logFiles: LiveData<List<LogFile>> = _logFiles

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage


    init {
        viewModelScope.launch {
            bleManager.scanResults.collect { bleDevice ->
                dataProcessingScope.launch {
                    processBleDeviceImmediate(bleDevice)
                }
            }
        }

        viewModelScope.launch {
            bleManager.scanErrors.collect { error ->
                _errorMessage.value = error
            }
        }

    }

    private suspend fun processBleDeviceImmediate(device: BleDevice) {
        synchronized(_allBleDevices) {
            _allBleDevices[device.macAddress] = device

            // Accumulate RSSI history for Apple lost devices (drives the detail screen's RSSI History chart)
            if (device.isAppleLostDevice()) {
                synchronized(rssiHistoryMap) {
                    val history = rssiHistoryMap.getOrPut(device.macAddress) { mutableListOf() }
                    history.add(
                        RssiDataPoint(
                            timestamp = device.timestamp,
                            rssi = device.rssi,
                            phoneDirection = null,
                            userPosition = null,
                            stepCount = null,
                            macAddress = device.macAddress
                        )
                    )
                    if (history.size > MAX_RSSI_HISTORY_SIZE) {
                        history.removeAt(0)
                    }
                    rssiHistoryLiveDataMap[device.macAddress]?.postValue(history.toList())
                }
            }
        }

        if (_isLogging.value == true) {
            if (device.isAppleLostDevice()) {
                if (_currentLostDeviceLogFile.value?.isNotEmpty() == true) {
                    logManager.logLostDevice(_currentLostDeviceLogFile.value!!, device)
                }
            }

            logManager.logBleDevice(_currentBleLogFile.value!!, device)
        }

        scheduleUiUpdate()
    }

    /**
     * RSSI history (timestamp + RSSI) for a given Apple lost device, observed by the detail screen.
     */
    fun getRssiHistory(deviceMac: String): LiveData<List<RssiDataPoint>> {
        return synchronized(rssiHistoryMap) {
            rssiHistoryLiveDataMap.getOrPut(deviceMac) {
                MutableLiveData(rssiHistoryMap[deviceMac]?.toList() ?: emptyList())
            }
        }
    }


    private suspend fun scheduleUiUpdate() {
        if (pendingUiUpdate) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUiUpdateTime >= uiUpdateInterval) {
            updateUiImmediately()
        } else {
            pendingUiUpdate = true
            delay(uiUpdateInterval - (currentTime - lastUiUpdateTime))
            if (pendingUiUpdate) {
                updateUiImmediately()
            }
        }
    }


    private suspend fun updateUiImmediately() {
        try {
            pendingUiUpdate = false
            lastUiUpdateTime = System.currentTimeMillis()

            val (lostDevices, displayDevices) = withContext(Dispatchers.Default) {
                synchronized(_allBleDevices) {

                    val lostDevices = _allBleDevices.values
                        .filter { it.isAppleLostDevice() }
                        .sortedWith(compareByDescending<BleDevice> { it.getAppleLostDeviceType()?.priority ?: 0 }
                            .thenByDescending { it.rssi })

                    val displayDevices = _allBleDevices.values
                        .filterNot { it.isAppleLostDevice() }
                        .sortedByDescending { it.rssi }
                        .take(maxDisplayDevices)

                    Pair(lostDevices, displayDevices)
                }
            }
            withContext(Dispatchers.Main) {
                _lostDevices.value = lostDevices
                _displayBleDevices.value = displayDevices
            }
        } catch (e: Exception) {
            Log.e("SharedDataViewModel", "UI update failed", e)
        }
    }


    fun getDeviceStats(): DeviceStats {
        return synchronized(_allBleDevices) {
            DeviceStats(
                totalDevices = _allBleDevices.size,
                uniqueDevices = _allBleDevices.size,
                displayedDevices = _displayBleDevices.value?.size ?: 0
            )
        }
    }

    fun clearBleDevices() {
        synchronized(_allBleDevices) {
            _allBleDevices.clear()
            _displayBleDevices.value = emptyList()
            _lostDevices.value = emptyList()
        }
        synchronized(rssiHistoryMap) {
            rssiHistoryMap.clear()
            rssiHistoryLiveDataMap.values.forEach { it.postValue(emptyList()) }
        }
        lastUiUpdateTime = 0L // Reset the UI update time
        pendingUiUpdate = false // Reset the pending-update flag
    }


    fun startDataCollection() {
        try {
            _isCollecting.value = true

            val bleStarted = bleManager.startScan()
            if (!bleStarted) {
                _errorMessage.value = "Failed to start BLE scanning"
            }

        } catch (e: Exception) {
            _errorMessage.value = "Failed to start BLE data collection: ${e.message}"
            _isCollecting.value = false
        }
    }


    fun stopDataCollection() {
        try {
            _isCollecting.value = false

            // Stop BLE scanning
            bleManager.stopScan()

        } catch (e: Exception) {
            _errorMessage.value = "Failed to stop BLE data collection: ${e.message}"
        }
    }

    fun startBleLogging(bleFileName: String? = null) {
        viewModelScope.launch {
            try {
                val bleFile = bleFileName ?: logManager.generateDefaultFileName("ble")
                val lostDeviceFile = logManager.generateDefaultFileName("lost_device")

                logManager.createBleLogFile(bleFile)
                logManager.createLostDeviceLogFile(lostDeviceFile)

                _currentBleLogFile.value = bleFile
                _currentLostDeviceLogFile.value = lostDeviceFile
                _isLogging.value = true

                refreshLogFiles()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopBleLogging() {
        _isLogging.value = false
        _currentBleLogFile.value = ""
        _currentLostDeviceLogFile.value = ""
    }

    fun refreshLogFiles() {
        viewModelScope.launch {
            _logFiles.value = logManager.getAllLogFiles()
        }
    }

    fun deleteLogFile(fileName: String) {
        viewModelScope.launch {
            if (logManager.deleteLogFile(fileName)) {
                refreshLogFiles()
            }
        }
    }

    fun deleteAllLogFiles() {
        viewModelScope.launch {
            if (logManager.deleteAllLogFiles()) {
                refreshLogFiles()
            }
        }
    }

    fun generateDefaultFileName(type: String): String {
        return logManager.generateDefaultFileName(type)
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        dataProcessingScope.cancel()

        try {
            bleManager.stopScan()
        } catch (e: Exception) {
            Log.e("SharedDataViewModel", "Cleanup failed", e)
        }
    }
}

data class DeviceStats(
    val totalDevices: Int,
    val uniqueDevices: Int,
    val displayedDevices: Int
)
