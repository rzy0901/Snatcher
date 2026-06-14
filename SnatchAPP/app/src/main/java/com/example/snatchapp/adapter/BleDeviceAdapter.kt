package com.example.snatchapp.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.snatchapp.databinding.ItemBleDeviceBinding
import com.example.snatchapp.model.BleDevice
import java.text.SimpleDateFormat
import java.util.*

class BleDeviceAdapter(
    private val onDeviceClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, BleDeviceAdapter.BleDeviceViewHolder>(BleDeviceDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BleDeviceViewHolder {
        val binding = ItemBleDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BleDeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BleDeviceViewHolder, position: Int) {
        // Add a safety check
        if (position < itemCount) {
            holder.bind(getItem(position))
        }
    }

    override fun onBindViewHolder(holder: BleDeviceViewHolder, position: Int, payloads: MutableList<Any>) {
        // Add a safety check
        if (position >= itemCount) {
            return
        }

        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Partial update for better performance
            holder.bindPartial(getItem(position), payloads)
        }
    }

    /**
     * Update device list efficiently - add a safety check
     */
    fun updateDevices(newDevices: List<BleDevice>) {
        try {
            // Create a defensive copy to avoid concurrent modification
            val devicesCopy = newDevices.toList()
            submitList(devicesCopy)
        } catch (e: Exception) {
            // If the update fails, log it but do not crash
            android.util.Log.e("BleDeviceAdapter", "Failed to update devices", e)
        }
    }

    inner class BleDeviceViewHolder(
        private val binding: ItemBleDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BleDevice) {
            try {
                binding.tvMacAddress.text = device.macAddress

                // Enhanced RSSI display with color coding
                binding.tvRssi.text = "${device.rssi} dBm"
                binding.tvRssi.setTextColor(getRssiColor(device.rssi))

                // Format payload display
                binding.tvPayload.text = formatPayload(device.payloadHex, device.payloadLength)

                // Display precise timestamp
                binding.tvTimestamp.text = dateFormat.format(Date(device.timestamp))

                binding.tvConnectable.text = "${device.getConnectivityStatus()}"

                binding.root.setOnClickListener {
                    onDeviceClick(device)
                }
            } catch (e: Exception) {
                android.util.Log.e("BleDeviceAdapter", "Failed to bind device", e)
            }
        }

        /**
         * Partial update binding for better performance
         */
        fun bindPartial(device: BleDevice, payloads: MutableList<Any>) {
            try {
                payloads.forEach { payload ->
                    when (payload) {
                        "rssi_changed" -> {
                            binding.tvRssi.text = "${device.rssi} dBm"
                            binding.tvRssi.setTextColor(getRssiColor(device.rssi))
                            binding.tvTimestamp.text = dateFormat.format(Date(device.timestamp))
                        }
                        "payload_changed" -> {
                            binding.tvPayload.text = formatPayload(device.payloadHex, device.payloadLength)
                            binding.tvTimestamp.text = dateFormat.format(Date(device.timestamp))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BleDeviceAdapter", "Failed to bind partial update", e)
            }
        }

        /**
         * Format payload display with length info
         */
        private fun formatPayload(hex: String, length: Int): String {
            return if (hex.length > 80) {
                "Payload: ${hex.take(80)}...($length bytes)"
            } else {
                "Payload: $hex ($length bytes)"
            }
        }

        /**
         * Get color based on RSSI strength
         */
        private fun getRssiColor(rssi: Int): Int {
            return when {
                rssi >= -50 -> Color.parseColor("#4CAF50") // Green - Excellent
                rssi >= -60 -> Color.parseColor("#8BC34A") // Light green - Very good
                rssi >= -70 -> Color.parseColor("#FFC107") // Amber - Good
                rssi >= -80 -> Color.parseColor("#FF9800") // Orange - Fair
                rssi >= -90 -> Color.parseColor("#FF5722") // Deep orange - Poor
                else -> Color.parseColor("#F44336")        // Red - Very poor
            }
        }
    }
}

/**
 * Optimized DiffUtil callback with a safety check
 */
class BleDeviceDiffCallback : DiffUtil.ItemCallback<BleDevice>() {
    override fun areItemsTheSame(oldItem: BleDevice, newItem: BleDevice): Boolean {
        return try {
            oldItem.macAddress == newItem.macAddress
        } catch (e: Exception) {
            false
        }
    }

    override fun areContentsTheSame(oldItem: BleDevice, newItem: BleDevice): Boolean {
        return try {
            oldItem == newItem
        } catch (e: Exception) {
            false
        }
    }

    override fun getChangePayload(oldItem: BleDevice, newItem: BleDevice): Any? {
        return try {
            val changes = mutableListOf<String>()

            if (oldItem.rssi != newItem.rssi) {
                changes.add("rssi_changed")
            }
            if (oldItem.payloadHex != newItem.payloadHex) {
                changes.add("payload_changed")
            }

            if (changes.isNotEmpty()) changes else null
        } catch (e: Exception) {
            // If the comparison fails, return null to let the system perform a full refresh
            android.util.Log.e("BleDeviceDiffCallback", "Failed to get change payload", e)
            null
        }
    }
}