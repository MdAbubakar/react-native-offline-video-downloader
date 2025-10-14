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

    // ✅ Service state tracking
    private var isServiceRunning = false
    private var isInitialized = false

    // ✅ Lifecycle tracking
    private var initializationAttempts = 0
    private val maxInitializationAttempts = 10
    private var isDestroyed = false

    override fun onCreate() {
        Log.d(TAG, "🚀 VideoDownloadService onCreate called")

        // ✅ Prevent multiple initialization
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, ignoring onCreate")
            return
        }

        isServiceRunning = true
        isDestroyed = false
        initializationAttempts = 0

        // Create notification channel first
        createNotificationChannel()

        // ✅ CRITICAL: Check if app is ready
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
            Log.d(TAG, "✅ VideoDownloadService created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating VideoDownloadService: ${e.message}")
            handleServiceStartFailure(e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand: ${intent?.action}")

        if (!isInitialized) {
            Log.w(TAG, "Service not initialized, deferring command")
            return START_NOT_STICKY
        }

        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onStartCommand: ${e.message}")
            START_NOT_STICKY
        }
    }

    // ✅ NEW: Check if app and modules are ready
    private fun isAppReady(): Boolean {
        return try {
            // Check if Registry is initialized
            if (!OfflineVideoRegistry.isInitialized()) {
                Log.d(TAG, "OfflineVideoRegistry not ready")
                return false
            }

            // Check if DownloadManager exists
            val downloadManager = OfflineVideoDownloaderModule.getDownloadManager()
            if (downloadManager == null) {
                Log.d(TAG, "DownloadManager not ready")
                return false
            }

            Log.d(TAG, "✅ App is ready for service start")
            true
        } catch (e: Exception) {
            Log.d(TAG, "App readiness check failed: ${e.message}")
            false
        }
    }

    // ✅ NEW: Defer service start until app is ready
    private fun deferServiceStart() {
        val handler = Handler(Looper.getMainLooper())

        val checkRunnable = object : Runnable {
            override fun run() {
                if (isDestroyed) {
                    Log.d(TAG, "Service destroyed during deferred start")
                    return
                }

                initializationAttempts++
                Log.d(TAG, "🔄 Initialization attempt $initializationAttempts/$maxInitializationAttempts")

                if (isAppReady()) {
                    Log.d(TAG, "✅ App ready after $initializationAttempts attempts, starting service")
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
                        Log.d(TAG, "✅ Deferred service start successful")

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Deferred service start failed: ${e.message}")
                        handleServiceStartFailure(e)
                    }
                } else if (initializationAttempts < maxInitializationAttempts) {
                    Log.d(TAG, "⏳ App not ready, retrying in 1s (attempt $initializationAttempts/$maxInitializationAttempts)")

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
                    Log.e(TAG, "❌ App not ready after $maxInitializationAttempts attempts, stopping service")
                    stopSelf()
                }
            }
        }

        handler.postDelayed(checkRunnable, 1000)
    }

    // ✅ NEW: Handle service start failures gracefully
    private fun handleServiceStartFailure(exception: Exception) {
        try {
            Log.e(TAG, "🔥 Service start failed: ${exception.message}")

            // Show error notification
            val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Video Downloader Error")
                .setContentText("Download service failed to start. Tap to retry.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setSilent(true)
                .build()

            startForeground(FOREGROUND_NOTIFICATION_ID, errorNotification)

            // Stop service after showing error
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
            Log.d(TAG, "✅ Retrieved DownloadManager successfully")
            downloadManager
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting DownloadManager: ${e.message}")
            throw e
        }
    }

    override fun getScheduler(): Scheduler? {
        // No scheduler needed for basic functionality
        return null
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        return try {
            Log.d(TAG, "📋 Creating notification for ${downloads.size} downloads")

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
            Log.e(TAG, "❌ Error creating notification: ${e.message}")
            createErrorNotification()
        }
    }

    // ✅ Notification creation methods
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
            Log.d(TAG, "📱 Notification channel created")
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

    // ✅ CRITICAL FIX: Don't kill service when task is removed
    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d(TAG, "📱 Task removed - keeping service alive for background downloads")

        // ✅ DON'T call stopSelf() - let downloads continue
        // Show notification that downloads are continuing
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloads continuing")
            .setContentText("Video downloads running in background")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

        // ✅ IMPORTANT: Don't call super.onTaskRemoved() or stopSelf()
        // This allows the service to continue running
    }

    override fun onDestroy() {
        Log.d(TAG, "🔄 VideoDownloadService onDestroy called")
        isDestroyed = true
        isServiceRunning = false
        isInitialized = false

        try {
            super.onDestroy()
            Log.d(TAG, "✅ VideoDownloadService destroyed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error destroying service: ${e.message}")
        }
    }
}
