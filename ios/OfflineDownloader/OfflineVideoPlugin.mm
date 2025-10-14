//
//  OfflineVideoPlugin.mm
//  Pods
//
//  Created by EtvWin on 29/09/25.
//

#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(OfflineVideoPlugin, NSObject)

// Static methods (matches Android companion object)
RCT_EXTERN_METHOD(setPlaybackMode:(NSInteger)mode)
RCT_EXTERN_METHOD(getPlaybackMode)
RCT_EXTERN_METHOD(isContentDownloaded:(NSString *)uri)

@end
