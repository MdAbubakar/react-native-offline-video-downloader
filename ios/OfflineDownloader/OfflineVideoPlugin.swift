import Foundation
import AVFoundation
import react_native_video

@objc(OfflineVideoPlugin)
class OfflineVideoPlugin: RNVAVPlayerPlugin {
    
    private var _playerRateChangeObserver: NSKeyValueObservation?
    private var _playerCurrentItemChangeObserver: NSKeyValueObservation?
    private var _playerItemStatusObserver: NSKeyValueObservation?
    
    // MARK: - Playback Mode
    @objc enum PlaybackMode: Int {
        case online = 0
        case offline = 1
        
        var description: String {
            switch self {
            case .online: return "ONLINE"
            case .offline: return "OFFLINE"
            }
        }
    }

    // MARK: - Singleton Pattern
    private static var instance: OfflineVideoPlugin?
    private static var playbackMode: PlaybackMode = .online
    
    private var contentDownloadCache: [String: Bool] = [:]
    private let cacheTimeout: TimeInterval = 2.0
    private var lastCacheCleanup: TimeInterval = 0
    private var cacheHitCount: [String: Int] = [:]
    private let maxCacheSize = 1000
    private let cacheCleanupInterval: TimeInterval = 600.0
    
    // MARK: - Singleton Methods
    @objc static func setPlaybackMode(_ mode: Int) {
        let newMode = PlaybackMode(rawValue: mode) ?? .online
        let oldMode = playbackMode
        playbackMode = newMode
        
        getInstance().contentDownloadCache.removeAll()
        print("🎯 Playback mode changed: \(oldMode.description) → \(newMode.description)")
    }
    
    @objc static func getPlaybackMode() -> Int {
        return playbackMode.rawValue
    }
    
    @objc static func getInstance() -> OfflineVideoPlugin {
        if instance == nil {
            instance = OfflineVideoPlugin()
        }
        return instance!
    }

    // MARK: - Plugin Registration
    override init() {
        super.init()
        OfflineVideoPlugin.instance = self
        ReactNativeVideoManager.shared.registerPlugin(plugin: self)
        print("✅ OfflineVideoPlugin registered successfully with ReactNativeVideoManager")
    }
       
    deinit {
        cleanupObservers()
        ReactNativeVideoManager.shared.unregisterPlugin(plugin: self)
        print("🗑️ OfflineVideoPlugin deinitialized")
    }

    // MARK: - JS-exposed methods
    @objc func isContentDownloaded(_ uri: String) -> Bool {
        return isContentDownloadedInternal(uri)
    }

    @objc static func requiresMainQueueSetup() -> Bool {
        return true
    }

    // MARK: - AVPlayer hook
    override func overridePlayerAsset(
        source: VideoSource,
        asset: AVAsset
    ) async -> OverridePlayerAssetResult? {

        guard let urlAsset = asset as? AVURLAsset else { return nil }
        let uri = urlAsset.url.absoluteString

        print("🔍 Checking DataSource override for: \(uri)")
        print("🎯 Playback mode: \(OfflineVideoPlugin.playbackMode.description)")

        switch OfflineVideoPlugin.playbackMode {
        case .online:
            print("🌐 ONLINE mode - using default DataSource for: \(uri)")
            return nil
            
        case .offline:
            let startTime = Date().timeIntervalSince1970
            let isCached = isContentCached(uri)
            let checkDuration = Date().timeIntervalSince1970 - startTime
                    
            print("⚡ Cache check took \(Int(checkDuration * 1000))ms")
            if isCached {
                print("📱 OFFLINE mode - using cached DataSource for: \(uri)")
                
                if let localURL = OfflineVideoRegistry.shared?.getLocalUrl(for: uri) {
                    let offlineAsset = AVURLAsset(url: localURL)
                                
                    return OverridePlayerAssetResult(type: .full, asset: offlineAsset)
                }
            } else {
                print("⚠️ OFFLINE mode - content not cached, using default: \(uri)")
            }
            return nil
        }
    }

    // MARK: - Cache Management
    
    func removeDownloadFromCache(_ downloadId: String) {
        print("🧹 Removing \(downloadId) from plugin cache")
        
        let keysToRemove = contentDownloadCache.keys.filter { uri in
            isUriRelatedToDownload(uri: uri, downloadId: downloadId)
        }
        
        keysToRemove.forEach { key in
            contentDownloadCache.removeValue(forKey: key)
            print("🗑️ Removed plugin cache key: \(key)")
        }
        
        print("✅ Removed \(keysToRemove.count) plugin cache entries for: \(downloadId)")
    }
    
    func isContentDownloadedInternal(_ uri: String) -> Bool {
        if let cached = contentDownloadCache[uri] {
            cacheHitCount[uri] = (cacheHitCount[uri] ?? 0) + 1
            return cached
        }
        
        let currentTime = Date().timeIntervalSince1970
        if currentTime - lastCacheCleanup > cacheCleanupInterval || contentDownloadCache.count > maxCacheSize {
            cleanupCacheIntelligently()
            lastCacheCleanup = currentTime
        }
        
        let startTime = Date().timeIntervalSince1970
        let isDownloaded = OfflineVideoRegistry.shared?.isContentCached(url: uri) ?? false
        let duration = Date().timeIntervalSince1970 - startTime
            
        print("⚡ Fast registry check took \(Int(duration * 1000))ms for: \(uri)")
            
        contentDownloadCache[uri] = isDownloaded
        cacheHitCount[uri] = 1
            
        return isDownloaded
    }
    
