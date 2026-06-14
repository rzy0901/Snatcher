package com.example.snatchapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.snatchapp.databinding.FragmentLostDeviceDetailBinding
import com.example.snatchapp.model.BleDevice
import com.example.snatchapp.viewmodel.ImuLoggingViewModel
import com.example.snatchapp.viewmodel.SharedDataViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.snatchapp.utils.LostDeviceConnector
import com.example.snatchapp.utils.AppleDeviceType
import com.example.snatchapp.utils.StereoAudioRecorder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.widget.Toast

class LostDeviceDetailFragment : Fragment() {

    companion object {
        private const val TAG = "LostDeviceDetail"
        private const val ARG_DEVICE_MAC = "device_mac"

        fun newInstance(deviceMac: String): LostDeviceDetailFragment {
            return LostDeviceDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_MAC, deviceMac)
                }
            }
        }
    }

    private var _binding: FragmentLostDeviceDetailBinding? = null
    private val binding get() = _binding!!

    // BLE data and RSSI history come from SharedDataViewModel
    private val sharedDataViewModel: SharedDataViewModel by activityViewModels()

    // IMU sensing + IMU/Nav-RSSI logging + step counting (no navigation guidance)
    private val imuLoggingViewModel: ImuLoggingViewModel by viewModels()

    private var deviceMac: String? = null
    private var currentDevice: BleDevice? = null
    private var lastNavLoggedTimestamp = 0L

    private var isViewCreated = false
    private var timeUpdateJob: Job? = null

    // AirTag connection manager (non-owner sound trigger)
    private var lostDeviceConnector: LostDeviceConnector? = null

    // Audio recording manager
    private var audioRecorder: StereoAudioRecorder? = null

    // Recording filename prefix (used to tag the test group)
    private var recordingFilePrefix: String = ""

    // Manually annotated angle (recorded into the audio metadata / file name)
    private var manualAngle: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceMac = arguments?.getString(ARG_DEVICE_MAC)
        Log.d(TAG, "Fragment created for device: $deviceMac")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLostDeviceDetailBinding.inflate(inflater, container, false)
        Log.d(TAG, "View created")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isViewCreated = true
        setupUI()

        // Delay observation to avoid blocking the UI
        view.post {
            if (isViewCreated && isAdded) {
                startImuLogging()
                observeViewModel()
                startTimeUpdate()
            }
        }

        Log.d(TAG, "View setup completed")
    }

    private fun startTimeUpdate() {
        timeUpdateJob = lifecycleScope.launch {
            while (isViewCreated && isAdded) {
                try {
                    updateCurrentTime()
                    delay(1000) // Update once per second
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update time", e)
                    break
                }
            }
        }
    }

    private fun updateCurrentTime() {
        try {
            if (_binding != null) {
                val currentTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                binding.tvCurrentTime.text = currentTime
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update current time", e)
        }
    }

    private fun startImuLogging() {
        val mac = deviceMac ?: return
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val macSafe = mac.replace(":", "")
        val imuFileName = "imu_data_${macSafe}_$ts.csv"
        val navRssiFileName = "NavRSSI_${macSafe}_$ts.csv"
        lastNavLoggedTimestamp = System.currentTimeMillis()
        imuLoggingViewModel.startLogging(imuFileName, navRssiFileName)
        // Honor the current step-detection toggle state
        imuLoggingViewModel.setStepDetectionEnabled(binding.switchStepDetector.isChecked)
        Log.d(TAG, "IMU + Nav-RSSI logging started: $imuFileName, $navRssiFileName")
    }

    private fun setupUI() {

        // Initialize the AirTag connector
        lostDeviceConnector = LostDeviceConnector(requireContext())
        val storageDir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        if (storageDir != null && deviceMac != null) {
            audioRecorder = StereoAudioRecorder(storageDir, deviceMac!!)
            setupAudioRecorderCallbacks()
        }

        // Back button
        binding.btnBack.setOnClickListener {
            Log.d(TAG, "Back button clicked")
            safeNavigateBack()
        }

        // Play sound button
        binding.btnPlaySound.setOnClickListener {
            Log.d(TAG, "Play sound button clicked")
            playDeviceSound()
        }

        // Recording button
        binding.btnStartRecording.setOnClickListener {
            Log.d(TAG, "Start recording button clicked")
            showRecordingPrefixDialog()
        }

        binding.btnStopRecording.setOnClickListener {
            Log.d(TAG, "Stop recording button clicked")
            stopAudioRecording()
        }

        // Step detection toggle (feeds the IMU log + Nav-RSSI log)
        binding.switchStepDetector.isChecked = true
        binding.switchStepDetector.text = "Steps: On"
        binding.switchStepDetector.setOnCheckedChangeListener { _, isChecked ->
            binding.switchStepDetector.text = if (isChecked) "Steps: On" else "Steps: Off"
            imuLoggingViewModel.setStepDetectionEnabled(isChecked)
            Log.d(TAG, "Step detection ${if (isChecked) "enabled" else "disabled"}")
        }

        // Set the device title
        deviceMac?.let { mac ->
            val lostDevices = sharedDataViewModel.lostDevices.value ?: emptyList()
            currentDevice = lostDevices.find { it.macAddress == mac }

            currentDevice?.let { device ->
                val deviceType = device.getAppleLostDeviceType()?.displayName ?: "Lost Device"
                binding.tvDeviceTitle.text = "$deviceType (${device.macAddress})"
                Log.d(TAG, "Device title set: $deviceType")

                // Only show the play sound button for connectable devices
                if (device.isConnectable) {
                    binding.btnPlaySound.visibility = View.VISIBLE
                    setupAirTagConnectorCallbacks()
                    Log.d(TAG, "Device is connectable, showing play sound button")
                } else {
                    binding.btnPlaySound.visibility = View.GONE
                    Log.d(TAG, "Device is not connectable, hiding play sound button")
                }
            } ?: run {
                binding.tvDeviceTitle.text = "Lost Device"
                binding.btnPlaySound.visibility = View.GONE
                Log.w(TAG, "Device not found in current lost devices")
            }
        }
    }

    private fun observeViewModel() {
        Log.d(TAG, "Setting up observers...")

        // Observe RSSI history - obtained from SharedDataViewModel
        deviceMac?.let { mac ->
            sharedDataViewModel.getRssiHistory(mac).observe(viewLifecycleOwner) { rssiHistory ->
                if (isViewCreated && isAdded && _binding != null) {
                    Log.d(TAG, "RSSI history updated: ${rssiHistory.size} points")
                    binding.rssiChartView.updateRssiData(rssiHistory)

                    binding.tvMaxRssi.text = if (rssiHistory.isNotEmpty()) {
                        "${rssiHistory.maxByOrNull { it.rssi }?.rssi ?: "N/A"} dBm"
                    } else {
                        "N/A (No data)"
                    }

                    binding.tvCurrentRssi.text = if (rssiHistory.isNotEmpty()) {
                        "${rssiHistory.last().rssi} dBm"
                    } else {
                        "N/A (No data)"
                    }

                    // Log each fresh RSSI sample to the Nav-RSSI log, annotated with IMU state
                    rssiHistory.filter { it.timestamp > lastNavLoggedTimestamp }.forEach { point ->
                        imuLoggingViewModel.logNavRssiSample(mac, point.rssi, point.timestamp)
                        if (point.timestamp > lastNavLoggedTimestamp) {
                            lastNavLoggedTimestamp = point.timestamp
                        }
                    }
                }
            }
        }

        // Observe step count - obtained from ImuLoggingViewModel
        imuLoggingViewModel.stepCount.observe(viewLifecycleOwner) { steps ->
            if (isViewCreated && isAdded && _binding != null) {
                binding.tvStepCount.text = steps.toString()
            }
        }

        Log.d(TAG, "All observers set up")
    }

    private fun safeNavigateBack() {
        try {
            if (isAdded && !requireActivity().isFinishing) {
                parentFragmentManager.popBackStack()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate back", e)
            try {
                requireActivity().onBackPressed()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to use onBackPressed", e2)
            }
        }
    }

    // ========== Non-owner Sound Playback ==========

    /**
     * Set up the AirTag connector callbacks
     */
    private fun setupAirTagConnectorCallbacks() {
        lostDeviceConnector?.apply {
            onConnectionStateChanged = { state ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        binding.tvPlaySoundStatus.text = "Connection: $state"
                        binding.tvPlaySoundStatus.visibility = View.VISIBLE

                        // Re-enable the button after disconnecting, allowing another click
                        if (state == "Disconnected") {
                            binding.btnPlaySound.isEnabled = true
                            Log.d(TAG, "Connection disconnected, button re-enabled")
                        } else if (state == "Connected") {
                            // Record the timestamp when the connection was established
                            val connectionTime = System.currentTimeMillis()
                            audioRecorder?.setConnectionEstablishedTime(connectionTime)
                            Log.d(TAG, "Connection established at: $connectionTime")
                        }

                        Log.d(TAG, "AirTag connection state: $state")
                    }
                }
            }

            onSoundPlaybackStateChanged = { state ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        binding.tvPlaySoundStatus.text = state
                        binding.tvPlaySoundStatus.visibility = View.VISIBLE
                        binding.btnPlaySound.isEnabled = (state == "Completed" || state == "Failed" || state.contains("not available") || state.contains("disabled"))

                        // On connection failure, stop recording and delete the file
                        if (state == "Failed") {
                            Log.w(TAG, "Connection failed, stopping and deleting recording")
                            audioRecorder?.stopAndDeleteRecording()
                            binding.tvRecordingStatus.text = "Recording cancelled (Connection failed)"
                            binding.btnStartRecording.isEnabled = true
                            binding.btnStopRecording.isEnabled = false
                        }

                        Log.d(TAG, "Sound playback state: $state")
                    }
                }
            }
        }
    }

    /**
     * Play the device sound
     */
    private fun playDeviceSound() {
        if (deviceMac == null) {
            Log.e(TAG, "Device MAC address is null")
            Toast.makeText(requireContext(), "Device MAC address not available", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentDevice?.isConnectable != true) {
            Log.e(TAG, "Device is not connectable")
            Toast.makeText(requireContext(), "Device is not connectable", Toast.LENGTH_SHORT).show()
            return
        }

        // Check the Bluetooth connect permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth connect permission not granted")
            Toast.makeText(requireContext(), "Bluetooth permission required", Toast.LENGTH_SHORT).show()
            return
        }

        // Show the prefix dialog first, then start recording and playing the sound
        showPlaySoundWithRecordingDialog()
    }

    /**
     * Show the play sound and record dialog
     */
    private fun showPlaySoundWithRecordingDialog() {
        // Create a custom layout
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // Prefix input field
        val prefixLabel = android.widget.TextView(requireContext()).apply {
            text = "Test Group Prefix:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        val prefixEditText = android.widget.EditText(requireContext()).apply {
            hint = "e.g., test1, indoor, outdoor_2m"
            setText(recordingFilePrefix)
            setSingleLine()
        }

        // Angle input field
        val angleLabel = android.widget.TextView(requireContext()).apply {
            text = "Angle (optional):"
            textSize = 14f
            setPadding(0, 20, 0, 8)
        }
        val angleEditText = android.widget.EditText(requireContext()).apply {
            hint = "Enter the angle manually if needed"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            manualAngle?.let { setText(it.toInt().toString()) }
        }

        layout.addView(prefixLabel)
        layout.addView(prefixEditText)
        layout.addView(angleLabel)
        layout.addView(angleEditText)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Play Sound & Record")
            .setMessage("This will:\n1. Start audio recording\n2. Connect to device\n3. Play sound\n\nConfigure recording parameters:")
            .setView(layout)
            .setPositiveButton("Start") { _, _ ->
                recordingFilePrefix = prefixEditText.text.toString().trim()

                // Handle the manual angle input
                val angleText = angleEditText.text.toString().trim()
                manualAngle = if (angleText.isNotEmpty()) {
                    try {
                        angleText.toFloat().also {
                            Log.d(TAG, "Manual angle set to: $it")
                        }
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Invalid angle input, ignoring")
                        null
                    }
                } else {
                    Log.d(TAG, "No angle provided")
                    null
                }

                Log.d(TAG, "Recording prefix set to: '$recordingFilePrefix'")
                executePlaySoundWithRecording()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Execute playing the sound and recording
     */
    private fun executePlaySoundWithRecording() {
        // Start recording immediately after the play sound button is clicked
        Log.d(TAG, "Auto-starting recording on play sound")
        val playSoundTime = System.currentTimeMillis()
        audioRecorder?.setPlaySoundPressTime(playSoundTime)
        startAudioRecording()

        // Choose the protocol based on the device type
        val deviceType = determineDeviceType(currentDevice)
        Log.d(TAG, "Attempting to play sound on device: $deviceMac (Type: $deviceType)")

        binding.btnPlaySound.isEnabled = false
        binding.tvPlaySoundStatus.text = "Connecting..."
        binding.tvPlaySoundStatus.visibility = View.VISIBLE

        val success = lostDeviceConnector?.connectAndPlaySound(deviceMac!!, deviceType) ?: false
        if (!success) {
            binding.btnPlaySound.isEnabled = true
            binding.tvPlaySoundStatus.text = "Failed to initiate connection"
            Log.e(TAG, "Failed to initiate connection")

            // Connection initiation failed: stop recording and delete the file
            Log.w(TAG, "Connection initiation failed, stopping and deleting recording")
            audioRecorder?.stopAndDeleteRecording()
            binding.tvRecordingStatus.text = "Recording cancelled (Failed to initiate connection)"
            binding.btnStartRecording.isEnabled = true
            binding.btnStopRecording.isEnabled = false
        }
    }

    /**
     * Determine the device type from the device information
     */
    private fun determineDeviceType(device: BleDevice?): AppleDeviceType {
        if (device == null) return AppleDeviceType.AIRTAG

        return when (device.getAppleLostDeviceType()) {
            BleDevice.AppleLostDeviceType.AIRTAG -> {
                Log.d(TAG, "Device type: AIRTAG")
                AppleDeviceType.AIRTAG
            }
            BleDevice.AppleLostDeviceType.AIRPODS -> {
                Log.d(TAG, "Device type: AIRPODS (${device.getAppleLostDeviceType()?.displayName})")
                AppleDeviceType.AIRPODS
            }
            BleDevice.AppleLostDeviceType.APPLE_DEVICE,
            BleDevice.AppleLostDeviceType.UNKNOWN_FIND_MY_DEVICE -> {
                Log.d(TAG, "Device type: FIND_MY_DEVICE (${device.getAppleLostDeviceType()?.displayName})")
                AppleDeviceType.FIND_MY_DEVICE
            }
            null -> {
                Log.d(TAG, "Device type: Unknown, defaulting to AIRTAG")
                AppleDeviceType.AIRTAG
            }
        }
    }

    // ========== Audio Recording ==========

    /**
     * Set up the audio recording callbacks
     */
    private fun setupAudioRecorderCallbacks() {
        audioRecorder?.apply {
            onRecordingStarted = { leftFile, rightFile ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        binding.btnStartRecording.isEnabled = false
                        binding.btnStopRecording.isEnabled = true
                        binding.tvRecordingStatus.text = "Recording...\nLeft: $leftFile\nRight: $rightFile"
                        Log.d(TAG, "Recording started: Left=$leftFile, Right=$rightFile")
                    }
                }
            }

            onRecordingProgress = { seconds ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        val minutes = seconds / 60
                        val secs = seconds % 60
                        val timeStr = String.format("%02d:%02d", minutes, secs)
                        binding.tvRecordingStatus.text = "Recording... $timeStr"
                    }
                }
            }

            onRecordingStopped = { leftFile, rightFile ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        binding.btnStartRecording.isEnabled = true
                        binding.btnStopRecording.isEnabled = false
                        binding.tvRecordingStatus.text = "Recording stopped\nSaved:\n• ${leftFile?.name}\n• ${rightFile?.name}"
                        Log.d(TAG, "Recording stopped: Left=${leftFile?.name}, Right=${rightFile?.name}")
                    }
                }
            }

            onRecordingError = { error ->
                lifecycleScope.launch {
                    if (isViewCreated && isAdded && _binding != null) {
                        binding.btnStartRecording.isEnabled = true
                        binding.btnStopRecording.isEnabled = false
                        binding.tvRecordingStatus.text = "Error: $error"
                        Log.e(TAG, "Recording error: $error")
                        Toast.makeText(requireContext(), "Recording error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Show the recording filename prefix input dialog
     */
    private fun showRecordingPrefixDialog() {
        // Create a custom layout
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // Prefix input field
        val prefixLabel = android.widget.TextView(requireContext()).apply {
            text = "Test Group Prefix:"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        val prefixEditText = android.widget.EditText(requireContext()).apply {
            hint = "e.g., test1, indoor, outdoor_2m"
            setText(recordingFilePrefix)
            setSingleLine()
        }

        // Angle input field
        val angleLabel = android.widget.TextView(requireContext()).apply {
            text = "Angle (optional):"
            textSize = 14f
            setPadding(0, 20, 0, 8)
        }
        val angleEditText = android.widget.EditText(requireContext()).apply {
            hint = "Enter the angle manually if needed"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            manualAngle?.let { setText(it.toInt().toString()) }
        }

        layout.addView(prefixLabel)
        layout.addView(prefixEditText)
        layout.addView(angleLabel)
        layout.addView(angleEditText)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Recording Settings")
            .setMessage("Configure recording parameters:\n\n• Prefix: Identify test group\n• Angle: Tag the recording")
            .setView(layout)
            .setPositiveButton("Start Recording") { _, _ ->
                recordingFilePrefix = prefixEditText.text.toString().trim()

                // Handle the manual angle input
                val angleText = angleEditText.text.toString().trim()
                manualAngle = if (angleText.isNotEmpty()) {
                    try {
                        angleText.toFloat().also {
                            Log.d(TAG, "Manual angle set to: $it")
                        }
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Invalid angle input, ignoring")
                        null
                    }
                } else {
                    Log.d(TAG, "No angle provided")
                    null
                }

                Log.d(TAG, "Recording prefix set to: '$recordingFilePrefix'")
                startAudioRecording()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Start audio recording
     */
    private fun startAudioRecording() {
        // Check the recording permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted")
            Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }

        if (audioRecorder?.isRecording() == true) {
            Log.w(TAG, "Already recording")
            Toast.makeText(requireContext(), "Already recording", Toast.LENGTH_SHORT).show()
            return
        }

        // Use the manually annotated angle (no auto-detection)
        val phoneDirection = manualAngle

        // Get the current RSSI
        val currentRssi = currentDevice?.rssi

        val prefixInfo = if (recordingFilePrefix.isNotEmpty()) " with prefix '$recordingFilePrefix'" else ""
        Log.d(TAG, "Starting audio recording$prefixInfo, angle: $phoneDirection, RSSI: $currentRssi dBm")
        binding.tvRecordingStatus.text = "Initializing..."
        audioRecorder?.startRecording(phoneDirection, currentRssi, recordingFilePrefix)
    }

    /**
     * Stop audio recording
     */
    private fun stopAudioRecording() {
        if (audioRecorder?.isRecording() != true) {
            Log.w(TAG, "Not currently recording")
            return
        }

        Log.d(TAG, "Stopping audio recording")
        binding.tvRecordingStatus.text = "Stopping..."
        audioRecorder?.stopRecording()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Fragment paused")

        // Unregister the AirTag connector's broadcast receiver
        lostDeviceConnector?.unregisterReceiver()

        // Pause the time updates
        timeUpdateJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Fragment resumed")

        // Register the AirTag connector's broadcast receiver
        lostDeviceConnector?.registerReceiver()

        // Resume the time updates
        if (isViewCreated && isAdded) {
            startTimeUpdate()
        }
    }

    override fun onDestroyView() {
        Log.d(TAG, "Destroying view")

        // Clean up the AirTag connector
        lostDeviceConnector?.disconnect()
        lostDeviceConnector?.unregisterReceiver()
        lostDeviceConnector = null

        // Stop audio recording
        audioRecorder?.release()
        audioRecorder = null

        // Stop IMU + Nav-RSSI logging
        imuLoggingViewModel.stopLogging()

        binding.rssiChartView.clearRSSIData()
        Log.d(TAG, "Cleared RSSI chart for device: $deviceMac")

        isViewCreated = false
        timeUpdateJob?.cancel()

        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        Log.d(TAG, "Fragment detached")
        timeUpdateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Fragment destroyed")
    }
}
