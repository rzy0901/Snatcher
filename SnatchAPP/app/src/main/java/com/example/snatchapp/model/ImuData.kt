package com.example.snatchapp.model

data class ImuData(
    val timestamp: Long,
    // Accelerometer data
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    // Gyroscope data
    val gyroscopeX: Float,
    val gyroscopeY: Float,
    val gyroscopeZ: Float,
    // Magnetometer data
    val magnetometerX: Float,
    val magnetometerY: Float,
    val magnetometerZ: Float,
    // Gravity sensor data
    val gravityX: Float,
    val gravityY: Float,
    val gravityZ: Float,
    // Orientation data (geomagnetic coordinate system)
    val orientationAzimuth: Float,
    // Position data (geomagnetic coordinate system, with the initial position as the origin)
    val positionX: Float,
    val positionY: Float,
    // Linear acceleration (gravity removed)
    val linearAccelerationX: Float,
    val linearAccelerationY: Float,
    val linearAccelerationZ: Float,
    // Step count data
    val stepCount: Int
)