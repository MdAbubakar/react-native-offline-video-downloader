// src/OfflineVideoDownloader.ts
import {
  NativeModules,
  DeviceEventEmitter,
  EmitterSubscription,
  NativeEventEmitter,
  Platform,
} from "react-native";
import type {
  DownloadStatus,
  DownloadInfo,
  AvailableTracksResult,
  DownloadProgressEvent,
  DownloadResult,
  DownloadOptions,
  StorageInfo,
  DownloadCapabilityCheck,
  PlaybackMode,
  PlaybackModeResult,
  RestartResult,
  SyncProgressResult,
} from "../types";

const { OfflineVideoDownloader } = NativeModules;

if (!OfflineVideoDownloader) {
  throw new Error(
    "OfflineVideoDownloader native module is not linked properly. " +
      "Please check your Android linking configuration."
  );
}

const eventEmitter =
  Platform.OS === "ios"
    ? new NativeEventEmitter(OfflineVideoDownloader)
    : DeviceEventEmitter;

export class OfflineDownloader {
  private static progressListeners: Map<string, EmitterSubscription> =
    new Map();
  private static globalListeners: EmitterSubscription[] = [];

  // ===== TRACK INFORMATION =====

  /**
   * Get available video and audio tracks with size information
   */
  static async getAvailableTracks(
    masterUrl: string,
    options: DownloadOptions = {}
  ): Promise<AvailableTracksResult> {
    try {
      return await OfflineVideoDownloader.getAvailableTracks(
        masterUrl,
        options
      );
    } catch (error) {
      console.error("Get available tracks failed:", error);
      throw error;
    }
  }

  // ===== STORAGE MANAGEMENT =====

  /**
   *  Check available storage space in 15GB cache
   */
  static async checkStorageSpace(): Promise<StorageInfo> {
    try {
      return await OfflineVideoDownloader.checkStorageSpace();
    } catch (error) {
      console.error("Check storage space failed:", error);
      throw error;
    }
  }

  /**
   *  Check if content can be downloaded based on size
   */
  static async canDownloadContent(
    estimatedSizeBytes: number
  ): Promise<DownloadCapabilityCheck> {
    try {
      return await OfflineVideoDownloader.canDownloadContent(
        estimatedSizeBytes
      );
    } catch (error) {
      console.error("Can download content check failed:", error);
      throw error;
    }
  }

  // ===== DOWNLOAD MANAGEMENT =====

  /**
   * Download video with specific quality and Dolby Atmos preference
   */
  static async downloadStream(
    masterUrl: string,
    downloadId: string,
    selectedHeight: number,
    selectedWidth: number = 1920,
    preferDolbyAtmos: boolean = false,
    options: DownloadOptions = {}
  ): Promise<DownloadResult> {
    try {
      return await OfflineVideoDownloader.downloadStream(
        masterUrl,
        downloadId,
        selectedHeight,
        selectedWidth,
        preferDolbyAtmos,
        options
      );
    } catch (error) {
      console.error("Download video failed:", error);
      throw error;
    }
  }

  // ===== DOWNLOAD CONTROL =====

  /**
   * Pause a specific download
   */
  static async pauseDownload(downloadId: string): Promise<boolean> {
    try {
      return await OfflineVideoDownloader.pauseDownload(downloadId);
    } catch (error) {
      console.error("Pause download failed:", error);
      throw error;
    }
  }

  /**
   * Resume a paused download
   */
  static async resumeDownload(downloadId: string): Promise<boolean> {
    try {
      return await OfflineVideoDownloader.resumeDownload(downloadId);
    } catch (error) {
      console.error("Resume download failed:", error);
      throw error;
    }
  }

  /**
   * Cancel a download
   */
  static async cancelDownload(downloadId: string): Promise<boolean> {
    try {
      return await OfflineVideoDownloader.cancelDownload(downloadId);
    } catch (error) {
      console.error("Cancel download failed:", error);
      throw error;
    }
  }

