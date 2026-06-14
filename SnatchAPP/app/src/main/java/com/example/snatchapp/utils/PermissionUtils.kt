package com.example.snatchapp.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.os.Build
import androidx.annotation.RequiresApi

class PermissionUtils {

    object Permissions {
        @RequiresApi(Build.VERSION_CODES.S)
        const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
        @RequiresApi(Build.VERSION_CODES.S)
        const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
        const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        const val READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE
        const val ACCESS_FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION
        const val ACCESS_COARSE_LOCATION = Manifest.permission.ACCESS_COARSE_LOCATION
        const val ACTIVITY_RECOGNITION = Manifest.permission.ACTIVITY_RECOGNITION
    }

    object RequestCodes {
        const val ALL = 100
    }

    companion object {

        /**
         * Check whether a single permission has been granted
         */
        fun hasPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Check whether all of multiple permissions have been granted
         */
        fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
            return permissions.all { hasPermission(context, it) }
        }

        /**
         * Request permissions within an Activity
         * @param activity the Activity instance
         * @param permissions the array of permissions to request
         * @param requestCode the request code
         */
        fun requestPermissions(
            activity: Activity,
            permissions: Array<String>,
            requestCode: Int = RequestCodes.ALL
        ) {
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }

        /**
         * Request permissions within a Fragment
         * @param fragment the Fragment instance
         * @param permissions the array of permissions to request
         * @param requestCode the request code
         */
        fun requestPermissions(
            fragment: Fragment,
            permissions: Array<String>,
            requestCode: Int = RequestCodes.ALL
        ) {
            fragment.requestPermissions(permissions, requestCode)
        }

        /**
         * Check and request permissions (Activity version)
         * @return true if permissions are already granted, false if a permission request is in progress
         */
        fun checkAndRequestPermissions(
            activity: Activity,
            permissions: Array<String>,
            requestCode: Int = RequestCodes.ALL
        ): Boolean {
            return if (hasPermissions(activity, permissions)) {
                true
            } else {
                requestPermissions(activity, permissions, requestCode)
                false
            }
        }

        /**
         * Check and request permissions (Fragment version)
         * @return true if permissions are already granted, false if a permission request is in progress
         */
        fun checkAndRequestPermissions(
            fragment: Fragment,
            permissions: Array<String>,
            requestCode: Int = RequestCodes.ALL
        ): Boolean {
            val context = fragment.requireContext()
            return if (hasPermissions(context, permissions)) {
                true
            } else {
                requestPermissions(fragment, permissions, requestCode)
                false
            }
        }
    }
}