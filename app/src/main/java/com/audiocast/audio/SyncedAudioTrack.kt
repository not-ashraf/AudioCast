package com.audiocast.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log

class SyncedAudioTrack(
    private val deviceInfo: AudioDeviceInfo,
    private val deviceName: String,
) {
    companion object {
        private const val TAG = "SyncedAudioTrack"
    }

    private var audioTrack: AudioTrack? = null
    private val timestamp = AudioTimestamp()
    var volume: Float = 1.0f

    val isRouteConfirmed: Boolean
        get() = audioTrack?.routedDevice?.address == deviceInfo.address

    val routedDeviceName: String?
        get() = audioTrack?.routedDevice?.productName?.toString()

    fun start(): Boolean {
        val bufferSize = AudioTrack.getMinBufferSize(
            SystemAudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            SystemAudioCapture.AUDIO_FORMAT
        ) * 2

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SystemAudioCapture.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(SystemAudioCapture.AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val track = audioTrack ?: return false

        // Route to the specific Bluetooth device
        val routed = track.setPreferredDevice(deviceInfo)
        if (!routed) {
            Log.w(TAG, "setPreferredDevice returned false for $deviceName")
        }

        track.play()

        // Verify routing
        val actual = track.routedDevice
        if (actual != null && actual.address != deviceInfo.address) {
            Log.w(TAG, "Routing mismatch for $deviceName: wanted ${deviceInfo.address}, got ${actual.address}")
        } else {
            Log.i(TAG, "Routing confirmed for $deviceName (${deviceInfo.address})")
        }

        return true
    }

    fun write(samples: ShortArray) {
        val track = audioTrack ?: return

        if (volume < 1.0f) {
            // Apply per-device volume scaling
            val scaled = ShortArray(samples.size) { i ->
                (samples[i] * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            track.write(scaled, 0, scaled.size)
        } else {
            track.write(samples, 0, samples.size)
        }
    }

    fun getPlaybackPositionFrames(): Long {
        val track = audioTrack ?: return 0
        return if (track.getTimestamp(timestamp)) {
            timestamp.framePosition
        } else {
            track.playbackHeadPosition.toLong()
        }
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