  /**
   * Delete all downloads with progress tracking
   * Uses existing deleteDownload() - no native changes needed!
   */
  static async deleteAllDownloads(
    onProgress?: (progress: {
      current: number;
      total: number;
      percentage: number;
      currentDownloadId: string;
    }) => void
  ): Promise<{
    success: boolean;
    deletedCount: number;
    failedCount: number;
    errors: Array<{ downloadId: string; error: string }>;
    message: string;
  }> {
    try {
      console.log("🗑️ Deleting ALL downloads...");

      const allDownloads = await this.getAllDownloads();
      const total = allDownloads.length;

      console.log(`📊 Found ${total} downloads to delete`);

      if (total === 0) {
        return {
          success: true,
          deletedCount: 0,
          failedCount: 0,
          errors: [],
          message: "No downloads to delete",
        };
      }

      let deletedCount = 0;
      let failedCount = 0;
      const errors: Array<{ downloadId: string; error: string }> = [];

      for (let i = 0; i < allDownloads.length; i++) {
        const download = allDownloads[i];
        const current = i + 1;
        const percentage = Math.round((current / total) * 100);

        onProgress?.({
          current,
          total,
          percentage,
          currentDownloadId: download.downloadId,
        });

        try {
          await this.cancelDownload(download.downloadId);
          console.log(
            `✅ Deleted (${current}/${total}): ${download.downloadId}`
          );
          deletedCount++;
        } catch (error) {
          console.error(`❌ Failed to delete ${download.downloadId}:`, error);
          failedCount++;
          errors.push({
            downloadId: download.downloadId,
            error: error instanceof Error ? error.message : "Unknown error",
          });
        }
      }

      console.log(
        `✅ Deletion complete: ${deletedCount} deleted, ${failedCount} failed`
      );

      return {
        success: failedCount === 0,
        deletedCount,
        failedCount,
        errors,
        message: `Deleted ${deletedCount} of ${total} downloads`,
      };
    } catch (error) {
      console.error("❌ Failed to delete all downloads:", error);
      throw error;
    }
  }

  // ===== STATUS & PROGRESS =====

  /**
   * Check if content is downloaded
   */
  static async isDownloaded(downloadId: string): Promise<boolean> {
    try {
      return await OfflineVideoDownloader.isDownloaded(downloadId);
    } catch (error) {
      console.error("Check download status failed:", error);
      return false;
    }
  }

  /**
   * Get detailed download status
   */
  static async getDownloadStatus(downloadId: string): Promise<DownloadStatus> {
    try {
      return await OfflineVideoDownloader.getDownloadStatus(downloadId);
    } catch (error) {
      console.error("Get download status failed:", error);
      throw error;
    }
  }

  /**
   * Get all downloads
   */
  static async getAllDownloads(): Promise<DownloadInfo[]> {
    try {
      return await OfflineVideoDownloader.getAllDownloads();
    } catch (error) {
      console.error("Get all downloads failed:", error);
      throw error;
    }
  }

  /**
   * Get offline playback URI for completed downloads
   */
  static async getOfflinePlaybackUri(
    downloadId: string
  ): Promise<string | null> {
    try {
      const result = await OfflineVideoDownloader.getOfflinePlaybackUri(
        downloadId
      );
      return result.uri;
    } catch (error) {
      console.error("Get offline playback URI failed:", error);
      return null;
    }
  }

  /**
   * CRITICAL: Validate that downloaded content is still cached
   * This prevents the network error you experienced after deleting other downloads
   */
  static async isDownloadCached(downloadId: string): Promise<boolean> {
    try {
      const result = await OfflineVideoDownloader.isDownloadCached(downloadId);
      console.log(`🔍 Cache validation for ${downloadId}: ${result}`);
      return result;
    } catch (error) {
      console.error("Cache validation failed:", error);
      return false;
    }
  }

  // ===== DOWNLOAD RESTORATION & SYNC =====

  /**
   * ✅ Sync download progress - restores pending downloads on app start
   * This triggers iOS's restorePendingDownloads() and checkForOrphanedDownloads()
   * Call this when your app starts to restore download state
   */
  static async syncDownloadProgress(): Promise<SyncProgressResult> {
    try {
      console.log("🔄 Syncing download progress with native...");

      if (Platform.OS === "ios") {
        // iOS: Triggers restorePendingDownloads() + resumePartialDownloads() + checkForOrphanedDownloads()
        const result = await OfflineVideoDownloader.syncDownloadProgress();
        console.log(
          `✅ iOS sync complete: ${result.totalDownloads} total, ${result.restoredDownloads} restored`
        );
        return result;
      } else {
        // Android: ExoPlayer auto-restores everything, just get current state
        const allDownloads = await this.getAllDownloads();
        const activeDownloads = allDownloads.filter(
          (d) => d.state === "downloading" || d.state === "queued"
        );
        const completedDownloads = allDownloads.filter(
          (d) => d.state === "completed"
        );

        console.log(
          `✅ Android state: ${allDownloads.length} total (ExoPlayer auto-restored)`
        );

        return {
          totalDownloads: allDownloads.length,
          activeDownloads: activeDownloads.length,
          completedDownloads: completedDownloads.length,
          incompleteDownloads: 0, // Android doesn't have incomplete concept
          restoredDownloads: 0, // Android auto-restores, not counted
          downloads: allDownloads,
        };
      }
    } catch (error) {
      console.error("Sync download progress failed:", error);
      throw error;
    }
  }

