export interface VideoTrack {
  height: number;
  width: number;
  bitrate: number;
  size: number; // Size in bytes
  formattedSize: string;
  quality: string;
  trackId?: string;
}

export interface AudioTrack {
  language: string;
  label: string;
  channelCount: number;
  audioType:
    | "dolby_atmos"
    | "dolby_digital_plus"
    | "dolby_digital"
    | "surround"
    | "stereo";
  isDolbyAtmos: boolean;
  size: number;
  formattedSize: string;
}

export interface AvailableTracksResult {
  videoTracks: VideoTrack[];
  audioTracks: AudioTrack[];
  duration: number;
}

export interface DownloadOptions {
  headers?: Record<string, string>;
}

export type DownloadState =
  | "queued"
  | "downloading"
  | "completed"
  | "failed"
  | "removing"
  | "restarting"
  | "stopped"
  | "unknown";

export interface DownloadProgressEvent {
  downloadId: string;
  progress: number; // 0-100
  bytesDownloaded: number;
  totalBytes: number;
  state: DownloadState;
  formattedDownloaded: string; // e.g., "125.5 MB"
  formattedTotal: string; // e.g., "1.2 GB"
  isCompleted: boolean;
}

export interface DownloadStatus {
  downloadId: string;
  uri?: string;
  state: DownloadState;
  progress: number;
  bytesDownloaded: number;
  totalBytes: number;
  formattedDownloaded: string;
  formattedTotal: string;
  isCompleted: boolean;
}

export interface DownloadInfo {
  downloadId: string;
  state: DownloadState;
  progress: number;
  bytesDownloaded: number;
  formattedDownloaded: string;
  uri?: string;
}

export interface OfflinePlaybackResult {
  uri: string;
  isOffline: boolean;
}

export interface DownloadResult {
  downloadId: string;
  uri: string;
  state: "queued";
  dolbyAtmosPreferred?: boolean;
  streamKeysCount?: number;
  expectedSize?: number;
}

export interface StorageInfo {
  currentCacheSizeMB: number;
  availableSpaceMB: number;
  totalCacheSizeMB: number;
  currentCacheFormatted: string;
  availableSpaceFormatted: string;
  hasEnoughSpace: boolean;
}

export interface DownloadCapabilityCheck {
  canDownload: boolean;
  availableSpaceMB: number;
  requiredSpaceMB: number;
  availableSpaceFormatted: string;
  requiredSpaceFormatted: string;
}

export type PlaybackMode = "online" | "offline";

export interface PlaybackModeResult {
  mode: PlaybackMode;
  status?: string;
}

export interface PlaybackModeInfo {
  currentMode: PlaybackMode;
  description: string;
  behavior: string;
}

export interface SyncProgressResult {
  totalDownloads: number;
  activeDownloads: number;
  completedDownloads: number;
  incompleteDownloads: number;
  restoredDownloads: number;
  downloads: DownloadInfo[];
}

export interface RestartResult {
  success: boolean;
  downloadId: string;
  state: DownloadState;
  message: string;
}

// Native module interface
export interface NativeOfflineVideoDownloader {
  downloadStream(
    masterUrl: string,
    downloadId: string,
    selectedHeight: number,
    selectedWidth: number,
    preferDolbyAtmos: boolean,
    options?: DownloadOptions
  ): Promise<DownloadResult>;

  getAvailableTracks(
    masterUrl: string,
    options?: DownloadOptions
  ): Promise<AvailableTracksResult>;

  getOfflinePlaybackUri(downloadId: string): Promise<OfflinePlaybackResult>;

  // Download control
  pauseDownload(downloadId: string): Promise<boolean>;
  resumeDownload(downloadId: string): Promise<boolean>;
  cancelDownload(downloadId: string): Promise<boolean>;
  syncDownloadProgress(): Promise<SyncProgressResult>;
  restartIncompleteDownload(downloadId: string): Promise<RestartResult>;

  // Status methods
  isDownloaded(downloadId: string): Promise<boolean>;
  getDownloadStatus(downloadId: string): Promise<DownloadStatus>;
  getAllDownloads(): Promise<DownloadInfo[]>;
  isDownloadCached(downloadId: string): Promise<boolean>;
  getAllCacheKeys(): Promise<number>;
  setPlaybackMode(mode: string): Promise<PlaybackModeResult>;
  getPlaybackMode(): Promise<PlaybackModeResult>;
  checkStorageSpace(): Promise<StorageInfo>;
  canDownloadContent(
    estimatedSizeBytes: number
  ): Promise<DownloadCapabilityCheck>;
}
