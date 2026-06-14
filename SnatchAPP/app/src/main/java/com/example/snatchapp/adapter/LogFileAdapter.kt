package com.example.snatchapp.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.snatchapp.databinding.ItemLogFileBinding
import com.example.snatchapp.utils.LogFile
import java.text.SimpleDateFormat
import java.util.*

class LogFileAdapter(
    private val onLoadClick: (LogFile) -> Unit,
    private val onDeleteClick: (LogFile) -> Unit
) : RecyclerView.Adapter<LogFileAdapter.LogFileViewHolder>() {

    private var logFiles: List<LogFile> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun updateLogFiles(newLogFiles: List<LogFile>) {
        logFiles = newLogFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogFileViewHolder {
        val binding = ItemLogFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogFileViewHolder, position: Int) {
        holder.bind(logFiles[position])
    }

    override fun getItemCount(): Int = logFiles.size

    inner class LogFileViewHolder(
        private val binding: ItemLogFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(logFile: LogFile) {
            binding.tvFileName.text = logFile.name
            binding.tvFileType.text = logFile.type
            binding.tvFileSize.text = Formatter.formatFileSize(binding.root.context, logFile.size)
            binding.tvLastModified.text = dateFormat.format(Date(logFile.lastModified))

            // Set the color based on the file type
            val typeColor = when (logFile.type) {
                "BLE" -> android.graphics.Color.parseColor("#2196F3") // Blue
                "IMU" -> android.graphics.Color.parseColor("#4CAF50") // Green
                "Nav RSSI" -> android.graphics.Color.parseColor("#800080") // Purple
                "Audio Left" -> android.graphics.Color.parseColor("#FF9800") // Orange
                "Audio Right" -> android.graphics.Color.parseColor("#FF5722") // Deep orange
                "Audio Left Metadata" -> android.graphics.Color.parseColor("#9C27B0") // Magenta
                "Audio Right Metadata" -> android.graphics.Color.parseColor("#673AB7") // Deep purple
                "Audio Metadata" -> android.graphics.Color.parseColor("#E91E63") // Pink
                "Audio" -> android.graphics.Color.parseColor("#FFC107") // Amber
                "Lost Device" -> android.graphics.Color.parseColor("#00BCD4") // Cyan
                else -> android.graphics.Color.parseColor("#757575")   // Gray
            }
            binding.tvFileType.setTextColor(typeColor)

            binding.btnLoad.setOnClickListener {
                onLoadClick(logFile)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(logFile)
            }
        }
    }
}