package com.example.snatchapp.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.snatchapp.R
import com.example.snatchapp.adapter.BleDeviceAdapter
import com.example.snatchapp.adapter.LostDeviceAdapter
import com.example.snatchapp.databinding.FragmentLostDeviceNavigatorBinding
import com.example.snatchapp.model.BleDevice
import com.example.snatchapp.viewmodel.SharedDataViewModel

class LostDeviceNavigatorFragment : Fragment() {

    companion object {
        private const val TAG = "LostDeviceNavigator"
        private var lastClickTime = 0L
    }

    private var _binding: FragmentLostDeviceNavigatorBinding? = null
    private val binding get() = _binding!!

    private val sharedDataViewModel: SharedDataViewModel by activityViewModels()
    private lateinit var lostDeviceAdapter: LostDeviceAdapter
    private lateinit var bleDeviceAdapter: BleDeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLostDeviceNavigatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupButtons()
        observeViewModel()
    }

    private fun setupButtons() {
        // Scan + logging controls (relocated from the former Collector tab)
        binding.btnStartScan.setOnClickListener {
            showStartScanDialog()
        }

        binding.btnStopScan.setOnClickListener {
            stopScanning()
        }

        binding.btnClearScan.setOnClickListener {
            sharedDataViewModel.clearBleDevices()
            Toast.makeText(requireContext(), "Data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStartScanDialog() {
        val fileNameEdit = EditText(requireContext()).apply {
            hint = "BLE log file name"
            setText(sharedDataViewModel.generateDefaultFileName("ble"))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Start scanning and logging")
            .setView(fileNameEdit)
            .setPositiveButton("Start") { _, _ ->
                val fileName = fileNameEdit.text.toString().trim()
                startScanning(if (fileName.isNotEmpty()) fileName else null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startScanning(bleFileName: String?) {
        try {
            sharedDataViewModel.startBleLogging(bleFileName)
            sharedDataViewModel.startDataCollection()
            Toast.makeText(requireContext(), "Scanning and logging started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScanning() {
        try {
            sharedDataViewModel.stopDataCollection()
            sharedDataViewModel.stopBleLogging()
            Toast.makeText(requireContext(), "Scanning and logging stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scanning", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupRecyclerViews() {
        // Setup Lost Device RecyclerView
        lostDeviceAdapter = LostDeviceAdapter { device ->
            Log.d(TAG, "Lost Device clicked: ${device.macAddress}")
            navigateToDeviceDetail(device)
        }

        binding.recyclerViewLostDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lostDeviceAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(20)
        }

        // Setup Normal BLE Device RecyclerView
        bleDeviceAdapter = BleDeviceAdapter { device ->
            Log.d(TAG, "Normal BLE device clicked: ${device.macAddress}")
        }

        binding.recyclerViewNormalDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = bleDeviceAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(30)
        }
    }

    private fun navigateToDeviceDetail(device: BleDevice) {
        // Add debounce handling to avoid rapid clicks
        if (System.currentTimeMillis() - lastClickTime < 500) {
            return
        }
        lastClickTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "Navigating to device detail: ${device.macAddress}")

            // Ensure the Fragment and Activity are in a valid state
            if (!isAdded || activity?.isFinishing == true) {
                Log.w(TAG, "Fragment not in valid state for navigation")
                return
            }

            val detailFragment = LostDeviceDetailFragment.newInstance(device.macAddress)

            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                )
                .replace(R.id.fragment_container, detailFragment)
                .addToBackStack("device_detail")
                .commitAllowingStateLoss()

        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed", e)
        }
    }

    private fun observeViewModel() {
        Log.d(TAG, "Setting up ViewModel observation")

        sharedDataViewModel.lostDevices.observe(viewLifecycleOwner) { lostDevices ->
            Log.d(TAG, "Received ${lostDevices.size} Lost Devices from ViewModel")
            lostDeviceAdapter.updateDevices(lostDevices)
            updateLostDeviceStatistics(lostDevices)
        }

        sharedDataViewModel.bleDevices.observe(viewLifecycleOwner) { normalDevices ->
            Log.d(TAG, "Received ${normalDevices.size} Normal BLE Devices from ViewModel")
            bleDeviceAdapter.updateDevices(normalDevices)
            updateNormalDeviceStatistics(normalDevices)
        }

        // Observe scan status
        sharedDataViewModel.isCollecting.observe(viewLifecycleOwner) { isCollecting ->
            binding.tvCollectionStatus.text = if (isCollecting) "Scanning..." else "Scan stopped"
            binding.btnStartScan.isEnabled = !isCollecting
            binding.btnStopScan.isEnabled = isCollecting
            Log.d(TAG, "Collection status: $isCollecting")
        }

        // Observe error messages
        sharedDataViewModel.errorMessage.observe(viewLifecycleOwner) { errorMessage ->
            if (!errorMessage.isNullOrEmpty()) {
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                sharedDataViewModel.clearError()
            }
        }
    }

    /**
     * Update Lost Device statistics
     */
    private fun updateLostDeviceStatistics(lostDevices: List<BleDevice>) {
        binding.tvLostDeviceCount.text = "Apple Lost Devices: ${lostDevices.size}"

        // Lost Devices RSSI range
        if (lostDevices.isNotEmpty()) {
            val lostRssiValues = lostDevices.map { it.rssi }
            val maxLostRssi = lostRssiValues.maxOrNull() ?: 0
            val minLostRssi = lostRssiValues.minOrNull() ?: 0
            binding.tvLostDeviceRssiRange?.text = "RSSI: ${maxLostRssi}dBm ~ ${minLostRssi}dBm"
        } else {
            binding.tvLostDeviceRssiRange?.text = "RSSI: --"
        }
    }

    /**
     * Update Normal Device statistics
     */
    private fun updateNormalDeviceStatistics(normalDevices: List<BleDevice>) {
        binding.tvNormalDeviceCount.text = "Normal BLE Devices: ${normalDevices.size}"

        // Normal Devices RSSI range
        if (normalDevices.isNotEmpty()) {
            val normalRssiValues = normalDevices.map { it.rssi }
            val maxNormalRssi = normalRssiValues.maxOrNull() ?: 0
            val minNormalRssi = normalRssiValues.minOrNull() ?: 0
            binding.tvNormalDeviceRssiRange?.text = "RSSI: ${maxNormalRssi}dBm ~ ${minNormalRssi}dBm"
        } else {
            binding.tvNormalDeviceRssiRange?.text = "RSSI: --"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "Fragment destroyed")
        _binding = null
    }
}
