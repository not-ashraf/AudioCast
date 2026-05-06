package com.audiocast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiocast.ui.components.CaptureControls
import com.audiocast.ui.components.DeviceCard
import com.audiocast.viewmodel.AudioCastViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCastScreen(
    viewModel: AudioCastViewModel,
    onStartCapture: () -> Unit,
) {
    val devices by viewModel.devices.collectAsState()
    val selectedDevices by viewModel.selectedDevices.collectAsState()
    val volumes by viewModel.deviceVolumes.collectAsState()
    val captureState by viewModel.captureState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AudioCast")
                        if (captureState.isCapturing) {
                            Text(
                                text = "Streaming to ${captureState.activeDevices.size} devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = "Bluetooth Devices",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                if (devices.isEmpty()) {
                    item {
                        Text(
                            text = "No paired Bluetooth audio devices found.\nPair a device in Android Settings first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                items(devices, key = { it.address }) { device ->
                    val isRouted = captureState.activeDevices.any {
                        it.startsWith(device.name)
                    }
                    DeviceCard(
                        name = device.name,
                        isConnected = device.isConnected,
                        isSelected = selectedDevices.contains(device.address),
                        isRouteConfirmed = isRouted,
                        isActiveOutput = device.isActiveOutput,
                        volume = volumes[device.address] ?: 1f,
                        isStreaming = captureState.isCapturing,
                        onSelectedChange = { selected ->
                            viewModel.toggleDevice(device.address, selected)
                        },
                        onVolumeChange = { volume ->
                            viewModel.setDeviceVolume(device.address, volume)
                        },
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            CaptureControls(
                isCapturing = captureState.isCapturing,
                selectedDeviceCount = selectedDevices.size,
                syncDriftMs = captureState.syncDriftMs,
                error = captureState.error,
                onStartCapture = onStartCapture,
                onStopCapture = { viewModel.stopCapture() },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
