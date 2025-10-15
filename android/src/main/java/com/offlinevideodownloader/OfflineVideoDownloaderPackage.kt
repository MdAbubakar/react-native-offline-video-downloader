package com.offlinevideodownloader

import androidx.media3.common.util.UnstableApi
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.facebook.react.bridge.LifecycleEventListener
import android.util.Log

@UnstableApi
class OfflineVideoDownloaderPackage : ReactPackage {

    private var downloaderModule: OfflineVideoDownloaderModule? = null

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        if (!OfflineVideoRegistry.isInitialized()) {
            OfflineVideoRegistry.setAppContext(reactContext.applicationContext)
        }

        downloaderModule = OfflineVideoDownloaderModule(reactContext)

        // Register lifecycle listener for proper initialization
        reactContext.addLifecycleEventListener(object : LifecycleEventListener {
            override fun onHostResume() {
                initializePluginSafely()
            }

            override fun onHostPause() {
            }

            override fun onHostDestroy() {
                cleanup()
            }
        })

        return listOf(downloaderModule!!)
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }

    private fun initializePluginSafely() {
        try {
            if (OfflineVideoRegistry.isInitialized()) {
                OfflineVideoPlugin.getInstance()
            }
        } catch (e: Exception) {
            Log.e("OfflineVideoPackage", "Plugin init failed: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            downloaderModule?.cleanup()
            OfflineVideoPlugin.cleanup()
            OfflineVideoRegistry.cleanup()
        } catch (e: Exception) {
            Log.e("OfflineVideoPackage", "Error during cleanup: ${e.message}")
        }
    }
}