  /**
   * ✅ iOS: Restart an incomplete download (downloads interrupted and not auto-resumed)
   * ❌ Android: Not needed (ExoPlayer auto-resumes all downloads)
   */
  static async restartIncompleteDownload(
    downloadId: string
  ): Promise<RestartResult> {
    if (Platform.OS !== "ios") {
      console.log(
        `ℹ️ restartIncompleteDownload() not needed on Android (ExoPlayer auto-resumes)`
      );
      return {
        success: false,
        downloadId,
        state: "unknown",
        message: "Not applicable on Android - ExoPlayer handles auto-resume",
      };
    }

    try {
      console.log(`🔄 Restarting incomplete download: ${downloadId}`);
      const result = await OfflineVideoDownloader.restartIncompleteDownload(
        downloadId
      );
      console.log(`✅ Restart result: ${result.message}`);
      return result;
    } catch (error) {
      console.error("Restart incomplete download failed:", error);
      throw error;
    }
  }

  /**
   * ✅ iOS: Get all incomplete downloads that need to be restarted
   * ✅ Android: Returns empty array (ExoPlayer auto-handles everything)
   */
  static async getIncompleteDownloads(): Promise<DownloadInfo[]> {
    try {
      const allDownloads = await this.getAllDownloads();

      if (Platform.OS === "ios") {
        // iOS: Filter for stopped/failed downloads that need manual restart
        return allDownloads.filter(
          (download) =>
            download.state === "stopped" || download.state === "failed"
        );
      } else {
        // Android: ExoPlayer auto-resumes, no incomplete concept
        return [];
      }
    } catch (error) {
      console.error("Get incomplete downloads failed:", error);
      return [];
    }
  }

  /**
   * ✅ Comprehensive download restoration on app start
   * This is the ONE method you should call when your app starts
   *
   * iOS: Syncs pending downloads, resumes partials, checks for orphans
   * Android: Just returns current state (ExoPlayer does everything automatically)
   */
  static async restoreDownloads(): Promise<{
    synced: SyncProgressResult;
    incomplete: DownloadInfo[];
    needsRestart: number;
  }> {
    try {
      console.log(`🚀 Starting download restoration (${Platform.OS})...`);

      // 1. Sync with native to restore pending downloads
      const synced = await this.syncDownloadProgress();

      // 2. Get incomplete downloads (iOS only)
      const incomplete = await this.getIncompleteDownloads();

      console.log(`📊 Restoration complete:`);
      console.log(`   - Platform: ${Platform.OS}`);
      console.log(`   - Total downloads: ${synced.totalDownloads}`);
      console.log(`   - Active: ${synced.activeDownloads}`);
      console.log(`   - Completed: ${synced.completedDownloads}`);
      console.log(`   - Incomplete: ${incomplete.length}`);
      console.log(`   - Restored: ${synced.restoredDownloads}`);

      return {
        synced,
        incomplete,
        needsRestart: incomplete.length,
      };
    } catch (error) {
      console.error("Restore downloads failed:", error);
      throw error;
    }
  }

