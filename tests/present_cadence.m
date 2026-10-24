#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include "mcmetal.h"
#include <dlfcn.h>
#include <math.h>
#include <os/lock.h>
#include <stdio.h>

static os_unfair_lock sampleLock = OS_UNFAIR_LOCK_INIT;
static double samples[8192];
static int sampleCount, dropped;

static int compareDouble(const void *a, const void *b) {
	double left = *(const double *)a, right = *(const double *)b;
	return left < right ? -1 : left > right;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 4) {
			fprintf(stderr, "usage: present_cadence library sync output.json\n");
			return 1;
		}
		void *library = dlopen(argv[1], RTLD_LOCAL | RTLD_NOW);
		if (!library) { fprintf(stderr, "%s\n", dlerror()); return 1; }
		Ctx *(*create)(char *, int, char *, int) = dlsym(library, "mc_create");
		void (*configure)(Ctx *, CAMetalLayer *, int, int, int) = dlsym(library, "mc_layer_configure");
		int (*pace)(double) = dlsym(library, "mc_pace");
		void (*paceWindow)(NSWindow *) = dlsym(library, "mc_pace_window");
		void (*paceAdapt)(int) = dlsym(library, "mc_pace_adapt");
		void (*sleepPrecise)(int64_t) = dlsym(library, "mc_sleep_precise");
		id<CAMetalDrawable> (*next)(CAMetalLayer *) = dlsym(library, "mc_layer_next");
		void (*present)(Enc *, id<CAMetalDrawable>, id<MTLTexture>) = dlsym(library, "mc_present");
		if (!create || !configure || !pace || !paceAdapt || !sleepPrecise || !next || !present) return 1;
		char name[256] = {0}, error[4096] = {0};
		Ctx *ctx = create(name, sizeof name, error, sizeof error);
		if (!ctx) { fprintf(stderr, "%s\n", error); return 1; }
		[NSApplication sharedApplication];
		[NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
		NSScreen *screen = NSScreen.mainScreen;
		double hz = screen.maximumFramesPerSecond;
		if (hz <= 0) hz = 60;
		NSRect area = screen.visibleFrame;
		NSPanel *window = [[NSPanel alloc] initWithContentRect:NSMakeRect(NSMaxX(area) - 320, NSMinY(area) + 32, 288, 180)
			styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskNonactivatingPanel backing:NSBackingStoreBuffered defer:NO screen:screen];
		window.title = @"Meshnet pacing check — closes automatically";
		window.level = NSFloatingWindowLevel;
		window.releasedWhenClosed = NO;
		CAMetalLayer *layer = [CAMetalLayer layer];
		window.contentView.wantsLayer = YES;
		window.contentView.layer = layer;
		configure(ctx, layer, 288, 180, atoi(argv[2]) | 3 << 8);
		if (paceWindow) paceWindow(window);
		paceAdapt(1);
		[window orderFront:nil];
		NSString *output = [NSString stringWithUTF8String:argv[3]];
		dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
			@autoreleasepool {
				MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:288 height:180 mipmapped:NO];
				td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
				id<MTLTexture> source = [ctx->device newTextureWithDescriptor:td];
				dispatch_semaphore_t flight = dispatch_semaphore_create(2);
				dispatch_group_t work = dispatch_group_create();
				double start = CACurrentMediaTime(), deadline = start;
				int rendered = 0, submitted = 0;
				while (CACurrentMediaTime() - start < 8) {
					@autoreleasepool {
						deadline += 1.0 / 260;
						double remaining = deadline - CACurrentMediaTime();
						if (remaining > 0) sleepPrecise((int64_t)(remaining * 1e9));
						else if (-remaining > 1.0 / 260) deadline = CACurrentMediaTime();
						dispatch_semaphore_wait(flight, DISPATCH_TIME_FOREVER);
						Enc enc = {0};
						enc.ctx = ctx;
						enc.cmd = [[ctx->queue commandBuffer] retain];
						MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
						pass.colorAttachments[0].texture = source;
						pass.colorAttachments[0].loadAction = MTLLoadActionClear;
						pass.colorAttachments[0].storeAction = MTLStoreActionStore;
						pass.colorAttachments[0].clearColor = MTLClearColorMake(0.08, 0.25 + 0.2 * sin(rendered * 0.04), 0.35, 1);
						id<MTLRenderCommandEncoder> render = [enc.cmd renderCommandEncoderWithDescriptor:pass];
						[render endEncoding];
						rendered++;
						if (pace(0.002)) {
							id<CAMetalDrawable> drawable = next(layer);
							if (drawable) {
								submitted++;
								[drawable addPresentedHandler:^(id<MTLDrawable> shown) {
									double time = shown.presentedTime;
									os_unfair_lock_lock(&sampleLock);
									if (time > start + 1 && time < start + 7.5 && sampleCount < 8192) samples[sampleCount++] = time;
									if (time <= 0) dropped++;
									os_unfair_lock_unlock(&sampleLock);
								}];
								present(&enc, drawable, source);
								[drawable release];
							}
						}
						dispatch_group_enter(work);
						[enc.cmd addCompletedHandler:^(id<MTLCommandBuffer> command) {
							dispatch_semaphore_signal(flight);
							dispatch_group_leave(work);
						}];
						[enc.cmd commit];
						[enc.cmd release];
					}
				}
				dispatch_group_wait(work, DISPATCH_TIME_FOREVER);
				sleepPrecise(300000000);
				os_unfair_lock_lock(&sampleLock);
				qsort(samples, sampleCount, sizeof(double), compareDouble);
				double gaps[8192], total = 0;
				int count = 0, missed = 0;
				for (int i = 1; i < sampleCount; i++) {
					double gap = (samples[i] - samples[i - 1]) * 1000;
					if (gap <= 0) continue;
					gaps[count++] = gap;
					total += gap;
					missed += gap > 1500 / hz;
				}
				qsort(gaps, count, sizeof(double), compareDouble);
				NSDictionary *report = @{@"display_hz": @(hz), @"frames_rendered": @(rendered), @"presents_submitted": @(submitted),
					@"scanout_samples": @(sampleCount), @"dropped_presents": @(dropped), @"long_display_intervals": @(missed),
					@"scanout_fps": @(count > 0 ? count * 1000 / total : 0),
					@"median_interval_ms": @(count > 0 ? gaps[count / 2] : 0),
					@"p99_interval_ms": @(count > 0 ? gaps[(int)((count - 1) * 0.99)] : 0),
					@"scope": @"standalone Metal presentation at a 260 FPS render cap, not Minecraft gameplay"};
				os_unfair_lock_unlock(&sampleLock);
				NSData *json = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil];
				[json writeToFile:output atomically:YES];
				printf("%s\n", [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding].UTF8String);
				dispatch_async(dispatch_get_main_queue(), ^{ [window close]; [NSApp terminate:nil]; });
			}
		});
		[NSApp run];
	}
	return 0;
}
