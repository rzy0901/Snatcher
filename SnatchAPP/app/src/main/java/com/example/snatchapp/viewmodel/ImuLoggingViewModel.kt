package com.example.snatchapp.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.snatchapp.model.RssiDataPoint
import com.example.snatchapp.utils.ImuManager
import com.example.snatchapp.utils.LogManager
import kotlinx.coroutines.launch

/**
 * Drives IMU sensing plus IMU / Nav-RSSI logging and step counting for the device detail screen.
 *
 * This intentionally contains NO navigation guidance (no gradient, target direction, compass,
 * trajectory, or clustering) — it only collects sensor data, counts steps, and writes the
 * IMU and Nav-RSSI CSV logs, mirroring the original logging behaviour.
 */
class ImuLoggingViewModel(application: Application) : AndroidViewModel(application) {

    private val imuManager = ImuManager(application)
    private val logManager = LogManager(application)

    companion object {
        private const val TAG = "ImuLoggingViewModel"
    }

    private val _stepCount = MutableLiveData(0)
    val stepCount: LiveData<Int> = _stepCount

    private val _phoneDirection = MutableLiveData(0f)
    val phoneDirection: LiveData<Float> = _phoneDirection

    private val _isLogging = MutableLiveData(false)
    val isLogging: LiveData<Boolean> = _isLogging

    // Latest IMU-derived state, used to annotate Nav-RSSI rows.
    @Volatile private var latestDirection: Float = 0f
    @Volatile private var latestPosition: Pair<Float, Float> = Pair(0f, 0f)
    @Volatile private var latestStepCount: Int = 0

    private var imuLogFile: String? = null
    private var navRssiLogFile: String? = null

    init {
        viewModelScope.launch {
            imuManager.imuData.collect { imuData ->
                latestDirection = imuData.orientationAzimuth
                latestPosition = Pair(imuData.positionX, imuData.positionY)
                latestStepCount = imuData.stepCount
                imuLogFile?.let { logManager.logImuData(it, imuData) }
            }
        }
        viewModelScope.launch {
            imuManager.stepCount.collect { steps ->
                latestStepCount = steps
                _stepCount.postValue(steps)
            }
        }
        viewModelScope.launch {
            imuManager.orientationData.collect { direction ->
                latestDirection = direction
                _phoneDirection.postValue(direction)
            }
        }
        viewModelScope.launch {
            imuManager.imuErrors.collect { error -> Log.e(TAG, "IMU error: $error") }
        }
    }

    /**
     * Start IMU collection + position tracking and open the IMU and Nav-RSSI log files.
     * Mirrors the original behaviour of starting logging when a device detail screen opens.
     */
    fun startLogging(imuFileName: String, navRssiFileName: String) {
        viewModelScope.launch {
            try {
                logManager.createImuLogFile(imuFileName)
                imuLogFile = imuFileName
                logManager.createNavRssiLogFile(navRssiFileName)
                navRssiLogFile = navRssiFileName
                _isLogging.postValue(true)
                Log.d(TAG, "Started IMU logging: $imuFileName, Nav-RSSI logging: $navRssiFileName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create log files", e)
            }
        }
        imuManager.startCollection()
        imuManager.startTracking()
    }

    /**
     * Log a single Nav-RSSI sample, annotated with the latest phone orientation, PDR position,
     * and step count. Called by the detail screen as fresh RSSI readings arrive for the device.
     */
    fun logNavRssiSample(macAddress: String, rssi: Int, timestamp: Long) {
        val file = navRssiLogFile ?: return
        val dataPoint = RssiDataPoint(
            timestamp = timestamp,
            rssi = rssi,
            phoneDirection = latestDirection,
            userPosition = latestPosition,
            stepCount = latestStepCount,
            macAddress = macAddress
        )
        viewModelScope.launch {
            logManager.logNavRssiData(file, dataPoint)
        }
    }

    fun setStepDetectionEnabled(enabled: Boolean) = imuManager.setStepDetectionEnabled(enabled)

    fun isStepDetectionEnabled(): Boolean = imuManager.isStepDetectionEnabled()

    fun getSensorStatus(): String = imuManager.getDebugStatus()

    fun stopLogging() {
        imuManager.stopTracking()
        imuManager.stopCollection()
        imuLogFile = null
        navRssiLogFile = null
        _isLogging.postValue(false)
        Log.d(TAG, "Stopped IMU / Nav-RSSI logging")
    }

    override fun onCleared() {
        super.onCleared()
        stopLogging()
    }
}
