package com.offlinevideodownloader

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.Log
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
object OfflineVideoRegistry {
    private const val TAG = "OfflineVideoRegistry"
    private var appContext: Context? = null

    private val registeredPlayers = ConcurrentHashMap.newKeySet<ExoPlayer>()
    private var isInitialized = false

    fun setAppContext(context: Context) {
        appContext = context.applicationContext
        isInitialized = true
    }

    fun registerPlayer(player: ExoPlayer) {
        registeredPlayers.add(player)
    }

    fun unregisterPlayer(player: ExoPlayer) {
        registeredPlayers.remove(player)
    }

    fun getAppContext(): Context? = appContext

    fun isInitialized(): Boolean = isInitialized && appContext != null

    fun getRegisteredPlayersCount(): Int = registeredPlayers.size

    fun createCacheAwareDataSourceFactory(headers: Map<String, String>? = null): DataSource.Factory? {
        return appContext?.let { context ->
            OfflineDataSourceProvider.getInstance(context).createCacheAwareDataSourceFactory(headers)
        }
    }

    fun cleanup() {
        registeredPlayers.clear()
        appContext = null
        isInitialized = false
        Log.d(TAG, "Registry cleaned up")
    }
}
