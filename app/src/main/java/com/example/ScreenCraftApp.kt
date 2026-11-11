package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class ScreenCraftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            val usbChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_USB_SHARE_ID,
                "USB Screen Share",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active USB screen share streaming status"
                setShowBadge(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(usbChannel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "screencraft_recording_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_USB_SHARE_ID = "screencraft_usb_share_channel"
        const val NOTIFICATION_USB_SHARE_ID = 2001
    }
}
