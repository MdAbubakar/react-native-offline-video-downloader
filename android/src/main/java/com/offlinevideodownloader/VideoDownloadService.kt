package com.offlinevideodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

@UnstableApi
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {
    companion object {
        private const val TAG = "VideoDownloadService"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VideoDownloadChannel"
        private const val JOB_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (!OfflineVideoDownloaderModule.isDownloadManagerInitialized()) {
            Log.w(TAG, "DownloadManager not initialized, initializing now...")
            try {
                OfflineVideoDownloaderModule.initializeDownloadManagerForService(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize DownloadManager in service", e)
            }
        }
    }

    override fun getDownloadManager(): DownloadManager {
        var manager = OfflineVideoDownloaderModule.getDownloadManager()

        if (manager == null) {
            Log.w(TAG, "DownloadManager was null, attempting initialization...")
            try {
                OfflineVideoDownloaderModule.initializeDownloadManagerForService(applicationContext)
                manager = OfflineVideoDownloaderModule.getDownloadManager()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize DownloadManager", e)
            }
        }

        return manager ?: throw IllegalStateException("DownloadManager could not be initialized")
    }

    override fun getScheduler(): Scheduler? {
        return try {
            PlatformScheduler(this, JOB_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create scheduler", e)
            null
        }
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        return when {
            downloads.isEmpty() -> {
                createNotification("Video Downloader", "Ready for downloads")
            }
            else -> {
                val activeDownloads = downloads.filter {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
                }

                when {
                    activeDownloads.isEmpty() -> {
                        val completedCount = downloads.count { it.state == Download.STATE_COMPLETED }
                        createNotification(
                            "Downloads complete",
                            "$completedCount video${if (completedCount != 1) "s" else ""} ready"
                        )
                    }
                    activeDownloads.size == 1 -> {
                        val download = activeDownloads.first()
                        val progress = download.percentDownloaded.toInt()
                        createProgressNotification("Downloading video...", "$progress% complete", progress)
                    }
                    else -> {
                        val totalProgress = activeDownloads.map { it.percentDownloaded }.average().toInt()
                        createProgressNotification(
                            "Downloading ${activeDownloads.size} videos",
                            "$totalProgress% overall",
                            totalProgress
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of video downloads"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createProgressNotification(title: String, text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }
}
