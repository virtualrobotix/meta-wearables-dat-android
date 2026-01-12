/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccess Sample App - Main Activity
//
// This is the main entry point for the CameraAccess sample application that demonstrates how to use
// the Meta Wearables Device Access Toolkit (DAT) to:
// - Initialize the DAT SDK
// - Handle device permissions (Bluetooth, Internet)
// - Request camera permissions from wearable devices (Ray-Ban Meta glasses)
// - Stream video and capture photos from connected wearable devices

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  companion object {
    private const val TAG = "MainActivity"
    
    // Required Android permissions for the DAT SDK and LiveKit WebRTC
    val PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT
      arrayOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN, RECORD_AUDIO, CAMERA)
    } else {
      // Older Android versions
      arrayOf(BLUETOOTH, RECORD_AUDIO, CAMERA)
    }
  }

  val viewModel: WearablesViewModel by viewModels()

  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  // Requesting wearable device permissions via the Meta AI app
  private val permissionsResultLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
      }

  // Convenience method to make a permission request in a sequential manner
  // Uses a Mutex to ensure requests are processed one at a time, preventing race conditions
  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        permissionContinuation = continuation
        continuation.invokeOnCancellation { permissionContinuation = null }
        permissionsResultLauncher.launch(permission)
      }
    }
  }

  private var permissionLauncher = registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
    Log.d(TAG, "Permissions result: $permissionsResult")
    
    // Check if at least Bluetooth permission is granted
    val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissionsResult[BLUETOOTH_CONNECT] == true
    } else {
      permissionsResult[BLUETOOTH] == true
    }
    
    if (bluetoothGranted) {
      initializeWearables()
    } else {
      viewModel.setRecentError(
          "Permesso Bluetooth necessario. Vai in Impostazioni > App > CameraAccess > Permessi"
      )
      // Still try to initialize - some features may work
      initializeWearables()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      CameraAccessScaffold(
          viewModel = viewModel,
          onRequestWearablesPermission = ::requestWearablesPermission,
      )
    }

    // Check if permissions are already granted
    if (hasRequiredPermissions()) {
      Log.d(TAG, "Permissions already granted")
      initializeWearables()
    } else {
      Log.d(TAG, "Requesting permissions")
      permissionLauncher.launch(PERMISSIONS)
    }
  }
  
  private fun hasRequiredPermissions(): Boolean {
    return PERMISSIONS.all { permission ->
      ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
  }
  
  private fun initializeWearables() {
    try {
      Log.d(TAG, "Initializing Wearables SDK")
      Wearables.initialize(this)
      viewModel.startMonitoring()
      Log.d(TAG, "Wearables SDK initialized successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize Wearables SDK", e)
      viewModel.setRecentError("Errore inizializzazione: ${e.message}")
    }
  }
}
