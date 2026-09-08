package com.notification2sail.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // 1. Read data from the server payload
        val regattaName = remoteMessage.data["title"] ?: remoteMessage.notification?.title ?: "Regatta Update"
        val infoUpdate = remoteMessage.data["body"] ?: remoteMessage.notification?.body ?: "There is a new update available."

        // Deep link data from the server scheduler
        val targetUrl = remoteMessage.data["target_url"]
        val targetTab = remoteMessage.data["target_tab"] // e.g., "entries", "results", etc.

        // 2. Display notification on the device
        sendNotification(regattaName, infoUpdate, targetUrl, targetTab)
    }

    private fun sendNotification(title: String, body: String, targetUrl: String?, targetTab: String?) {
        // Prepare Intent: Clicking opens MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Attach URL and specific tab as extras
            putExtra("target_url", targetUrl)
            putExtra("target_tab", targetTab)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "regatta_channel_id"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Regatta Updates",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
