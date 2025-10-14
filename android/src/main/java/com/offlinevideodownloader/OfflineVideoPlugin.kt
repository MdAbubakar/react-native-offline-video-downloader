package com.offlinevideodownloader

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.brentvatne.exoplayer.RNVExoplayerPlugin
import com.brentvatne.react.ReactNativeVideoManager
import androidx.media3.common.util.Log
import com.brentvatne.common.api.Source
import androidx.media3.exoplayer.offline.Download
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class PlaybackMode {
    ONLINE,   // Default - never use cache, always stream online
    OFFLINE   // Only use cache if available, no network requests
}

@UnstableApi
class OfflineVideoPlugin : RNVExoplayerPlugin {

    companion object {
        private const val TAG = "OfflineVideoPlugin"
        private var instance: OfflineVideoPlugin? = null

        private var playbackMode = PlaybackMode.ONLINE

        private val cacheCheckExecutor = Executors.newFixedThreadPool(3)
        private val handler = Handler(Looper.getMainLooper())

        fun cleanup() {
            try {
                cacheCheckExecutor.shutdown()
                if (!cacheCheckExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    cacheCheckExecutor.shutdownNow()
                }
                Log.d(TAG, "✅ Cache executor cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "Warning: Error shutting down executor: ${e.message}")
            }
        }

        fun setPlaybackMode(mode: PlaybackMode) {
            val pluginInstance = getInstance()

            synchronized(this) {
                val oldMode = playbackMode
                playbackMode = mode

                try {
                    pluginInstance.contentDownloadCache.clear()  // ✅ Use pre-obtained instance
                    Log.d(TAG, "🎯 Playback mode changed: $oldMode → $mode")
                } catch (e: Exception) {
                    Log.w(TAG, "Warning: Error clearing cache during mode change: ${e.message}")
                }
            }
        }

        fun getPlaybackMode(): PlaybackMode = playbackMode

        fun getInstance(): OfflineVideoPlugin {
            if (instance == null) {
                instance = OfflineVideoPlugin()
            }
            return instance!!
        }
    }

    // ✅ ENHANCED: Better cache management
    private val contentDownloadCache = ConcurrentHashMap<String, Boolean>()
    private val cacheTimeout = 2000L
    private var lastCacheCleanup = 0L
    private val cacheCleanupInterval = 300000L // 5 minutes

    init {
        try {
            ReactNativeVideoManager.getInstance().registerPlugin(this)
            Log.d(TAG, "✅ OfflineVideoPlugin registered successfully with ReactNativeVideoManager")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register plugin: ${e.message}")
        }
    }

    override fun onInstanceCreated(id: String, player: ExoPlayer) {
        OfflineVideoRegistry.registerPlayer(player)
        Log.d(TAG, "📱 ExoPlayer instance created: $id")
    }

    override fun onInstanceRemoved(id: String, player: ExoPlayer) {
        OfflineVideoRegistry.unregisterPlayer(player)
        Log.d(TAG, "📱 ExoPlayer instance removed: $id")
    }

    // ✅ NEW: Remove only specific download from plugin cache
    fun removeDownloadFromCache(downloadId: String) {
        synchronized(this) {
            try {
                Log.d(TAG, "🧹 Removing $downloadId from plugin cache")

                // ✅ FIXED: Only remove entries related to this download
                val keysToRemove = mutableListOf<String>()

                contentDownloadCache.keys.forEach { uri ->
                    // Check if URI is related to this download
                    if (isUriRelatedToDownload(uri, downloadId)) {
                        keysToRemove.add(uri)
                    }
                }

                keysToRemove.forEach { key ->
                    contentDownloadCache.remove(key)
                    Log.d(TAG, "🗑️ Removed plugin cache key: $key")
                }

                Log.d(TAG, "✅ Removed ${keysToRemove.size} plugin cache entries for: $downloadId")

            } catch (e: Exception) {
                Log.w(TAG, "Error removing download from plugin cache: ${e.message}")
            }
        }
    }

    // ✅ NEW: Check if URI is related to a specific download
    private fun isUriRelatedToDownload(uri: String, downloadId: String): Boolean {
        return try {
            // Direct match
            if (uri == downloadId || uri.contains(downloadId)) {
                return true
            }

            // Extract content ID from both and compare
            val uriContentId = extractContentId(uri)
            val downloadContentId = extractContentId(downloadId)

            if (uriContentId.isNotEmpty() && downloadContentId.isNotEmpty() && uriContentId == downloadContentId) {
                return true
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking URI relation: ${e.message}")
            false
        }
    }

    // ✅ NEW: Extract content ID from URI or download ID
    private fun extractContentId(identifier: String): String {
        return try {
            // For your URLs like: etvwin-s3.akamaized.net/6782084dc7036a0cfa096af2/HD_playlist.m3u8
            val regex = Regex("([a-f0-9]{24})")
            val match = regex.find(identifier)
            val contentId = match?.value ?: ""
            if (contentId.isNotEmpty()) {
                Log.d(TAG, "🔍 Extracted content ID '$contentId' from: $identifier")
            }
            contentId
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract content ID from: $identifier")
            ""
        }
    }

    override fun overrideMediaDataSourceFactory(
        source: Source,
        mediaDataSourceFactory: DataSource.Factory
    ): DataSource.Factory? {
        return try {
            val uri = source.uri?.toString()
            if (uri == null) {
                Log.d(TAG, "URI is null, no override needed")
                return null
            }

            Log.d(TAG, "🔍 Checking DataSource override for: $uri")
            Log.d(TAG, "🎯 Playback mode: $playbackMode")

            // ✅ FIXED: Respect playback mode
            when (playbackMode) {
                PlaybackMode.ONLINE -> {
                    // ✅ ONLINE: Never override, always use default (fast)
                    Log.d(TAG, "🌐 ONLINE mode - using default DataSource for: $uri")
                    return null
                }

                PlaybackMode.OFFLINE -> {
                    // ✅ OFFLINE: Only override if content is cached
                    val isCached = isContentCached(uri)
                    if (isCached) {
                        Log.d(TAG, "📱 OFFLINE mode - using cached DataSource for: $uri")
                        val headers = extractHeadersFromSource(source)
                        return OfflineVideoRegistry.createCacheAwareDataSourceFactory(headers)
                    } else {
                        Log.w(TAG, "⚠️ OFFLINE mode - content not cached, using default: $uri")
                        return null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in overrideMediaDataSourceFactory: ${e.message}")
            null
        }
    }

    // ✅ Helper method
    private fun isContentCached(uri: String): Boolean {
        return try {
            // Check in-memory cache first
            contentDownloadCache[uri]?.let { return it }

            // ✅ Quick check with timeout
            val startTime = System.currentTimeMillis()
            val isCached = checkCacheWithTimeout(uri)
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "💾 Cache check took ${duration}ms for: $uri")

            // Cache result to avoid future checks
            contentDownloadCache[uri] = isCached
            isCached

        } catch (e: Exception) {
            Log.e(TAG, "❌ Quick cache check failed: ${e.message}")
            false
        }
    }

    private fun checkCacheWithTimeout(uri: String): Boolean {
        return try {
            val context = OfflineVideoRegistry.getAppContext()
            if (context == null) {
                Log.w(TAG, "⚠️ App context is null, cannot check cache")
                return false
            }

            if (!OfflineVideoRegistry.isInitialized()) {
                Log.w(TAG, "⚠️ Registry not initialized")
                return false
            }

            // ✅ Use timeout to prevent ANR
            val future = cacheCheckExecutor.submit<Boolean> {
                try {
                    OfflineDataSourceProvider.getInstance(context).isContentCached(uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Cache check failed in executor: ${e.message}")
                    false
                }
            }

            future.get(1000L, TimeUnit.MILLISECONDS)

        } catch (e: TimeoutException) {
            Log.w(TAG, "⏰ Cache check timed out for: $uri")
            false
        } catch (e: Exception) {
            Log.w(TAG, "❌ Cache check failed: ${e.message}")
            false
        }
    }

    override fun overrideMediaItemBuilder(
        source: Source,
        mediaItemBuilder: MediaItem.Builder
    ): MediaItem.Builder? {
        return try {
            val uri = source.uri?.toString()
            if (uri == null) return null

            Log.d(TAG, "🔍 Checking MediaItem override for: $uri")
            Log.d(TAG, "🎯 MediaItem playback mode: $playbackMode")

            // ✅ FIXED: Use same playback mode logic as DataSource override
            when (playbackMode) {
                PlaybackMode.ONLINE -> {
                    // ✅ ONLINE: Never override MediaItem (fast)
                    Log.d(TAG, "🌐 ONLINE mode - using default MediaItem for: $uri")
                    return null
                }

                PlaybackMode.OFFLINE -> {
                    // ✅ OFFLINE: Only override if content is cached
                    val isCached = isContentCached(uri)
                    if (isCached) {
                        Log.d(TAG, "📱 OFFLINE mode - using cached MediaItem for: $uri")

                        val streamKeys = getStreamKeys(uri)
                        if (streamKeys.isNotEmpty()) {
                            Log.d(TAG, "🔑 Setting ${streamKeys.size} stream keys")
                            mediaItemBuilder.setStreamKeys(streamKeys)
                        }
                        return mediaItemBuilder
                    } else {
                        Log.w(TAG, "⚠️ OFFLINE mode - content not cached, using default: $uri")
                        return null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in overrideMediaItemBuilder: ${e.message}")
            null
        }
    }

    override fun shouldDisableCache(source: Source): Boolean {
        return false
    }

    override fun overrideMediaSourceFactory(
        source: Source,
        mediaSourceFactory: MediaSource.Factory,
        mediaDataSourceFactory: DataSource.Factory
    ): MediaSource.Factory? {
        return null
    }

    // ✅ FIXED: Use SAME logic as OfflineDataSourceProvider
    fun isContentDownloaded(uri: String): Boolean {
        return try {
            // Periodic cache cleanup
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCacheCleanup > cacheCleanupInterval) {
                contentDownloadCache.clear()
                lastCacheCleanup = currentTime
                Log.d(TAG, "🧹 Cleaned up content cache")
            }

            // Check cache first
            contentDownloadCache[uri]?.let {
                Log.d(TAG, "📋 Cache hit for $uri: $it")
                return it
            }

            // ✅ CRITICAL FIX: Force cache refresh by calling data source provider directly
            val isDownloaded = OfflineVideoRegistry.getAppContext()?.let { context ->
                Log.d(TAG, "🔄 Force checking cache via DataSourceProvider for: $uri")
                OfflineDataSourceProvider.getInstance(context).isContentCached(uri)
            } ?: false

            // ✅ Update cache with correct result
            contentDownloadCache[uri] = isDownloaded
            Log.d(TAG, "💾 Content $uri is downloaded: $isDownloaded (updated cache)")

            isDownloaded
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking if content is downloaded: ${e.message}")
            false
        }
    }

    private fun extractHeadersFromSource(source: Source): Map<String, String>? {
        return try {
            val headersMap = mutableMapOf<String, String>()

            try {
                // ✅ More defensive reflection
                val headersField = source.javaClass.getDeclaredField("headers")
                if (headersField != null) {
                    headersField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val headers = headersField.get(source) as? Map<String, String>
                    headers?.let {
                        if (it.isNotEmpty()) {
                            headersMap.putAll(it)
                            Log.d(TAG, "📋 Extracted ${it.size} headers from source")
                        }
                    }
                }
            } catch (e: NoSuchFieldException) {
                Log.d(TAG, "Headers field not found in source (normal for some sources)")
            } catch (e: SecurityException) {
                Log.d(TAG, "Security exception accessing headers (normal)")
            } catch (e: IllegalAccessException) {
                Log.d(TAG, "Cannot access headers field (normal)")
            } catch (e: ClassCastException) {
                Log.d(TAG, "Headers field is not Map<String, String> type")
            }

            headersMap.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error extracting headers: ${e.message}")
            null
        }
    }

    private fun getStreamKeys(uri: String): List<StreamKey> {
        return try {
            val context = OfflineVideoRegistry.getAppContext() ?: return emptyList()
            val downloadManager = OfflineVideoDownloaderModule.getDownloadManager() ?: return emptyList()

            // ✅ Use timeout to prevent ANR
            val future = cacheCheckExecutor.submit<List<StreamKey>> {
                val downloadsCursor = downloadManager.downloadIndex.getDownloads()
                var streamKeys = emptyList<StreamKey>()

                try {
                    while (downloadsCursor.moveToNext()) {
                        val download = downloadsCursor.download
                        if (download.request.uri.toString() == uri &&
                            download.state == Download.STATE_COMPLETED) {
                            streamKeys = download.request.streamKeys
                            break
                        }
                    }
                } finally {
                    downloadsCursor.close()
                }

                streamKeys
            }

            // ✅ Wait max 500ms for stream keys
            future.get(500L, TimeUnit.MILLISECONDS)

        } catch (e: TimeoutException) {
            Log.w(TAG, "⏰ Stream keys retrieval timed out for: $uri")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "❌ Stream keys retrieval failed: ${e.message}")
            emptyList()
        }
    }
}
