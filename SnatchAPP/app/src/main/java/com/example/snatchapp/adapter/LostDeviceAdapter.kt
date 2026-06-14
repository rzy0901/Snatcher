package com.example.snatchapp.adapter

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.snatchapp.databinding.ItemLostDeviceBinding
import com.example.snatchapp.model.BleDevice
import java.text.SimpleDateFormat
import java.util.*

class LostDeviceAdapter(
    private val onDeviceClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, LostDeviceAdapter.LostDeviceViewHolder>(LostDeviceDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LostDeviceViewHolder {
        val binding = ItemLostDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LostDeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LostDeviceViewHolder, position: Int) {
        // Add a safety check
        if (position < itemCount) {
            holder.bind(getItem(position))
        }
    }

    override fun onBindViewHolder(holder: LostDeviceViewHolder, position: Int, payloads: MutableList<Any>) {
        // Add a safety check
        if (position >= itemCount) {
            return
        }

        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindPartial(getItem(position), payloads)
        }
    }

    fun updateDevices(newDevices: List<BleDevice>) {
        try {
            // Create a defensive copy to avoid concurrent modification
            val devicesCopy = newDevices.toList()
            submitList(devicesCopy)
        } catch (e: Exception) {
            Log.e("LostDeviceAdapter", "Failed to update devices", e)
        }
    }

    inner class LostDeviceViewHolder(
        private val binding: ItemLostDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: BleDevice) {
            try {
                binding.tvMacAddress.text = device.macAddress
                binding.tvRssi.text = "${device.rssi} dBm"
                binding.tvRssi.setTextColor(getRssiColor(device.rssi))

                // Show payload instead of public key
                binding.tvPayload.text = formatPayload(device.payloadHex, device.payloadLength)

                // Show timestamp
                binding.tvTimestamp.text = dateFormat.format(Date(device.timestamp))

                // Apple Lost Device indicator
                val deviceType = device.getAppleLostDeviceType()
                binding.tvDeviceType.text = deviceType?.displayName ?: "Unknown Device"
                binding.tvDeviceType.setTextColor(Color.parseColor("#007AFF")) // Apple blue

                binding.tvConnectable.text = "${device.getConnectivityStatus()}"

                binding.root.setOnClickListener {
                    onDeviceClick(device)
                }
            } catch (e: Exception) {
                Log.e("LostDeviceAdapter", "Failed to bind device", e)
            }
        }

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
                Log.e("LostDeviceAdapter", "Failed to bind partial update", e)
            }
        }

        /**
         * Format payload display with length info - same as BleDeviceAdapter
         */
        private fun formatPayload(hex: String, length: Int): String {
            return if (hex.length > 80) {
                "Payload: ${hex.take(80)}...($length bytes)"
            } else {
                "Payload: $hex ($length bytes)"
            }
        }

        private fun getRssiColor(rssi: Int): Int {
            return when {
                rssi >= -50 -> Color.parseColor("#4CAF50")
                rssi >= -60 -> Color.parseColor("#8BC34A")
                rssi >= -70 -> Color.parseColor("#FFC107")
                rssi >= -80 -> Color.parseColor("#FF9800")
                rssi >= -90 -> Color.parseColor("#FF5722")
                else -> Color.parseColor("#F44336")
            }
        }
    }
}

class LostDeviceDiffCallback : DiffUtil.ItemCallback<BleDevice>() {
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

            Log.d("LostDeviceDiffCallback", "Changes for ${oldItem.macAddress}: $changes")

            if (changes.isNotEmpty()) changes else null
        } catch (e: Exception) {
            Log.e("LostDeviceDiffCallback", "Failed to get change payload", e)
            null
        }
    }
}