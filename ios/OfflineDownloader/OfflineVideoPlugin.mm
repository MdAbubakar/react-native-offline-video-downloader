#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(OfflineVideoPlugin, NSObject)

RCT_EXTERN_METHOD(setPlaybackMode:(NSInteger)mode)
RCT_EXTERN_METHOD(getPlaybackMode)
RCT_EXTERN_METHOD(isContentDownloaded:(NSString *)uri)

@end
