package com.offlinevideodownloader

import androidx.media3.common.util.UnstableApi
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

@UnstableApi
class OfflineVideoDownloaderPackage : ReactPackage {

    private var downloaderModule: OfflineVideoDownloaderModule? = null

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        downloaderModule = OfflineVideoDownloaderModule(reactContext)
        return listOf(downloaderModule!!)
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }

    fun cleanup() {
        downloaderModule?.cleanup()
    }
}
