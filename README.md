# react-native-offline-video-downloader
A comprehensive offline video download utility for React Native applications, providing seamless HLS video downloading and offline playback capabilities using native ExoPlayer for Android and AVPlayer for iOS platforms.

Features
Download HLS (HTTP Live Streaming) videos for offline viewing

Native integration: ExoPlayer on Android and AVPlayer on iOS for efficient playback

Cross-platform support tailored for React Native apps

TypeScript definitions included for better development experience

Handles background downloading and resumable downloads

Configurable download options to optimize storage and quality

Easy-to-use API for integrating offline video functionality

Installation
Install the package via npm or yarn:

bash
npm install react-native-offline-video-downloader
# or
yarn add react-native-offline-video-downloader
Linking Native Modules
For React Native 0.60 and above, auto-linking should handle this automatically.

For older versions, link manually:

bash
react-native link react-native-offline-video-downloader
Usage
Import and use the downloader in your React Native project:

typescript
import OfflineVideoDownloader from 'react-native-offline-video-downloader';

// Start a video download
OfflineVideoDownloader.downloadVideo(hlsUrl, options)
  .then(downloadId => {
    console.log('Download started with ID:', downloadId);
  })
  .catch(error => {
    console.error('Download error:', error);
  });

// Query download status
OfflineVideoDownloader.getDownloadStatus(downloadId)
  .then(status => {
    console.log('Download status:', status);
  });
Refer to the package TypeScript definitions and source code for full API details.

Configuration
Android uses ExoPlayer's native download manager

iOS uses AVAssetDownloadTask with background support

Supports multiple concurrent downloads and resume on app restart

Download quality and storage paths are configurable

Development
Clone the repository:

bash
git clone https://github.com/MdAbubakar/react-native-offline-video-downloader.git
cd react-native-offline-video-downloader
Install dependencies and run tests (if implemented):

bash
npm install
npm test
Contributing
Contributions are welcome! Please open issues or pull requests on the GitHub repo.

License
MIT License © Md Abubakar
