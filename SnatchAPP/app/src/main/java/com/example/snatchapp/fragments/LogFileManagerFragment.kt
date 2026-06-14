package com.example.snatchapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.snatchapp.adapter.LogFileAdapter
import com.example.snatchapp.databinding.FragmentLogFileManagerBinding
import com.example.snatchapp.viewmodel.SharedDataViewModel

class LogFileManagerFragment : Fragment() {

    private var _binding: FragmentLogFileManagerBinding? = null
    private val binding get() = _binding!!

    private val sharedDataViewModel: SharedDataViewModel by activityViewModels()
    private lateinit var logFileAdapter: LogFileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogFileManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observeViewModel()

        // Refresh the log file list
        sharedDataViewModel.refreshLogFiles()
    }

    private fun setupRecyclerView() {
        logFileAdapter = LogFileAdapter(
            onLoadClick = { logFile ->
//                when (logFile.type) {
//                    "BLE" -> sharedDataViewModel.loadBleLogFile(logFile.name)
//                    "IMU" -> sharedDataViewModel.loadImuLogFile(logFile.name)
//                }
//
//                // Switch to the device list page to view the loaded data
//                // Here we can notify MainActivity to switch the Fragment via a callback
                showLoadConfirmDialog(logFile.name, logFile.type)
            },
            onDeleteClick = { logFile ->
                showDeleteConfirmDialog(logFile.name)
            }
        )

        binding.recyclerViewLogFiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logFileAdapter
        }

        binding.btnRefresh.setOnClickListener {
            sharedDataViewModel.refreshLogFiles()
        }

        binding.btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete confirmation")
                .setMessage("Are you sure you want to delete all log files?")
                .setPositiveButton("Delete") { _, _ ->
                    sharedDataViewModel.deleteAllLogFiles()
//                    Toast NOT IMPLEMENTED
//                    Toast.makeText(requireContext(), "Delete all log files [NOT IMPLEMENTED]", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun observeViewModel() {
        sharedDataViewModel.logFiles.observe(viewLifecycleOwner) { logFiles ->
            logFileAdapter.updateLogFiles(logFiles)
            binding.tvLogFileCount.text = "Log num: ${logFiles.size}"
        }
    }

    private fun showLoadConfirmDialog(fileName: String, fileType: String) {
        AlertDialog.Builder(requireContext())
//            .setTitle("Load confirmation")
//            .setMessage("Are you sure you want to load log file \"$fileName\"?")
//            .setPositiveButton("Load") { _, _ ->
//                when (fileType) {
//                    "BLE" -> sharedDataViewModel.loadBleLogFile(fileName)
//                    "IMU" -> sharedDataViewModel.loadImuLogFile(fileName)
//                }
//            }
//            .setNegativeButton("Cancel", null)
//            .show()
        // NOT IMPLEMENTED
        AlertDialog.Builder(requireContext())
            .setTitle("Load confirmation [NOT IMPLEMENTED]")
            .setMessage("Are you sure you want to load log file \"$fileName\" of type \"$fileType\"?")
            .setPositiveButton("Load") { _, _ ->
                // Here we can add the logic to load the log file
                // For example, navigate to the corresponding device list page
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmDialog(fileName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete confirmation")
            .setMessage("Are you sure you want to delete log file \"$fileName\"?")
            .setPositiveButton("Delete") { _, _ ->
                sharedDataViewModel.deleteLogFile(fileName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}