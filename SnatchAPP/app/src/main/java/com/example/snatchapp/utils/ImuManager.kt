package com.example.snatchapp.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.snatchapp.model.ImuData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.*

class ImuManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ImuManager"
        
        // PDR parameters
        private const val ALPHA = 1.0f // Filter coefficient
        private const val POSITION_SCALE = 1.0f // Position scaling factor
        private const val MOVEMENT_THRESHOLD = 0.5f // Motion detection threshold
        private const val MIN_VELOCITY = 0.1f // Minimum velocity threshold
        private const val VELOCITY_DECAY = 1.0f // Velocity decay
        private const val STEP_LENGTH = 0.5f // Average step length (meters)

        // Accelerometer-based step detection parameters (from AccelSensorDetector)
        private const val ACCEL_RING_SIZE = 500
        private const val VEL_RING_SIZE = 100
        private const val STEP_THRESHOLD = 40f
        private const val STEP_DELAY_NS = 250000000L // 250ms in nanoseconds

        // Performance optimization parameters
        private const val DATA_UPDATE_INTERVAL_NS = 50_000_000L // 50ms update interval, balancing responsiveness and performance
        private const val MAX_QUEUE_SIZE = 200 // Maximum queue size, prevents memory overflow
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // All sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // Data flow - optimized buffer size
    private val _imuData = MutableSharedFlow<ImuData>(
        replay = 1,
        extraBufferCapacity = MAX_QUEUE_SIZE
    )
    val imuData: SharedFlow<ImuData> = _imuData

    private val _imuErrors = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val imuErrors: SharedFlow<String> = _imuErrors

    // Orientation data flow - optimized buffer
    private val _orientationData = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = MAX_QUEUE_SIZE
    )
    val orientationData: SharedFlow<Float> = _orientationData

    // Position data flow - optimized buffer
    private val _positionData = MutableSharedFlow<Pair<Float, Float>>(
        replay = 1,
        extraBufferCapacity = MAX_QUEUE_SIZE
    )
    val positionData: SharedFlow<Pair<Float, Float>> = _positionData

    // Step count data flow - optimized for responsiveness
    private val _stepCount = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = MAX_QUEUE_SIZE
    )
    val stepCount: SharedFlow<Int> = _stepCount

    // Sensor data cache
    private var accelerometerValues = FloatArray(3)
    private var gyroscopeValues = FloatArray(3)
    private var magnetometerValues = FloatArray(3)
    private var gravityValues = FloatArray(3)
    
    private var hasAccelerometerData = false
    private var hasGyroscopeData = false
    private var hasMagnetometerData = false
    private var hasGravityData = false

    // PDR-related variables
    private var currentPosition = Pair(0f, 0f)
    private var velocity = Pair(0f, 0f)
    private var lastTimestamp = 0L
    private var estimatedGravity = FloatArray(3) { i -> if (i == 2) 9.8f else 0f }
    private var isInitialized = false

    // Step count-related variables
    private var totalStepCount = 0
    private var lastStepTimestamp = 0L

    // Accelerometer-based step detection variables (from AccelSensorDetector)
    private var accelRingCounter = 0
    private val accelRingX = FloatArray(ACCEL_RING_SIZE)
    private val accelRingY = FloatArray(ACCEL_RING_SIZE)
    private val accelRingZ = FloatArray(ACCEL_RING_SIZE)
    private var velRingCounter = 0
    private val velRing = FloatArray(VEL_RING_SIZE)
    private var lastStepTimeNs: Long = 0
    private var oldVelocityEstimate = 0f

    // Control state
    private var isCollecting = false
    private var isTracking = false
    private var isStepDetectionEnabled = false // Toggle that controls step detection

    // Performance optimization variables
    private var lastDataUpdateTime = 0L
    private var pendingImuUpdate = false
    private var dataProcessingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        Log.d(TAG, "Initializing ImuManager")
        Log.d(TAG, "Accelerometer available: ${accelerometer != null}")
        Log.d(TAG, "Gyroscope available: ${gyroscope != null}")
        Log.d(TAG, "Magnetometer available: ${magnetometer != null}")
        Log.d(TAG, "Gravity available: ${gravity != null}")

        accelerometer?.let {
            Log.d(TAG, "Accelerometer: ${it.name}, Vendor: ${it.vendor}, Resolution: ${it.resolution}")
        }
        gyroscope?.let {
            Log.d(TAG, "Gyroscope: ${it.name}, Vendor: ${it.vendor}, Resolution: ${it.resolution}")
        }
        magnetometer?.let {
            Log.d(TAG, "Magnetometer: ${it.name}, Vendor: ${it.vendor}, Resolution: ${it.resolution}")
        }
        gravity?.let {
            Log.d(TAG, "Gravity: ${it.name}, Vendor: ${it.vendor}, Resolution: ${it.resolution}")
        }
    }

    /**
     * Start IMU data collection (uses SENSOR_DELAY_FASTEST for the best responsiveness)
     */
    fun startCollection() {
        Log.d(TAG, "Starting IMU collection with SENSOR_DELAY_FASTEST")

        try {
            if (accelerometer == null) {
                val error = "Accelerometer not supported on this device"
                Log.e(TAG, error)
                _imuErrors.tryEmit(error)
                return
            }

            if (gyroscope == null) {
                val error = "Gyroscope not supported on this device"
                Log.e(TAG, error)
                _imuErrors.tryEmit(error)
                return
            }

            // Register all sensor listeners using SENSOR_DELAY_FASTEST
            val success1 = sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val success2 = sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val success3 = magnetometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            } ?: false
            val success4 = gravity?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            } ?: false

            Log.d(TAG, "Sensor registration with SENSOR_DELAY_FASTEST - Accel: $success1, Gyro: $success2, Mag: $success3, Gravity: $success4")

            if (!success1 || !success2) {
                val error = "Failed to register essential sensor listeners"
                Log.e(TAG, error)
                _imuErrors.tryEmit(error)
                return
            }

            isCollecting = true
            lastDataUpdateTime = 0L
            pendingImuUpdate = false

            // Ensure step detection starts in the off state
            isStepDetectionEnabled = false
            
            Log.d(TAG, "IMU collection started successfully with SENSOR_DELAY_FASTEST")

        } catch (e: Exception) {
            val error = "Failed to start IMU collection: ${e.message}"
            Log.e(TAG, error, e)
            _imuErrors.tryEmit(error)
        }
    }

    /**
     * Stop IMU data collection
     */
    fun stopCollection() {
        Log.d(TAG, "Stopping IMU collection")

        try {
            isCollecting = false
            sensorManager.unregisterListener(this)

            // Reset state
            hasAccelerometerData = false
            hasGyroscopeData = false
            hasMagnetometerData = false
            hasGravityData = false

            // Reset step detection-related variables
            totalStepCount = 0
            lastStepTimestamp = 0L
            lastStepTimeNs = 0L
            oldVelocityEstimate = 0f
            accelRingCounter = 0
            velRingCounter = 0

            // Keep the step detection toggle state unchanged; do not reset it

            // Clean up performance optimization resources
            pendingImuUpdate = false
            lastDataUpdateTime = 0L

            Log.d(TAG, "IMU collection stopped successfully")

        } catch (e: Exception) {
            val error = "Failed to stop IMU collection: ${e.message}"
            Log.e(TAG, error, e)
            _imuErrors.tryEmit(error)
        }
    }

    /**
     * Start position tracking (PDR)
     */
    fun startTracking() {
        if (isTracking) return

        Log.d(TAG, "Starting position tracking (PDR)")
        isTracking = true
        resetPosition()
    }

    /**
     * Stop position tracking
     */
    fun stopTracking() {
        if (!isTracking) return

        Log.d(TAG, "Stopping position tracking")
        isTracking = false
    }

    /**
     * Reset position (set a new coordinate origin)
     */
    fun resetPosition() {
        Log.d(TAG, "Resetting position (new coordinate origin)")
        currentPosition = Pair(0f, 0f)
        velocity = Pair(0f, 0f)
        totalStepCount = 0
        lastTimestamp = System.currentTimeMillis()
        isInitialized = true

        // Reset step detection-related variables
        lastStepTimestamp = 0L
        lastStepTimeNs = 0L
        oldVelocityEstimate = 0f
        accelRingCounter = 0
        velRingCounter = 0
        
        _positionData.tryEmit(currentPosition)
        _stepCount.tryEmit(0)
    }

    /**
     * Check whether collection is in progress
     */
    fun isCollecting(): Boolean = isCollecting

    /**
     * Check whether tracking is in progress
     */
    fun isTracking(): Boolean = isTracking

    /**
     * Enable or disable step detection
     */
    fun setStepDetectionEnabled(enabled: Boolean) {
        isStepDetectionEnabled = enabled
        Log.d(TAG, "Step detection ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check whether step detection is enabled
     */
    fun isStepDetectionEnabled(): Boolean = isStepDetectionEnabled

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Filter the accelerometer data
                    accelerometerValues[0] = ALPHA * it.values[0] + (1 - ALPHA) * accelerometerValues[0]
                    accelerometerValues[1] = ALPHA * it.values[1] + (1 - ALPHA) * accelerometerValues[1]
                    accelerometerValues[2] = ALPHA * it.values[2] + (1 - ALPHA) * accelerometerValues[2]
                    hasAccelerometerData = true

                    // Accelerometer-based step detection (only when enabled)
                    if (isStepDetectionEnabled) {
                        updateAccelForStepDetection(it.timestamp, it.values[0], it.values[1], it.values[2])
                    }

                    // Process the sensor data using the throttling mechanism
                    scheduleSensorDataProcessing()
                }

                Sensor.TYPE_GYROSCOPE -> {
                    // Filter the gyroscope data
                    gyroscopeValues[0] = ALPHA * it.values[0] + (1 - ALPHA) * gyroscopeValues[0]
                    gyroscopeValues[1] = ALPHA * it.values[1] + (1 - ALPHA) * gyroscopeValues[1]
                    gyroscopeValues[2] = ALPHA * it.values[2] + (1 - ALPHA) * gyroscopeValues[2]
                    hasGyroscopeData = true

                    // Process the sensor data using the throttling mechanism
                    scheduleSensorDataProcessing()
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    // Filter the magnetometer data
                    magnetometerValues[0] = ALPHA * it.values[0] + (1 - ALPHA) * magnetometerValues[0]
                    magnetometerValues[1] = ALPHA * it.values[1] + (1 - ALPHA) * magnetometerValues[1]
                    magnetometerValues[2] = ALPHA * it.values[2] + (1 - ALPHA) * magnetometerValues[2]
                    hasMagnetometerData = true

                    // Process the sensor data using the throttling mechanism
                    scheduleSensorDataProcessing()
                }

                Sensor.TYPE_GRAVITY -> {
                    // Use the gravity sensor data directly
                    gravityValues[0] = it.values[0]
                    gravityValues[1] = it.values[1]
                    gravityValues[2] = it.values[2]
                    hasGravityData = true

                    // Process the sensor data using the throttling mechanism
                    scheduleSensorDataProcessing()
                }
            }
        }
    }

    /**
     * Throttling mechanism: schedule sensor data processing to avoid overly frequent updates
     */
    private fun scheduleSensorDataProcessing() {
        if (pendingImuUpdate) return

        val currentTime = System.nanoTime()
        if (currentTime - lastDataUpdateTime >= DATA_UPDATE_INTERVAL_NS) {
            // Process the data immediately
            processSensorDataImmediately()
        } else {
            // Process the data with a delay
            pendingImuUpdate = true
            dataProcessingScope.launch {
                delay((DATA_UPDATE_INTERVAL_NS - (currentTime - lastDataUpdateTime)) / 1_000_000L)
                if (pendingImuUpdate) {
                    processSensorDataImmediately()
                }
            }
        }
    }

    /**
     * Process the sensor data immediately
     */
    private fun processSensorDataImmediately() {
        pendingImuUpdate = false
        lastDataUpdateTime = System.nanoTime()
        processSensorData()
    }

    private fun processSensorData() {
        if (!hasAccelerometerData || !hasGyroscopeData) return

        // Calculate the orientation
        val orientation = calculateOrientation()

        // Calculate the linear acceleration
        val linearAccel = getLinearAcceleration()

        // Build the complete IMU data
        val imuData = ImuData(
            timestamp = System.currentTimeMillis(),
            accelerometerX = accelerometerValues[0],
            accelerometerY = accelerometerValues[1],
            accelerometerZ = accelerometerValues[2],
            gyroscopeX = gyroscopeValues[0],
            gyroscopeY = gyroscopeValues[1],
            gyroscopeZ = gyroscopeValues[2],
            magnetometerX = magnetometerValues[0],
            magnetometerY = magnetometerValues[1],
            magnetometerZ = magnetometerValues[2],
            gravityX = gravityValues[0],
            gravityY = gravityValues[1],
            gravityZ = gravityValues[2],
            orientationAzimuth = orientation,
            positionX = currentPosition.first,
            positionY = currentPosition.second,
            linearAccelerationX = linearAccel[0],
            linearAccelerationY = linearAccel[1],
            linearAccelerationZ = linearAccel[2],
            stepCount = totalStepCount
        )

        // Emit the IMU data
        _imuData.tryEmit(imuData)
    }

    /**
     * Accelerometer-based step detection (from AccelSensorDetector)
     */
    private fun updateAccelForStepDetection(timeNs: Long, x: Float, y: Float, z: Float) {
        val currentAccel = FloatArray(3)
        currentAccel[0] = x
        currentAccel[1] = y
        currentAccel[2] = z

        // Update the acceleration ring buffer
        accelRingCounter++
        accelRingX[accelRingCounter % ACCEL_RING_SIZE] = currentAccel[0]
        accelRingY[accelRingCounter % ACCEL_RING_SIZE] = currentAccel[1]
        accelRingZ[accelRingCounter % ACCEL_RING_SIZE] = currentAccel[2]

        // Calculate the world Z axis (gravity direction)
        val worldZ = FloatArray(3)
        worldZ[0] = sum(accelRingX) / accelRingCounter.coerceAtMost(ACCEL_RING_SIZE)
        worldZ[1] = sum(accelRingY) / accelRingCounter.coerceAtMost(ACCEL_RING_SIZE)
        worldZ[2] = sum(accelRingZ) / accelRingCounter.coerceAtMost(ACCEL_RING_SIZE)

        // Normalize the world Z axis
        val normalizationFactor = norm(worldZ)
        if (normalizationFactor > 0) {
            worldZ[0] = worldZ[0] / normalizationFactor
            worldZ[1] = worldZ[1] / normalizationFactor
            worldZ[2] = worldZ[2] / normalizationFactor

            // Calculate the component of the current acceleration along the world Z axis, subtracting the gravity contribution
            val currentZ = dot(worldZ, currentAccel) - normalizationFactor

            // Update the velocity ring buffer
            velRingCounter++
            velRing[velRingCounter % VEL_RING_SIZE] = currentZ

            // Calculate the velocity estimate
            val velocityEstimate = sum(velRing)

            // Detect a step: when the velocity exceeds the threshold, did not previously exceed it, and enough time has elapsed
            if (velocityEstimate > STEP_THRESHOLD && oldVelocityEstimate <= STEP_THRESHOLD &&
                timeNs - lastStepTimeNs > STEP_DELAY_NS) {
                handleStepDetection()
                lastStepTimeNs = timeNs
            }
            oldVelocityEstimate = velocityEstimate
        }
    }

    private fun calculateOrientation(): Float {
        if (!hasMagnetometerData) return 0f

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            azimuth = (azimuth + 360) % 360

            _orientationData.tryEmit(azimuth)
            return azimuth
        }
        return 0f
    }

    private fun getLinearAcceleration(): FloatArray {
        val linearAccel = FloatArray(3)

        if (hasGravityData) {
            // If a gravity sensor is available, subtract gravity directly
            linearAccel[0] = accelerometerValues[0] - gravityValues[0]
            linearAccel[1] = accelerometerValues[1] - gravityValues[1]
            linearAccel[2] = accelerometerValues[2] - gravityValues[2]
        } else {
            // Simple gravity estimation - using a low-pass filter
            val gravityAlpha = 0.9f
            estimatedGravity[0] = gravityAlpha * estimatedGravity[0] + (1 - gravityAlpha) * accelerometerValues[0]
            estimatedGravity[1] = gravityAlpha * estimatedGravity[1] + (1 - gravityAlpha) * accelerometerValues[1]
            estimatedGravity[2] = gravityAlpha * estimatedGravity[2] + (1 - gravityAlpha) * accelerometerValues[2]

            linearAccel[0] = accelerometerValues[0] - estimatedGravity[0]
            linearAccel[1] = accelerometerValues[1] - estimatedGravity[1]
            linearAccel[2] = accelerometerValues[2] - estimatedGravity[2]
        }

        return linearAccel
    }

    /**
     * Handle a step detection event
     */
    private fun handleStepDetection() {
        val currentTime = System.currentTimeMillis()

        totalStepCount++
        lastStepTimestamp = currentTime

        // Emit the step count update immediately (use tryEmit to ensure it does not block)
        _stepCount.tryEmit(totalStepCount)

        // If position tracking is active, update the position immediately
        if (isTracking && isInitialized) {
            updatePositionFromStep()
        }

        Log.d(TAG, "Step detected! Total steps: $totalStepCount, Time: $currentTime")
    }

    /**
     * Update the position based on step count and direction - optimized for responsiveness
     */
    private fun updatePositionFromStep() {
        val currentDirection = calculateOrientation()
        val directionRadians = Math.toRadians(currentDirection.toDouble())

        // Calculate the displacement corresponding to the step
        val stepDisplacement = STEP_LENGTH

        // Calculate the displacement along the X and Y directions
        val deltaX = stepDisplacement * sin(directionRadians).toFloat()
        val deltaY = stepDisplacement * cos(directionRadians).toFloat()

        // Update the current position
        currentPosition = Pair(
            currentPosition.first + deltaX,
            currentPosition.second + deltaY
        )

        // Emit the position update immediately
        _positionData.tryEmit(currentPosition)

        Log.d(TAG, "Position updated from step - Direction: ${currentDirection.toInt()}°, " +
                "Delta: (${String.format("%.2f", deltaX)}, ${String.format("%.2f", deltaY)}), " +
                "Position: (${String.format("%.2f", currentPosition.first)}, ${String.format("%.2f", currentPosition.second)})")

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val sensorName = when(sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
            Sensor.TYPE_GYROSCOPE -> "Gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
            Sensor.TYPE_GRAVITY -> "Gravity"
            else -> "Unknown Sensor"
        }

        val accuracyStr = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
            else -> "Unknown ($accuracy)"
        }

        Log.d(TAG, "$sensorName accuracy changed to: $accuracyStr")

        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            _imuErrors.tryEmit("$sensorName accuracy is unreliable")
        }
    }

    /**
     * Get sensor information
     */
    fun getSensorInfo(): String {
        val accelerometerInfo = accelerometer?.let {
            "Accelerometer: ${it.name}\nVendor: ${it.vendor}\nResolution: ${it.resolution}\nMax Range: ${it.maximumRange}"
        } ?: "Accelerometer not available"

        val gyroscopeInfo = gyroscope?.let {
            "Gyroscope: ${it.name}\nVendor: ${it.vendor}\nResolution: ${it.resolution}\nMax Range: ${it.maximumRange}"
        } ?: "Gyroscope not available"

        val magnetometerInfo = magnetometer?.let {
            "Magnetometer: ${it.name}\nVendor: ${it.vendor}\nResolution: ${it.resolution}\nMax Range: ${it.maximumRange}"
        } ?: "Magnetometer not available"

        val gravityInfo = gravity?.let {
            "Gravity: ${it.name}\nVendor: ${it.vendor}\nResolution: ${it.resolution}\nMax Range: ${it.maximumRange}"
        } ?: "Gravity not available"

        return "$accelerometerInfo\n\n$gyroscopeInfo\n\n$magnetometerInfo\n\n$gravityInfo"
    }

    /**
     * Get the debug status
     */
    fun getDebugStatus(): String {
        return """
            Is Collecting: $isCollecting
            Is Tracking: $isTracking
            Is Initialized: $isInitialized
            Step Detection Enabled: $isStepDetectionEnabled
            Has Accelerometer Data: $hasAccelerometerData
            Has Gyroscope Data: $hasGyroscopeData
            Has Magnetometer Data: $hasMagnetometerData
            Has Gravity Data: $hasGravityData
            Total Step Count: $totalStepCount
            Current Position: (${String.format("%.3f", currentPosition.first)}, ${String.format("%.3f", currentPosition.second)})
            Current Velocity: (${String.format("%.3f", velocity.first)}, ${String.format("%.3f", velocity.second)})
            Step Detection - Accel Ring Counter: $accelRingCounter, Vel Ring Counter: $velRingCounter
            Step Detection - Last Step Time: $lastStepTimestamp, Last Step Time NS: $lastStepTimeNs
            Flow Subscription Count: ${_imuData.subscriptionCount.value}
        """.trimIndent()
    }

    // Math functions related to step detection (from AccelSensorDetector)
    private fun sum(array: FloatArray): Float {
        var retval = 0f
        for (i in array.indices) {
            retval += array[i]
        }
        return retval
    }

    private fun norm(array: FloatArray): Float {
        var retval = 0f
        for (i in array.indices) {
            retval += array[i] * array[i]
        }
        return sqrt(retval.toDouble()).toFloat()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }
}