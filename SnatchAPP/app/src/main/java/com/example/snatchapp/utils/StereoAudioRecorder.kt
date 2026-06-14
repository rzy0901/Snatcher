package com.example.snatchapp.utils

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class StereoAudioRecorder(
    private val storageDir: File,
    private val deviceMacAddress: String
) {

    companion object {
        private const val TAG = "StereoAudioRecorder"
        private const val SAMPLE_RATE = 44100 // 44.1kHz sample rate
    }

    private var stereoRecorder: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    
    private var leftChannelFile: File? = null
    private var rightChannelFile: File? = null
    private var leftMetadataFile: File? = null
    private var rightMetadataFile: File? = null
    
    private var recordingThread: Thread? = null
    private var totalSamplesRecorded = 0L
    private var recordedPhoneDirection: Float? = null // Record the phone direction at the start of recording
    private var recordedRssi: Int? = null // Record the RSSI at the start of recording

    // New member variables for timestamps
    private var firstSampleTimeNanos: Long? = null
    private var firstSampleTimeMillis: Long? = null
    private var playSoundPressTime: Long? = null
    private var connectionEstablishedTime: Long? = null
    
    var onRecordingStarted: ((String, String) -> Unit)? = null
    var onRecordingProgress: ((Long) -> Unit)? = null // Recording duration (seconds)
    var onRecordingStopped: ((File?, File?) -> Unit)? = null
    var onRecordingError: ((String) -> Unit)? = null


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(phoneDirection: Float? = null, initialRssi: Int? = null, filePrefix: String = "") {
        if (isRecording.get()) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $bufferSize")
                onRecordingError?.invoke("Failed to get buffer size")
                return
            }

            // Use a larger buffer to ensure stability
            val actualBufferSize = bufferSize * 2

            stereoRecorder = AudioRecord(
//                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, // Stereo
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferSize
            )

            if (stereoRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord can't initialize!")
                onRecordingError?.invoke("Failed to initialize recorder")
                return
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val macSafe = deviceMacAddress.replace(":", "")
            
            // If a prefix is provided, add it to the file name
            val prefixStr = if (filePrefix.isNotEmpty()) {
                "${filePrefix}_"
            } else {
                ""
            }
            
            // If angle information is provided, add it to the file name
            val angleStr = if (phoneDirection != null) {
                "_angle${phoneDirection.toInt()}"
            } else {
                ""
            }

            // If RSSI information is provided, add it to the file name
            val rssiStr = if (initialRssi != null) {
                "_rssi$initialRssi"
            } else {
                ""
            }
            
            leftChannelFile = File(storageDir, "${prefixStr}audio_left_${macSafe}${angleStr}${rssiStr}_$timestamp.pcm")
            rightChannelFile = File(storageDir, "${prefixStr}audio_right_${macSafe}${angleStr}${rssiStr}_$timestamp.pcm")
            leftMetadataFile = File(storageDir, "${prefixStr}audio_left_${macSafe}${angleStr}${rssiStr}_${timestamp}_metadata.txt")
            rightMetadataFile = File(storageDir, "${prefixStr}audio_right_${macSafe}${angleStr}${rssiStr}_${timestamp}_metadata.txt")

            isRecording.set(true)
            totalSamplesRecorded = 0L
            recordedPhoneDirection = phoneDirection // Save the phone direction at the start of recording
            recordedRssi = initialRssi
            firstSampleTimeNanos = null
            firstSampleTimeMillis = null

            // Start the recording thread
            recordingThread = thread(name = "StereoRecordingThread") {
                recordStereoAndSeparate(actualBufferSize)
            }

            onRecordingStarted?.invoke(
                leftChannelFile?.name ?: "left_channel.pcm",
                rightChannelFile?.name ?: "right_channel.pcm"
            )

            Log.d(TAG, "Stereo recording started")

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}", e)
            onRecordingError?.invoke("Permission denied: ${e.message}")
            stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            onRecordingError?.invoke("Failed to start: ${e.message}")
            stopRecording()
        }
    }


    private fun recordStereoAndSeparate(bufferSize: Int) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

        Log.v(TAG, "Start stereo recording loop")

        // Stereo buffer (interleaved format: LRLRLR...)
        val stereoBuffer = ShortArray(bufferSize / 2) // /2 because a short is 2 bytes

        // Mono buffer
        val monoBufferSize = stereoBuffer.size / 2
        val leftChannelBuffer = ShortArray(monoBufferSize)
        val rightChannelBuffer = ShortArray(monoBufferSize)

        try {
            // Start recording
            stereoRecorder?.startRecording()

            FileOutputStream(leftChannelFile).use { leftStream ->
                FileOutputStream(rightChannelFile).use { rightStream ->

                    while (isRecording.get()) {
                        // Record the time before reading
                        val beforeReadNanos = SystemClock.elapsedRealtimeNanos()
                        val beforeReadMillis = System.currentTimeMillis()

                        // Read the stereo data
                        val samplesRead = stereoRecorder?.read(stereoBuffer, 0, stereoBuffer.size) ?: 0

                        if (samplesRead > 0) {
                            // Record the timestamp of the first sample
                            if (firstSampleTimeNanos == null) {
                                firstSampleTimeNanos = beforeReadNanos
                                firstSampleTimeMillis = beforeReadMillis

                                // Save the timestamp information (both channels use the same timestamp)
                                saveTimestampMetadata(
                                    leftMetadataFile!!,
                                    "Left Channel"
                                )
                                saveTimestampMetadata(
                                    rightMetadataFile!!,
                                    "Right Channel"
                                )

                                Log.d(TAG, "First sample timestamp: $firstSampleTimeNanos ns")
                            }

                            // Separate the stereo data into left and right channels
                            // Stereo data format: L0, R0, L1, R1, L2, R2...
                            val monoSamples = samplesRead / 2
                            for (i in 0 until monoSamples) {
                                leftChannelBuffer[i] = stereoBuffer[2 * i] // Left channel (even index)
                                rightChannelBuffer[i] = stereoBuffer[2 * i + 1] // Right channel (odd index)
                            }

                            // Convert the ShortArray to a ByteArray and write it
                            val leftBytes = shortArrayToByteArray(leftChannelBuffer, monoSamples)
                            val rightBytes = shortArrayToByteArray(rightChannelBuffer, monoSamples)

                            leftStream.write(leftBytes)
                            rightStream.write(rightBytes)

                            totalSamplesRecorded += monoSamples

                            // Report progress every 5 seconds
                            if (totalSamplesRecorded % (SAMPLE_RATE * 5) == 0L) {
                                val seconds = totalSamplesRecorded / SAMPLE_RATE
                                Log.d(TAG, "Recorded $seconds seconds")
                                onRecordingProgress?.invoke(seconds)
                            }
                        } else {
                            // Handle errors
                            when (samplesRead) {
                                AudioRecord.ERROR_INVALID_OPERATION -> {
                                    Log.e(TAG, "Invalid operation")
                                    break
                                }
                                AudioRecord.ERROR_BAD_VALUE -> {
                                    Log.e(TAG, "Bad value")
                                    break
                                }
                                AudioRecord.ERROR_DEAD_OBJECT -> {
                                    Log.e(TAG, "Dead object")
                                    break
                                }
                                AudioRecord.ERROR -> {
                                    Log.e(TAG, "General error")
                                    break
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Recording completed. Total samples: $totalSamplesRecorded")

            // Update the metadata files
            if (firstSampleTimeNanos != null) {
                updateMetadataFile(leftMetadataFile!!, "Left Channel")
                updateMetadataFile(rightMetadataFile!!, "Right Channel")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            onRecordingError?.invoke("Recording error: ${e.message}")
        } finally {
            stereoRecorder?.stop()
        }

        Log.v(TAG, "End stereo recording loop")
    }


    fun stopRecording() {
        if (!isRecording.get()) {
            Log.w(TAG, "Not currently recording")
            return
        }

        Log.d(TAG, "Stopping stereo recording")
        isRecording.set(false)

        try {
            // Wait for the recording thread to finish
            recordingThread?.join(3000) // Wait up to 3 seconds

            stereoRecorder?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }

            stereoRecorder = null

            Log.d(TAG, "Stereo recording stopped and resources released")
            onRecordingStopped?.invoke(leftChannelFile, rightChannelFile)

            // Reset the timestamps
            resetEventTimestamps()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ${e.message}", e)
            onRecordingError?.invoke("Error stopping: ${e.message}")
        }
    }

    fun setPlaySoundPressTime(time: Long) {
        playSoundPressTime = time
    }

    fun setConnectionEstablishedTime(time: Long) {
        connectionEstablishedTime = time
        // Update the metadata file immediately
        if (isRecording.get() && firstSampleTimeNanos != null) {
            try {
                if (leftMetadataFile != null) updateMetadataFile(leftMetadataFile!!, "Left Channel")
                if (rightMetadataFile != null) updateMetadataFile(rightMetadataFile!!, "Right Channel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update metadata on connection established", e)
            }
        }
    }

    private fun resetEventTimestamps() {
        playSoundPressTime = null
        connectionEstablishedTime = null
    }

    private fun shortArrayToByteArray(shorts: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            buffer.putShort(shorts[i])
        }
        return bytes
    }

    private fun generateMetadataContent(
        channelName: String,
        isFinalUpdate: Boolean
    ): String {
        val phoneDirectionStr = if (recordedPhoneDirection != null) {
            "${recordedPhoneDirection!!.toInt()}°"
        } else {
            "Not recorded"
        }
        
        val rssiStr = if (recordedRssi != null) {
            "$recordedRssi dBm"
        } else {
            "Not recorded"
        }

        val sb = StringBuilder()
        sb.append("Recording Metadata\n")
        sb.append("==================\n")
        sb.append("Device MAC: $deviceMacAddress\n")
        sb.append("Channel: $channelName\n")
        sb.append("Sample Rate: $SAMPLE_RATE Hz\n")
        sb.append("Phone Direction at Recording Start: $phoneDirectionStr\n")
        sb.append("RSSI at Recording Start: $rssiStr\n")

        if (isFinalUpdate) {
            val duration = totalSamplesRecorded.toDouble() / SAMPLE_RATE
            sb.append("Total Samples: $totalSamplesRecorded\n")
            sb.append("Duration: %.6f seconds\n".format(duration))
        }

        sb.append("\nTiming Information:\n")
        sb.append("------------------\n")
        
        firstSampleTimeNanos?.let { nanos ->
            firstSampleTimeMillis?.let { millis ->
                sb.append("First Sample Time (nanos): $nanos\n")
                sb.append("First Sample Time (millis): $millis\n")
                sb.append("First Sample Time (ISO): ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))}\n")
            }
        }
        
        sb.append("System Clock (nanos): ${SystemClock.elapsedRealtimeNanos()}\n")
        
        if (isFinalUpdate) {
            val endTimeNanos = SystemClock.elapsedRealtimeNanos()
            val endTimeMillis = System.currentTimeMillis()
            sb.append("End Time (nanos): $endTimeNanos\n")
            sb.append("End Time (millis): $endTimeMillis\n")
            sb.append("End Time (ISO): ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(endTimeMillis))}\n")
            
            firstSampleTimeNanos?.let { startNanos ->
                sb.append("Recording Duration (calculated): ${(endTimeNanos - startNanos) / 1_000_000_000.0} seconds\n")
            }
        } else {
             sb.append("Recording Start: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        }

        sb.append("\nConnection Events:\n")
        sb.append("------------------\n")
        
        playSoundPressTime?.let { time ->
            sb.append("Play Sound Button Pressed: $time (${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))})\n")
        } ?: sb.append("Play Sound Button Pressed: N/A\n")
        
        connectionEstablishedTime?.let { time ->
            sb.append("Connection Established: $time (${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))})\n")
            
            playSoundPressTime?.let { startTime ->
                val durationMs = time - startTime
                sb.append("Connection Establishment Duration: ${durationMs}ms (${durationMs / 1000.0}s)\n")
            }
        } ?: sb.append("Connection Established: N/A\n")

        sb.append("\nRecording Mode: STEREO (Channel Separated)\n")
        if (isFinalUpdate) {
             sb.append("Note: Both channels were recorded simultaneously and are perfectly synchronized\n")
        }
        
        return sb.toString()
    }

    private fun saveTimestampMetadata(
        metadataFile: File,
        channelName: String
    ) {
        try {
            val metadata = generateMetadataContent(channelName, false)
            metadataFile.writeText(metadata)
            Log.d(TAG, "Timestamp metadata saved to: ${metadataFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save timestamp metadata: ${e.message}")
        }
    }


    private fun updateMetadataFile(
        metadataFile: File,
        channelName: String
    ) {
        try {
            val metadata = generateMetadataContent(channelName, true)
            metadataFile.writeText(metadata)
            Log.d(TAG, "Metadata file updated: ${metadataFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata file: ${e.message}")
        }
    }


    fun isRecording(): Boolean = isRecording.get()


    fun getRecordingDuration(): Long {
        return if (isRecording.get()) {
            totalSamplesRecorded / SAMPLE_RATE
        } else {
            0L
        }
    }

    /**
     * Delete the current recording files (including PCM files and metadata files)
     * Typically called when a connection fails, to clean up an incomplete recording
     */
    fun deleteCurrentRecordingFiles() {
        try {
            val filesToDelete = mutableListOf<File?>()
            filesToDelete.add(leftChannelFile)
            filesToDelete.add(rightChannelFile)
            filesToDelete.add(leftMetadataFile)
            filesToDelete.add(rightMetadataFile)
            
            var deletedCount = 0
            filesToDelete.forEach { file ->
                if (file?.exists() == true) {
                    if (file.delete()) {
                        Log.d(TAG, "Deleted file: ${file.name}")
                        deletedCount++
                    } else {
                        Log.w(TAG, "Failed to delete file: ${file.name}")
                    }
                }
            }
            
            Log.d(TAG, "Deleted $deletedCount recording files")

            // Clear the file references
            leftChannelFile = null
            rightChannelFile = null
            leftMetadataFile = null
            rightMetadataFile = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting recording files: ${e.message}", e)
        }
    }
    
    /**
     * Stop recording and delete all related files
     * Used in situations that require cleanup, such as a connection failure
     */
    fun stopAndDeleteRecording() {
        if (isRecording.get()) {
            Log.d(TAG, "Stopping and deleting current recording due to failure")
            isRecording.set(false)
            
            try {
                // Wait for the recording thread to finish
                recordingThread?.join(1000) // Wait up to 1 second
                
                stereoRecorder?.apply {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                    release()
                }
                
                stereoRecorder = null
                
                // Delete the recording files
                deleteCurrentRecordingFiles()

                // Reset the timestamps
                resetEventTimestamps()
                firstSampleTimeNanos = null
                firstSampleTimeMillis = null
                totalSamplesRecorded = 0L
                
                Log.d(TAG, "Recording stopped and deleted, timestamps reset")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping and deleting recording: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Not recording, but will attempt to delete any leftover files")
            deleteCurrentRecordingFiles()
            resetEventTimestamps()
        }
    }

    fun release() {
        stopRecording()
    }
}
