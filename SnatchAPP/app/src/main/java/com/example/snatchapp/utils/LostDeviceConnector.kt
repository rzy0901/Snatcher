/* Reference: 
   AirTag: https://github.com/seemoo-lab/AirGuard/blob/main/app/src/main/java/de/seemoo/at_tracking_detection/database/models/device/types/AirTag.kt
   FindMy: https://github.com/seemoo-lab/AirGuard/blob/main/app/src/main/java/de/seemoo/at_tracking_detection/database/models/device/types/AppleFindMy.kt
   AirPods: https://github.com/seemoo-lab/AirGuard/blob/main/app/src/main/java/de/seemoo/at_tracking_detection/database/models/device/types/AirPods.kt
*/

package com.example.snatchapp.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

/**
 * Device type enumeration
 */
enum class AppleDeviceType {
    AIRTAG,           // AirTag device
    AIRPODS,          // AirPods device (uses the Find My protocol)
    FIND_MY_DEVICE    // Other Find My devices
}

/**
 * Apple device connector
 * Supports sound playback for AirTag, AirPods, and other Find My devices
 */
class LostDeviceConnector(private val context: Context) {

    companion object {
        private const val TAG = "LostDeviceConnector"
        
        // AirTag protocol
        private val AIR_TAG_SOUND_SERVICE = UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C")
        private val AIR_TAG_SOUND_CHARACTERISTIC = UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C")
        private const val AIR_TAG_EVENT_CALLBACK = 0x302
        
        // Find My Device protocol (see AppleFindMy)
        private const val FINDMY_SOUND_SERVICE = "fd44"  // Partial UUID match
        private val FINDMY_SOUND_CHARACTERISTIC = UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A")
        private val FINDMY_START_SOUND_OPCODE = byteArrayOf(0x01, 0x00, 0x03)
        private val FINDMY_STOP_SOUND_OPCODE = byteArrayOf(0x01, 0x01, 0x03)
        
        const val ACTION_GATT_CONNECTED = "com.example.snatchapp.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.snatchapp.ACTION_GATT_DISCONNECTED"
        const val ACTION_EVENT_RUNNING = "com.example.snatchapp.ACTION_EVENT_RUNNING"
        const val ACTION_EVENT_COMPLETED = "com.example.snatchapp.ACTION_EVENT_COMPLETED"
        const val ACTION_EVENT_FAILED = "com.example.snatchapp.ACTION_EVENT_FAILED"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    private var deviceType: AppleDeviceType = AppleDeviceType.AIRTAG
    private val handler = Handler(Looper.getMainLooper())
    
    var onConnectionStateChanged: ((String) -> Unit)? = null
    var onSoundPlaybackStateChanged: ((String) -> Unit)? = null

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private val gattUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GATT_CONNECTED -> {
                    Log.d(TAG, "Bluetooth connected")
                    onConnectionStateChanged?.invoke("Connected")
                }
                ACTION_GATT_DISCONNECTED -> {
                    Log.d(TAG, "Bluetooth disconnected")
                    onConnectionStateChanged?.invoke("Disconnected")
                    isConnecting = false
                }
                ACTION_EVENT_RUNNING -> {
                    Log.d(TAG, "Sound playback running")
                    onSoundPlaybackStateChanged?.invoke("Playing sound...")
                }
                ACTION_EVENT_COMPLETED -> {
                    Log.d(TAG, "Sound playback completed")
                    onSoundPlaybackStateChanged?.invoke("Completed")
                }
                ACTION_EVENT_FAILED -> {
                    Log.d(TAG, "Sound playback failed")
                    onSoundPlaybackStateChanged?.invoke("Failed")
                    disconnect()
                }
            }
        }
    }


    fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(ACTION_GATT_CONNECTED)
            addAction(ACTION_GATT_DISCONNECTED)
            addAction(ACTION_EVENT_RUNNING)
            addAction(ACTION_EVENT_COMPLETED)
            addAction(ACTION_EVENT_FAILED)
        }
        
        // Android 13+ requires explicitly specifying RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(gattUpdateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(gattUpdateReceiver, intentFilter)
        }
        
        Log.d(TAG, "Broadcast receiver registered")
    }


    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(gattUpdateReceiver)
            Log.d(TAG, "Broadcast receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }


    /**
     * Connect to the device and play a sound
     * @param macAddress The device MAC address
     * @param deviceType The device type (AIRTAG, AIRPODS, or FIND_MY_DEVICE)
     */
    @SuppressLint("MissingPermission")
    fun connectAndPlaySound(macAddress: String, deviceType: AppleDeviceType = AppleDeviceType.AIRTAG): Boolean {
        if (isConnecting) {
            Log.w(TAG, "Already connecting to a device")
            return false
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            onSoundPlaybackStateChanged?.invoke("Bluetooth not available")
            return false
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            onSoundPlaybackStateChanged?.invoke("Bluetooth is disabled")
            return false
        }

        try {
            val device = bluetoothAdapter!!.getRemoteDevice(macAddress)
            if (device == null) {
                Log.e(TAG, "Device not found: $macAddress")
                onSoundPlaybackStateChanged?.invoke("Device not found")
                return false
            }

            this.deviceType = deviceType
            Log.d(TAG, "Connecting to device: $macAddress (Type: $deviceType)")
            isConnecting = true
            onConnectionStateChanged?.invoke("Connecting...")
            
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            onSoundPlaybackStateChanged?.invoke("Connection failed: ${e.message}")
            isConnecting = false
            return false
        }
    }


    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            // Clear all pending Handler callbacks
            handler.removeCallbacksAndMessages(null)
            
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            isConnecting = false
            Log.d(TAG, "Disconnected from device")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect: ${e.message}", e)
        }
    }


    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to GATT server, discovering services...")
                            broadcastUpdate(ACTION_GATT_CONNECTED)
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected from GATT server")
                            broadcastUpdate(ACTION_GATT_DISCONNECTED)
                        }
                        else -> {
                            Log.d(TAG, "Connection state changed to $newState")
                        }
                    }
                }
                19 -> {
                    // A specific status code indicating the operation completed
                    Log.d(TAG, "Event completed with status 19")
                    broadcastUpdate(ACTION_EVENT_COMPLETED)
                }
                else -> {
                    Log.e(TAG, "Connection failed with status: $status")
                    broadcastUpdate(ACTION_EVENT_FAILED)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            Log.d(TAG, "Services discovered, device type: $deviceType")
            val uuids = gatt.services.map { it.uuid.toString() }
            Log.d(TAG, "Found service UUIDs: $uuids")

            when (deviceType) {
                AppleDeviceType.AIRTAG -> handleAirTagService(gatt)
                AppleDeviceType.AIRPODS -> handleAirPodsService(gatt)
                AppleDeviceType.FIND_MY_DEVICE -> handleFindMyDeviceService(gatt)
            }
        }

        /**
         * Handle the AirTag device's service
         */
        @SuppressLint("MissingPermission")
        private fun handleAirTagService(gatt: BluetoothGatt) {
            Log.d(TAG, "Handling AirTag service...")
            val service = gatt.getService(AIR_TAG_SOUND_SERVICE)
            
            if (service == null) {
                Log.e(TAG, "AirTag sound service not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            val characteristic = service.getCharacteristic(AIR_TAG_SOUND_CHARACTERISTIC)
            if (characteristic != null) {
                characteristic.setValue(175, BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                val writeSuccess = gatt.writeCharacteristic(characteristic)
                
                if (writeSuccess) {
                    Log.d(TAG, "AirTag: Playing sound... Write initiated")
                    broadcastUpdate(ACTION_EVENT_RUNNING)
                } else {
                    Log.e(TAG, "AirTag: Failed to initiate characteristic write")
                    broadcastUpdate(ACTION_EVENT_FAILED)
                    disconnect(gatt)
                }
            } else {
                Log.e(TAG, "AirTag sound characteristic not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
            }
        }

        /**
         * Handle the AirPods device's service (see AppleFindMy/AirPods)
         * AirPods uses the Find My protocol, but may have special handling requirements
         */
        @SuppressLint("MissingPermission")
        private fun handleAirPodsService(gatt: BluetoothGatt) {
            Log.d(TAG, "Handling AirPods service...")

            // AirPods uses the same protocol as Find My
            // Look for the service containing "fd44"
            val service = gatt.services.firstOrNull {
                it.uuid.toString().lowercase().contains(FINDMY_SOUND_SERVICE)
            }
            
            if (service == null) {
                Log.e(TAG, "AirPods: Playing sound service not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            val characteristic = service.getCharacteristic(FINDMY_SOUND_CHARACTERISTIC)
            if (characteristic == null) {
                Log.e(TAG, "AirPods: Sound characteristic not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(characteristic, true)

            // Write the start playback command
            val writeSuccess = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, FINDMY_START_SOUND_OPCODE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = FINDMY_START_SOUND_OPCODE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (writeSuccess != false) {
                Log.d(TAG, "AirPods: Playing sound with ${characteristic.uuid}")
                broadcastUpdate(ACTION_EVENT_RUNNING)
            } else {
                Log.e(TAG, "AirPods: Failed to write characteristic")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
            }
        }

        /**
         * Handle the Find My device's service (see AppleFindMy)
         */
        @SuppressLint("MissingPermission")
        private fun handleFindMyDeviceService(gatt: BluetoothGatt) {
            Log.d(TAG, "Handling Find My Device service...")

            // Look for the service containing "fd44"
            val service = gatt.services.firstOrNull {
                it.uuid.toString().lowercase().contains(FINDMY_SOUND_SERVICE)
            }
            
            if (service == null) {
                Log.e(TAG, "Find My: Playing sound service not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            val characteristic = service.getCharacteristic(FINDMY_SOUND_CHARACTERISTIC)
            if (characteristic == null) {
                Log.e(TAG, "Find My: Sound characteristic not found!")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(characteristic, true)

            // Write the start playback command
            val writeSuccess = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, FINDMY_START_SOUND_OPCODE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = FINDMY_START_SOUND_OPCODE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (writeSuccess != false) {
                Log.d(TAG, "Find My: Playing sound with ${characteristic.uuid}")
                broadcastUpdate(ACTION_EVENT_RUNNING)
            } else {
                Log.e(TAG, "Find My: Failed to write characteristic")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
            }
        }

        /**
         * Stop sound playback on the Find My device
         */
        @SuppressLint("MissingPermission")
        private fun stopSoundOnFindMyDevice(gatt: BluetoothGatt) {
            Log.d(TAG, "Stopping sound on Find My device...")
            
            val service = gatt.services.firstOrNull {
                it.uuid.toString().lowercase().contains(FINDMY_SOUND_SERVICE)
            }

            if (service == null) {
                Log.d(TAG, "Find My: Sound service not found for stop command")
                return
            }

            val characteristic = service.getCharacteristic(FINDMY_SOUND_CHARACTERISTIC)
            if (characteristic != null) {
                gatt.setCharacteristicNotification(characteristic, true)
                
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, FINDMY_STOP_SOUND_OPCODE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = FINDMY_STOP_SOUND_OPCODE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                
                Log.d(TAG, "Find My: Stop command sent with ${characteristic.uuid}")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null && gatt != null) {
                Log.d(TAG, "Characteristic write successful")
                
                when (deviceType) {
                    AppleDeviceType.AIRTAG -> {
                        // AirTag protocol: check the property callback
                        when (characteristic.properties and AIR_TAG_EVENT_CALLBACK) {
                            AIR_TAG_EVENT_CALLBACK -> {
                                Log.d(TAG, "AirTag: Sound playback completed")
                                broadcastUpdate(ACTION_EVENT_COMPLETED)
                                disconnect(gatt)
                            }
                        }
                    }
                    AppleDeviceType.AIRPODS -> {
                        // AirPods uses the Find My protocol
                        val value = if (Build.VERSION.SDK_INT >= 33) {
                            characteristic.value
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value
                        }

                        when {
                            value.contentEquals(FINDMY_START_SOUND_OPCODE) -> {
                                // After playback starts, send the stop command 5 seconds later
                                Log.d(TAG, "AirPods: Sound started, will stop after 5 seconds")
                                handler.postDelayed({
                                    stopSoundOnFindMyDevice(gatt)
                                }, 5000)
                            }
                            value.contentEquals(FINDMY_STOP_SOUND_OPCODE) -> {
                                // The stop command succeeded; disconnect
                                Log.d(TAG, "AirPods: Sound stopped")
                                disconnect(gatt)
                                broadcastUpdate(ACTION_EVENT_COMPLETED)
                            }
                        }
                    }
                    AppleDeviceType.FIND_MY_DEVICE -> {
                        // Find My protocol: check the written value
                        val value = if (Build.VERSION.SDK_INT >= 33) {
                            characteristic.value
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value
                        }

                        when {
                            value.contentEquals(FINDMY_START_SOUND_OPCODE) -> {
                                // After playback starts, send the stop command 5 seconds later
                                Log.d(TAG, "Find My: Sound started, will stop after 5 seconds")
                                handler.postDelayed({
                                    stopSoundOnFindMyDevice(gatt)
                                }, 5000)
                            }
                            value.contentEquals(FINDMY_STOP_SOUND_OPCODE) -> {
                                // The stop command succeeded; disconnect
                                Log.d(TAG, "Find My: Sound stopped")
                                disconnect(gatt)
                                broadcastUpdate(ACTION_EVENT_COMPLETED)
                            }
                        }
                    }
                }
            } else if (status == 133) {
                Log.e(TAG, "Characteristic write timeout (status 133)")
                broadcastUpdate(ACTION_EVENT_FAILED)
                disconnect(gatt)
            } else {
                Log.e(TAG, "Characteristic write failed with status: $status")
                disconnect(gatt)
                broadcastUpdate(ACTION_EVENT_FAILED)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                when (characteristic.properties and AIR_TAG_EVENT_CALLBACK) {
                    AIR_TAG_EVENT_CALLBACK -> {
                        Log.d(TAG, "Characteristic read successful")
                        broadcastUpdate(ACTION_EVENT_COMPLETED)
                        disconnect(gatt)
                    }
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun disconnect(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting GATT: ${e.message}", e)
        }
    }


    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        context.sendBroadcast(intent)
    }

    fun isConnecting(): Boolean = isConnecting
}

