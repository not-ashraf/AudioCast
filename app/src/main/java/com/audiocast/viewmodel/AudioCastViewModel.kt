package com.audiocast.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.audiocast.bluetooth.BluetoothDeviceScanner
import com.audiocast.bluetooth.BtAudioDevice
import com.audiocast.bluetooth.DeviceConnectionTracker
import com.audiocast.service.AudioCaptureService
import com.audiocast.service.CaptureState
import com.audiocast.service.PendingDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCastViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_SELECTED = "selected_devices"
        private const val KEY_VOLUMES = "device_volumes"
    }

    private val scanner = BluetoothDeviceScanner(application)
    private val connectionTracker = DeviceConnectionTracker(application) {
        scanner.refreshDevices()
    }

    val devices: StateFlow<List<BtAudioDevice>> = scanner.devices

    private val _selectedDevices = MutableStateFlow(
        savedState.get<ArrayList<String>>(KEY_SELECTED)?.toSet() ?: emptySet()
    )
    val selectedDevices: StateFlow<Set<String>> = _selectedDevices.asStateFlow()

    private val _deviceVolumes = MutableStateFlow(
        savedState.get<HashMap<String, Float>>(KEY_VOLUMES)?.toMap() ?: emptyMap()
    )
    val deviceVolumes: StateFlow<Map<String, Float>> = _deviceVolumes.asStateFlow()

    private var service: AudioCaptureService? = null
    private val _serviceBound = MutableStateFlow(false)

    val captureState: StateFlow<CaptureState> = _serviceBound.flatMapLatest { bound ->
        if (bound) service?.state ?: flowOf(CaptureState())
        else flowOf(CaptureState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CaptureState())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AudioCaptureService.LocalBinder).getService()
            // Share the scanner so the service can attempt A2DP connections
            service?.scanner = scanner
            _serviceBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _serviceBound.value = false
        }
    }

    private var initialized = false

    init {
        bindService()
    }

    fun onPermissionsGranted() {
        if (initialized) return
        initialized = true
        scanner.start()
        connectionTracker.register()
    }

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AudioCaptureService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun toggleDevice(address: String, selected: Boolean) {
        val updated = if (selected) {
            _selectedDevices.value + address
        } else {
            _selectedDevices.value - address
        }
        _selectedDevices.value = updated
        savedState[KEY_SELECTED] = ArrayList(updated)
    }

    fun setDeviceVolume(address: String, volume: Float) {
        val updated = _deviceVolumes.value + (address to volume)
        _deviceVolumes.value = updated
        savedState[KEY_VOLUMES] = HashMap(updated)
        val device = devices.value.find { it.address == address }
        if (device != null) {
            service?.updateDeviceVolume(device.name, volume)
        }
    }

    private fun buildPendingDevices(): List<PendingDevice> {
        // Refresh device list to get latest connection state
        scanner.refreshDevices()

        return _selectedDevices.value.mapNotNull { address ->
            val device = devices.value.find { it.address == address } ?: return@mapNotNull null
            PendingDevice(
                name = device.name,
                address = device.address,
                audioDeviceInfo = device.audioDeviceInfo ?: scanner.getAudioDeviceInfo(address),
                bluetoothDevice = device.bluetoothDevice,
                isActiveOutput = device.isActiveOutput,
            )
        }
    }

    fun startCapture(resultCode: Int, data: Intent) {
        val pending = buildPendingDevices()
        if (pending.isEmpty()) return

        val context = getApplication<Application>()

        service?.pendingDevices = pending
        service?.scanner = scanner

        val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_DATA, data)
        }
        context.startForegroundService(serviceIntent)

        // Apply pre-set volumes after capture starts
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            for ((address, volume) in _deviceVolumes.value) {
                val device = devices.value.find { it.address == address }
                if (device != null) {
                    service?.updateDeviceVolume(device.name, volume)
                }
            }
        }
    }

    fun stopCapture() {
        service?.stopCapture()
    }

    override fun onCleared() {
        connectionTracker.unregister()
        scanner.stop()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) { }
        super.onCleared()
    }
}
