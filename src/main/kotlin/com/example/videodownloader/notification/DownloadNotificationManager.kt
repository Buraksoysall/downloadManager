package com.example.videodownloader.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class DownloadNotificationManager(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val CHANNEL_NAME = "İndirme İlerlemesi"
        private const val CHANNEL_DESCRIPTION = "Video indirme ilerlemesi bildirimleri"
    }
    
    private val notificationManager = NotificationManagerCompat.from(context)
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d("NotificationManager", "Notification channel created: $CHANNEL_ID")
        }
    }
    
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    
    fun showProgressNotification(
        notificationId: Int,
        title: String,
        progress: Int,
        maxProgress: Int = 100,
        isIndeterminate: Boolean = false
    ) {
        if (!hasNotificationPermission()) {
            Log.w("NotificationManager", "Notification permission not granted")
            return
        }
        
        val progressText = if (isIndeterminate) "İndiriliyor..." else "İndiriliyor... %${progress}"
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(maxProgress, progress, isIndeterminate)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
            
        try {
            notificationManager.notify(notificationId, notification)
            Log.d("NotificationManager", "Progress notification shown: $title - $progressText")
        } catch (e: SecurityException) {
            Log.e("NotificationManager", "Failed to show notification: ${e.message}")
        }
    }
    
    fun showCompletedNotification(
        notificationId: Int,
        title: String,
        message: String = "İndirme tamamlandı"
    ) {
        if (!hasNotificationPermission()) {
            Log.w("NotificationManager", "Notification permission not granted")
            return
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            
        try {
            notificationManager.notify(notificationId, notification)
            Log.d("NotificationManager", "Completed notification shown: $title")
        } catch (e: SecurityException) {
            Log.e("NotificationManager", "Failed to show completed notification: ${e.message}")
        }
    }
    
    fun showErrorNotification(
        notificationId: Int,
        title: String,
        error: String = "İndirme başarısız"
    ) {
        if (!hasNotificationPermission()) {
            Log.w("NotificationManager", "Notification permission not granted")
            return
        }
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
            
        try {
            notificationManager.notify(notificationId, notification)
            Log.d("NotificationManager", "Error notification shown: $title - $error")
        } catch (e: SecurityException) {
            Log.e("NotificationManager", "Failed to show error notification: ${e.message}")
        }
    }
    
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}
