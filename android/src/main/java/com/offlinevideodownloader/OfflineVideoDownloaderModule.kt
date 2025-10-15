package com.offlinevideodownloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.*
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@UnstableApi
class OfflineVideoDownloaderModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val progressHandler = Handler(Looper.getMainLooper())
    private val activeDownloads = mutableMapOf<String, Runnable>()

    private val cachedSizes = mutableMapOf<String, Long>()

    private val storedTrackIdentifiers = mutableMapOf<String, Map<Int, TrackIdentifier>>()

    data class PlaylistUrls(
        val videoUrl: String,
        val audioUrl: String?
    )

    private val segmentSamplingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    enum class StreamType {
        SEPARATE_AUDIO_VIDEO,
        MUXED_VIDEO_AUDIO,
        UNKNOWN
    }

    companion object {
        private const val MODULE_NAME = "OfflineVideoDownloader"
        private var _downloadManager: DownloadManager? = null

        @JvmStatic
        fun getDownloadManager(): DownloadManager? = _downloadManager

        internal fun setDownloadManager(manager: DownloadManager?) {
            _downloadManager = manager
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        initializeDownloadManager()
    }

    override fun getName(): String = MODULE_NAME

    // Track identifier data class
    data class TrackIdentifier(
        val periodIndex: Int,
        val groupIndex: Int,
        val trackIndex: Int,
        val format: Format,
        val trackGroup: TrackGroup,
        val actualSizeBytes: Long
    )

    data class TrackInfo(
        val trackGroup: TrackGroup,
        val trackIndex: Int,
        val periodIndex: Int,
        val groupIndex: Int,
        val format: Format,
        val actualSizeBytes: Long,
        val width: Int = format.width,
        val height: Int = format.height,
        val bitrate: Int = format.bitrate,
        val codecs: String? = format.codecs,
        val mimeType: String? = format.sampleMimeType
    ) {
        // Helper method for logging
        override fun toString(): String {
            return "TrackInfo(${width}x${height}, ${bitrate}bps, $mimeType, ${formatBytes(actualSizeBytes)})"
        }

        // Helper to format bytes
        @SuppressLint("DefaultLocale")
        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val k = 1024
            val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
            val i = (ln(bytes.toDouble()) / ln(k.toDouble())).toInt()
            return String.format("%.1f %s", bytes / k.toDouble().pow(i.toDouble()), sizes[i])
        }
    }

    private fun initializeDownloadManager() {
        val context = reactApplicationContext
        val downloadCache = VideoCache.getInstance(context)
        val databaseProvider = StandaloneDatabaseProvider(context)
        val downloadIndex = DefaultDownloadIndex(databaseProvider)

        val downloadDirectory = File(VideoCache.getDownloadsDirectoryPath(context))

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setUserAgent("ExoPlayer/OfflineDownloader")

        val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val downloadExecutor: Executor = Executors.newFixedThreadPool(3)
        val downloaderFactory = DefaultDownloaderFactory(cacheDataSourceFactory, downloadExecutor)

        val downloadManager = DownloadManager(context, downloadIndex, downloaderFactory).apply {
            requirements = Requirements(Requirements.NETWORK)
            maxParallelDownloads = 3
            addListener(DownloadManagerListener())
        }

        setDownloadManager(downloadManager)
    }

    fun cleanup() {
        try {
            activeDownloads.keys.forEach { downloadId ->
                stopProgressReporting(downloadId)
            }
            storedTrackIdentifiers.clear()
            segmentSamplingScope.cancel()
            _downloadManager?.release()
            setDownloadManager(null)
            VideoCache.release()
        } catch (e: Exception) {
            Log.e(MODULE_NAME, "Error during cleanup: ${e.message}")
        }
    }

    @ReactMethod
    fun getAvailableTracks(masterUrl: String, options: ReadableMap?, promise: Promise) {
        mainHandler.post {
            try {
                cachedSizes.clear()

                val context = reactApplicationContext
                val headers = extractHeadersFromOptions(options)
                val mediaItem = MediaItem.fromUri(masterUrl)

                val dataSourceFactory = createHttpOnlyDataSourceFactory(context, headers)

                @Suppress("DEPRECATION")
                val downloadHelper = DownloadHelper.forMediaItem(
                    context,
                    mediaItem,
                    DefaultRenderersFactory(context),
                    dataSourceFactory
                )

                downloadHelper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        try {
                            // Detect stream type first
                            val streamType = detectStreamType(helper)

                            val allowedQualities = setOf(480, 720, 1080)

                            val videoTrackMap = mutableMapOf<Int, WritableMap>()
                            val videoBitrateMap = mutableMapOf<Int, Int>()
                            val videoTrackIdentifiers = mutableMapOf<Int, TrackIdentifier>()
                            val audioTrackMap = mutableMapOf<String, WritableMap>()
                            val audioPriorityMap = mutableMapOf<String, Int>()
                            var totalDurationSec = 0.0

                            for (periodIndex in 0 until helper.periodCount) {
                                val mappedTrackInfo = helper.getMappedTrackInfo(periodIndex)

                                if (periodIndex == 0) {
                                    val manifest = helper.manifest as? HlsManifest
                                    totalDurationSec = manifest?.mediaPlaylist?.segments?.sumOf { it.durationUs / 1_000_000.0 } ?: 0.0
                                }

                                for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                                    val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                                    if (trackGroups.length > 0) {
                                        val rendererType = mappedTrackInfo.getRendererType(rendererIndex)

                                        if (rendererType == C.TRACK_TYPE_VIDEO) {
                                            for (groupIndex in 0 until trackGroups.length) {
                                                val group = trackGroups.get(groupIndex)
                                                for (trackIndex in 0 until group.length) {
                                                    val format = group.getFormat(trackIndex)

                                                    if (format.height in allowedQualities && format.bitrate > 0) {
                                                        // I-FRAME filtering
                                                        val isIFrameStream = format.roleFlags and C.ROLE_FLAG_TRICK_PLAY != 0 ||
                                                                format.containerMimeType?.contains("image") == true

                                                        val expectedMinBitrate = getMinExpectedBitrate(format.height)
                                                        val isProbablyIFrame = format.bitrate < expectedMinBitrate

                                                        if (!isIFrameStream && !isProbablyIFrame) {
                                                            val currentBitrate = format.bitrate
                                                            val existingBitrate = videoBitrateMap[format.height]

                                                            if (existingBitrate == null || currentBitrate > existingBitrate) {
                                                                videoBitrateMap[format.height] = currentBitrate

                                                                val estimatedSizeBytes = if (totalDurationSec > 0) {
                                                                    runBlocking {
                                                                        calculateAccurateStreamSize(
                                                                            helper,
                                                                            format,
                                                                            totalDurationSec,
                                                                            streamType,
                                                                            headers
                                                                        )
                                                                    }
                                                                } else 0L

                                                                videoTrackIdentifiers[format.height] = TrackIdentifier(
                                                                    periodIndex = periodIndex,
                                                                    groupIndex = groupIndex,
                                                                    trackIndex = trackIndex,
                                                                    format = format,
                                                                    trackGroup = group,
                                                                    actualSizeBytes = estimatedSizeBytes
                                                                )

                                                                val trackData = Arguments.createMap().apply {
                                                                    putInt("height", format.height)
                                                                    putInt("width", format.width)
                                                                    putInt("bitrate", format.bitrate)
                                                                    putDouble("size", estimatedSizeBytes.toDouble())
                                                                    putString("formattedSize", formatBytes(estimatedSizeBytes))
                                                                    putString("trackId", "${periodIndex}_${groupIndex}_${trackIndex}")
                                                                    putString("quality", "${format.height}p")
                                                                    putString("streamType", streamType.name)
                                                                }
                                                                videoTrackMap[format.height] = trackData

                                                                val sizeType = if (streamType == StreamType.SEPARATE_AUDIO_VIDEO) "video only" else "video+audio"
                                                            }
                                                        } else {
                                                            Log.d(MODULE_NAME, "Filtered I-FRAME: ${format.height}p, ${format.bitrate} bps")
                                                        }
                                                    } else if (format.height !in allowedQualities) {
                                                        Log.d(MODULE_NAME, "Skipped ${format.height}p (not in allowed qualities)")
                                                    }
                                                }
                                            }
                                        }

                                        // Audio processing (only for separate audio streams)
                                        if (rendererType == C.TRACK_TYPE_AUDIO && streamType == StreamType.SEPARATE_AUDIO_VIDEO && periodIndex == 0) {
                                            for (groupIndex in 0 until trackGroups.length) {
                                                val group = trackGroups.get(groupIndex)
                                                for (trackIndex in 0 until group.length) {
                                                    val format = group.getFormat(trackIndex)

                                                    val language = format.language ?: "unknown"
                                                    val audioBitrate = if (format.bitrate > 0) format.bitrate else 128000

                                                    val estimatedSizeBytes = if (totalDurationSec > 0) {
                                                        calculateAudioOnlySize(audioBitrate, totalDurationSec)
                                                    } else 0L

                                                    val isDolbyAtmos = format.sampleMimeType?.contains("eac3-joc") == true
                                                    val isDolbyDigital = format.sampleMimeType?.contains("eac3") == true || format.sampleMimeType?.contains("ac-3") == true
                                                    val audioType = when {
                                                        isDolbyAtmos -> "dolby_atmos"
                                                        isDolbyDigital -> "dolby_digital"
                                                        format.channelCount > 2 -> "surround"
                                                        else -> "stereo"
                                                    }

                                                    val audioTrackData = Arguments.createMap().apply {
                                                        putString("language", language)
                                                        putString("label", format.label ?: "stream_$groupIndex")
                                                        putInt("channelCount", format.channelCount)
                                                        putString("audioType", audioType)
                                                        putBoolean("isDolbyAtmos", isDolbyAtmos)
                                                        putDouble("size", estimatedSizeBytes.toDouble())
                                                        putString("formattedSize", formatBytes(estimatedSizeBytes))
                                                    }

                                                    val currentPriority = getAudioPriority(audioType)
                                                    val existingPriority = audioPriorityMap[language] ?: 0

                                                    if (currentPriority > existingPriority) {
                                                        audioPriorityMap[language] = currentPriority
                                                        audioTrackMap[language] = audioTrackData
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            storedTrackIdentifiers[masterUrl] = videoTrackIdentifiers

                            // Sort video tracks by quality (highest first) and ensure only allowed qualities
                            val videoTracks = Arguments.createArray()
                            val sortedVideoQualities = listOf(1080, 720, 480)

                            sortedVideoQualities.forEach { height ->
                                videoTrackMap[height]?.let { trackData ->
                                    videoTracks.pushMap(trackData)
                                }
                            }

                            val audioTracks = Arguments.createArray()
                            audioTrackMap.values.forEach { audioTracks.pushMap(it) }

                            promise.resolve(Arguments.createMap().apply {
                                putArray("videoTracks", videoTracks)
                                putArray("audioTracks", audioTracks)
                                putDouble("duration", totalDurationSec)
                                putString("streamType", streamType.name)
                                putArray("allowedQualities", Arguments.createArray().apply {
                                    allowedQualities.forEach { pushInt(it) }
                                })
                                putInt("availableQualityCount", videoTrackMap.size)
                            })

                        } catch (e: Exception) {
                            promise.reject("PROCESSING_ERROR", "Failed to process tracks: ${e.message}")
                        } finally {
                            helper.release()
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: java.io.IOException) {
                        promise.reject("TRACK_FETCH_ERROR", "Could not get tracks: ${e.message}")
                        helper.release()
                    }
                })
            } catch (e: Exception) {
                promise.reject("SETUP_ERROR", "Failed to set up track helper: ${e.message}")
            }
        }
    }


    @ReactMethod
    fun downloadStream(
        masterUrl: String,
        downloadId: String,
        selectedHeight: Int,
        selectedWidth: Int,
        preferDolbyAtmos: Boolean,
        options: ReadableMap?,
        promise: Promise
    ) {
        mainHandler.post {
            try {
                checkAndRemoveExistingDownload(downloadId)

                val context = reactApplicationContext
                val headers = extractHeadersFromOptions(options)
                val mediaItem = MediaItem.fromUri(masterUrl)

                val dataSourceFactory = createCacheAwareDataSourceFactory(context, headers)

                @Suppress("DEPRECATION")
                val downloadHelper = DownloadHelper.forMediaItem(
                    context,
                    mediaItem,
                    DefaultRenderersFactory(context),
                    dataSourceFactory
                )

                downloadHelper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        try {
                            val streamType = detectStreamType(helper)
                            val storedTracks = storedTrackIdentifiers[masterUrl]
                            val targetTrack = storedTracks?.get(selectedHeight)

                            if (targetTrack != null) {
                                for (periodIndex in 0 until helper.periodCount) {
                                    helper.clearTrackSelections(periodIndex)
                                }

                                when (streamType) {
                                    StreamType.SEPARATE_AUDIO_VIDEO -> {
                                        selectSeparateAudioVideoTracks(
                                            helper,
                                            targetTrack,
                                            preferDolbyAtmos,
                                            masterUrl
                                        )
                                    }

                                    StreamType.MUXED_VIDEO_AUDIO -> {
                                        selectMuxedVideoTrack(helper, targetTrack, preferDolbyAtmos)
                                    }

                                    StreamType.UNKNOWN -> {
                                        selectFallbackTracks(helper, targetTrack, preferDolbyAtmos)
                                    }
                                }

                            } else {
                                selectFallbackByResolution(helper, selectedWidth, selectedHeight, preferDolbyAtmos)
                            }

                            val downloadRequest = helper.getDownloadRequest(downloadId, null)

                            validateDownloadRequest(downloadRequest, streamType)

                            DownloadService.sendAddDownload(
                                context, VideoDownloadService::class.java, downloadRequest, false
                            )

                            promise.resolve(createDownloadResponse(downloadRequest, streamType, targetTrack))

                        } catch (e: Exception) {
                            promise.reject("SELECTION_ERROR", "Failed to select tracks: ${e.message}")
                        } finally {
                            helper.release()
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: java.io.IOException) {
                        promise.reject("PREPARE_ERROR", "Failed to prepare download helper: ${e.message}")
                        helper.release()
                    }
                })
            } catch (e: Exception) {
                promise.reject("DOWNLOAD_SETUP_ERROR", "Failed to set up download: ${e.message}")
            }
        }
    }

    private fun checkAndRemoveExistingDownload(downloadId: String) {
        try {
            _downloadManager?.let { manager ->
                val existingDownload = manager.downloadIndex.getDownload(downloadId)
                if (existingDownload != null) {
                    DownloadService.sendRemoveDownload(
                        reactContext,
                        VideoDownloadService::class.java,
                        downloadId,
                        false
                    )
                    Thread.sleep(300)
                }
            }

        } catch (e: Exception) {
            Log.w(MODULE_NAME, "Warning checking existing download: ${e.message}")
        }
    }

    private fun selectSeparateAudioVideoTracks(
        helper: DownloadHelper,
        targetVideoTrack: TrackIdentifier,
        preferDolbyAtmos: Boolean,
        masterUrl: String
    ) {
        try {
            val parametersBuilder = DefaultTrackSelector.Parameters.Builder()
                .setMaxVideoSize(targetVideoTrack.format.width, targetVideoTrack.format.height)
                .setMinVideoSize(targetVideoTrack.format.width, targetVideoTrack.format.height)
                .setMaxVideoBitrate(targetVideoTrack.format.bitrate + 200000)
                .setMinVideoBitrate(maxOf(targetVideoTrack.format.bitrate - 200000, 0))

            if (preferDolbyAtmos) {
                parametersBuilder
                    .setPreferredAudioMimeType("audio/eac3-joc")
                    .setMaxAudioChannelCount(16)
            } else {
                parametersBuilder
                    .setPreferredAudioMimeType("audio/mp4a-latm")
                    .setMaxAudioChannelCount(2)
            }

            val parameters = parametersBuilder
                .setSelectUndeterminedTextLanguage(false)
                .setForceHighestSupportedBitrate(false)
                .build()

            for (periodIndex in 0 until helper.periodCount) {
                helper.clearTrackSelections(periodIndex)
                helper.addTrackSelection(periodIndex, parameters)
            }

        } catch (e: Exception) {
            throw e
        }
    }

    private fun selectMuxedVideoTrack(
        helper: DownloadHelper,
        targetTrack: TrackIdentifier,
        preferDolbyAtmos: Boolean
    ) {
        val trackSelectionOverride = TrackSelectionOverride(
            targetTrack.trackGroup,
            listOf(targetTrack.trackIndex)
        )

        val parametersBuilder = DefaultTrackSelector.Parameters.Builder()
            .addOverride(trackSelectionOverride)

        if (preferDolbyAtmos) {
            parametersBuilder
                .setPreferredAudioMimeType("audio/eac3-joc")
                .setMaxAudioChannelCount(16)
        } else {
            parametersBuilder.setMaxAudioChannelCount(2)
        }

        val parameters = parametersBuilder.build()
        helper.addTrackSelection(targetTrack.periodIndex, parameters)

    }

    private fun selectFallbackTracks(
        helper: DownloadHelper,
        targetTrack: TrackIdentifier,
        preferDolbyAtmos: Boolean
    ) {
        val trackSelectionOverride = TrackSelectionOverride(
            targetTrack.trackGroup,
            listOf(targetTrack.trackIndex)
        )

        val parametersBuilder = DefaultTrackSelector.Parameters.Builder()
            .addOverride(trackSelectionOverride)
            .setMaxAudioChannelCount(if (preferDolbyAtmos) 16 else 2)

        val parameters = parametersBuilder.build()

        for (periodIndex in 0 until helper.periodCount) {
            helper.addTrackSelection(periodIndex, parameters)
        }
    }

    private fun selectFallbackByResolution(
        helper: DownloadHelper,
        selectedWidth: Int,
        selectedHeight: Int,
        preferDolbyAtmos: Boolean
    ) {

        val trackSelectorBuilder = DefaultTrackSelector.Parameters.Builder()
            .setForceHighestSupportedBitrate(true)
            .setMaxVideoSize(selectedWidth, selectedHeight)
            .setMinVideoSize(selectedWidth, selectedHeight)
            .setMaxAudioChannelCount(if (preferDolbyAtmos) 16 else 2)

        if (preferDolbyAtmos) {
            trackSelectorBuilder.setPreferredAudioMimeType("audio/eac3-joc")
        }

        val trackSelectorParameters = trackSelectorBuilder.build()

        for (periodIndex in 0 until helper.periodCount) {
            helper.clearTrackSelections(periodIndex)
            helper.addTrackSelection(periodIndex, trackSelectorParameters)
        }
    }

    private fun validateDownloadRequest(downloadRequest: DownloadRequest, streamType: StreamType) {
        val expectedMinStreams = when (streamType) {
            StreamType.SEPARATE_AUDIO_VIDEO -> 2
            StreamType.MUXED_VIDEO_AUDIO -> 1 
            StreamType.UNKNOWN -> 1
        }
    }

    private fun createDownloadResponse(
        downloadRequest: DownloadRequest,
        streamType: StreamType,
        targetTrack: TrackIdentifier?
    ): WritableMap {
        return Arguments.createMap().apply {
            putString("downloadId", downloadRequest.id)
            putString("state", "queued")
            putInt("streamKeysCount", downloadRequest.streamKeys.size)
            putString("streamType", streamType.name)

            val minExpectedStreams = when (streamType) {
                StreamType.SEPARATE_AUDIO_VIDEO -> 2
                StreamType.MUXED_VIDEO_AUDIO -> 1    
                StreamType.UNKNOWN -> 1              
            }

            putInt("minExpectedStreams", minExpectedStreams)
            putBoolean("hasExpectedStreams", downloadRequest.streamKeys.size >= minExpectedStreams)

            if (targetTrack != null) {
                putString("expectedSize", formatBytes(targetTrack.actualSizeBytes))
                putInt("selectedHeight", targetTrack.format.height)
                putInt("selectedWidth", targetTrack.format.width)
                putInt("bitrate", targetTrack.format.bitrate)
            } else {
                putString("expectedSize", "Unknown")
            }
            putString("uri", downloadRequest.uri.toString())
        }
    }

    private fun getMinExpectedBitrate(height: Int): Int {
        return when (height) {
            144 -> 100000   // 100 Kbps minimum for 144p
            240 -> 200000   // 200 Kbps minimum for 240p
            360 -> 400000   // 400 Kbps minimum for 360p
            480 -> 600000   // 600 Kbps minimum for 480p
            576 -> 800000   // 800 Kbps minimum for 576p
            720 -> 1200000  // 1.2 Mbps minimum for 720p
            1080 -> 2500000 // 2.5 Mbps minimum for 1080p
            else -> 100000  // Default 100 Kbps
        }
    }

    private fun detectStreamType(helper: DownloadHelper): StreamType {
        try {
            val manifest = helper.manifest as? HlsManifest
            if (manifest != null) {
                val multivariantPlaylist = manifest.multivariantPlaylist

                val hasAudioRenditions = multivariantPlaylist.audios.isNotEmpty()

                val hasMuxedAudio = multivariantPlaylist.muxedAudioFormat != null

                return when {
                    hasAudioRenditions -> {
                        StreamType.SEPARATE_AUDIO_VIDEO
                    }
                    hasMuxedAudio -> {
                        StreamType.MUXED_VIDEO_AUDIO
                    }
                    else -> {
                        val firstVariant = multivariantPlaylist.variants.firstOrNull()
                        val codecs = firstVariant?.format?.codecs

                        if (codecs?.contains("mp4a") == true) {
                            StreamType.MUXED_VIDEO_AUDIO
                        } else {
                            StreamType.UNKNOWN
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(MODULE_NAME, "Could not detect stream type from manifest: ${e.message}")
        }

        return StreamType.UNKNOWN
    }

    private fun extractBitrateFromManifest(helper: DownloadHelper, format: Format): Int {
        try {
            val manifest = helper.manifest as? HlsManifest
            if (manifest != null) {
                val matchingVariant = manifest.multivariantPlaylist.variants.find { variant ->
                    variant.format.width == format.width &&
                            variant.format.height == format.height &&
                            variant.format.bitrate == format.bitrate
                }

                if (matchingVariant != null) {
                    val averageBandwidth = matchingVariant.format.averageBitrate
                    if (averageBandwidth > 0) {
                        return averageBandwidth
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(MODULE_NAME, "Could not extract average bandwidth: ${e.message}")
        }

        return format.bitrate
    }

    private fun getPlaylistUrls(helper: DownloadHelper, format: Format, streamType: StreamType): PlaylistUrls {
        try {
            val manifest = helper.manifest as? HlsManifest
            if (manifest != null) {
                val baseUrl = manifest.mediaPlaylist.baseUri

                // Find matching variant
                val matchingVariant = manifest.multivariantPlaylist.variants.find { variant ->
                    variant.format.width == format.width &&
                            variant.format.height == format.height
                }

                if (matchingVariant != null) {
                    val videoUrl = resolveUrl(baseUrl, matchingVariant.url.toString())

                    val audioUrl = if (streamType == StreamType.SEPARATE_AUDIO_VIDEO) {
                        val audioRendition = manifest.multivariantPlaylist.audios.firstOrNull()
                        audioRendition?.let { resolveUrl(baseUrl, it.url.toString()) }
                    } else null

                    return PlaylistUrls(videoUrl, audioUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(MODULE_NAME, "Could not extract playlist URLs: ${e.message}")
        }

        return PlaylistUrls("", null)
    }

    private suspend fun sampleSegments(
        videoUrl: String,
        audioUrl: String?,
        headers: Map<String, String>?,
        sampleCount: Int = 8
    ): Pair<Long, Long> {
        return withContext(Dispatchers.IO) {
            try {
                val videoSize = sampleVideoSegments(videoUrl, headers, sampleCount)
                val audioSize = if (audioUrl != null) {
                    sampleAudioSegments(audioUrl, headers, sampleCount)
                } else 0L

                Pair(videoSize, audioSize)
            } catch (e: Exception) {
                Pair(0L, 0L)
            }
        }
    }

    private fun distributeIndices(total: Int, sampleCount: Int): List<Int> {
        if (total <= sampleCount) {
            return (0 until total).toList()
        }

        val indices = mutableListOf<Int>()

        when (sampleCount) {
            8 -> {
                indices.add(0)                           // Start
                indices.add((total * 0.125).toInt())     // 12.5%
                indices.add((total * 0.25).toInt())      // 25%
                indices.add((total * 0.375).toInt())     // 37.5%
                indices.add((total * 0.5).toInt())       // 50%
                indices.add((total * 0.625).toInt())     // 62.5%
                indices.add((total * 0.75).toInt())      // 75%
                indices.add(total - 1)                   // End
            }
            else -> {
                val step = total.toFloat() / sampleCount
                for (i in 0 until sampleCount) {
                    val index = (i * step).toInt().coerceIn(0, total - 1)
                    indices.add(index)
                }
            }
        }

        return indices.distinct()
    }

    private suspend fun calculateAccurateStreamSize(
        helper: DownloadHelper,
        format: Format,
        durationSec: Double,
        streamType: StreamType,
        headers: Map<String, String>?
    ): Long {
        return withContext(Dispatchers.IO) {
            try {

                val cacheKey = "${format.height}p_${format.bitrate}_${streamType.name}"
                cachedSizes[cacheKey]?.let { cachedSize ->
                    return@withContext cachedSize
                }

                val allowedQualities = setOf(480, 720, 1080)
                if (format.height !in allowedQualities) {
                    return@withContext 0L
                }

                val adjustedBitrate = extractBitrateFromManifest(helper, format)

                val playlistUrls = getPlaylistUrls(helper, format, streamType)

                if (playlistUrls.videoUrl.isNotEmpty()) {

                    val (videoSampleSize, audioSampleSize) = sampleSegments(
                        playlistUrls.videoUrl,
                        playlistUrls.audioUrl,
                        headers,
                        sampleCount = 8
                    )

                    if (videoSampleSize > 0) {
                        val totalSize = videoSampleSize + audioSampleSize
                        cachedSizes[cacheKey] = totalSize
                        return@withContext totalSize
                    }
                }

                val calculatedSize = when (streamType) {
                    StreamType.SEPARATE_AUDIO_VIDEO -> calculateVideoOnlySize(adjustedBitrate, durationSec)
                    StreamType.MUXED_VIDEO_AUDIO -> calculateMuxedStreamSize(adjustedBitrate, durationSec)
                    StreamType.UNKNOWN -> calculateVideoOnlySize(adjustedBitrate, durationSec)
                }
                cachedSizes[cacheKey] = calculatedSize
                calculatedSize

            } catch (e: Exception) {
                calculateSmartStreamSize(format.bitrate, durationSec, streamType)
            }
        }
    }

    private suspend fun sampleVideoSegments(
        playlistUrl: String,
        headers: Map<String, String>?,
        sampleCount: Int
    ): Long {
        return withContext(Dispatchers.IO) {
            try {
                // Download playlist
                val playlist = downloadPlaylist(playlistUrl, headers)
                val segments = parseSegmentUrls(playlist, playlistUrl)

                if (segments.isEmpty()) return@withContext 0L

                // Sample evenly distributed segments
                val sampleIndices = distributeIndices(segments.size, sampleCount)
                val segmentSizes = mutableListOf<Long>()

                for (index in sampleIndices) {
                    try {
                        val size = getSegmentSize(segments[index], headers)
                        if (size > 0) {
                            segmentSizes.add(size)
                        }
                    } catch (e: Exception) {
                        Log.w(MODULE_NAME, "Failed to sample segment $index: ${e.message}")
                    }
                }

                return@withContext if (segmentSizes.isNotEmpty()) {
                    val avgSize = segmentSizes.average().toLong()
                    val totalSize = avgSize * segments.size
                    totalSize
                } else 0L

            } catch (e: Exception) {
                Log.w(MODULE_NAME, "Video segment sampling failed: ${e.message}")
                0L
            }
        }
    }

    private suspend fun sampleAudioSegments(
        playlistUrl: String,
        headers: Map<String, String>?,
        sampleCount: Int
    ): Long {
        return sampleVideoSegments(playlistUrl, headers, sampleCount)
    }

    // Download playlist content
    private suspend fun downloadPlaylist(url: String, headers: Map<String, String>?): String {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                headers?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                connection.connect()

                if (connection.responseCode == 200) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    throw Exception("HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.w(MODULE_NAME, "Failed to download playlist $url: ${e.message}")
                ""
            }
        }
    }

    private fun parseSegmentUrls(playlist: String, baseUrl: String): List<String> {
        val segments = mutableListOf<String>()

        playlist.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segments.add(resolveUrl(baseUrl, trimmed))
            }
        }

        return segments
    }

    private suspend fun getSegmentSize(url: String, headers: Map<String, String>?): Long {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                headers?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                connection.connect()
                val contentLength = connection.contentLengthLong
                connection.disconnect()

                if (contentLength > 0) contentLength else 0L
            } catch (e: Exception) {
                Log.w(MODULE_NAME, "Failed to get segment size for $url: ${e.message}")
                0L
            }
        }
    }

    private fun resolveUrl(baseUrl: String, path: String): String {
        return if (path.startsWith("http")) {
            path
        } else {
            val base = baseUrl.substringBeforeLast("/")
            "$base/$path"
        }
    }

    private fun calculateVideoOnlySize(videoBitrate: Int, durationSec: Double): Long {
        if (videoBitrate <= 0 || durationSec <= 0) return 0L

        val videoBits = videoBitrate.toDouble() * durationSec
        val videoBytes = videoBits / 8.0 * 1.02

        return videoBytes.toLong()
    }

    private fun calculateMuxedStreamSize(combinedBitrate: Int, durationSec: Double): Long {
        if (combinedBitrate <= 0 || durationSec <= 0) return 0L

        val totalBits = combinedBitrate.toDouble() * durationSec
        val totalBytes = totalBits / 8.0 * 1.02

        return totalBytes.toLong()
    }

    private fun calculateAudioOnlySize(audioBitrate: Int, durationSec: Double): Long {
        if (audioBitrate <= 0 || durationSec <= 0) return 0L

        val audioBits = audioBitrate.toDouble() * durationSec
        val audioBytes = audioBits / 8.0 * 1.02

        return audioBytes.toLong()
    }


    private fun calculateSmartStreamSize(
        videoBitrate: Int,
        durationSec: Double,
        streamType: StreamType
    ): Long {
        if (videoBitrate <= 0 || durationSec <= 0) return 0L

        return when (streamType) {
            StreamType.SEPARATE_AUDIO_VIDEO -> {
                calculateVideoOnlySize(videoBitrate, durationSec)
            }
            StreamType.MUXED_VIDEO_AUDIO -> {
                calculateMuxedStreamSize(videoBitrate, durationSec)
            }
            StreamType.UNKNOWN -> {
                calculateVideoOnlySize(videoBitrate, durationSec)
            }
        }
    }

    @ReactMethod
    fun setPlaybackMode(mode: String, promise: Promise) {
        try {
            val playbackMode = when (mode.lowercase()) {
                "offline" -> PlaybackMode.OFFLINE
                "online" -> PlaybackMode.ONLINE
                else -> PlaybackMode.ONLINE
            }

            OfflineVideoPlugin.setPlaybackMode(playbackMode)

            promise.resolve(Arguments.createMap().apply {
                putString("mode", playbackMode.name.lowercase())
                putString("status", "success")
            })
        } catch (e: Exception) {
            promise.reject("MODE_ERROR", "Failed to set playback mode: ${e.message}")
        }
    }

    @ReactMethod
    fun getPlaybackMode(promise: Promise) {
        try {
            val currentMode = OfflineVideoPlugin.getPlaybackMode()
            promise.resolve(Arguments.createMap().apply {
                putString("mode", currentMode.name.lowercase())
            })
        } catch (e: Exception) {
            promise.reject("MODE_ERROR", "Failed to get playback mode: ${e.message}")
        }
    }

    @ReactMethod
    fun cancelDownload(downloadId: String, promise: Promise) {
        try {
            val downloadManager = getDownloadManager()
            if (downloadManager == null) {
                promise.reject("DOWNLOAD_MANAGER_ERROR", "DownloadManager not initialized")
                return
            }

            stopProgressReporting(downloadId)

            try {
                downloadManager.removeDownload(downloadId)
            } catch (e: Exception) {
                Log.w(MODULE_NAME, "Warning: Could not remove from DownloadManager: ${e.message}")
            }

            mainHandler.postDelayed({
                try {
                    VideoCache.removeDownload(reactApplicationContext, downloadId)

                    try {
                        OfflineVideoPlugin.getInstance().removeDownloadFromCache(downloadId)
                    } catch (e: Exception) {
                        Log.w(MODULE_NAME, "Warning: Plugin cache cleanup failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(MODULE_NAME, "Warning: Cache cleanup failed for $downloadId: ${e.message}")
                }
            }, 1000)

            promise.resolve(true)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun isDownloadCached(downloadId: String, promise: Promise) {
        try {
            val isCached = VideoCache.isDownloadCached(reactApplicationContext, downloadId)
            promise.resolve(isCached)
        } catch (e: Exception) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getAllCacheKeys(promise: Promise) {
        try {
            val keys = VideoCache.getAllCacheKeys(reactApplicationContext)
            promise.resolve(keys.size)
        } catch (e: Exception) {
            promise.resolve(0)
        }
    }


    private fun getAudioPriority(audioType: String): Int {
        return when (audioType) {
            "dolby_atmos" -> 4
            "dolby_digital" -> 3
            "surround" -> 2
            "stereo" -> 1
            else -> 0
        }
    }

    @ReactMethod
    fun getStorageInfo(promise: Promise) {
        try {
            val context = reactApplicationContext

            val storageInfo = Arguments.createMap()

            val cacheStats = VideoCache.getStorageStats(context)
            storageInfo.putString("cachePath", cacheStats.path)
            storageInfo.putBoolean("isProtected", cacheStats.isProtected)
            storageInfo.putString("totalSize", formatBytes(cacheStats.totalSize))
            storageInfo.putString("availableSpace", formatBytes(cacheStats.availableSpace))
            storageInfo.putInt("usedPercentage", cacheStats.usedPercentage)

            promise.resolve(storageInfo)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

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
            Log.w(MODULE_NAME, "Error calculating folder size: ${e.message}")
            0L
        }
    }


    @ReactMethod
    fun checkStorageSpace(promise: Promise) {
        try {
            val context = reactApplicationContext
            val currentCacheSize = VideoCache.getCacheSize(context)
            val availableSpace = VideoCache.getAvailableCacheSpace(context)
            val totalCacheSize = 15L * 1024 * 1024 * 1024

            promise.resolve(Arguments.createMap().apply {
                putDouble("currentCacheSizeMB", currentCacheSize / (1024.0 * 1024.0))
                putDouble("availableSpaceMB", availableSpace / (1024.0 * 1024.0))
                putDouble("totalCacheSizeMB", totalCacheSize / (1024.0 * 1024.0))
                putString("currentCacheFormatted", formatBytes(currentCacheSize))
                putString("availableSpaceFormatted", formatBytes(availableSpace))
                putBoolean("hasEnoughSpace", availableSpace > (2L * 1024 * 1024 * 1024))
            })
        } catch (e: Exception) {
            promise.reject("STORAGE_CHECK_ERROR", "Failed to check storage: ${e.message}")
        }
    }

    @ReactMethod
    fun canDownloadContent(estimatedSizeBytes: Double, promise: Promise) {
        try {
            val context = reactApplicationContext
            val availableSpace = VideoCache.getAvailableCacheSpace(context)
            val canDownload = availableSpace > estimatedSizeBytes.toLong()

            promise.resolve(Arguments.createMap().apply {
                putBoolean("canDownload", canDownload)
                putDouble("availableSpaceMB", availableSpace / (1024.0 * 1024.0))
                putDouble("requiredSpaceMB", estimatedSizeBytes / (1024.0 * 1024.0))
                putString("availableSpaceFormatted", formatBytes(availableSpace))
                putString("requiredSpaceFormatted", formatBytes(estimatedSizeBytes.toLong()))
            })
        } catch (e: Exception) {
            promise.reject("SIZE_CHECK_ERROR", "Failed to check download size: ${e.message}")
        }
    }

    @ReactMethod
    fun getOfflinePlaybackUri(downloadId: String, promise: Promise) {
        try {
            val downloadManager = _downloadManager
            val download = downloadManager?.downloadIndex?.getDownload(downloadId)

            if (download == null) {
                promise.reject("NOT_FOUND", "Download not found: $downloadId")
                return
            }

            if (download.state == Download.STATE_COMPLETED) {
                promise.resolve(Arguments.createMap().apply {
                    putString("uri", download.request.uri.toString())
                    putBoolean("isOffline", true)
                    putString("downloadId", downloadId)
                    putString("state", "completed")
                })
            } else {
                promise.reject("NOT_COMPLETED", "Download is not yet complete. State: ${getDownloadStateString(download.state)}")
            }
        } catch (e: Exception) {
            Log.e(MODULE_NAME, "Error getting offline playback URI: ${e.message}", e)
            promise.reject("ERROR", "Failed to get offline playback URI: ${e.message}")
        }
    }

    @ReactMethod
    fun pauseDownload(downloadId: String, promise: Promise) {
        DownloadService.sendSetStopReason(reactContext, VideoDownloadService::class.java, downloadId, 1, false)
        promise.resolve(true)
    }

    @ReactMethod
    fun resumeDownload(downloadId: String, promise: Promise) {
        DownloadService.sendSetStopReason(reactContext, VideoDownloadService::class.java, downloadId, Download.STOP_REASON_NONE, false)
        promise.resolve(true)
    }

    @ReactMethod
    fun isDownloaded(downloadId: String, promise: Promise) {
        try {
            val download = _downloadManager?.downloadIndex?.getDownload(downloadId)
            val isDownloaded = download?.state == Download.STATE_COMPLETED
            promise.resolve(isDownloaded)
        } catch (e: Exception) {
            Log.e(MODULE_NAME, "Failed to check download status for $downloadId", e)
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getDownloadStatus(downloadId: String, promise: Promise) {
        try {
            val download = _downloadManager?.currentDownloads?.find { it.request.id == downloadId }

            if (download != null) {
                val progress = download.percentDownloaded.roundToInt()
                val state = getDownloadStateString(download.state)

                promise.resolve(Arguments.createMap().apply {
                    putString("downloadId", downloadId)
                    putString("uri", download.request.uri.toString())
                    putString("state", state)
                    putInt("progress", progress)
                    putDouble("bytesDownloaded", download.bytesDownloaded.toDouble())
                    putDouble("totalBytes", download.contentLength.toDouble())
                    putString("formattedDownloaded", formatBytes(download.bytesDownloaded))
                    putString("formattedTotal", formatBytes(download.contentLength))
                    putBoolean("isCompleted", download.state == Download.STATE_COMPLETED)
                })
            } else {
                promise.resolve(Arguments.createMap().apply {
                    putString("downloadId", downloadId)
                    putString("state", "not_found")
                    putInt("progress", 0)
                    putBoolean("isCompleted", false)
                })
            }
        } catch (e: Exception) {
            promise.reject("STATUS_ERROR", "Failed to get download status: ${e.message}")
        }
    }

    @ReactMethod
    fun getAllDownloads(promise: Promise) {
        try {
            val downloadManager = _downloadManager
            if (downloadManager == null) {
                Log.w(MODULE_NAME, "DownloadManager is null")
                promise.resolve(Arguments.createArray())
                return
            }

            val allDownloads = Arguments.createArray()
            val processedIds = mutableSetOf<String>()

            downloadManager.currentDownloads.forEach { download ->
                processedIds.add(download.request.id)

                allDownloads.pushMap(Arguments.createMap().apply {
                    putString("downloadId", download.request.id)
                    putString("state", getDownloadStateString(download.state))
                    putInt("progress", download.percentDownloaded.roundToInt())
                    putDouble("bytesDownloaded", download.bytesDownloaded.toDouble())
                    putString("formattedDownloaded", formatBytes(download.bytesDownloaded))

                    if (download.state == Download.STATE_COMPLETED) {
                        putString("uri", download.request.uri.toString())
                    }
                })
            }

            val downloadsCursor = downloadManager.downloadIndex.getDownloads()

            while (downloadsCursor.moveToNext()) {
                val download = downloadsCursor.download

                if (processedIds.contains(download.request.id)) {
                    continue
                }

                processedIds.add(download.request.id)

                allDownloads.pushMap(Arguments.createMap().apply {
                    putString("downloadId", download.request.id)
                    putString("state", getDownloadStateString(download.state))

                    putInt("progress", download.percentDownloaded.roundToInt())
                    putDouble("bytesDownloaded", download.bytesDownloaded.toDouble())
                    putString("formattedDownloaded", formatBytes(download.bytesDownloaded))

                    if (download.state == Download.STATE_COMPLETED) {
                        putString("uri", download.request.uri.toString())
                    }
                })
            }

            downloadsCursor.close()

            promise.resolve(allDownloads)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to get downloads: ${e.message}")
        }
    }

    @ReactMethod
    fun syncDownloadProgress(promise: Promise) {
        try {
            val downloadManager = getDownloadManager()
            if (downloadManager == null) {
                promise.reject("ERROR", "DownloadManager not initialized")
                return
            }

            val downloads = mutableListOf<WritableMap>()
            val cursor = downloadManager.downloadIndex.getDownloads()

            try {
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    val downloadInfo = Arguments.createMap().apply {
                        putString("downloadId", download.request.id)
                        putString("uri", download.request.uri.toString())
                        putString("state", getDownloadStateString(download.state))
                        putInt("progress", download.percentDownloaded.roundToInt())
                        putDouble("bytesDownloaded", download.bytesDownloaded.toDouble())
                        putString("formattedDownloaded", formatBytes(download.bytesDownloaded))
                        putBoolean("isCompleted", download.state == Download.STATE_COMPLETED)
                    }
                    downloads.add(downloadInfo)
                }
            } finally {
                cursor.close()
            }

            promise.resolve(Arguments.createArray().apply {
                downloads.forEach { pushMap(it) }
            })
        } catch (e: Exception) {
            promise.reject("SYNC_ERROR", e.message)
        }
    }

    private inner class DownloadManagerListener : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            val state = getDownloadStateString(download.state)
            val progress = download.percentDownloaded.roundToInt()

            when (download.state) {
                Download.STATE_DOWNLOADING -> {
                    startProgressReporting(download.request.id)
                }
                Download.STATE_COMPLETED,
                Download.STATE_FAILED,
                Download.STATE_STOPPED -> {
                    stopProgressReporting(download.request.id)
                }
            }

            sendProgressEvent(download)
        }
    }

    private fun startProgressReporting(downloadId: String) {
        stopProgressReporting(downloadId)

        val progressRunnable = object : Runnable {
            override fun run() {
                try {
                    val download = _downloadManager?.currentDownloads?.find { it.request.id == downloadId }
                    if (download != null && download.state == Download.STATE_DOWNLOADING) {
                        sendProgressEvent(download)
                        progressHandler.postDelayed(this, 1000)
                    }
                } catch (e: Exception) {
                    Log.e(MODULE_NAME, "Error in progress reporting: ${e.message}")
                }
            }
        }

        activeDownloads[downloadId] = progressRunnable
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressReporting(downloadId: String) {
        activeDownloads[downloadId]?.let { runnable ->
            progressHandler.removeCallbacks(runnable)
            activeDownloads.remove(downloadId)
        }
    }

    private fun sendProgressEvent(download: Download) {
        val progress = download.percentDownloaded.roundToInt()
        val state = getDownloadStateString(download.state)

        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("DownloadProgress", Arguments.createMap().apply {
                putString("downloadId", download.request.id)
                putInt("progress", progress)
                putDouble("bytesDownloaded", download.bytesDownloaded.toDouble())
                putDouble("totalBytes", download.contentLength.toDouble())
                putString("state", state)
                putString("formattedDownloaded", formatBytes(download.bytesDownloaded))
                putString("formattedTotal", formatBytes(download.contentLength))
                putBoolean("isCompleted", download.state == Download.STATE_COMPLETED)
            })
    }

    private fun createHttpOnlyDataSourceFactory(
        context: Context,
        headers: Map<String, String>?
    ): DefaultHttpDataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setUserAgent("ExoPlayer/OfflineDownloader")

        headers?.takeIf { it.isNotEmpty() }?.let { headerMap ->
            httpDataSourceFactory.setDefaultRequestProperties(headerMap.toMutableMap())
        }

        return httpDataSourceFactory
    }

    private fun createCacheAwareDataSourceFactory(
        context: Context,
        headers: Map<String, String>?
    ): CacheDataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setUserAgent("ExoPlayer/OfflineDownloader")

        headers?.takeIf { it.isNotEmpty() }?.let { headerMap ->
            httpDataSourceFactory.setDefaultRequestProperties(headerMap.toMutableMap())
        }

        val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(VideoCache.getInstance(context))
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun extractHeadersFromOptions(options: ReadableMap?): Map<String, String>? {
        if (options == null || !options.hasKey("headers")) return null
        val headersMap = options.getMap("headers") ?: return null
        val headers = mutableMapOf<String, String>()
        val iterator = headersMap.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            headersMap.getString(key)?.let { value -> headers[key] = value }
        }
        return headers.ifEmpty { null }
    }

    @SuppressLint("DefaultLocale")
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun getDownloadStateString(state: Int): String {
        return when (state) {
            Download.STATE_QUEUED -> "queued"
            Download.STATE_DOWNLOADING -> "downloading"
            Download.STATE_COMPLETED -> "completed"
            Download.STATE_FAILED -> "failed"
            Download.STATE_REMOVING -> "removing"
            Download.STATE_RESTARTING -> "restarting"
            Download.STATE_STOPPED -> "stopped"
            else -> "unknown"
        }
    }
}
