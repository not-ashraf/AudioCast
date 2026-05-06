package com.audiocast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AudioCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when AudioCast is actively streaming audio"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "audiocast_capture"
        const val NOTIFICATION_ID = 1001
    }
}
