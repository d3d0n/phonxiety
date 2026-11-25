// DeviceCheck token helper for phonxiety.
//
// Generated as an attestation source by phonxiety/native/devicecheck.py, then
// compiled on first use with clang.  It resolves DCDevice.currentDevice,
// checks isSupported and calls generateTokenWithCompletionHandler:, reporting
// one JSON object on stdout:
//
//   {"supported":bool,"token_base64":string|null,"error":string|null,
//    "latency_ms":number|null}
//
// The GUI login-session guard mirrors the Codex desktop behaviour: DeviceCheck
// opens an XPC connection to a per-user daemon that only exists in an
// interactive GUI session.  Calling isSupported outside one can abort the
// process with SIGTRAP, before any error can be reported.  A process without
// graphic access therefore reports "unsupported" without touching
// DeviceCheck.framework.

#import <Foundation/Foundation.h>
#import <DeviceCheck/DeviceCheck.h>
#import <Security/Security.h>
#import <mach/mach_time.h>

#include <stdint.h>
#include <stdio.h>

// Security/AuthSession.h names, declared here so the helper does not depend on
// a private SDK header.  callerSecuritySession is UINT32_MAX.
typedef uint32_t PXSecuritySessionId;
extern OSStatus SessionGetInfo(PXSecuritySessionId session,
                               PXSecuritySessionId *sessionId,
                               uint32_t *attributes) __attribute__((weak_import));

#define PX_CALLER_SECURITY_SESSION UINT32_MAX
#define PX_SESSION_HAS_GRAPHIC_ACCESS 0x0010u
#define PX_TOKEN_TIMEOUT_SECONDS 1.0

@interface PXDeviceCheckResult : NSObject
@property(nonatomic, copy) NSString *token;
@property(nonatomic, copy) NSString *error;
@end

@implementation PXDeviceCheckResult
@end

static BOOL PXSessionHasGraphicAccess(void) {
    if (SessionGetInfo == NULL) {
        return NO;
    }
    uint32_t attributes = 0;
    OSStatus status = SessionGetInfo(PX_CALLER_SECURITY_SESSION, NULL, &attributes);
    return status == 0 && (attributes & PX_SESSION_HAS_GRAPHIC_ACCESS) != 0;
}

static void PXEmit(NSDictionary *payload) {
    NSError *jsonError = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:payload options:0 error:&jsonError];
    if (data == nil) {
        fprintf(stdout, "{\"supported\":false,\"token_base64\":null,"
                        "\"error\":\"could not encode helper result\","
                        "\"latency_ms\":null}\n");
        fflush(stdout);
        return;
    }
    fwrite(data.bytes, 1, data.length, stdout);
    fputc('\n', stdout);
    fflush(stdout);
}

static NSDictionary *PXErrorResult(BOOL supported, NSString *error, double latencyMs) {
    return @{
        @"supported": @(supported),
        @"token_base64": [NSNull null],
        @"error": error.length > 0 ? error : @"DeviceCheck returned no token",
        @"latency_ms": @(latencyMs),
    };
}

int main(int argc, const char *argv[]) {
    (void)argc;
    (void)argv;
    @autoreleasepool {
        double startedMs = 0.0;
        mach_timebase_info_data_t timebase;
        if (mach_timebase_info(&timebase) == KERN_SUCCESS && timebase.denom != 0) {
            startedMs = ((double)mach_absolute_time() * timebase.numer / timebase.denom) / 1.0e6;
        }
        #define PX_ELAPSED_MS()                                                  \
            (({                                                                  \
                double now = 0.0;                                                \
                mach_timebase_info_data_t tb;                                    \
                if (mach_timebase_info(&tb) == KERN_SUCCESS && tb.denom != 0) {   \
                    now = ((double)mach_absolute_time() * tb.numer / tb.denom) / 1.0e6; \
                }                                                                \
                now - startedMs;                                                 \
            }))

        if (!PXSessionHasGraphicAccess()) {
            PXEmit(PXErrorResult(NO, @"DeviceCheck unavailable without a GUI login session", PX_ELAPSED_MS()));
            return 0;
        }

        if (@available(macOS 11.0, *)) {
            Class deviceClass = NSClassFromString(@"DCDevice");
            if (deviceClass == Nil) {
                PXEmit(PXErrorResult(NO, @"DeviceCheck framework unavailable", PX_ELAPSED_MS()));
                return 0;
            }
            DCDevice *device = [DCDevice currentDevice];
            if (device == nil) {
                PXEmit(PXErrorResult(NO, @"DeviceCheck currentDevice unavailable", PX_ELAPSED_MS()));
                return 0;
            }
            if (!device.isSupported) {
                PXEmit(PXErrorResult(NO, @"DeviceCheck is not supported on this device", PX_ELAPSED_MS()));
                return 0;
            }

            PXDeviceCheckResult *result = [[PXDeviceCheckResult alloc] init];
            dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
            [device generateTokenWithCompletionHandler:^(NSData *token, NSError *error) {
                if (token != nil) {
                    result.token = [token base64EncodedStringWithOptions:0];
                } else if (error != nil) {
                    result.error = error.localizedDescription;
                } else {
                    result.error = @"DeviceCheck returned no token";
                }
                dispatch_semaphore_signal(semaphore);
            }];

            dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW,
                (int64_t)(PX_TOKEN_TIMEOUT_SECONDS * NSEC_PER_SEC));
            if (dispatch_semaphore_wait(semaphore, deadline) == 0) {
                if (result.token.length > 0) {
                    PXEmit(@{
                        @"supported": @YES,
                        @"token_base64": result.token,
                        @"error": [NSNull null],
                        @"latency_ms": @(PX_ELAPSED_MS()),
                    });
                    return 0;
                }
                PXEmit(PXErrorResult(YES, result.error, PX_ELAPSED_MS()));
                return 0;
            }
            // The completion may still fire on DeviceCheck's queue.  The block
            // retains the result object and semaphore, so this return is safe;
            // the process exits shortly afterwards either way.
            PXEmit(PXErrorResult(YES, @"timed out waiting for DeviceCheck token", PX_ELAPSED_MS()));
            return 0;
        }

        PXEmit(PXErrorResult(NO, @"DeviceCheck requires macOS 11 or newer", PX_ELAPSED_MS()));
        return 0;
        #undef PX_ELAPSED_MS
    }
}
