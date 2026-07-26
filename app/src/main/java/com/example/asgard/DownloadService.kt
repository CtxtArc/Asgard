package com.example.asgard

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "download_channel"
    private var isCollecting = false

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isCollecting) {
            isCollecting = true
            val app = applicationContext as AsgardApp
            val viewModel = app.downloaderViewModel
            
            serviceScope.launch {
                viewModel.downloadQueue.collect { queue ->
                    val activeTasks = queue.count { it.status.contains("Downloading") || it.status.contains("Extracting") || it.status.contains("Moving") }
                    if (activeTasks > 0) {
                        startForeground(NOTIFICATION_ID, createNotification(activeTasks))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (queue.isEmpty() || queue.all { it.status.contains("Completed") || it.status.contains("Error") }) {
                            stopSelf()
                        }
                    }
                }
            }
        }
        
        return START_NOT_STICKY
    }

    private fun createNotification(activeCount: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asgard Downloader")
            .setContentText("Processing $activeCount active download(s)...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows background download status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
