import AVFoundation
import Foundation
import React
import Network
import MMKV

enum PlaybackMode {
    case online
    case offline
}

enum StreamType {
    case separateAudioVideo
    case muxedVideoAudio
    case unknown
}

extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        return Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

class VideoDownloadManager: NSObject {
    
    weak var eventEmitter: RCTEventEmitter?
    
    private static var sharedInstance: VideoDownloadManager?
    var backgroundCompletionHandler: (() -> Void)?
        
    private var downloadSession: AVAssetDownloadURLSession?
    private var delegateDispatchQueue: DispatchQueue?
    private var activeDownloads: [String: AVAssetDownloadTask] = [:]
    private var downloadProgress: [String: DownloadProgressInfo] = [:]
    private var incompleteDownloads: Set<String> = []
    private var mmkv: MMKV!
    private let partialDownloadsKey = "PartialDownloads"

        
    private var offlineRegistry: OfflineVideoRegistry
    private var videoQualityManager: VideoQualityManager
    private var progressTracker: DownloadProgressTracker
    private var progressTimers: [String: Timer] = [:]
    private let progressUpdateInterval: TimeInterval = 1.0
        
    private var playbackMode: PlaybackMode = .online
    private var cachedSizes: [String: Int64] = [:]
    private var storedTrackIdentifiers: [String: [Int: TrackIdentifier]] = [:]
    private var downloadToTrackMapping: [String: TrackIdentifier] = [:]
    
    private var isJavascriptLoaded = false
    
    private let networkMonitor = NWPathMonitor()
    private var isNetworkAvailable = true
        
    // MARK: - Data Structures
    
    @objc static func getSharedInstance() -> VideoDownloadManager? {
        return sharedInstance
    }
       
    @objc static func setSharedInstance(_ instance: VideoDownloadManager) {
        if sharedInstance == nil {
            sharedInstance = instance
            print("✅ VideoDownloadManager singleton created")
        } else {
            sharedInstance?.eventEmitter = instance.eventEmitter
            print("✅ VideoDownloadManager singleton updated (hot reload)")
        }
    }
    
    struct TrackIdentifier {
        let height: Int
        let width: Int
        let bitrate: Int
        let actualSizeBytes: Int64
        let variant: AVAssetVariant?
    }
    
    struct DownloadProgressInfo {
        var percentage: Float = 0.0
        var downloadedBytes: Int64 = 0
        var totalBytes: Int64 = 0
        var state: String = "queued"
        var startTime: Date = Date()
    }
    
