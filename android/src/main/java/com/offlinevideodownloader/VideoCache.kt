package com.offlinevideodownloader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

@UnstableApi
object VideoCache {
    private var instance: SimpleCache? = null
    private val lock = Any()
    private const val TAG = "VideoCache"

    // ✅ 15GB cache for movies
    private const val CACHE_SIZE = 15L * 1024 * 1024 * 1024 // 15 GB

    // ✅ SIMPLE: Everything goes in downloads directory
    private const val DOWNLOADS_DIR_NAME = "downloads"

    // ✅ SIMPLE: Back to original data class
    data class StorageStats(
        val totalSize: Long,
        val maxSize: Long,
        val availableSpace: Long,
        val usedPercentage: Int,
        val path: String,
        val isProtected: Boolean
    )

    fun getInstance(context: Context): SimpleCache {
        synchronized(lock) {
            if (instance == null) {
                // ✅ SIMPLE: Single directory for everything
                val downloadsDir = File(context.filesDir, DOWNLOADS_DIR_NAME)

                Log.d(TAG, "📂 Creating unified downloads directory: ${downloadsDir.absolutePath}")
                Log.d(TAG, "📦 Video segments + metadata will be stored together")
                Log.d(TAG, "🛡️ Directory is PROTECTED (data directory)")

                // ✅ Custom evictor that NEVER auto-deletes content
                val cacheEvictor = PersistentCacheEvictor()
                val databaseProvider = StandaloneDatabaseProvider(context)

                // Ensure directory exists
                if (!downloadsDir.exists()) {
                    val created = downloadsDir.mkdirs()
                    Log.d(TAG, "📁 Downloads directory created: $created")
                }

                instance = SimpleCache(downloadsDir, cacheEvictor, databaseProvider)
                Log.d(TAG, "✅ VideoCache initialized - unified storage in downloads directory")
            }
            return instance!!
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                instance?.let { cache ->
                    Log.d(TAG, "🔄 Releasing VideoCache...")
                    cache.release()
                    instance = null
                    Log.d(TAG, "✅ VideoCache released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error releasing VideoCache: ${e.message}", e)
            }
        }
    }

    fun isInitialized(): Boolean {
        synchronized(lock) {
            return instance != null
        }
    }

    // ✅ Get current cache usage
    fun getCacheSize(context: Context): Long {
        return try {
            getInstance(context).cacheSpace
        } catch (e: Exception) {
            Log.w(TAG, "Error getting cache size: ${e.message}")
            0L
        }
    }

    // ✅ Get available cache space
    fun getAvailableCacheSpace(context: Context): Long {
        return CACHE_SIZE - getCacheSize(context)
    }

