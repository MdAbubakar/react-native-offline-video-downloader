import AVFoundation
import Foundation

class VideoQualityManager {
    
    func analyzeQualities(for asset: AVURLAsset) -> [[String: Any]] {
        var qualities: [[String: Any]] = []
        
        for variant in asset.variants {
            guard let videoAttributes = variant.videoAttributes else { continue }
            
            let height = Int(videoAttributes.presentationSize.height)
            let width = Int(videoAttributes.presentationSize.width)
            
            // FIXED: Safe unwrapping instead of force unwrap
            let peakBitRate = variant.peakBitRate ?? 0
            let averageBitRate = variant.averageBitRate ?? 0
            let bitrate = Int(averageBitRate > 0 ? averageBitRate : peakBitRate)
            
            // Skip if no valid bitrate
            guard bitrate > 0 else { continue }
            
            let quality: [String: Any] = [
                "height": height,
                "width": width,
                "bitrate": bitrate,
                "averageBitrate": Int(averageBitRate),
                "frameRate": videoAttributes.nominalFrameRate ?? 0,
                "codec": codecTypeToString(videoAttributes.codecTypes.first ?? 0)
            ]
            
            qualities.append(quality)
        }
        
        return qualities
    }
    
    func filterAllowedQualities(_ qualities: [[String: Any]], allowed: [Int]) -> [[String: Any]] {
        return qualities.filter { quality in
            let height = quality["height"] as? Int ?? 0
            return allowed.contains(height)
        }
    }
    
    // ADDED: Helper function to convert CMVideoCodecType to readable string
    private func codecTypeToString(_ codecType: CMVideoCodecType) -> String {
        switch codecType {
        case kCMVideoCodecType_H264:
            return "H.264"
        case kCMVideoCodecType_HEVC:
            return "H.265/HEVC"
        case kCMVideoCodecType_VP9:
            return "VP9"
        case kCMVideoCodecType_AV1:
            return "AV1"
        default:
            // Convert UInt32 to FourCC string
            let chars = [
                Character(UnicodeScalar((codecType >> 24) & 0xFF)!),
                Character(UnicodeScalar((codecType >> 16) & 0xFF)!),
                Character(UnicodeScalar((codecType >> 8) & 0xFF)!),
                Character(UnicodeScalar(codecType & 0xFF)!)
            ]
            return String(chars)
        }
    }
}
