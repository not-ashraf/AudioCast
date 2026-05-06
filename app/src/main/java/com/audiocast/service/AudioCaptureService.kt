package com.audiocast.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.audiocast.AudioCastApp
import com.audiocast.MainActivity
import com.audiocast.R
import com.audiocast.audio.AudioSplitter
import com.audiocast.audio.SyncedAudioTrack
import com.audiocast.audio.SystemAudioCapture
import com.audiocast.bluetooth.BluetoothDeviceScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureState(
    val isCapturing: Boolean = false,
    val activeDevices: List<String> = emptyList(),
    val error: String? = null,
    val syncDriftMs: Long = 0,
)

/**
 * Pending device info passed from ViewModel before service start.
 * Contains both the AudioDeviceInfo (if available) and the raw BluetoothDevice
 * so we can attempt A2DP connection for devices that aren't yet active.
 */
data class PendingDevice(
    val name: String,
    val address: String,
    val audioDeviceInfo: AudioDeviceInfo?,
    val bluetoothDevice: BluetoothDevice?,
    val isActiveOutput: Boolean,
)

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START = "com.audiocast.START_CAPTURE"
        const val ACTION_STOP = "com.audiocast.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var captureJob: Job? = null
    private var syncJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var systemCapture: SystemAudioCapture? = null
    private val splitter = AudioSplitter()
    private val activeTracks = mutableMapOf<String, SyncedAudioTrack>()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    var pendingDevices: List<PendingDevice> = emptyList()
    var scanner: BluetoothDeviceScanner? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)

                if (data == null) {
                    Log.e(TAG, "No projection data in intent")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val deviceNames = pendingDevices.map { it.name }.ifEmpty { listOf("devices") }
                startForeground(
                    AudioCastApp.NOTIFICATION_ID,
                    buildNotification(deviceNames),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )

                // Launch capture on a coroutine to allow A2DP connection delay
                scope.launch {
                    beginCapture(resultCode, data, pendingDevices)
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun beginCapture(
        resultCode: Int,
        data: Intent,
        devices: List<PendingDevice>,
    ) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            _state.value = _state.value.copy(error = "Failed to get MediaProjection")
            stopSelf()
            return
        }

        val audioManager = getSystemService(AudioManager::class.java)

        // Separate devices into two categories:
        // 1. Already-active output: system already routes audio here, no AudioTrack needed
        // 2. Additional devices: need AudioTrack + setPreferredDevice
        val activeDevice = devices.find { it.isActiveOutput }
        val additionalDevices = devices.filter { !it.isActiveOutput }

        if (activeDevice != null) {
            Log.i(TAG, "${activeDevice.name} is already the active output — system handles it, no AudioTrack needed")
        }

        if (additionalDevices.isEmpty()) {
            // All selected devices are already active — nothing extra to do
            _state.value = CaptureState(
                isCapturing = true,
                activeDevices = devices.map { it.name },
            )
            Log.i(TAG, "All selected devices already receive audio via system routing. No capture needed.")
            // Don't start capture pipeline since there's nothing extra to route
            return
        }

        // For additional devices that don't have AudioDeviceInfo, try to connect via A2DP
        for (device in additionalDevices) {
            if (device.audioDeviceInfo == null && device.bluetoothDevice != null) {
                Log.i(TAG, "Attempting A2DP connection for: ${device.name} (${device.address})")
                scanner?.connectA2dp(device.bluetoothDevice)
            }
        }

        // Wait for A2DP connection to establish and AudioDeviceInfo to become available
        if (additionalDevices.any { it.audioDeviceInfo == null }) {
            Log.i(TAG, "Waiting for A2DP connections to establish...")
            delay(3000)
        }

        // Set up audio capture
        val capture = SystemAudioCapture(mediaProjection!!)
        systemCapture = capture

        if (!capture.start()) {
            _state.value = _state.value.copy(error = "Failed to start audio capture")
            stopSelf()
            return
        }

        // Create AudioTrack ONLY for additional (non-active) devices
        for (device in additionalDevices) {
            // Re-query AudioDeviceInfo in case A2DP just connected
            val audioInfo = device.audioDeviceInfo
                ?: scanner?.getAudioDeviceInfo(device.address)
                ?: findAudioDeviceByAddress(audioManager, device.address)

            if (audioInfo == null) {
                Log.w(TAG, "SKIPPED ${device.name} — no AudioDeviceInfo. Device may not be connected as A2DP sink.")
                continue
            }

            val track = SyncedAudioTrack(audioInfo, device.name)
            if (track.start()) {
                activeTracks[device.name] = track
                splitter.addTrack(track)
                Log.i(TAG, "Output track started for: ${device.name} (address: ${device.address})")
            } else {
                Log.w(TAG, "Failed to start output track for: ${device.name}")
            }
        }

        // Build the full list of devices that will receive audio
        val allReceivingDevices = mutableListOf<String>()
        if (activeDevice != null) {
            allReceivingDevices.add(activeDevice.name + " (system)")
        }
        allReceivingDevices.addAll(activeTracks.keys)

        if (activeTracks.isEmpty()) {
            _state.value = _state.value.copy(
                error = "Could not route audio to additional devices. " +
                    "Make sure they are connected via Bluetooth."
            )
            capture.stop()
            // Don't stopSelf — the active device still works via system routing
            if (activeDevice != null) {
                _state.value = CaptureState(
                    isCapturing = true,
                    activeDevices = listOf(activeDevice.name + " (system only)"),
                    error = "Second device could not be connected as A2DP output. " +
                        "Try connecting it manually in Bluetooth settings first.",
                )
            } else {
                stopSelf()
            }
            return
        }

        _state.value = CaptureState(
            isCapturing = true,
            activeDevices = allReceivingDevices,
        )

        // Start the capture -> splitter pipeline
        captureJob = scope.launch {
            launch(Dispatchers.IO) { capture.readLoop() }
            launch(Dispatchers.IO) { splitter.processLoop(capture.audioChannel) }
        }

        syncJob = scope.launch {
            monitorSync()
        }

        Log.i(TAG, "Capture started — system routes to: ${activeDevice?.name ?: "none"}, " +
            "AudioTrack routes to: ${activeTracks.keys}")
    }

    private fun findAudioDeviceByAddress(audioManager: AudioManager, address: String): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .find { it.address == address }
    }

    fun updateDeviceVolume(deviceName: String, volume: Float) {
        activeTracks[deviceName]?.volume = volume.coerceIn(0f, 1f)
    }

    fun stopCapture() {
        captureJob?.cancel()
        syncJob?.cancel()
        captureJob = null
        syncJob = null

        systemCapture?.stop()
        systemCapture = null

        splitter.clearTracks()
        activeTracks.clear()

        mediaProjection?.stop()
        mediaProjection = null

        _state.value = CaptureState(isCapturing = false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Capture stopped")
    }

    private suspend fun monitorSync() {
        while (true) {
            delay(1000)
            if (activeTracks.size < 2) continue

            val positions = activeTracks.mapValues { it.value.getPlaybackPositionFrames() }
            val maxPos = positions.values.maxOrNull() ?: continue
            val minPos = positions.values.minOrNull() ?: continue
            val driftFrames = maxPos - minPos
            val driftMs = driftFrames * 1000 / SystemAudioCapture.SAMPLE_RATE

            _state.value = _state.value.copy(syncDriftMs = driftMs)

            if (driftMs > 50) {
                Log.w(TAG, "Sync drift detected: ${driftMs}ms between devices")
            }
        }
    }

    private fun buildNotification(deviceNames: List<String>): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val devicesText = deviceNames.joinToString(", ")

        return Notification.Builder(this, AudioCastApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText("Playing on: $devicesText")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.stop_capture),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }
}
