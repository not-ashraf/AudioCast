package com.audiocast.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BtAudioDevice(
    val name: String,
    val address: String,
    val bluetoothDevice: BluetoothDevice,
    val audioDeviceInfo: AudioDeviceInfo?,
    val isConnected: Boolean,
    val isActiveOutput: Boolean,
)

class BluetoothDeviceScanner(private val context: Context) {

    companion object {
        private const val TAG = "BtDeviceScanner"
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _devices = MutableStateFlow<List<BtAudioDevice>>(emptyList())
    val devices: StateFlow<List<BtAudioDevice>> = _devices.asStateFlow()

    var a2dpProxy: BluetoothA2dp? = null
        private set

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = proxy as BluetoothA2dp
                refreshDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProxy = null
            }
        }
    }

    fun start() {
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        refreshDevices()
    }

    fun stop() {
        a2dpProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dpProxy = null
    }

    @SuppressLint("MissingPermission")
    fun refreshDevices() {
        val adapter = bluetoothAdapter ?: return

        val paired = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            return
        }

        // Get all Bluetooth audio output devices recognized by AudioManager
        val btAudioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

        val connectedAddresses = a2dpProxy?.connectedDevices
            ?.map { it.address }
            ?.toSet()
            ?: emptySet()

        // Find the currently active audio output device
        val activeAddress = getActiveBluetoothOutputAddress()

        Log.d(TAG, "BT audio devices from AudioManager: ${btAudioDevices.map { "${it.productName}(${it.address})" }}")
        Log.d(TAG, "A2DP connected: $connectedAddresses")
        Log.d(TAG, "Active BT output: $activeAddress")

        val devices = paired
            .filter { device ->
                device.bluetoothClass?.majorDeviceClass == 0x0400 ||
                    connectedAddresses.contains(device.address) ||
                    device.uuids?.any {
                        it.uuid.toString().startsWith("0000110a") ||
                            it.uuid.toString().startsWith("0000110b")
                    } == true
            }
            .map { device ->
                val audioInfo = btAudioDevices.find { audioDevice ->
                    audioDevice.address == device.address
                }
                BtAudioDevice(
                    name = device.name ?: device.address,
                    address = device.address,
                    bluetoothDevice = device,
                    audioDeviceInfo = audioInfo,
                    isConnected = connectedAddresses.contains(device.address),
                    isActiveOutput = device.address == activeAddress,
                )
            }
            .sortedByDescending { it.isConnected }

        _devices.value = devices
    }

    fun getAudioDeviceInfo(address: String): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .find { it.address == address }
    }

    private fun getActiveBluetoothOutputAddress(): String? {
        // The active BT output is the one AudioManager currently routes media to
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .firstOrNull()
            ?.address
    }

    /**
     * Try to connect a BT device as an A2DP sink using reflection.
     * This is needed because Android only keeps one A2DP device active at a time.
     * The connect() method is hidden but works on most Android 12+ devices.
     */
    @SuppressLint("MissingPermission")
    fun connectA2dp(device: BluetoothDevice): Boolean {
        val proxy = a2dpProxy ?: return false
        return try {
            val connectMethod = BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
            connectMethod.invoke(proxy, device) as Boolean
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect A2DP via reflection for ${device.name}: ${e.message}")
            false
        }
    }
}