    // ✅ FIXED: Surgical removal - only remove cache keys for specific download
    fun removeDownload(context: Context, downloadId: String) {
        try {
            Log.d(TAG, "🗑️ Surgically removing download cache: $downloadId")
            val cache = getInstance(context)

            // ✅ CRITICAL: Get all cache keys and filter for this download only
            val cacheKeys = cache.keys.toList() // Create immutable copy to avoid concurrent modification
            var removedCount = 0
            val totalKeys = cacheKeys.size

            Log.d(TAG, "🔍 Scanning $totalKeys cache keys for download: $downloadId")

            for (key in cacheKeys) {
                try {
                    // ✅ CRITICAL: Only remove if key is related to this download
                    if (isKeyRelatedToDownload(key, downloadId)) {
                        cache.removeResource(key)
                        removedCount++
                        Log.d(TAG, "🗑️ Removed cache key: $key")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove cache key: $key - ${e.message}")
                }
            }

            Log.d(TAG, "✅ Surgically removed $removedCount/$totalKeys cache entries for: $downloadId")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing download $downloadId: ${e.message}", e)
        }
    }

    // ✅ NEW: Check if specific download is cached
    fun isDownloadCached(context: Context, downloadId: String): Boolean {
        return try {
            Log.d(TAG, "🔍 Checking if download is cached: $downloadId")
            val cache = getInstance(context)
            val cacheKeys = cache.keys.toList()

            val relatedKeys = cacheKeys.filter { key ->
                isKeyRelatedToDownload(key, downloadId)
            }

            val isCached = relatedKeys.isNotEmpty()
            Log.d(TAG, "📊 Download $downloadId cached: $isCached (found ${relatedKeys.size} cache keys)")

            isCached
        } catch (e: Exception) {
            Log.w(TAG, "Error checking cache for $downloadId: ${e.message}")
            false
        }
    }

    // ✅ NEW: Determine if a cache key is related to a specific download
    private fun isKeyRelatedToDownload(cacheKey: String, downloadId: String): Boolean {
        try {
            // Direct match
            if (cacheKey == downloadId) {
                return true
            }

            // Check if cache key contains the download ID
            if (cacheKey.contains(downloadId)) {
                return true
            }

            // Extract content ID from both and compare
            val keyContentId = extractContentId(cacheKey)
            val downloadContentId = extractContentId(downloadId)

            if (keyContentId.isNotEmpty() && downloadContentId.isNotEmpty() && keyContentId == downloadContentId) {
                return true
            }

            return false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking key relation: ${e.message}")
            return false
        }
    }

    // ✅ NEW: Extract content ID from URI or download ID
    private fun extractContentId(identifier: String): String {
        return try {
            // For your URLs like: etvwin-s3.akamaized.net/6782084dc7036a0cfa096af2/HD_playlist.m3u8
            val regex = Regex("([a-f0-9]{24})")
            val match = regex.find(identifier)
            val contentId = match?.value ?: ""
            Log.v(TAG, "🔍 Extracted content ID '$contentId' from: $identifier")
            contentId
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract content ID from: $identifier")
            ""
        }
    }

    // ✅ NEW: Get all cache keys for debugging
    fun getAllCacheKeys(context: Context): List<String> {
        return try {
            getInstance(context).keys.toList()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting cache keys: ${e.message}")
            emptyList()
        }
    }

    // ✅ SIMPLE: Return downloads directory path
    fun getCacheDirectoryPath(context: Context): String {
        return File(context.filesDir, DOWNLOADS_DIR_NAME).absolutePath
    }

    // ✅ SAME: Downloads and cache are in same place
    fun getDownloadsDirectoryPath(context: Context): String {
        return getCacheDirectoryPath(context) // Same directory!
    }

    // ✅ Get storage statistics
    fun getStorageStats(context: Context): StorageStats {
        return try {
            val downloadsDir = File(context.filesDir, DOWNLOADS_DIR_NAME)
            val totalSize = if (downloadsDir.exists()) getFolderSize(downloadsDir) else 0L
            val availableSpace = CACHE_SIZE - totalSize
            val usedPercentage = (totalSize * 100 / CACHE_SIZE).toInt()

            StorageStats(
                totalSize = totalSize,
                maxSize = CACHE_SIZE,
                availableSpace = availableSpace,
                usedPercentage = usedPercentage,
                path = downloadsDir.absolutePath,
                isProtected = true // Data directory is protected
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage stats: ${e.message}")
            StorageStats(0, CACHE_SIZE, CACHE_SIZE, 0, "unknown", true)
        }
    }

    // ✅ Calculate folder size
    private fun getFolderSize(folder: File): Long {
        return try {
            if (!folder.exists()) return 0L

            var size = 0L
            folder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
            size
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating folder size: ${e.message}")
            0L
        }
    }
}

/**
 * ✅ Custom Cache Evictor - same as before
 */
@UnstableApi
class PersistentCacheEvictor : CacheEvictor {

    companion object {
        private const val TAG = "PersistentCacheEvictor"
    }

    override fun onCacheInitialized() {
        Log.d(TAG, "🔄 Cache initialized - persistent mode (no auto-deletion)")
    }

    override fun onStartFile(cache: androidx.media3.datasource.cache.Cache, key: String, position: Long, length: Long) {
        Log.d(TAG, "📁 Starting file: $key (${formatBytes(length)})")
    }

    override fun onSpanAdded(cache: androidx.media3.datasource.cache.Cache, span: CacheSpan) {
        Log.d(TAG, "➕ Content cached: ${span.key} (${formatBytes(span.length)})")
    }

    override fun onSpanRemoved(cache: androidx.media3.datasource.cache.Cache, span: CacheSpan) {
        Log.d(TAG, "➖ Content removed: ${span.key} (${formatBytes(span.length)})")
    }

    override fun onSpanTouched(cache: androidx.media3.datasource.cache.Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        Log.v(TAG, "👆 Content accessed: ${newSpan.key}")
    }

    override fun requiresCacheSpanTouches(): Boolean {
        return false
    }

    // ✅ Helper method to format bytes
    @SuppressLint("DefaultLocale")
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val k = 1024
        val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (ln(bytes.toDouble()) / ln(k.toDouble())).toInt()
        return String.format("%.1f %s", bytes / k.toDouble().pow(i.toDouble()), sizes[i])
    }
}
