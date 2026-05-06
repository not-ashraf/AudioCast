package com.audiocast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.audiocast.ui.AudioCastScreen
import com.audiocast.ui.theme.AudioCastTheme
import com.audiocast.viewmodel.AudioCastViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AudioCastViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // ViewModel handles startForegroundService + startCapture in correct order
            viewModel.startCapture(result.resultCode, result.data!!)
        }
    }

    // Used for initial BT permission request (on launch)
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val btGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (btGranted) {
            viewModel.onPermissionsGranted()
        }
    }

    // Used when user clicks "Start Streaming"
    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            viewModel.onPermissionsGranted()
            requestMediaProjection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request BT permissions early so the device list populates
        requestBluetoothPermissions()

        setContent {
            AudioCastTheme {
                AudioCastScreen(
                    viewModel = viewModel,
                    onStartCapture = ::onStartCaptureClicked,
                )
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val btPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = btPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            btPermissionLauncher.launch(needed.toTypedArray())
        } else {
            // Already granted
            viewModel.onPermissionsGranted()
        }
    }

    private fun onStartCaptureClicked() {
        val allPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = allPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            capturePermissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.onPermissionsGranted()
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
