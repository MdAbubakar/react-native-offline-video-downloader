// index.js
import { NativeModules, NativeEventEmitter } from "react-native";

const { OfflineVideoDownloader } = NativeModules;

if (!OfflineVideoDownloader) {
  throw new Error(
    "OfflineVideoDownloader native module is not linked properly. " +
      "Please check your Android linking configuration.",
  );
}

const eventEmitter = new NativeEventEmitter(OfflineVideoDownloader);

export default OfflineVideoDownloader;
export { eventEmitter };