    private func cleanupCacheIntelligently() {
        let sortedByHits = cacheHitCount.sorted { $0.value > $1.value }
        let keepCount = min(500, sortedByHits.count)  // Keep top 500
        
        let keysToKeep = Set(sortedByHits.prefix(keepCount).map { $0.key })
        
        contentDownloadCache = contentDownloadCache.filter { keysToKeep.contains($0.key) }
        cacheHitCount = cacheHitCount.filter { keysToKeep.contains($0.key) }
        
        print("🧹 Intelligently cleaned cache: kept \(contentDownloadCache.count) items")
    }
    
    private func isContentCached(_ uri: String) -> Bool {
        if let cached = contentDownloadCache[uri] {
            return cached
        }
        
        guard let registry = OfflineVideoRegistry.shared else {
            contentDownloadCache[uri] = false
            return false
        }
        
        let startTime = Date().timeIntervalSince1970
        
        let isCached = registry.isContentCached(url: uri)
        
        let duration = Date().timeIntervalSince1970 - startTime
        print("💾 Fast cache check took \(Int(duration * 1000))ms for: \(uri)")
        
        contentDownloadCache[uri] = isCached
        return isCached
    }

    
    private func isUriRelatedToDownload(uri: String, downloadId: String) -> Bool {
        if uri == downloadId || uri.contains(downloadId) {
            return true
        }
        
        let uriContentId = extractContentId(from: uri)
        let downloadContentId = extractContentId(from: downloadId)
        
        return !uriContentId.isEmpty && !downloadContentId.isEmpty && uriContentId == downloadContentId
    }
    
    private func extractContentId(from identifier: String) -> String {
        let regex = try? NSRegularExpression(pattern: "([a-f0-9]{24})", options: [])
        let range = NSRange(location: 0, length: identifier.utf16.count)
        
        if let match = regex?.firstMatch(in: identifier, options: [], range: range) {
            let contentId = String(identifier[Range(match.range, in: identifier)!])
            if !contentId.isEmpty {
                print("🔍 Extracted content ID '\(contentId)' from: \(identifier)")
            }
            return contentId
        }
        
        return ""
    }

    // MARK: - Player lifecycle callbacks
    override func onInstanceCreated(id: String, player: AVPlayer) {
        print("📱 AVPlayer instance created: \(id)")
        setupObservers(for: player)
    }

    override func onInstanceRemoved(id: String, player: AVPlayer) {
        print("📱 AVPlayer instance removed: \(id)")
        cleanupObservers()
    }
    
    // MARK: - Observer Management
    private func setupObservers(for player: AVPlayer) {
        cleanupObservers()
        
        _playerRateChangeObserver = player.observe(
            \.rate, options: [.old], changeHandler: handlePlaybackRateChange
        )
        
        _playerCurrentItemChangeObserver = player.observe(
            \.currentItem, options: [.old], changeHandler: handleCurrentItemChange
        )
    }
    
    private func cleanupObservers() {
        _playerRateChangeObserver?.invalidate()
        _playerRateChangeObserver = nil
        
        _playerCurrentItemChangeObserver?.invalidate()
        _playerCurrentItemChangeObserver = nil
        
        _playerItemStatusObserver?.invalidate()
        _playerItemStatusObserver = nil
    }
    
    private func handlePlaybackRateChange(player: AVPlayer, change: NSKeyValueObservedChange<Float>) {
        if OfflineVideoPlugin.playbackMode == .offline && player.rate > 0 {
            print("📱 Offline content started playing")
        }
    }

    private func handlePlayerItemStatusChange(playerItem: AVPlayerItem, change: NSKeyValueObservedChange<AVPlayerItem.Status>) {
        if OfflineVideoPlugin.playbackMode == .offline {
            switch playerItem.status {
            case .readyToPlay:
                print("📱 Offline content ready to play")
            case .failed:
                if let error = playerItem.error {
                    print("❌ Offline playback failed: \(error.localizedDescription)")
                }
            case .unknown:
                print("⏳ Offline content loading...")
            @unknown default:
                print("🔄 Unknown player item status")
            }
        }
    }
    
    private func handleCurrentItemChange(player: AVPlayer, change: NSKeyValueObservedChange<AVPlayerItem?>) {
        _playerItemStatusObserver?.invalidate()
        _playerItemStatusObserver = nil
        
        guard let playerItem = player.currentItem else { return }
        
        _playerItemStatusObserver = playerItem.observe(
            \.status, options: [.new, .old], changeHandler: handlePlayerItemStatusChange
        )
        
        if OfflineVideoPlugin.playbackMode == .offline {
            if let urlAsset = playerItem.asset as? AVURLAsset {
                let isLocalFile = urlAsset.url.isFileURL
                print("📱 New offline content loaded: \(isLocalFile ? "local file" : "network")")
            }
        }
    }
}
