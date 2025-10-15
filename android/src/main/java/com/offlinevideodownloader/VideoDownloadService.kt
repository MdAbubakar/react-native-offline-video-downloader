package com.offlinevideodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
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
    }

    private var isServiceRunning = false
    private var isInitialized = false

    private var initializationAttempts = 0
    private val maxInitializationAttempts = 10
    private var isDestroyed = false

    override fun onCreate() {

        if (isServiceRunning) {
            return
        }

        isServiceRunning = true
        isDestroyed = false
        initializationAttempts = 0

        // Create notification channel first
        createNotificationChannel()

        if (!isAppReady()) {
            Log.w(TAG, "App not ready, deferring service start")
            startForeground(FOREGROUND_NOTIFICATION_ID, createWaitingNotification())
            deferServiceStart()
            return
        }

        // Start service normally if app is ready
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, createInitializingNotification())
            super.onCreate()
            isInitialized = true
        } catch (e: Exception) {
            handleServiceStartFailure(e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isInitialized) {
            return START_NOT_STICKY
        }

        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            START_NOT_STICKY
        }
    }

    private fun isAppReady(): Boolean {
        return try {
            // Check if Registry is initialized
            if (!OfflineVideoRegistry.isInitialized()) {
                return false
            }

            // Check if DownloadManager exists
            val downloadManager = OfflineVideoDownloaderModule.getDownloadManager()
            if (downloadManager == null) {
                return false
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun deferServiceStart() {
        val handler = Handler(Looper.getMainLooper())

        val checkRunnable = object : Runnable {
            override fun run() {
                if (isDestroyed) {
                    return
                }

                initializationAttempts++

                if (isAppReady()) {
                    try {
                        // Update notification
                        val notification = NotificationCompat.Builder(this@VideoDownloadService, CHANNEL_ID)
                            .setContentTitle("Video Downloader")
                            .setContentText("Initializing download service...")
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setOngoing(true)
                            .setSilent(true)
                            .setProgress(100, 90, false)
                            .build()

                        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

                        // Initialize parent service
                        super@VideoDownloadService.onCreate()
                        isInitialized = true

                    } catch (e: Exception) {
                        handleServiceStartFailure(e)
                    }
                } else if (initializationAttempts < maxInitializationAttempts) {
                    // Update waiting notification
                    val waitingNotification = NotificationCompat.Builder(this@VideoDownloadService, CHANNEL_ID)
                        .setContentTitle("Video Downloader")
                        .setContentText("Waiting for app initialization... ($initializationAttempts/$maxInitializationAttempts)")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        .setSilent(true)
                        .setProgress(maxInitializationAttempts, initializationAttempts, false)
                        .build()

                    startForeground(FOREGROUND_NOTIFICATION_ID, waitingNotification)
                    handler.postDelayed(this, 1000)
                } else {
                    stopSelf()
                }
            }
        }

        handler.postDelayed(checkRunnable, 1000)
    }

    private fun handleServiceStartFailure(exception: Exception) {
        try {
            // Show error notification
            val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Video Downloader Error")
                .setContentText("Download service failed to start. Tap to retry.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setSilent(true)
                .build()

            startForeground(FOREGROUND_NOTIFICATION_ID, errorNotification)

            Handler(Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling service start failure: ${e.message}")
            stopSelf()
        }
    }

    override fun getDownloadManager(): DownloadManager {
        return try {
            val downloadManager = OfflineVideoDownloaderModule.getDownloadManager()
                ?: throw IllegalStateException("DownloadManager not initialized")
            downloadManager
        } catch (e: Exception) {
            throw e
        }
    }

    override fun getScheduler(): Scheduler? {
        return null
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        return try {

            if (downloads.isEmpty()) {
                createIdleNotification()
            } else {
                val activeDownloads = downloads.filter {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
                }

                when (activeDownloads.size) {
                    0 -> createCompletedNotification(downloads)
                    1 -> createSingleDownloadNotification(activeDownloads.first())
                    else -> createMultipleDownloadsNotification(activeDownloads)
                }
            }
        } catch (e: Exception) {
            createErrorNotification()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows progress of video downloads for offline playback"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createWaitingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Downloader")
            .setContentText("Waiting for app to initialize...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createInitializingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Downloader")
            .setContentText("Initializing download service...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, 50, false)
            .build()
    }

    private fun createIdleNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Downloader")
            .setContentText("Ready for downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createSingleDownloadNotification(download: Download): Notification {
        val progress = download.percentDownloaded.toInt()
        val title = "Downloading video..."
        val text = if (progress > 0) "$progress% complete" else "Starting download..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }

    private fun createMultipleDownloadsNotification(downloads: List<Download>): Notification {
        val totalProgress = downloads.map { it.percentDownloaded }.average().toInt()
        val completedCount = downloads.count { it.percentDownloaded >= 100f }
        val totalCount = downloads.size

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading $totalCount videos")
            .setContentText("$completedCount completed • $totalProgress% overall")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, totalProgress, false)
            .build()
    }

    private fun createCompletedNotification(downloads: List<Download>): Notification {
        val completedCount = downloads.count { it.state == Download.STATE_COMPLETED }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloads complete")
            .setContentText("$completedCount video${if (completedCount != 1) "s" else ""} ready for offline viewing")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setSilent(true)
            .setAutoCancel(false)
            .build()
    }

    private fun createErrorNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Downloader")
            .setContentText("Download service error")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        // Show notification that downloads are continuing
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloads continuing")
            .setContentText("Video downloads running in background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isDestroyed = true
        isServiceRunning = false
        isInitialized = false

        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying service: ${e.message}")
        }
    }
}
