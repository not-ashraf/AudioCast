package com.audiocast.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioSplitter {

    companion object {
        private const val TAG = "AudioSplitter"
    }

    private val tracks = mutableListOf<SyncedAudioTrack>()

    fun addTrack(track: SyncedAudioTrack) {
        tracks.add(track)
    }

    fun removeTrack(track: SyncedAudioTrack) {
        tracks.remove(track)
    }

    fun clearTracks() {
        tracks.forEach { it.stop() }
        tracks.clear()
    }

    suspend fun processLoop(audioChannel: ReceiveChannel<ShortArray>) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Splitter started with ${tracks.size} output tracks")

        for (samples in audioChannel) {
            if (!isActive) break

            for (track in tracks) {
                track.write(samples)
            }
        }

        Log.i(TAG, "Splitter stopped")
    }

    fun getTrackCount(): Int = tracks.size
}
