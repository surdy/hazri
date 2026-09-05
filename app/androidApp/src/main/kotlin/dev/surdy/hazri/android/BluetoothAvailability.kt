package dev.surdy.hazri.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which permissions a BLE scan needs on this device, and whether they are held.
 *
 * The set changed at API 31: before it, a scan needed the *location* permission because
 * BLE results can be used to locate a phone; from 31 an app can instead declare
 * `neverForLocation` and ask only for `BLUETOOTH_SCAN`, which is what the manifest does.
 */
object BluetoothAvailability {

    /** The permissions to request, correct for this API level. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Whether every permission in [requiredPermissions] has been granted. */
    fun hasScanPermission(context: Context): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