    override init() {
        offlineRegistry = OfflineVideoRegistry()
        OfflineVideoRegistry.setShared(offlineRegistry)
        
        videoQualityManager = VideoQualityManager()
        progressTracker = DownloadProgressTracker()
        storedTrackIdentifiers = [:]
        downloadToTrackMapping = [:]
        
        super.init()
        
        MMKV.initialize(rootDir: nil)
        mmkv = MMKV(mmapID: "VideoDownloadManager")
        print("✅ MMKV initialized")
        
        loadPersistedDownloadTasks()
        
        VideoDownloadManager.setSharedInstance(self)
        setupNetworkMonitoring()
        setupDownloadSession()
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleJavascriptLoad),
            name: NSNotification.Name("RCTJavaScriptDidLoadNotification"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleHotReload),
            name: NSNotification.Name("RCTJavaScriptWillStartLoadingNotification"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleBackgroundSessionReady(_:)),
            name: NSNotification.Name("BackgroundDownloadSessionReady"),
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        
        print("📱 VideoDownloadManager initialized")
    }
        
    deinit {
        progressTimers.values.forEach { $0.invalidate() }
        progressTimers.removeAll()
        networkMonitor.cancel()
        
        if activeDownloads.isEmpty {
            downloadSession?.invalidateAndCancel()
        } else {
            print("⚠️ \(activeDownloads.count) downloads active - session kept alive")
        }
        
        NotificationCenter.default.removeObserver(self)
        print("🗑️ VideoDownloadManager deinitialized")
    }

    private func setupDownloadSession() {
        
        guard downloadSession == nil else { return }
        
        let identifier = "com.etv.ott.background-downloads"
        let config = URLSessionConfiguration.background(withIdentifier: identifier)
           
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.networkServiceType = .video
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600
        
        if #available(iOS 13.0, *) {
            config.allowsExpensiveNetworkAccess = true
            config.allowsConstrainedNetworkAccess = true
        }
            
        if #available(iOS 14.0, *) {
            config.multipathServiceType = .handover
        }
        
        let queueLabel = "com.etv.backgroundDownloadDelegateQueue"
        let operationQueue = OperationQueue()
        operationQueue.maxConcurrentOperationCount = 3
        
        self.delegateDispatchQueue = DispatchQueue(label: queueLabel)
        operationQueue.underlyingQueue = delegateDispatchQueue
        
        downloadSession = AVAssetDownloadURLSession(
            configuration: config,
            assetDownloadDelegate: self,
            delegateQueue: operationQueue
        )
            
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 3.0) {
            self.checkForOrphanedDownloads()
        }
        
        print("✅ VideoDownloadManager initialized successfully")
        print("📂 Downloads will be stored in: \(getDownloadsDirectoryPath())")
        print("🛡️ Storage is PROTECTED (data directory)")
    }
    
    @objc private func handleJavascriptLoad() {
        isJavascriptLoaded = true
        print("✅ JavaScript loaded - ready to emit events")
    }
    
    @objc private func handleHotReload() {
        print("🔥 Hot reload detected - preserving session")
        
        // ⭐ NEVER invalidate session - AVAssetDownloadTask cannot survive session invalidation
        isJavascriptLoaded = false
        
        let activeCount = activeDownloads.count
        if activeCount > 0 {
            print("📋 Preserving \(activeCount) active downloads:")
            for id in activeDownloads.keys {
                print("   - \(id)")
            }
        }
    }
    
    @objc private func appWillEnterForeground() {
        print("📱 App entering foreground")
        
        for (downloadId, downloadTask) in activeDownloads {
            if downloadTask.state == .running {
                print("🔄 Force refreshing: \(downloadId)")
                downloadTask.suspend()
                downloadTask.resume()
            }
        }
    }

    private func persistDownloadTask(
        downloadId: String,
        masterUrl: String,
        selectedHeight: Int,
        selectedWidth: Int,
        taskIdentifier: UInt
    ) {
        let taskData: [String: Any] = [
            "downloadId": downloadId,
            "masterUrl": masterUrl,
            "selectedHeight": selectedHeight,
            "selectedWidth": selectedWidth,
            "taskIdentifier": taskIdentifier,
            "timestamp": Date().timeIntervalSince1970,
            "state": "active"
        ]
        
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: taskData)
            mmkv.set(jsonData, forKey: "download_\(downloadId)")
            print("💾 Persisted task to MMKV: \(downloadId)")
        } catch {
            print("❌ Failed to persist task: \(error)")
        }
    }

    private func loadPersistedDownloadTasks() {
        print("📂 Loading persisted tasks from MMKV...")
        
        let allKeys = mmkv.allKeys()
        print("🔑 MMKV has \(allKeys.count) total keys")
        
        for key in allKeys {
            if let keyString = key as? String {
                print("🔑 Found key: \(keyString)")
            }
        }
        
        if allKeys.isEmpty {
            print("ℹ️ No persisted tasks found")
            return
        }
        
        var loadedCount = 0
        
        for key in allKeys {
            if let keyString = key as? String, keyString.hasPrefix("download_") {
                if let data = mmkv.data(forKey: keyString) {
                    do {
                        if let taskData = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let downloadId = taskData["downloadId"] as? String {
                            
                            // Mark as incomplete until we verify with iOS
                            incompleteDownloads.insert(downloadId)
                            loadedCount += 1
                            print("📝 Loaded persisted task: \(downloadId)")
                        }
                    } catch {
                        print("⚠️ Failed to decode task data for key: \(keyString)")
                    }
                }
            }
        }
        
        print("✅ Loaded \(loadedCount) persisted tasks")
    }


    private func updatePersistedTaskState(downloadId: String, state: String) {
        if let data = mmkv.data(forKey: "download_\(downloadId)") {
            do {
                if var taskData = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    taskData["state"] = state
                    taskData["lastUpdated"] = Date().timeIntervalSince1970
                    
                    let updatedData = try JSONSerialization.data(withJSONObject: taskData)
                    mmkv.set(updatedData, forKey: "download_\(downloadId)")
                    print("💾 Updated task state: \(downloadId) -> \(state)")
                }
            } catch {
                print("❌ Failed to update task state: \(error)")
            }
        }
    }

    private func removePersistedTask(downloadId: String) {
        mmkv.removeValue(forKey: "download_\(downloadId)")
        print("🗑️ Removed persisted task: \(downloadId)")
    }
    
    // MARK: - Partial Download Support

    private func savePartialDownload(downloadId: String, location: URL, options: [String: Any]?) {
        let fileManager = FileManager.default
        
        // ⭐ Clean the path - remove .nofollow prefix if present
        var cleanPath = location.path
        if cleanPath.hasPrefix("/.nofollow/") {
            cleanPath = String(cleanPath.dropFirst("/.nofollow".count))
        }
        
        let cleanURL = URL(fileURLWithPath: cleanPath)
        
        var isDirectory: ObjCBool = false
        let existsAtLocation = fileManager.fileExists(atPath: cleanURL.path, isDirectory: &isDirectory)
        
        if existsAtLocation {
            print("✅ Partial file exists at iOS location: \(cleanURL.path)")
            
            let partialInfo: [String: Any] = [
                "absolutePath": cleanURL.absoluteString,
                "relativePath": cleanURL.path,  // ⭐ Save cleaned path
                "downloadId": downloadId,
                "savedAt": Date().timeIntervalSince1970,
                "options": options ?? [:],
                "fileSize": getFileSize(at: cleanURL)
            ]
            
            if let jsonData = try? JSONSerialization.data(withJSONObject: partialInfo),
               let jsonString = String(data: jsonData, encoding: .utf8) {
                mmkv.set(jsonString, forKey: "partial_\(downloadId)")
                print("💾 Saved partial info to MMKV: \(downloadId)")
                print("📍 Saved path: \(cleanURL.path)")
            }
            
            return
        }
        
        // If file doesn't exist at cleaned location, try to find it
        print("⚠️ File not at expected location: \(cleanURL.path)")
        print("🔍 Searching UserManagedAssets...")
        
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        let libraryPath = documentsPath.deletingLastPathComponent().appendingPathComponent("Library")
        
        do {
            let libraryContents = try fileManager.contentsOfDirectory(
                at: libraryPath,
                includingPropertiesForKeys: nil,
                options: []
            )
            
            for item in libraryContents where item.lastPathComponent.hasPrefix("com.apple.UserManagedAssets.") {
                let assetContents = try fileManager.contentsOfDirectory(
                    at: item,
                    includingPropertiesForKeys: nil,
                    options: []
                )
                
                for assetItem in assetContents where assetItem.pathExtension == "movpkg" {
                    if assetItem.lastPathComponent.hasPrefix(downloadId) {
                        print("✅ Found partial at: \(assetItem.path)")
                        
                        let partialInfo: [String: Any] = [
                            "absolutePath": assetItem.absoluteString,
                            "relativePath": assetItem.path,
                            "downloadId": downloadId,
                            "savedAt": Date().timeIntervalSince1970,
                            "options": options ?? [:],
                            "fileSize": getFileSize(at: assetItem)
                        ]
                        
                        if let jsonData = try? JSONSerialization.data(withJSONObject: partialInfo),
                           let jsonString = String(data: jsonData, encoding: .utf8) {
                            mmkv.set(jsonString, forKey: "partial_\(downloadId)")
                            print("💾 Saved partial info to MMKV: \(downloadId)")
                            print("📍 Saved path: \(assetItem.path)")
                        }
                        
                        return
                    }
                }
            }
        } catch {
            print("❌ Error searching for partial file: \(error)")
        }
        
        print("❌ Could not find partial file for: \(downloadId)")
    }

    private func resumePartialDownloads() {
        print("🔄 Checking for partial downloads...")
        
        guard let allKeys = mmkv.allKeys() as? [String] else {
            print("⚠️ Cannot get MMKV keys")
            return
        }
        
        print("🔑 Total MMKV keys: \(allKeys.count)")
        
        let partialKeys = allKeys.filter { $0.hasPrefix("partial_") && !$0.contains("partial_abs_") }
        print("🔑 Found \(partialKeys.count) partial keys: \(partialKeys)")
        
        if partialKeys.isEmpty {
            print("ℹ️ No partial downloads found")
            return
        }
        
        let fileManager = FileManager.default
        let documentsDirectory = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        
        var resumedCount = 0
        
        for key in partialKeys {
            let downloadId = String(key.dropFirst("partial_".count))
            
            // ⭐ Parse JSON to get partial info
            guard let jsonString = mmkv.string(forKey: key),
                  let jsonData = jsonString.data(using: .utf8),
                  let partialInfo = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
                  let relativePath = partialInfo["relativePath"] as? String else {
                print("⚠️ Cannot parse partial info for key: \(key)")
                mmkv.removeValue(forKey: key)
                continue
            }
            
            print("📂 Partial path: \(relativePath)")
            
            let partialURL = documentsDirectory.appendingPathComponent(relativePath)
            
            // Check if file/directory exists
            var isDirectory: ObjCBool = false
            let exists = fileManager.fileExists(atPath: partialURL.path, isDirectory: &isDirectory)
            
            guard exists else {
                print("⚠️ Partial file missing: \(downloadId) at \(partialURL.path)")
                
                // Try absolute path as fallback
                if let absolutePath = partialInfo["absolutePath"] as? String,
                   let absoluteURL = URL(string: absolutePath),
                   fileManager.fileExists(atPath: absoluteURL.path) {
                    print("✅ Found partial at absolute path: \(absoluteURL.path)")
                    resumePartialDownloadFrom(url: absoluteURL, downloadId: downloadId)
                    resumedCount += 1
                    continue
                }
                
                mmkv.removeValue(forKey: key)
                continue
            }
            
            print("✅ Found partial \(isDirectory.boolValue ? "directory" : "file"): \(partialURL.path)")
            print("🔄 Resuming partial download: \(downloadId)")
            
            resumePartialDownloadFrom(url: partialURL, downloadId: downloadId)
            resumedCount += 1
        }
        
        print("✅ Resumed \(resumedCount) partial downloads")
    }

    private func resumePartialDownloadFrom(url: URL, downloadId: String) {
        print("🔄 Attempting to resume from: \(url.path)")
        
        // ⭐ Verify file exists before creating task
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: url.path) else {
            print("❌ Partial file doesn't exist at: \(url.path)")
            mmkv.removeValue(forKey: "partial_\(downloadId)")
            return
        }
        
        print("✅ Partial file verified at: \(url.path)")
        
        let partialAsset = AVURLAsset(url: url)
        var downloadOptions: [String: Any]? = nil
        
        // Get saved options
        if let jsonString = mmkv.string(forKey: "partial_\(downloadId)"),
           let jsonData = jsonString.data(using: .utf8),
           let partialInfo = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
           let savedOptions = partialInfo["options"] as? [String: Any] {
            downloadOptions = savedOptions
            print("✅ Restored download options for: \(downloadId)")
        }
        
        // Create task with original options
        guard let downloadTask = downloadSession?.makeAssetDownloadTask(
            asset: partialAsset,
            assetTitle: downloadId,
            assetArtworkData: nil,
            options: downloadOptions
        ) else {
            print("❌ Failed to create download task for: \(downloadId)")
            return
        }
        
        downloadTask.taskDescription = downloadId
        activeDownloads[downloadId] = downloadTask
        downloadProgress[downloadId] = DownloadProgressInfo(state: "downloading")
        
        downloadTask.resume()
        startProgressReporting(for: downloadId)
        
        print("✅ Resumed partial: \(downloadId) with original quality settings")
        
        guard self.isJavascriptLoaded else {
            print("⏳ Waiting for JS to load before emitting")
            return
        }
        
        DispatchQueue.main.async {
            self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                "downloadId": downloadId,
                "state": "downloading",
                "progress": 0,
                "message": "Resuming from partial download"
            ])
        }
    }

    private func removePartialDownload(downloadId: String) {
        mmkv.removeValue(forKey: "partial_\(downloadId)")
        mmkv.removeValue(forKey: "partial_abs_\(downloadId)")
        print("🗑️ Removed partial download: \(downloadId)")
    }


    private func getAllPersistedTasks() -> [[String: Any]] {
        var tasks: [[String: Any]] = []
        
        let allKeys = mmkv.allKeys()
        
        for key in allKeys {
            if let keyString = key as? String, keyString.hasPrefix("download_") {
                if let data = mmkv.data(forKey: keyString),
                   let taskData = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    tasks.append(taskData)
                }
            }
        }
        
        return tasks
    }
    
    private func restorePendingDownloads() {
        print("🔄 Checking for pending downloads...")
        
        guard let session = downloadSession else {
            print("⚠️ Download session not ready")
            return
        }
        
        // Load persisted task metadata
        let persistedTasks = getAllPersistedTasks()
        print("💾 Found \(persistedTasks.count) persisted tasks in MMKV")
        
        session.getAllTasks { [weak self] tasks in
            guard let self = self else { return }
            
            Thread.sleep(forTimeInterval: 0.1)
            
            DispatchQueue.main.async {
                print("📊 iOS returned \(tasks.count) tasks")
                
                var restoredCount = 0
                var processedDownloadIds = Set<String>()
                
                for task in tasks {
                    guard let downloadTask = task as? AVAssetDownloadTask else {
                        continue
                    }
                    
                    // Get downloadId from taskDescription (only reliable way for AVAssetDownloadTask)
                    var downloadId = downloadTask.taskDescription
                    
                    // If not found, try to match with persisted tasks by taskIdentifier
                    if downloadId == nil || downloadId?.isEmpty == true {
                        let taskId = downloadTask.taskIdentifier
                        print("⚠️ No taskDescription, checking persisted tasks for taskId: \(taskId)")
                        
                        for persistedTask in persistedTasks {
                            if let savedId = persistedTask["downloadId"] as? String,
                               let savedTaskId = persistedTask["taskIdentifier"] as? UInt,
                               savedTaskId == taskId,
                               !processedDownloadIds.contains(savedId) {
                                downloadId = savedId
                                print("🔗 Matched by taskIdentifier: \(taskId) -> \(savedId)")
                                break
                            }
                        }
                        
                        // If still no match, use first unprocessed persisted task
                        if downloadId == nil || downloadId?.isEmpty == true {
                            for persistedTask in persistedTasks {
                                if let savedId = persistedTask["downloadId"] as? String,
                                   !processedDownloadIds.contains(savedId) {
                                    downloadId = savedId
                                    print("🔗 Matched by first available: \(savedId)")
                                    break
                                }
                            }
                        }
                    }
                    
                    guard let id = downloadId, !id.isEmpty else {
                        print("⚠️ Task has no identifier, canceling")
                        downloadTask.cancel()
                        continue
                    }
                    
                    // Avoid processing same download twice
                    if processedDownloadIds.contains(id) {
                        print("⏭️ Already processed: \(id)")
                        continue
                    }
                    processedDownloadIds.insert(id)
                    
                    let taskState = downloadTask.state
                    print("🔍 Found task: \(id), state: \(taskState.rawValue)")
                    
                    switch taskState {
                    case .running, .suspended:
                        // Add to activeDownloads
                        self.activeDownloads[id] = downloadTask
                        
                        // Get progress
                        let progress = downloadTask.progress
                        let percentage = progress.totalUnitCount > 0
                            ? (Double(progress.completedUnitCount) / Double(progress.totalUnitCount)) * 100
                            : 0
                        
                        // Restore progress info
                        self.downloadProgress[id] = DownloadProgressInfo(
                            percentage: Float(percentage),
                            downloadedBytes: Int64(progress.completedUnitCount),
                            totalBytes: Int64(progress.totalUnitCount),
                            state: taskState == .running ? "downloading" : "stopped"
                        )
                        
                        // Remove from incomplete set
                        self.incompleteDownloads.remove(id)
                        
                        // Force refresh (like react-native-background-downloader)
                        if taskState == .running {
                            downloadTask.suspend()
                            downloadTask.resume()
                            print("🔄 Force refreshed running task")
                        } else if taskState == .suspended {
                            downloadTask.resume()
                            print("▶️ Resumed suspended task")
                        }
                        
                        // Update persisted state
                        self.updatePersistedTaskState(downloadId: id, state: "active")
                        
                        // Start progress reporting
                        self.startProgressReporting(for: id)
                        
                        print("✅ Restored: \(id) at \(Int(percentage))%")
                        restoredCount += 1
                        
                    case .completed:
                        print("⏭️ Task already completed: \(id)")
                        self.removePersistedTask(downloadId: id)
                        
                    case .canceling:
                        print("⏭️ Task is canceling: \(id)")
                        self.removePersistedTask(downloadId: id)
                        
                    @unknown default:
                        print("⚠️ Unknown state: \(taskState.rawValue)")
                    }
                }
                
                print("📊 Restoration complete: \(restoredCount) restored")
                
                // Clean up persisted tasks that weren't found in iOS session
                for persistedTask in persistedTasks {
                    if let savedId = persistedTask["downloadId"] as? String,
                       !processedDownloadIds.contains(savedId),
                       self.activeDownloads[savedId] == nil {
                        print("🧹 Cleaning up stale persisted task: \(savedId)")
                        self.removePersistedTask(downloadId: savedId)
                    }
                }
            }
        }
    }
    
    @objc private func handleBackgroundSessionReady(_ notification: Notification) {
        if let wrapper = notification.object as? NSObject,
           let handler = wrapper.value(forKey: "handler") as? () -> Void {
            self.backgroundCompletionHandler = handler
            print("✅ Received background completion handler via notification")
        }
    }
    
    @objc static func setBackgroundCompletionHandler(_ handler: @escaping () -> Void) {
        sharedInstance?.backgroundCompletionHandler = handler
    }
    
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        print("✅ Background session finished all events")
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            if let handler = self.backgroundCompletionHandler {
                print("📱 Calling iOS background completion handler")
                handler()
                self.backgroundCompletionHandler = nil
            }
        }
    }
    
    private func setupNetworkMonitoring() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
        let wasConnected = self?.isNetworkAvailable ?? true
        self?.isNetworkAvailable = path.status == .satisfied
               
        print("📶 Network status: \(path.status == .satisfied ? "Connected" : "Disconnected")")
               
        if !wasConnected && self?.isNetworkAvailable == true {
            print("📶 Network reconnected - attempting to resume stalled downloads")
            self?.resumeStalledDownloads()
        } else if self?.isNetworkAvailable == false {
                print("📵 Network disconnected")
            }
        }
           
        let queue = DispatchQueue.global(qos: .background)
        networkMonitor.start(queue: queue)
    }
    
    private func checkForOrphanedDownloads() {
        guard Thread.isMainThread == false else {
            DispatchQueue.global(qos: .utility).async {
                self.checkForOrphanedDownloads()
            }
            return
        }
        
        print("🔍 Checking for orphaned completed downloads...")
        
        let fileManager = FileManager.default
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        let libraryPath = documentsPath.deletingLastPathComponent().appendingPathComponent("Library")
        
        do {
            let libraryContents = try fileManager.contentsOfDirectory(
                at: libraryPath,
                includingPropertiesForKeys: nil,
                options: []
            )
            
            var processedFiles = Set<String>()
            
            for item in libraryContents where item.lastPathComponent.hasPrefix("com.apple.UserManagedAssets.") {
                print("📁 Scanning directory: \(item.lastPathComponent)")
                
                let assetContents = try fileManager.contentsOfDirectory(
                    at: item,
                    includingPropertiesForKeys: nil,
                    options: []
                )
                
                for assetItem in assetContents where assetItem.pathExtension == "movpkg" {
                    let fileName = assetItem.lastPathComponent
                    
                    guard !processedFiles.contains(fileName) else {
                        continue
                    }
                    processedFiles.insert(fileName)
                    
                    if isDownloadComplete(at: assetItem) {
                        if let downloadId = extractDownloadIdFromFilename(fileName) {
                            print("✅ Found complete orphaned download: \(downloadId)")
                            DispatchQueue.main.async {
                                self.registerOrphanedDownload(downloadId: downloadId, location: assetItem)
                            }
                        }
                    }
                }
            }
            
            print("✅ Orphan check complete. Processed \(processedFiles.count) unique files")
            
        } catch {
            print("❌ Error checking for orphaned downloads: \(error)")
        }
    }
    
    private func registerOrphanedDownload(downloadId: String, location: URL) {
        let size = getFileSize(at: location)
        
        guard size > 0 else {
            print("⚠️ Invalid file size for orphaned download")
            return
        }
        
        let downloadInfo: [String: Any] = [
            "downloadId": downloadId,
            "localUri": location.absoluteString,
            "fileSize": size,
            "formattedSize": formatBytes(size),
            "downloadDate": Date().timeIntervalSince1970
        ]
        offlineRegistry.registerDownload(
            downloadId: downloadId,
            localUrl: location
        ) { success in
            if success {
                print("✅ Registered aggregate download: \(downloadId)")
                
                guard self.isJavascriptLoaded else {
                    print("⏳ Waiting for JS to load before emitting")
                    return
                }
                
                print("✅ Registered download: \(downloadId) (\(self.formatBytes(size)))")
                print("✅ Registered orphaned download: \(downloadId)")
                // Emit completion event
                DispatchQueue.main.async {
                    self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                        "downloadId": downloadId,
                        "localUri": location.absoluteString,
                        "state": "completed",
                        "progress": 100,
                        "bytesDownloaded": size,
                        "isCompleted": true
                    ])
                }
                
                self.removePersistedTask(downloadId: downloadId)
            }
        }
    }


    private func extractDownloadIdFromFilename(_ filename: String) -> String? {
        let components = filename.replacingOccurrences(of: ".movpkg", with: "").split(separator: "_")
        if let firstPart = components.first {
            let downloadId = String(firstPart)
            if downloadId.count == 24 && downloadId.rangeOfCharacter(from: CharacterSet.alphanumerics.inverted) == nil {
                return downloadId
            }
        }
        return nil
    }

    private func isDownloadComplete(at url: URL) -> Bool {
        let fileManager = FileManager.default
        
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            print("⚠️ Path doesn't exist or is not a directory: \(url.path)")
            return false
        }
        
        print("🔍 Validating asset at: \(url.lastPathComponent)")
        
        let asset = AVURLAsset(url: url)
        
        let durationSeconds = CMTimeGetSeconds(asset.duration)
        let isPlayable = asset.isPlayable
        
        print("✅ Asset validation passed:")
        print("   - Duration: \(String(format: "%.1f", durationSeconds))s")
        print("   - Playable: \(isPlayable)")
        
        let isComplete = durationSeconds > 0 && isPlayable
        print("🔍 Validation for \(url.lastPathComponent.components(separatedBy: "_").first ?? "unknown"): \(isComplete)")
        
        return isComplete
    }
    
    func getAvailableTracks(
        masterUrl: String,
        options: NSDictionary?,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        if masterUrl.contains(".movpkg") {
                print("⏭️ Skipping track parsing for .movpkg file")
                resolver([
                    "tracks": [],
                    "message": "Local .movpkg file - no track parsing needed"
                ])
                return
            }
        
        DispatchQueue.main.async {
            self.cachedSizes.removeAll()
            
            guard let url = URL(string: masterUrl) else {
                rejecter("INVALID_URL", "Invalid master URL", nil)
                return
            }
            
            let headers = self.extractHeadersFromOptions(options)
            let asset = self.createAsset(from: url, headers: headers)
            
            // Load asset properties
            asset.loadValuesAsynchronously(forKeys: ["variants", "duration", "tracks"]) {
                DispatchQueue.main.async {
                    var error: NSError?
                    let status = asset.statusOfValue(forKey: "variants", error: &error)
                    
                    guard status == .loaded else {
                        rejecter("QUALITY_LOAD_FAILED", error?.localizedDescription ?? "Failed to load stream qualities", nil)
                        return
                    }
                    
                    // FIXED: Detect stream type using Android-like logic
                    let streamType = self.detectStreamTypeAdvanced(asset: asset)
                    print("🔍 Detected stream type: \(streamType)")
                    
                    // Define allowed qualities (matching Android)
                    let allowedQualities = [480, 720, 1080]
                    print("🎯 Filtering for allowed qualities: \(allowedQualities)")
                    
                    // MOVED: Variables moved to scope where they'll be used
                    var audioTracks: [[String: Any]] = []
                    var totalDurationSec = 0.0
                    
                    // Get duration
                    if asset.statusOfValue(forKey: "duration", error: nil) == .loaded {
                        totalDurationSec = CMTimeGetSeconds(asset.duration)
                    }
                    
                    // Process video variants with segment sampling
                    Task {
                        await self.processVideoVariantsWithSampling(
                            asset: asset,
                            masterUrl: masterUrl,
                            allowedQualities: allowedQualities,
                            totalDurationSec: totalDurationSec,
                            streamType: streamType,
                            headers: headers
                        ) { videoTracks, videoTrackIdentifiers in
                            
                            // Process audio tracks (if separate audio/video)
                            if streamType == .separateAudioVideo {
                                audioTracks = self.processAudioTracks(asset: asset, duration: totalDurationSec)
                            }
                            
                            // Store track identifiers
                            self.storedTrackIdentifiers[masterUrl] = videoTrackIdentifiers
                            
                            let sortedVideoTracks = videoTracks.sorted { (first, second) -> Bool in
                                let firstHeight = first["height"] as? Int ?? 0
                                let secondHeight = second["height"] as? Int ?? 0
                                return firstHeight > secondHeight
                            }
                            
                            print("✅ Final curated qualities: \(videoTrackIdentifiers.keys.sorted().map { "\($0)p" }.joined(separator: ", "))")
                            print("✅ Total: \(sortedVideoTracks.count) video, \(audioTracks.count) audio (\(streamType))")
                            
                            let result: [String: Any] = [
                                "videoTracks": sortedVideoTracks,
                                "audioTracks": audioTracks,
                                "duration": totalDurationSec,
                                "streamType": streamType.description,
                                "allowedQualities": allowedQualities,
                                "availableQualityCount": sortedVideoTracks.count
                            ]
                            
                            DispatchQueue.main.async {
                                resolver(result)
                            }
                        }
                    }
                }
            }
        }
    }
    
    func downloadStream(
        masterUrl: String,
        downloadId: String,
        selectedHeight: Int,
        selectedWidth: Int,
        preferDolbyAtmos: Bool,
        options: NSDictionary?,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        DispatchQueue.main.async {
            
            guard self.isNetworkAvailable else {
                rejecter("NETWORK_UNAVAILABLE", "No network connection available. Please check your internet connection and try again.", nil)
                return
            }
            
            if let existingTask = self.activeDownloads[downloadId] {
                existingTask.cancel()
                self.activeDownloads.removeValue(forKey: downloadId)
            }
                        
            self.checkAndRemoveExistingDownload(downloadId: downloadId)
            
            guard let url = URL(string: masterUrl) else {
                rejecter("INVALID_URL", "Invalid master URL", nil)
                return
            }
            
            let headers = self.extractHeadersFromOptions(options)
            let asset = self.createAsset(from: url, headers: headers)
            
            asset.loadValuesAsynchronously(forKeys: ["variants", "duration"]) {
                DispatchQueue.main.async {
                    var error: NSError?
                    let status = asset.statusOfValue(forKey: "variants", error: &error)
                    
                    guard status == .loaded else {
                        rejecter("PREPARE_ERROR", error?.localizedDescription ?? "Failed to prepare asset", nil)
                        return
                    }
                    
                    // Get stored track identifiers
                    let storedTracks = self.storedTrackIdentifiers[masterUrl]
                    let targetTrack = storedTracks?[selectedHeight]
                    
                    guard let track = targetTrack else {
                        rejecter("TRACK_NOT_FOUND", "Target track not found for \(selectedHeight)p", nil)
                        return
                    }

                    guard track.variant != nil else {
                        rejecter("TRACK_NOT_FOUND", "Track variant not available for \(selectedHeight)p", nil)
                        return
                    }

                    
                    // Detect stream type
                    let streamType = self.detectStreamTypeAdvanced(asset: asset)
                    print("🔍 Stream type for download: \(streamType)")
                    
                    // Configure download options
                    var downloadOptions: [String: Any] = [:]
                    
                    if #available(iOS 14.0, *) {
                        downloadOptions[AVAssetDownloadTaskMinimumRequiredPresentationSizeKey] = CGSize(width: track.width, height: track.height)
                    }
                    
                    // Configure media selections for audio
                    if preferDolbyAtmos {
                        if let audioGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .audible) {
                            for option in audioGroup.options {
                                if self.checkForDolbyAtmos(option: option) {
                                    let mediaSelection = AVMutableMediaSelection()
                                    mediaSelection.select(option, in: audioGroup)
                                    downloadOptions[AVAssetDownloadTaskMediaSelectionKey] = mediaSelection
                                    break
                                }
                            }
                        }
                    }
                    
                    // Create download task
                    self.createDownloadTaskWithRetry(
                        asset: asset,
                        downloadId: downloadId,
                        downloadOptions: downloadOptions,
                        track: track,
                        streamType: streamType,
                        masterUrl: masterUrl,
                        selectedHeight: selectedHeight,
                        selectedWidth: selectedWidth,
                        resolver: resolver,
                        rejecter: rejecter
                    )
                }
            }
        }
    }
    
    private func createDownloadTaskWithRetry(
            asset: AVURLAsset,
            downloadId: String,
            downloadOptions: [String: Any],
            track: TrackIdentifier,
            streamType: StreamType,
            masterUrl: String,
            selectedHeight: Int,
            selectedWidth: Int,
            resolver: @escaping RCTPromiseResolveBlock,
            rejecter: @escaping RCTPromiseRejectBlock
        ) {
            guard let downloadTask = downloadSession?.makeAssetDownloadTask(
                asset: asset,
                assetTitle: downloadId,
                assetArtworkData: nil,
                options: downloadOptions.isEmpty ? nil : downloadOptions
            ) else {
                print("❌ Failed to create download task, recreating session...")
                
                guard let retryTask = downloadSession?.makeAssetDownloadTask(
                    asset: asset,
                    assetTitle: downloadId,
                    assetArtworkData: nil,
                    options: downloadOptions.isEmpty ? nil : downloadOptions
                ) else {
                    print("❌ Failed to create download task after session recreation")
                    rejecter("DOWNLOAD_TASK_FAILED", "Failed to create download task", nil)
                    return
                }
                
                self.startDownloadTask(
                    retryTask,
                    downloadId: downloadId,
                    track: track,
                    streamType: streamType,
                    masterUrl: masterUrl,
                    selectedHeight: selectedHeight,
                    selectedWidth: selectedWidth,
                    resolver: resolver
                )
                return
            }
            
            startDownloadTask(
                downloadTask,
                downloadId: downloadId,
                track: track,
                streamType: streamType,
                masterUrl: masterUrl,
                selectedHeight: selectedHeight,
                selectedWidth: selectedWidth,
                resolver: resolver
            )
        }
        
        private func startDownloadTask(
            _ downloadTask: AVAssetDownloadTask,
            downloadId: String,
            track: TrackIdentifier,
            streamType: StreamType,
            masterUrl: String,
            selectedHeight: Int,
            selectedWidth: Int,
            resolver: @escaping RCTPromiseResolveBlock
        ) {
            
            downloadTask.taskDescription = downloadId
            activeDownloads[downloadId] = downloadTask
            downloadProgress[downloadId] = DownloadProgressInfo()
            
            persistDownloadTask(
                downloadId: downloadId,
                masterUrl: masterUrl,
                selectedHeight: selectedHeight,
                selectedWidth: selectedWidth,
                taskIdentifier: UInt(downloadTask.taskIdentifier)
            )
            
            downloadTask.resume()
            
            guard isJavascriptLoaded else {
                print("⏳ Waiting for JS to load before emitting")
                return
            }
            
            DispatchQueue.main.async {
                self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                    "downloadId": downloadId,
                    "state": "queued",
                    "progress": 0
                ])
            }
            
            print("📥 Download started for \(downloadId)")
            downloadToTrackMapping[downloadId] = track
            
            let response: [String: Any] = [
                "downloadId": downloadId,
                "state": "queued",
                "streamType": streamType.description,
                "expectedSize": formatBytes(track.actualSizeBytes),
                "selectedHeight": selectedHeight,
                "selectedWidth": selectedWidth,
                "bitrate": track.bitrate,
                "uri": masterUrl
            ]
            
            resolver(response)
        }
        
    private func resumeStalledDownloads() {
        DispatchQueue.main.async {
            for (downloadId, downloadTask) in self.activeDownloads {
                if downloadTask.state == .suspended {
                    print("🔄 Resuming stalled download: \(downloadId)")
                    downloadTask.resume()
                }
            }
        }
    }
    
    func pauseDownload(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        guard let downloadTask = activeDownloads[downloadId] else {
            rejecter("DOWNLOAD_NOT_FOUND", "Download not found: \(downloadId)", nil)
            return
        }
        let currentProgress = downloadProgress[downloadId]?.percentage ?? 0.0
        print("📊 Pausing at \(currentProgress)% progress")
        print("⏸️ Pausing download: \(downloadId)")
        
        downloadTask.suspend()
        
        downloadProgress[downloadId]?.state = "stopped"
        
        stopProgressReporting(for: downloadId)
        
        guard isJavascriptLoaded else {
            print("⏳ Waiting for JS to load before emitting")
            return
        }
        
        DispatchQueue.main.async {
            self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                "downloadId": downloadId,
                "progress": Int(currentProgress),
                "state": "stopped"
            ])
        }
        
        print("✅ Download paused: \(downloadId)")
        resolver(["downloadId": downloadId, "status": "stopped"])
    }
    
    func resumeDownload(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        if incompleteDownloads.contains(downloadId) {
            print("⚠️ Download was incomplete - needs to be restarted")
            rejecter("DOWNLOAD_INCOMPLETE", "Download is incomplete and needs to be restarted. Please start a new download.", nil)
            return
        }
        guard let downloadTask = activeDownloads[downloadId] else {
            rejecter("DOWNLOAD_NOT_FOUND", "Download not found: \(downloadId)", nil)
            return
        }
        
        print("▶️ Resuming download: \(downloadId)")
        
        if var progressInfo = downloadProgress[downloadId] {
            let currentProgress = progressInfo.percentage
            print("📊 Resuming from \(currentProgress)% progress")
            
            progressInfo.state = "downloading"
            downloadProgress[downloadId] = progressInfo
        }
        
        downloadTask.resume()
        
        if progressTimers[downloadId] == nil {
            startProgressReporting(for: downloadId)
        }
        
        guard isJavascriptLoaded else {
            print("⏳ Waiting for JS to load before emitting")
            return
        }
        
        let currentProgress = downloadProgress[downloadId]?.percentage ?? 0
        DispatchQueue.main.async {
            self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                "downloadId": downloadId,
                "progress": Int(currentProgress),
                "state": "downloading"
            ])
        }
        
        print("✅ Download resumed: \(downloadId) at \(Int(currentProgress))%")
        resolver(["downloadId": downloadId, "status": "downloading"])
    }
    
    func restartIncompleteDownload(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        print("🔄 Restarting incomplete download: \(downloadId)")
        
        // Check if it's actually incomplete
        guard incompleteDownloads.contains(downloadId) else {
            rejecter("NOT_INCOMPLETE", "Download is not marked as incomplete", nil)
            return
        }
        
        // Get persisted task data
        if let data = mmkv.data(forKey: "download_\(downloadId)"),
           let taskData = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let masterUrl = taskData["masterUrl"] as? String,
           let selectedHeight = taskData["selectedHeight"] as? Int,
           let selectedWidth = taskData["selectedWidth"] as? Int {
            
            print("📋 Found task data for \(downloadId)")
            
            // Remove incomplete marker
            incompleteDownloads.remove(downloadId)
            removePersistedTask(downloadId: downloadId)
            
            // Start fresh download
            downloadStream(
                masterUrl: masterUrl,
                downloadId: downloadId,
                selectedHeight: selectedHeight,
                selectedWidth: selectedWidth,
                preferDolbyAtmos: false,
                options: nil,
                resolver: resolver,
                rejecter: rejecter
            )
        } else {
            rejecter("NO_TASK_DATA", "Cannot restart - original download parameters not found", nil)
        }
    }
    
    func getDownloadState(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        guard let downloadTask = activeDownloads[downloadId] else {
            rejecter("DOWNLOAD_NOT_FOUND", "Download not found: \(downloadId)", nil)
            return
        }
        
        let taskState: String
        switch downloadTask.state {
        case .running:
            taskState = "downloading"
        case .suspended:
            taskState = "stopped"
        case .canceling:
            taskState = "cancelling"
        case .completed:
            taskState = "completed"
        @unknown default:
            taskState = "unknown"
        }
        
        let progressInfo = downloadProgress[downloadId]
        
        let result: [String: Any] = [
            "downloadId": downloadId,
            "state": taskState,
            "progress": progressInfo?.percentage ?? 0.0,
            "downloadedBytes": Double(progressInfo?.downloadedBytes ?? 0),
            "totalBytes": Double(progressInfo?.totalBytes ?? 0)
        ]
        
        resolver(result)
    }
    
    func cancelDownload(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        print("🛑 Attempting to cancel/delete download: \(downloadId)")
        
        UserDefaults.standard.set(true, forKey: "cancelled_\(downloadId)")
        
        // If download is active, cancel it
        if let downloadTask = activeDownloads[downloadId] {
            print("🛑 Cancelling active download task: \(downloadId)")
            downloadTask.cancel()
            activeDownloads.removeValue(forKey: downloadId)
            downloadProgress.removeValue(forKey: downloadId)
            stopProgressReporting(for: downloadId)
        } else {
            print("ℹ️ Download not active (may be completed or not started): \(downloadId)")
        }
        
        removePersistedTask(downloadId: downloadId)
        incompleteDownloads.remove(downloadId)
        
        if offlineRegistry.checkFileExists(downloadId: downloadId).exists {
            offlineRegistry.removeDownload(downloadId: downloadId) { success, error in
                resolver(success)
            }
        } else {
            print("ℹ️ Download cancelled before registration: \(downloadId)")
            resolver(true)
        }
    }
    
    func getAllDownloads(
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        print("📋 Getting all downloads")
        
        let registeredDownloads = offlineRegistry.getAllDownloadedStreams()
        print("📊 Found \(registeredDownloads.count) registered downloads")
        
        var allDownloads: [[String: Any]] = []
        
        // ⭐ Add registered (completed) downloads with STATE
        for download in registeredDownloads {
            if let downloadId = download["downloadId"] as? String {
                var downloadInfo = download
                
                // ⭐ Add state information for completed downloads
                downloadInfo["state"] = "completed"
                downloadInfo["progress"] = 100
                downloadInfo["isCompleted"] = true
                
                print("📱 Including registered download: \(downloadId) (completed)")
                allDownloads.append(downloadInfo)
            }
        }
        
        // Add active downloads
        for (downloadId, progressInfo) in downloadProgress {
            if !allDownloads.contains(where: { ($0["downloadId"] as? String) == downloadId }) {
                allDownloads.append([
                    "downloadId": downloadId,
                    "state": progressInfo.state,
                    "progress": Int(progressInfo.percentage),
                    "bytesDownloaded": progressInfo.downloadedBytes,
                    "totalBytes": progressInfo.totalBytes,
                    "formattedDownloaded": formatBytes(progressInfo.downloadedBytes),
                    "formattedTotal": formatBytes(progressInfo.totalBytes),
                    "isCompleted": false
                ])
                print("📱 Including active download: \(downloadId) (\(progressInfo.state))")
            }
        }
        
        // Add incomplete downloads
        for downloadId in incompleteDownloads {
            if !allDownloads.contains(where: { ($0["downloadId"] as? String) == downloadId }) {
                print("⏸️ Including incomplete download: \(downloadId)")
                allDownloads.append([
                    "downloadId": downloadId,
                    "state": "stopped",
                    "progress": 0,
                    "isCompleted": false,
                    "message": "Paused by iOS. Will resume automatically."
                ])
            }
        }
        
        print("📋 Found \(allDownloads.count) downloads total")
        resolver(allDownloads)
    }

    
    private func getTotalBytes(downloadId: String) -> Int64 {
        if let trackInfo = getStoredTrackInfo(for: downloadId) {
            return trackInfo.actualSizeBytes
        }
        
        if let progressInfo = downloadProgress[downloadId] {
            return progressInfo.totalBytes
        }
        
        return 0
    }
    
    private func getFileSize(at url: URL) -> Int64 {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            return attributes[.size] as? Int64 ?? 0
        } catch {
            print("⚠️ Failed to get file size for \(url): \(error)")
            return 0
        }
    }

    private func taskStateToString(_ state: URLSessionTask.State) -> String {
        switch state {
        case .running:
            return "downloading"
        case .suspended:
            return "stopped"
        case .completed:
            return "completed"
        case .canceling:
            return "cancelled"
        @unknown default:
            return "unknown"
        }
    }
    
    func urlSession(
        _ session: URLSession,
        aggregateAssetDownloadTask: AVAggregateAssetDownloadTask,
        didCompleteDownloadingTo location: URL
    ) {
        let downloadId = findDownloadId(for: aggregateAssetDownloadTask)
        guard !downloadId.isEmpty else { return }
        
        print("✅ Aggregate download completed: \(downloadId) at \(location)")
        
        stopProgressReporting(for: downloadId)

        let actualFileSize = getFileSize(at: location)
        
        // Register with OfflineVideoRegistry
        offlineRegistry.registerDownload(downloadId: downloadId, localUrl: location) { success in
            if success {
                print("✅ Registered aggregate download: \(downloadId)")
                
                guard self.isJavascriptLoaded else {
                    print("⏳ Waiting for JS to load before emitting")
                    return
                }
                // Emit completion event
                DispatchQueue.main.async {
                    self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                        "downloadId": downloadId,
                        "localUri": location.absoluteString,
                        "state": "completed",
                        "progress": 100,
                        "bytesDownloaded": actualFileSize,
                        "isCompleted": true
                    ])
                }
            }
        }
        
        activeDownloads.removeValue(forKey: downloadId)
        downloadProgress.removeValue(forKey: downloadId)
    }
    
    func getStorageInfo(
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let storageStats = offlineRegistry.getStorageStats()
        resolver(storageStats)
    }
    
    func syncDownloadProgress(
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        print("🔄 Syncing download progress...")
        restorePendingDownloads()
        resumePartialDownloads()
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            let activeCount = self.activeDownloads.count
            let incompleteCount = self.incompleteDownloads.count
            let allDownloads = self.offlineRegistry.getAllDownloadedStreams()
            let completedCount = allDownloads.count
            let totalCount = activeCount + completedCount + incompleteCount
            
            let result: [String: Any] = [
                "totalDownloads": totalCount,
                "activeDownloads": activeCount,
                "completedDownloads": completedCount,
                "incompleteDownloads": incompleteCount,
                "restoredDownloads": activeCount,
                "downloads": allDownloads
            ]
            
            resolver(result)
        }
    }
    
    func testOfflinePlayback(
        playbackUrl: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let isCached = offlineRegistry.isContentCached(url: playbackUrl)
        let isDownloaded = offlineRegistry.isContentDownloaded(url: playbackUrl)
            
        let debugInfo: [String: Any] = [
            "url": playbackUrl,
            "dataSourceDetection": isCached,
            "pluginDetection": isDownloaded,
            "registryInitialized": true
        ]
            
        resolver(debugInfo)
    }
    
    // MARK: - Private Helper Methods
    
    private func createAsset(from url: URL, headers: [String: String]?) -> AVURLAsset {
        var options: [String: Any] = [:]
        
        // FIXED: Use AVFoundation's built-in header support instead of custom loader
        if let headers = headers, !headers.isEmpty {
            // Use the official AVURLAssetHTTPHeaderFieldsKey for custom headers
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
            print("📋 Applied \(headers.count) custom headers to AVURLAsset")
        }
        
        return AVURLAsset(url: url, options: options.isEmpty ? nil : options)
    }

    
    private func extractHeadersFromOptions(_ options: NSDictionary?) -> [String: String]? {
        guard let opts = options as? [String: Any],
              let headers = opts["headers"] as? [String: String] else {
            return nil
        }
        return headers
    }
    
    // FIXED: Advanced stream type detection matching Android logic
    private func detectStreamTypeAdvanced(asset: AVURLAsset) -> StreamType {
        // Check for separate audio renditions (like Android's multivariant playlist check)
        let hasAudioRenditions = asset.mediaSelectionGroup(forMediaCharacteristic: .audible) != nil
        
        // Check if variants have video only (no audio attributes)
        let hasVideoOnlyVariants = asset.variants.contains { variant in
            variant.videoAttributes != nil && variant.audioAttributes == nil
        }
        
        // Check if variants have muxed audio+video
        let hasMuxedVariants = asset.variants.contains { variant in
            variant.videoAttributes != nil && variant.audioAttributes != nil
        }
        
        // Android-like logic: separate audio renditions = SEPARATE_AUDIO_VIDEO
        if hasAudioRenditions && hasVideoOnlyVariants {
            print("🔍 Found separate AUDIO renditions + video-only variants - SEPARATE_AUDIO_VIDEO")
            return .separateAudioVideo
        }
        
        // Muxed variants only = MUXED_VIDEO_AUDIO
        if hasMuxedVariants && !hasVideoOnlyVariants {
            print("🔍 Found muxed variants only - MUXED_VIDEO_AUDIO")
            return .muxedVideoAudio
        }
        
        print("🔍 Unknown stream structure - UNKNOWN")
        return .unknown
    }
    
    // FIXED: Process video variants with segment sampling (matching Android)
    private func processVideoVariantsWithSampling(
        asset: AVURLAsset,
        masterUrl: String,
        allowedQualities: [Int],
        totalDurationSec: Double,
        streamType: StreamType,
        headers: [String: String]?,
        completion: @escaping ([[String: Any]], [Int: TrackIdentifier]) -> Void
    ) async {
        var videoTracks: [[String: Any]] = []
        var videoTrackIdentifiers: [Int: TrackIdentifier] = [:]
        
        for variant in asset.variants {
            guard let videoAttributes = variant.videoAttributes else { continue }
            
            let height = Int(videoAttributes.presentationSize.height)
            let width = Int(videoAttributes.presentationSize.width)
            let peakBitRate = variant.peakBitRate ?? 0
            let averageBitRate = variant.averageBitRate ?? 0
            let bitrate = Int(averageBitRate > 0 ? averageBitRate : peakBitRate)

            guard bitrate > 0 else {
                print("⚠️ Skipping variant with invalid bitrate")
                continue
            }
            
            print("🔍 Found video track: \(height)p, \(bitrate) bps")
            
            // Filter by allowed qualities
            if allowedQualities.contains(height) && bitrate > 0 {
                // Check for I-Frame streams (similar to Android logic)
                let expectedMinBitrate = getMinExpectedBitrate(height: height)
                let isProbablyIFrame = bitrate < expectedMinBitrate
                
                if !isProbablyIFrame {
                    // FIXED: Calculate size using segment sampling (like Android)
                    let estimatedSizeBytes = await calculateSizeWithSegmentSampling(
                        variant: variant,
                        masterUrl: masterUrl,
                        duration: totalDurationSec,
                        streamType: streamType,
                        headers: headers
                    )
                    
                    let trackIdentifier = TrackIdentifier(
                        height: height,
                        width: width,
                        bitrate: bitrate,
                        actualSizeBytes: estimatedSizeBytes,
                        variant: variant
                    )
                    
                    videoTrackIdentifiers[height] = trackIdentifier
                    
                    let trackData: [String: Any] = [
                        "height": height,
                        "width": width,
                        "bitrate": bitrate,
                        "size": Double(estimatedSizeBytes),
                        "formattedSize": formatBytes(estimatedSizeBytes),
                        "trackId": "\(height)_\(width)_\(bitrate)",
                        "quality": "\(height)p",
                        "streamType": streamType.description
                    ]
                    
                    videoTracks.append(trackData)
                    
                    let sizeType = streamType == .separateAudioVideo ? "video only" : "video+audio"
                    print("✅ Selected \(height)p: \(formatBytes(estimatedSizeBytes)) (\(sizeType)) [segment sampled]")
                } else {
                    print("🚫 Filtered I-FRAME: \(height)p, \(bitrate) bps")
                }
            } else if !allowedQualities.contains(height) {
                print("⏭️ Skipped \(height)p (not in allowed qualities)")
            }
        }
        
        completion(videoTracks, videoTrackIdentifiers)
    }
    
    // FIXED: Segment sampling for accurate size calculation (matching Android logic)
    private func calculateSizeWithSegmentSampling(
        variant: AVAssetVariant,
        masterUrl: String,
        duration: Double,
        streamType: StreamType,
        headers: [String: String]?
    ) async -> Int64 {
        // First try segment sampling approach (like Android)
        if let sampledSize = await performSegmentSampling(
            variant: variant,
            masterUrl: masterUrl,
            headers: headers
        ) {
            print("📊 Segment sampling successful: \(formatBytes(sampledSize))")
            return sampledSize
        }
        
        // Fallback to bitrate calculation
        print("📊 Falling back to bitrate calculation")
        return calculateAccurateStreamSize(
            variant: variant,
            duration: duration,
            streamType: streamType,
            headers: headers
        )
    }
    
    // FIXED: Implement segment sampling (iOS version of Android logic)
    private func performSegmentSampling(
        variant: AVAssetVariant,
        masterUrl: String,
        headers: [String: String]?
    ) async -> Int64? {
        
        do {
            guard let variantURL = getVariantPlaylistURL(from: variant, masterUrl: masterUrl) else {
                print("⚠️ Could not get variant playlist URL")
                return nil
            }
            
            let sampledBytes = try await sampleSegments(
                playlistURL: variantURL,
                headers: headers,
                sampleCount: 8
            )
            
            return sampledBytes
            
        } catch {
            print("⚠️ Segment sampling failed: \(error)")
            return nil
        }
    }
    
    // MARK: - Segment Sampling Implementation (Based on Android)

    private func getVariantPlaylistURL(from variant: AVAssetVariant, masterUrl: String) -> URL? {
        // Parse master playlist to extract variant URL (iOS equivalent of Android's playlist parsing)
        do {
            let masterURL = URL(string: masterUrl)!
            let masterData = try Data(contentsOf: masterURL)
            let masterContent = String(data: masterData, encoding: .utf8) ?? ""
            
            // Find the variant line that matches our bitrate/resolution
            let lines = masterContent.components(separatedBy: .newlines)
            var foundVariantLine = false
            
            for i in 0..<lines.count {
                let line = lines[i].trimmingCharacters(in: .whitespacesAndNewlines)
                
                if line.hasPrefix("#EXT-X-STREAM-INF:") {
                    // Check if this matches our variant
                    if line.contains("BANDWIDTH=\(variant.peakBitRate ?? 0)") ||
                       line.contains("AVERAGE-BANDWIDTH=\(variant.averageBitRate ?? 0)") {
                        foundVariantLine = true
                        continue
                    }
                }
                
                if foundVariantLine && !line.hasPrefix("#") && !line.isEmpty {
                    // This is the URL for our variant
                    let variantURLString = line.hasPrefix("http") ? line : resolveURL(base: masterUrl, relative: line)
                    return URL(string: variantURLString)
                }
            }
            
        } catch {
            print("⚠️ Failed to parse master playlist: \(error)")
        }
        
        return nil
    }

    private func sampleSegments(
        playlistURL: URL,
        headers: [String: String]?,
        sampleCount: Int
    ) async throws -> Int64 {
        // iOS implementation of Android's segment sampling
        return try await withCheckedThrowingContinuation { continuation in
            Task {
                do {
                    // Download playlist
                    let playlist = try await downloadPlaylist(url: playlistURL, headers: headers)
                    let segments = parseSegmentURLs(playlist: playlist, baseURL: playlistURL.absoluteString)
                    
                    guard !segments.isEmpty else {
                        continuation.resume(returning: 0)
                        return
                    }
                    
                    // Sample distributed segments (like Android's distributeIndices)
                    let sampleIndices = distributeIndices(total: segments.count, sampleCount: sampleCount)
                    var segmentSizes: [Int64] = []
                    
                    // Sample segments in parallel (like Android)
                    await withTaskGroup(of: Int64.self) { group in
                        for index in sampleIndices.prefix(sampleCount) {
                            group.addTask {
                                do {
                                    return try await self.getSegmentSize(url: segments[index], headers: headers)
                                } catch {
                                    print("⚠️ Failed to sample segment \(index): \(error)")
                                    return 0
                                }
                            }
                        }
                        
                        for await size in group {
                            if size > 0 {
                                segmentSizes.append(size)
                            }
                        }
                    }
                    
                    if segmentSizes.isEmpty {
                        continuation.resume(returning: 0)
                        return
                    }
                    
                    // Calculate total size (like Android)
                    let avgSize = segmentSizes.reduce(0, +) / Int64(segmentSizes.count)
                    let totalSize = avgSize * Int64(segments.count)
                    
                    print("🎯 iOS Segment sampling: \(segmentSizes.count)/\(sampleCount) segments sampled")
                    print("🎯 Average: \(formatBytes(avgSize)), Total: \(formatBytes(totalSize))")
                    
                    continuation.resume(returning: totalSize)
                    
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    // MARK: - Helper Methods (iOS versions of Android methods)

    private func downloadPlaylist(url: URL, headers: [String: String]?) async throws -> String {
        var request = URLRequest(url: url)
        request.timeoutInterval = 10.0
        
        // Apply headers
        headers?.forEach { key, value in
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw NSError(domain: "SegmentSampling", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1)"
            ])
        }
        
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func parseSegmentURLs(playlist: String, baseURL: String) -> [String] {
        let lines = playlist.components(separatedBy: .newlines)
        var segments: [String] = []
        
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && !trimmed.hasPrefix("#") {
                let segmentURL = trimmed.hasPrefix("http") ? trimmed : resolveURL(base: baseURL, relative: trimmed)
                segments.append(segmentURL)
            }
        }
        
        return segments
    }

    private func getSegmentSize(url: String, headers: [String: String]?) async throws -> Int64 {
        guard let segmentURL = URL(string: url) else { return 0 }
        
        var request = URLRequest(url: segmentURL)
        request.httpMethod = "HEAD"  // Only get headers, not content
        request.timeoutInterval = 5.0
        
        headers?.forEach { key, value in
            request.setValue(value, forHTTPHeaderField: key)
        }
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else { return 0 }
        
        return httpResponse.expectedContentLength > 0 ? httpResponse.expectedContentLength : 0
    }

    private func distributeIndices(total: Int, sampleCount: Int) -> [Int] {
        guard total > sampleCount else {
            return Array(0..<total)
        }
        
        var indices: [Int] = []
        
        // Strategic sampling (matching Android's 8-segment strategy)
        switch sampleCount {
        case 8:
            indices.append(0)                            // Start
            indices.append(Int(Double(total) * 0.125))   // 12.5%
            indices.append(Int(Double(total) * 0.25))    // 25%
            indices.append(Int(Double(total) * 0.375))   // 37.5%
            indices.append(Int(Double(total) * 0.5))     // 50%
            indices.append(Int(Double(total) * 0.625))   // 62.5%
            indices.append(Int(Double(total) * 0.75))    // 75%
            indices.append(total - 1)                    // End
        default:
            // Even distribution fallback
            let step = Float(total) / Float(sampleCount)
            for i in 0..<sampleCount {
                let index = Int(Float(i) * step).clamped(to: 0...(total-1))
                indices.append(index)
            }
        }
        
        return Array(Set(indices)).sorted() // Remove duplicates and sort
    }

    private func resolveURL(base: String, relative: String) -> String {
        if relative.hasPrefix("http") {
            return relative
        }
        
        guard let baseURL = URL(string: base) else { return relative }
        let baseWithoutFile = baseURL.deletingLastPathComponent().absoluteString
        return baseWithoutFile.hasSuffix("/") ? baseWithoutFile + relative : baseWithoutFile + "/" + relative
    }

    
    private func processAudioTracks(asset: AVURLAsset, duration: Double) -> [[String: Any]] {
        var audioTracks: [[String: Any]] = []
        
        // Process audio tracks from mediaSelectionGroups
        if let audioGroup = asset.mediaSelectionGroup(forMediaCharacteristic: .audible) {
            for option in audioGroup.options {
                let language = option.locale?.languageCode ?? "unknown"
                let channelCount = getChannelCount(for: option)
                let audioBitrate = 128000 // Default audio bitrate
                
                let estimatedSizeBytes = calculateAudioOnlySize(audioBitrate: audioBitrate, duration: duration)
                
                let isDolbyAtmos = checkForDolbyAtmos(option: option)
                let audioType = isDolbyAtmos ? "dolby_atmos" : (channelCount > 2 ? "surround" : "stereo")
                
                let audioTrackData: [String: Any] = [
                    "language": language,
                    "label": option.displayName,
                    "channelCount": channelCount,
                    "audioType": audioType,
                    "isDolbyAtmos": isDolbyAtmos,
                    "size": Double(estimatedSizeBytes),
                    "formattedSize": formatBytes(estimatedSizeBytes)
                ]
                
                audioTracks.append(audioTrackData)
            }
        }
        
        return audioTracks
    }
    
    private func getChannelCount(for option: AVMediaSelectionOption) -> Int {
        let displayName = option.displayName.lowercased()
        let extendedLanguageTag = option.extendedLanguageTag?.lowercased() ?? ""
        
        // Check both display name and language tag
        let combinedText = displayName + " " + extendedLanguageTag
        
        if combinedText.contains("7.1") {
            return 8
        } else if combinedText.contains("5.1") || combinedText.contains("atmos") {
            return 6
        } else if combinedText.contains("surround") {
            return 6
        } else if combinedText.contains("stereo") || combinedText.contains("2.0") {
            return 2
        } else if combinedText.contains("mono") || combinedText.contains("1.0") {
            return 1
        }
        
        // Default to stereo
        return 2
    }

    private func checkForDolbyAtmos(option: AVMediaSelectionOption) -> Bool {
        let displayName = option.displayName.lowercased()
        let extendedLanguageTag = option.extendedLanguageTag?.lowercased() ?? ""
        
        // Check display name for Atmos keywords
        let hasAtmosInName = displayName.contains("atmos") ||
                            displayName.contains("ec-3") ||
                            displayName.contains("eac3") ||
                            displayName.contains("dolby")
        
        // Check extended language tag for codec info
        let hasAtmosInTag = extendedLanguageTag.contains("ec-3") ||
                           extendedLanguageTag.contains("eac3") ||
                           extendedLanguageTag.contains("atmos")
        
        // Check if it's multichannel (Atmos is usually 5.1+ channels)
        let isMultiChannel = displayName.contains("5.1") ||
                            displayName.contains("7.1") ||
                            displayName.contains("surround")
        
        return hasAtmosInName || hasAtmosInTag || (isMultiChannel && displayName.contains("dolby"))
    }

    private func getMinExpectedBitrate(height: Int) -> Int {
        switch height {
        case 144: return 100000
        case 240: return 200000
        case 360: return 400000
        case 480: return 600000
        case 576: return 800000
        case 720: return 1200000
        case 1080: return 2500000
        default: return 100000
        }
    }

    // FIXED: Accurate stream size calculation with fallbacks
    private func calculateAccurateStreamSize(
        variant: AVAssetVariant,
        duration: Double,
        streamType: StreamType,
        headers: [String: String]?
    ) -> Int64 {
        // Safe bitrate handling
        let peakBitrate = variant.peakBitRate ?? 0
        let avgBitrate = variant.averageBitRate ?? 0
        let bitrate = Int64(avgBitrate > 0 ? avgBitrate : peakBitrate)
        
        guard bitrate > 0 && duration > 0 else {
            print("⚠️ Invalid bitrate (\(bitrate)) or duration (\(duration))")
            return estimateSizeFromResolution(variant: variant, duration: duration, streamType: streamType)
        }
        
        // Calculate size based on stream type
        let estimatedSize: Int64
        switch streamType {
        case .separateAudioVideo:
            estimatedSize = calculateVideoOnlySize(videoBitrate: bitrate, duration: duration)
        case .muxedVideoAudio:
            estimatedSize = calculateMuxedStreamSize(combinedBitrate: bitrate, duration: duration)
        case .unknown:
            estimatedSize = calculateVideoOnlySize(videoBitrate: bitrate, duration: duration)
        }
        
        print("📊 Size calculation: \(bitrate) bps × \(duration)s = \(formatBytes(estimatedSize))")
        return estimatedSize
    }
    
    // FIXED: Fallback size estimation based on resolution
    private func estimateSizeFromResolution(variant: AVAssetVariant, duration: Double, streamType: StreamType) -> Int64 {
        guard let videoAttributes = variant.videoAttributes else { return 0 }
        
        let height = Int(videoAttributes.presentationSize.height)
        
        // Estimate bitrate based on resolution (conservative estimates)
        let estimatedBitrate: Int64
        switch height {
        case 480:
            estimatedBitrate = 1_000_000  // 1 Mbps
        case 720:
            estimatedBitrate = 3_000_000  // 3 Mbps
        case 1080:
            estimatedBitrate = 6_000_000  // 6 Mbps
        default:
            estimatedBitrate = Int64(height * 2000) // 2kbps per pixel height
        }
        
        print("📊 Using estimated bitrate for \(height)p: \(estimatedBitrate) bps")
        
        switch streamType {
        case .separateAudioVideo:
            return calculateVideoOnlySize(videoBitrate: estimatedBitrate, duration: duration)
        case .muxedVideoAudio:
            return calculateMuxedStreamSize(combinedBitrate: estimatedBitrate, duration: duration)
        case .unknown:
            return calculateVideoOnlySize(videoBitrate: estimatedBitrate, duration: duration)
        }
    }

    private func calculateVideoOnlySize(videoBitrate: Int64, duration: Double) -> Int64 {
        guard videoBitrate > 0 && duration > 0 else { return 0 }
        
        // Calculate raw size with HLS overhead (segments, playlists, etc.)
        let rawBits = Double(videoBitrate) * duration
        let rawBytes = rawBits / 8.0
        
        // Add realistic HLS overhead (5-10% for segments + metadata)
        let hlsOverhead = 1.02 // 2% overhead
        let estimatedBytes = rawBytes * hlsOverhead
        
        return Int64(estimatedBytes)
    }
    
    private func calculateMuxedStreamSize(combinedBitrate: Int64, duration: Double) -> Int64 {
        guard combinedBitrate > 0 && duration > 0 else { return 0 }
        
        // For muxed streams, bitrate already includes audio
        let rawBits = Double(combinedBitrate) * duration
        let rawBytes = rawBits / 8.0
        
        // Add HLS overhead
        let hlsOverhead = 1.02 // 2% overhead
        let estimatedBytes = rawBytes * hlsOverhead
        
        return Int64(estimatedBytes)
    }
    
    private func calculateAudioOnlySize(audioBitrate: Int, duration: Double) -> Int64 {
        guard audioBitrate > 0 && duration > 0 else { return 0 }
        
        let audioBits = Double(audioBitrate) * duration
        let audioBytes = audioBits / 8.0 * 1.02 // 2% overhead
        
        return Int64(audioBytes)
    }
    
    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useBytes, .useKB, .useMB, .useGB, .useTB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
    
    private func startProgressReporting(for downloadId: String) {
        stopProgressReporting(for: downloadId)
            
        print("📊 Started progress reporting for: \(downloadId)")
            
        let timer = Timer.scheduledTimer(withTimeInterval: progressUpdateInterval, repeats: true) { [weak self] _ in
            guard let self = self,
                let downloadTask = self.activeDownloads[downloadId] else {
                self?.stopProgressReporting(for: downloadId)
                return
            }
                
            if downloadTask.state == .running {
                if let progressInfo = self.downloadProgress[downloadId] {
                        self.emitProgressEvent(
                        downloadId: downloadId,
                        progress: progressInfo.percentage,
                        downloadedBytes: progressInfo.downloadedBytes,
                        state: "downloading"
                    )
                }
            } else {
                self.stopProgressReporting(for: downloadId)
            }
        }
            
            progressTimers[downloadId] = timer
        }
        
        private func stopProgressReporting(for downloadId: String) {
            if let timer = progressTimers[downloadId] {
                timer.invalidate()
                progressTimers.removeValue(forKey: downloadId)
                print("🛑 Stopped progress reporting for: \(downloadId)")
            }
        }
        
        private func emitProgressEvent(downloadId: String, progress: Float, downloadedBytes: Int64, state: String) {
            guard isJavascriptLoaded else {
                print("⏳ Waiting for JS to load before emitting")
                return
            }
            let totalBytes = getTotalBytes(downloadId: downloadId)
            DispatchQueue.main.async {
                if let emitter = self.eventEmitter as? OfflineVideoDownloader {
                    emitter.sendEvent(withName: "DownloadProgress", body: [
                        "downloadId": downloadId,
                        "progress": Int(progress),
                        "bytesDownloaded": downloadedBytes,
                        "formattedDownloaded": self.formatBytes(downloadedBytes),
                        "totalBytes": Int64(totalBytes),
                        "formattedTotal": self.formatBytes(totalBytes),
                        "state": state,
                        "isCompleted": state == "completed"
                    ])
                }
            }
        }
    
    func getOfflinePlaybackUri(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        if let localUrl = offlineRegistry.getLocalUrl(for: downloadId) {
            resolver([
                "uri": localUrl.absoluteString,
                "isOffline": true
            ])
        } else {
            resolver([
                "uri": "",
                "isOffline": false
            ])
        }
    }

    func isDownloaded(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let isDownloaded = offlineRegistry.isContentDownloaded(url: downloadId)
        resolver(isDownloaded)
    }

    func getDownloadStatus(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        // Check if it's a completed download
        if let localUrl = offlineRegistry.getLocalUrl(for: downloadId) {
            resolver([
                "downloadId": downloadId,
                "uri": localUrl.absoluteString,
                "state": "completed",
                "progress": 100,
                "isCompleted": true
            ])
            return
        }
        
        // Check if it's an active download
        if let progressInfo = downloadProgress[downloadId] {
            resolver([
                "downloadId": downloadId,
                "state": progressInfo.state,
                "progress": Int(progressInfo.percentage),
                "bytesDownloaded": progressInfo.downloadedBytes,
                "isCompleted": progressInfo.state == "completed"
            ])
        } else {
            rejecter("DOWNLOAD_NOT_FOUND", "Download not found: \(downloadId)", nil)
        }
    }

    func checkStorageSpace(
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let storageInfo = offlineRegistry.getStorageStats()
        resolver(storageInfo)
    }

    func canDownloadContent(
        estimatedSizeBytes: Int64,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let storageInfo = offlineRegistry.getStorageStats()
        let availableSpace = storageInfo["availableSpace"] as? Double ?? 0
        let canDownload = Double(estimatedSizeBytes) <= availableSpace
        
        resolver([
            "canDownload": canDownload,
            "availableSpaceMB": availableSpace / (1024 * 1024),
            "requiredSpaceMB": Double(estimatedSizeBytes) / (1024 * 1024)
        ])
    }

    func isDownloadCached(
        downloadId: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let isCached = offlineRegistry.isContentDownloaded(url: downloadId)
        resolver(isCached)
    }

    func setPlaybackMode(
        mode: String,
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        let modeValue: Int
        switch mode.lowercased() {
        case "offline":
            modeValue = 1
        case "online":
            modeValue = 0
        default:
            rejecter("INVALID_MODE", "Invalid playback mode: \(mode). Use 'online' or 'offline'", nil)
            return
        }
        
        OfflineVideoPlugin.setPlaybackMode(modeValue)
        
        print("🎯 Playback mode set to: \(mode)")
        
        resolver([
            "mode": mode,
            "status": "success"
        ])
    }

    func getPlaybackMode(
        resolver: @escaping RCTPromiseResolveBlock,
        rejecter: @escaping RCTPromiseRejectBlock
    ) {
        // FIXED: Get mode from OfflineVideoPlugin singleton
        let currentModeValue = OfflineVideoPlugin.getPlaybackMode()
        let currentMode = currentModeValue == 1 ? "offline" : "online"
        
        resolver([
            "mode": currentMode
        ])
    }

    private func checkAndRemoveExistingDownload(downloadId: String) {
        if let existingTask = activeDownloads[downloadId] {
            print("🗑️ Removing existing download entry: \(downloadId)")
            existingTask.cancel()
            activeDownloads.removeValue(forKey: downloadId)
            downloadProgress.removeValue(forKey: downloadId)
        }
    }
    
    private func getDownloadsDirectoryPath() -> String {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return documentsPath.appendingPathComponent("downloads").path
    }
}

// MARK: - Extensions for StreamType
extension StreamType {
    var description: String {
        switch self {
        case .separateAudioVideo:
            return "SEPARATE_AUDIO_VIDEO"
        case .muxedVideoAudio:
            return "MUXED_VIDEO_AUDIO"
        case .unknown:
            return "UNKNOWN"
        }
    }
}

// MARK: - AVAssetDownloadDelegate (FIXED)

extension VideoDownloadManager: AVAssetDownloadDelegate {
    
    func urlSession(
        _ session: URLSession,
        assetDownloadTask: AVAssetDownloadTask,
        didLoad timeRange: CMTimeRange,
        totalTimeRangesLoaded loadedTimeRanges: [NSValue],
        timeRangeExpectedToLoad: CMTimeRange
    ) {
        let downloadId = findDownloadId(for: assetDownloadTask)
        guard !downloadId.isEmpty else { return }
        
        handleProgressUpdate(
            downloadId: downloadId,
            timeRange: timeRange,
            loadedTimeRanges: loadedTimeRanges,
            expectedTimeRange: timeRangeExpectedToLoad
        )
        
        if progressTimers[downloadId] == nil {
            startProgressReporting(for: downloadId)
        }
    }
    
    // Download completion
    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        let downloadId = findDownloadId(for: assetDownloadTask)
        guard !downloadId.isEmpty else { return }
        
        print("📥 Download finished to location: \(location.path)")
            
        let wasCancelled = assetDownloadTask.error != nil ||
            (assetDownloadTask.error as NSError?)?.code == NSURLErrorCancelled ||
            UserDefaults.standard.bool(forKey: "cancelled_\(downloadId)")
            
        if wasCancelled {
            print("⏭️ Download cancelled/failed - saving partial: \(downloadId)")
            UserDefaults.standard.removeObject(forKey: "cancelled_\(downloadId)")
                
            savePartialDownload(downloadId: downloadId, location: location, options: assetDownloadTask.options )
            return
        }
        
        print("✅ Download completed: \(downloadId) at \(location.path)")
        
        let actualFileSize = getFileSize(at: location)
        let totalBytes = getTotalBytes(downloadId: downloadId)
        
        print("📝 Attempting to register download: \(downloadId)")
        
        // Register download SYNCHRONOUSLY
        offlineRegistry.registerDownload(downloadId: downloadId, localUrl: location) { [weak self] success in
            guard let self = self else { return }
            
            if success {
                print("✅ Successfully registered download: \(downloadId)")
                
                self.removePersistedTask(downloadId: downloadId)
                self.removePartialDownload(downloadId: downloadId)
                
                // Update progress info
                self.updateProgressInfo(
                    downloadId: downloadId,
                    progress: 100.0,
                    downloadedBytes: actualFileSize,
                    isCompleted: true
                )
                
                guard isJavascriptLoaded else {
                    print("⏳ Waiting for JS to load before emitting")
                    return
                }
                // Emit completion event
                DispatchQueue.main.async {
                    self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                        "downloadId": downloadId,
                        "localUri": location.absoluteString,
                        "state": "completed",
                        "progress": 100,
                        "bytesDownloaded": actualFileSize,
                        "formattedDownloaded": self.formatBytes(actualFileSize),
                        "totalBytes": totalBytes,
                        "formattedTotal": self.formatBytes(totalBytes),
                        "isCompleted": true
                    ])
                }
                
                stopProgressReporting(for: downloadId)
                self.activeDownloads.removeValue(forKey: downloadId)
                self.downloadProgress.removeValue(forKey: downloadId)
                
            } else {
                print("❌ Failed to register download: \(downloadId)")
            }
        }
    }
    
    // Download error handling
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let downloadId = findDownloadId(for: task)
        guard !downloadId.isEmpty else { return }
        
        if let error = error {
            print("❌ Download error for \(downloadId): \(error.localizedDescription)")
            
            stopProgressReporting(for: downloadId)
            
            guard isJavascriptLoaded else {
                print("⏳ Waiting for JS to load before emitting")
                return
            }
            
            DispatchQueue.main.async {
                self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                    "downloadId": downloadId,
                    "error": error.localizedDescription,
                    "state": "failed"
                ])
            }
            
            activeDownloads.removeValue(forKey: downloadId)
            downloadProgress.removeValue(forKey: downloadId)
        }
    }
    
    func urlSession(_ session: URLSession,
                    aggregateAssetDownloadTask: AVAggregateAssetDownloadTask,
                    didLoad timeRange: CMTimeRange,
                    totalTimeRangesLoaded loadedTimeRanges: [NSValue],
                    timeRangeExpectedToLoad: CMTimeRange,
                    for mediaSelection: AVMediaSelection) {

        guard !findDownloadId(for: aggregateAssetDownloadTask).isEmpty else { return }
        let downloadId = findDownloadId(for: aggregateAssetDownloadTask)
        
        handleProgressUpdate(
            downloadId: downloadId,
            timeRange: timeRange,
            loadedTimeRanges: loadedTimeRanges,
            expectedTimeRange: timeRangeExpectedToLoad
        )
    }
    
    private func handleProgressUpdate(
        downloadId: String,
        timeRange: CMTimeRange,
        loadedTimeRanges: [NSValue],
        expectedTimeRange: CMTimeRange
    ) {
        var percentComplete: Float = 0.0
        let totalDuration = CMTimeGetSeconds(expectedTimeRange.duration)
        
        if totalDuration > 0 {
            for value in loadedTimeRanges {
                let loadedTimeRange: CMTimeRange = value.timeRangeValue
                let loadedDuration = CMTimeGetSeconds(loadedTimeRange.duration)
                percentComplete += Float(loadedDuration / totalDuration)
            }
            percentComplete = min(percentComplete * 100, 100.0)
        }
        
        let estimatedBytes = calculateEstimatedBytes(downloadId: downloadId, progress: percentComplete)
        let totalBytes = getTotalBytes(downloadId: downloadId)
        
        print("📊 Download progress: \(downloadId) = \(Int(percentComplete))%")
        
        updateProgressInfo(downloadId: downloadId, progress: percentComplete, downloadedBytes: estimatedBytes)
        
        guard isJavascriptLoaded else {
            print("⏳ Waiting for JS to load before emitting")
            return
        }
        
        DispatchQueue.main.async {
            self.eventEmitter?.sendEvent(withName: "DownloadProgress", body: [
                "downloadId": downloadId,
                "progress": Int(percentComplete),
                "bytesDownloaded": Int64(estimatedBytes),
                "formattedDownloaded": self.formatBytes(estimatedBytes),
                "totalBytes": Int64(totalBytes),
                "formattedTotal": self.formatBytes(totalBytes),
                "state": percentComplete >= 100.0 ? "completed" : "downloading"
            ])
        }
    }

    private func findDownloadId(for task: URLSessionTask) -> String {
        if let taskDescription = task.taskDescription, !taskDescription.isEmpty {
            return taskDescription
        }
        
        for (downloadId, downloadTask) in activeDownloads {
            if downloadTask.taskIdentifier == task.taskIdentifier {
                return downloadId
            }
        }
        
        if let assetTask = task as? AVAssetDownloadTask {
            let urlString = assetTask.urlAsset.url.absoluteString
            for (downloadId, _) in activeDownloads {
                if urlString.contains(downloadId) {
                    return downloadId
                }
            }
        }
        
        print("⚠️ Could not find downloadId for task: \(task.taskIdentifier)")
        return ""
    }

    
    private func getStoredTrackInfo(for downloadId: String) -> TrackIdentifier? {
        if let trackInfo = downloadToTrackMapping[downloadId] {
            return trackInfo
        }
        
        for (_, trackDict) in storedTrackIdentifiers {
            for (_, track) in trackDict {
                return track
            }
        }
        
        return nil
    }

    
    private func calculateEstimatedBytes(downloadId: String, progress: Float) -> Int64 {
        if let trackInfo = getStoredTrackInfo(for: downloadId) {
            return Int64(Float(trackInfo.actualSizeBytes) * (progress / 100.0))
        }
        
        return Int64(progress * 1024 * 1024)
    }
    
    private func updateProgressInfo(downloadId: String, progress: Float, downloadedBytes: Int64, isCompleted: Bool = false) {
        var progressInfo = downloadProgress[downloadId] ?? DownloadProgressInfo()
        progressInfo.percentage = progress
        progressInfo.downloadedBytes = downloadedBytes
        progressInfo.state = isCompleted ? "completed" : "downloading"
        if progressInfo.totalBytes == 0 {
            progressInfo.totalBytes = getTotalBytes(downloadId: downloadId)
        }
        
        downloadProgress[downloadId] = progressInfo
    }
}
