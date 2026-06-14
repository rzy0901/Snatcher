package com.example.snatchapp

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.snatchapp.databinding.ActivityMainBinding
import com.example.snatchapp.fragments.LogFileManagerFragment
import com.example.snatchapp.fragments.LostDeviceNavigatorFragment
import com.example.snatchapp.viewmodel.SharedDataViewModel
import com.example.snatchapp.utils.PermissionUtils

class MainActivity : AppCompatActivity() {

    @RequiresApi(Build.VERSION_CODES.S)
    private val permissions = arrayOf(
        PermissionUtils.Permissions.RECORD_AUDIO,
        PermissionUtils.Permissions.BLUETOOTH_SCAN,
        PermissionUtils.Permissions.BLUETOOTH_CONNECT,
        PermissionUtils.Permissions.ACCESS_FINE_LOCATION,
        PermissionUtils.Permissions.ACCESS_COARSE_LOCATION,
        PermissionUtils.Permissions.WRITE_EXTERNAL_STORAGE,
        PermissionUtils.Permissions.READ_EXTERNAL_STORAGE,
        PermissionUtils.Permissions.ACTIVITY_RECOGNITION
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedDataViewModel: SharedDataViewModel

    // Keep Fragment instances to avoid recreating them
    private var lostDeviceNavigatorFragment: LostDeviceNavigatorFragment? = null
    private var logFileManagerFragment: LogFileManagerFragment? = null

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the shared ViewModel
        sharedDataViewModel = ViewModelProvider(this)[SharedDataViewModel::class.java]

        // Set up the bottom navigation bar
        setupBottomNavigation()

        // Show the scanner page by default
        if (savedInstanceState == null) {
            showLostDeviceNavigatorFragment()
        }

        PermissionUtils.checkAndRequestPermissions(this, permissions)
    }

    private fun setupBottomNavigation() {
        // Set the bottom navigation bar style
        binding.bottomNavigation.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
        binding.bottomNavigation.itemIconTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
        binding.bottomNavigation.itemTextColor = ContextCompat.getColorStateList(this, R.color.purple_500)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_lost_device -> {
                    showLostDeviceNavigatorFragment()
                    true
                }
                R.id.nav_log_manager -> {
                    showLogFileManagerFragment()
                    true
                }
                else -> false
            }
        }
    }

    private fun showLostDeviceNavigatorFragment() {
        if (lostDeviceNavigatorFragment == null) {
            lostDeviceNavigatorFragment = LostDeviceNavigatorFragment()
        }
        showFragment(lostDeviceNavigatorFragment!!)
    }

    private fun showLogFileManagerFragment() {
        if (logFileManagerFragment == null) {
            logFileManagerFragment = LogFileManagerFragment()
        }
        showFragment(logFileManagerFragment!!)
    }

    private fun showFragment(fragment: Fragment) {
        // Check whether the Fragment is already displayed
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.javaClass == fragment.javaClass) {
            return
        }

        // Ensure execution on the main thread, and check the Activity state
        if (isFinishing || isDestroyed) {
            return
        }
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitAllowingStateLoss() // Allow state loss to avoid IllegalStateException
        } catch (e: Exception) {
            Log.e("MainActivity", "Fragment transaction failed", e)
            Toast(this).apply {
                setText("Failed: ${e.message}")
                show()
            }
        }
    }

//    private fun safeFragmentTransaction(action: () -> Unit) {
//        if (!isFinishing && !isDestroyed) {
//            try {
//                action()
//            } catch (e: Exception) {
//                Log.e("MainActivity", "Fragment operation failed", e)
//            }
//        }
//    }
}