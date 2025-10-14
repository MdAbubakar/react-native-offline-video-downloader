import Foundation

class DownloadProgressTracker {
    
    private var progressData: [String: ProgressInfo] = [:]
    private let queue = DispatchQueue(label: "com.offlinevideodownloader.progress", attributes: .concurrent)
    
    private struct ProgressInfo {
        var percentage: Float = 0.0
        var downloadedBytes: Int64 = 0
        var totalBytes: Int64 = 0
        var startTime: Date = Date()
        var lastUpdateTime: Date = Date()
        var isCompleted: Bool = false
        var downloadSpeed: Double = 0.0
    }
    
    func startTracking(downloadId: String) {
        queue.async(flags: .barrier) {
            self.progressData[downloadId] = ProgressInfo()
        }
    }
    
    func updateProgress(downloadId: String, progress: Float, downloadedBytes: Int64, totalBytes: Int64) {
        queue.async(flags: .barrier) {
            guard var info = self.progressData[downloadId] else { return }
            
            // Calculate download speed
            let currentTime = Date()
            let timeElapsed = currentTime.timeIntervalSince(info.lastUpdateTime)
            let bytesIncrease = downloadedBytes - info.downloadedBytes
            
            if timeElapsed > 0 && bytesIncrease > 0 {
                info.downloadSpeed = Double(bytesIncrease) / timeElapsed
            }
            
            info.percentage = progress
            info.downloadedBytes = downloadedBytes
            info.totalBytes = totalBytes
            info.lastUpdateTime = currentTime
            
            self.progressData[downloadId] = info
        }
    }
    
    func getProgress(downloadId: String) -> (Float, Int64, Int64, Double)? {
        return queue.sync {
            guard let info = progressData[downloadId] else { return nil }
            return (info.percentage, info.downloadedBytes, info.totalBytes, info.downloadSpeed)
        }
    }
    
    func completeTracking(downloadId: String) {
        queue.async(flags: .barrier) {
            self.progressData[downloadId]?.isCompleted = true
            self.progressData[downloadId]?.percentage = 100.0
        }
    }
    
    func removeTracking(downloadId: String) {
        queue.async(flags: .barrier) {
            self.progressData.removeValue(forKey: downloadId)
        }
    }
    
    func getAllActiveDownloads() -> [String] {
        return queue.sync {
            return Array(progressData.keys)
        }
    }
}