  /**
   *  iOS: Auto-restart all incomplete downloads
   *  Android: Not needed (ExoPlayer auto-resumes)
   *
   * Use with caution - this will restart ALL failed/stopped downloads on iOS
   */
  static async autoRestartIncompleteDownloads(): Promise<{
    attempted: number;
    succeeded: number;
    failed: number;
    results: RestartResult[];
  }> {
    if (Platform.OS !== "ios") {
      console.log(`ℹ️ autoRestartIncompleteDownloads() not needed on Android`);
      return {
        attempted: 0,
        succeeded: 0,
        failed: 0,
        results: [],
      };
    }

    try {
      const incomplete = await this.getIncompleteDownloads();

      if (incomplete.length === 0) {
        return {
          attempted: 0,
          succeeded: 0,
          failed: 0,
          results: [],
        };
      }

      console.log(
        `🔄 Auto-restarting ${incomplete.length} incomplete downloads...`
      );

      const results: RestartResult[] = [];
      let succeeded = 0;
      let failed = 0;

      for (const download of incomplete) {
        try {
          const result = await this.restartIncompleteDownload(
            download.downloadId
          );
          results.push(result);

          if (result.success) {
            succeeded++;
          } else {
            failed++;
          }
        } catch (error) {
          failed++;
          results.push({
            success: false,
            downloadId: download.downloadId,
            state: "failed",
            message: error instanceof Error ? error.message : "Unknown error",
          });
        }
      }

      console.log(
        `✅ Auto-restart complete: ${succeeded} succeeded, ${failed} failed`
      );

      return {
        attempted: incomplete.length,
        succeeded,
        failed,
        results,
      };
    } catch (error) {
      console.error("Auto-restart incomplete downloads failed:", error);
      throw error;
    }
  }

  // ===== PLAYBACK MODE CONTROL =====

  /**
   * Set playback mode for downloaded content
   * @param mode - The playback mode to set
   * @returns Promise with the result
   */
  static async setPlaybackMode(
    mode: PlaybackMode
  ): Promise<PlaybackModeResult> {
    try {
      const result = await OfflineVideoDownloader.setPlaybackMode(mode);
      console.log(`🎯 Playback mode set to: ${mode}`);
      return result;
    } catch (error) {
      console.error("Set playback mode failed:", error);
      throw error;
    }
  }

  /**
   * Get current playback mode
   * @returns Promise with current playback mode
   */
  static async getPlaybackMode(): Promise<PlaybackMode> {
    try {
      const result = await OfflineVideoDownloader.getPlaybackMode();
      return result.mode;
    } catch (error) {
      console.error("Get playback mode failed:", error);
      throw error;
    }
  }

  /**
   * Set playback mode to PREFER_OFFLINE (cache first)
   * Always uses cached content if available
   */
  static async setOfflineMode(): Promise<PlaybackModeResult> {
    return this.setPlaybackMode("offline");
  }

  /**
   * Set playback mode to PREFER_ONLINE (online first)
   * Uses online content when network available, cache when offline
   */
  static async setOnlineMode(): Promise<PlaybackModeResult> {
    return this.setPlaybackMode("online");
  }

  // ===== EVENT LISTENERS =====

  /**
   * Listen for download progress updates
   */
  static onDownloadProgress(
    downloadId: string,
    callback: (progress: DownloadProgressEvent) => void
  ): () => void {
    const listener = eventEmitter.addListener(
      "DownloadProgress",
      (event: DownloadProgressEvent) => {
        if (event.downloadId === downloadId) {
          callback(event);
        }
      }
    );

    this.progressListeners.set(downloadId, listener);

    return () => {
      listener.remove();
      this.progressListeners.delete(downloadId);
    };
  }

  /**
   * Listen for all download events globally
   */
  static onAllDownloadEvents(
    callback: (event: DownloadProgressEvent) => void
  ): () => void {
    const listener = eventEmitter.addListener("DownloadProgress", callback);
    this.globalListeners.push(listener);

    return () => {
      listener.remove();
      this.globalListeners = this.globalListeners.filter((l) => l !== listener);
    };
  }

  /**
   * Remove all listeners for a specific download
   */
  static removeListenersForDownload(downloadId: string): void {
    this.progressListeners.get(downloadId)?.remove();
    this.progressListeners.delete(downloadId);
  }

  /**
   * Remove all listeners
   */
  static removeAllListeners(): void {
    this.progressListeners.forEach((listener) => listener.remove());
    this.progressListeners.clear();

    this.globalListeners.forEach((listener) => listener.remove());
    this.globalListeners.length = 0;
  }

  // ===== UTILITY METHODS =====

  /**
   * Format bytes to human readable format
   */
  static formatBytes(bytes: number): string {
    if (bytes === 0) return "0 B";

    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  }

  /**
   * Calculate progress percentage (0-100)
   */
  static calculateProgressPercentage(current: number, total: number): number {
    if (total <= 0) return 0;
    const percentage = (current / total) * 100;
    return Math.min(100, Math.max(0, Math.round(percentage)));
  }
}

export default OfflineDownloader;
export { eventEmitter };
