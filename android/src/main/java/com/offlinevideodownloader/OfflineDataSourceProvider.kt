package com.offlinevideodownloader

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.common.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import java.lang.ref.WeakReference
import kotlin.math.ln
import kotlin.math.pow

@UnstableApi
class OfflineDataSourceProvider(private val context: Context) {

    companion object {
        private var instanceRef: WeakReference<OfflineDataSourceProvider>? = null
        private const val TAG = "OfflineDataSource"

        fun getInstance(context: Context): OfflineDataSourceProvider {
            instanceRef?.get()?.let { existingInstance ->
                return existingInstance
            }

            val newInstance = OfflineDataSourceProvider(context.applicationContext)
            instanceRef = WeakReference(newInstance)
            return newInstance
        }

        fun cleanup() {
            instanceRef?.clear()
            instanceRef = null
        }
    }

    /**
     * ✅ FIXED: Cache-aware DataSource factory
     */
    fun createCacheAwareDataSourceFactory(headers: Map<String, String>? = null): DataSource.Factory {
        val cache = VideoCache.getInstance(context)

        Log.d(TAG, "🎯 Creating cache-aware DataSource factory")
        Log.d(TAG, "🎯 Cache instance: ${cache.hashCode()}")
        Log.d(TAG, "🎯 Cache size: ${formatBytes(cache.cacheSpace)}")

        // Create HTTP data source factory
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setUserAgent("ExoPlayer-OfflineDownloader")

        // Apply headers if provided
        headers?.let {
            Log.d(TAG, "📋 Applying ${it.size} custom headers")
            httpDataSourceFactory.setDefaultRequestProperties(it.toMutableMap())
        }

        // Create default data source factory
        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // ✅ FIXED: Allow cache writes and proper cache handling
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR) // ✅ Removed read-only restriction
    }

    /**
     * ✅ FIXED: Proper cache detection for HLS downloads
     */
    fun isContentCached(uri: String): Boolean {
        var downloadsCursor: DownloadCursor? = null
        return try {
            Log.d(TAG, "🔍 Checking if content is cached: $uri")

            // ✅ Check download manager for completed downloads
            val downloadManager = OfflineVideoDownloaderModule.getDownloadManager()
            if (downloadManager == null) {
                Log.d(TAG, "❌ Download manager is null")
                return false
            }

            downloadsCursor = downloadManager.downloadIndex.getDownloads()

            var foundMatch = false
            var checkedCount = 0
            val maxChecks = 50
            val startTime = System.currentTimeMillis()
            val maxTime = 3000L

            try {
                while (downloadsCursor.moveToNext() && checkedCount < maxChecks &&
                    (System.currentTimeMillis() - startTime) < maxTime
                ) {
                    checkedCount++
                    try {
                        val download = downloadsCursor.download

                        if (download.state == Download.STATE_COMPLETED) {
                            val downloadUri = download.request.uri.toString()

                            Log.d(TAG, "🔍 Comparing:")
                            Log.d(TAG, "📥 Download URI: $downloadUri")
                            Log.d(TAG, "🎬 Playback URI: $uri")

                            // ✅ Direct match or content ID match
                            if (downloadUri == uri || extractContentId(downloadUri) == extractContentId(
                                    uri
                                )
                            ) {
                                Log.d(TAG, "✅ CACHE HIT! Content is downloaded")
                                foundMatch = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing download $checkedCount: ${e.message}")
                        continue
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error iterating downloads: ${e.message}")
            }
            foundMatch
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking cache for $uri: ${e.message}")
            false
        }  finally {
            // ✅ CRITICAL: Safe cursor cleanup
            try {
                downloadsCursor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Warning: Error closing cursor: ${e.message}")
            }
        }
    }

    /**
     * ✅ Helper method for content ID extraction
     */
    private fun extractContentId(url: String): String {
        return try {
            // For your URLs like: etvwin-s3.akamaized.net/6782084dc7036a0cfa096af2/HD_playlist.m3u8
            val regex = Regex("([a-f0-9]{24})")
            val match = regex.find(url)
            val contentId = match?.value ?: ""
            Log.d(TAG, "🔍 Extracted content ID '$contentId' from: $url")
            contentId
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract content ID from: $url")
            ""
        }
    }

    /**
     * ✅ Helper to format bytes
     */
    @SuppressLint("DefaultLocale")
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val k = 1024
        val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (ln(bytes.toDouble()) / ln(k.toDouble())).toInt()
        return String.format("%.1f %s", bytes / k.toDouble().pow(i.toDouble()), sizes[i])
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): Map<String, Any> {
        return try {
            val cache = VideoCache.getInstance(context)
            mapOf(
                "cacheSize" to cache.cacheSpace,
                "isInitialized" to VideoCache.isInitialized(),
                "cacheDirectory" to VideoCache.getCacheDirectoryPath(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cache stats: ${e.message}")
            mapOf("error" to e.message.orEmpty())
        }
    }
}

