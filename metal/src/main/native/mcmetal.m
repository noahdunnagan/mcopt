// Thin C surface over Metal for the Java backend (called through java.lang.foreign).
// Objects cross the boundary as retained pointers; Java owns them and hands them back to mc_release.
// Built without ARC so ownership is explicit. Hot encoder calls allocate nothing, so they need no autorelease pool.
#include <stdatomic.h>
#import "mcmetal.h"
#import <AppKit/AppKit.h>
#import <CoreVideo/CoreVideo.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CABase.h>
#include <mach/mach_time.h>
#include <sys/event.h>
#include <math.h>
#include <os/lock.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

#define FAN_PRIMITIVE 5
#define FAN_MAX_VERTICES 65536  // vanilla fans are 10-18 vertices (sky, sunrise, debug shapes)

static void copyError(NSError *e, char *err, int cap) {
	if (err && cap > 0) strlcpy(err, e ? e.description.UTF8String : "unknown error", cap);
}

static NSString *presentSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V present_vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = p; return v;\n"   // uv.y = 0 at the bottom: row 0 is the scene's bottom row
	"}\n"
	"fragment float4 present_fs(V v [[stage_in]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]]) {\n"
	"  return t.sample(s, v.uv);\n"
	"}\n"
	"struct Clear { float4 color; float depth; };\n"
	"vertex float4 clear_vs(uint id [[vertex_id]], constant Clear &c [[buffer(0)]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  return float4(p * 2.0 - 1.0, c.depth, 1.0);\n"
	"}\n"
	"fragment float4 clear_fs(constant Clear &c [[buffer(0)]]) { return c.color; }\n"
	// Camera-only motion for the temporal upscaler: each pixel's depth, reprojected into last frame. Texture row r holds
	// NDC y = (r + 0.5) / h * 2 - 1 (vertex Y is flipped), so uv and NDC line up without a flip here.
	"struct Reproject { float4x4 m; float2 size; };\n"
	"kernel void motion_cs(texture2d<float, access::read> depth [[texture(0)]], texture2d<half, access::write> motion [[texture(1)]],\n"
	"                      constant Reproject &r [[buffer(0)]], uint2 id [[thread_position_in_grid]]) {\n"
	"  if (id.x >= uint(r.size.x) || id.y >= uint(r.size.y)) return;\n"
	"  float2 uv = (float2(id) + 0.5) / r.size;\n"
	"  float4 prev = r.m * float4(uv * 2.0 - 1.0, depth.read(id).r, 1.0);\n"
	"  float2 prevUv = prev.w > 0.0 ? prev.xy / prev.w * 0.5 + 0.5 : uv;\n"
	"  motion.write(half4(half2(prevUv - uv), 0.0h, 0.0h), id);\n"
	"}\n";

Ctx *mc_create(char *name, int nameCap, char *err, int errCap) {
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		if (!device) { copyError(nil, err, errCap); return NULL; }
		NSError *e = nil;
		id<MTLLibrary> lib = [device newLibraryWithSource:presentSource options:nil error:&e];
		if (!lib) { copyError(e, err, errCap); return NULL; }
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[lib newFunctionWithName:@"present_vs"] autorelease];
		pd.fragmentFunction = [[lib newFunctionWithName:@"present_fs"] autorelease];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
		id<MTLRenderPipelineState> present = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!present) { copyError(e, err, errCap); return NULL; }
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterNearest;
		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionAlways;

		Ctx *ctx = calloc(1, sizeof(Ctx));
		ctx->device = device;
		ctx->queue = [device newCommandQueueWithMaxCommandBufferCount:64];
		ctx->builtins = lib;
		ctx->present = present;
		ctx->presentSampler = [device newSamplerStateWithDescriptor:sd];
		ctx->clearPipelines = [NSMutableDictionary new];
		ctx->depthKeep = [device newDepthStencilStateWithDescriptor:dd];
		dd.depthWriteEnabled = YES;
		ctx->depthWrite = [device newDepthStencilStateWithDescriptor:dd];
		ctx->fanIndices = [device newBufferWithLength:(FAN_MAX_VERTICES - 2) * 3 * sizeof(uint32_t) options:MTLResourceStorageModeShared];
		uint32_t *fan = ctx->fanIndices.contents;
		for (uint32_t k = 0; k < FAN_MAX_VERTICES - 2; k++) {
			fan[k * 3] = 0;
			fan[k * 3 + 1] = k + 1;
			fan[k * 3 + 2] = k + 2;
		}
		ctx->motion = [device newComputePipelineStateWithFunction:[[lib newFunctionWithName:@"motion_cs"] autorelease] error:&e];
		if (!ctx->motion) { copyError(e, err, errCap); return NULL; }
		strlcpy(name, device.name.UTF8String, nameCap);
		return ctx;
	}
}

uint64_t mc_max_buffer_length(Ctx *ctx) { return ctx->device.maxBufferLength; }
static int poolRelease(id obj);
void mc_release(id obj) {
	if (!poolRelease(obj)) [obj release];
}

// ---- resources ----

// ---- opt-in stall diagnostics (-Dmcopt.metal.cbdiag=MS) ----
// Logs every buffer allocation of 1 MiB or more, and every command buffer that the kernel scheduled or the GPU ran for longer
// than MS, that started more than MS after its kernel scheduling ended, or that was committed in a submit with a big (>= 16 MiB)
// allocation. Times are host-clock ms relative to the commit; "at" is wall-clock seconds, to line up with report.json and gc.log.
static int diagOn;
static double diagMs, diagWallMinusHost;
static uint64_t diagBigBytes, diagPreBytes, diagMainBytes;
static long diagSubmit;

void mc_diag_enable(double thresholdMs) {
	struct timeval tv;
	gettimeofday(&tv, NULL);
	diagWallMinusHost = tv.tv_sec + tv.tv_usec * 1e-6 - CACurrentMediaTime();
	diagMs = thresholdMs;
	diagOn = 1;
}

static void diagReport(const char *which, long submit, double commit, uint64_t big, uint64_t copied, id<MTLCommandBuffer> cb) {
	double ks = cb.kernelStartTime, ke = cb.kernelEndTime, gs = cb.GPUStartTime, ge = cb.GPUEndTime;
	if (!big && (ke - ks) * 1e3 < diagMs && (ge - gs) * 1e3 < diagMs && (gs - ke) * 1e3 < diagMs) return;
	fprintf(stderr, "mcopt-metal diag: %s submit %ld at %.6f kernel %+.3f..%+.3f gpu %+.3f..%+.3f ms (gpu %.3f) copied %llu big %llu\n",
		which, submit, commit + diagWallMinusHost, (ks - commit) * 1e3, (ke - commit) * 1e3, (gs - commit) * 1e3, (ge - commit) * 1e3,
		(ge - gs) * 1e3, (unsigned long long) copied, (unsigned long long) big);
}

// ---- provisioned buffers: memory made resident ahead of need, off the render thread ----
// newBufferWithLength only reserves address space. The pages are found, zeroed and wired for the GPU in the submission of the
// first command buffer that uses the buffer, the whole buffer at once, and when free memory is short the kernel first compresses
// other memory to make room, synchronously: for Sodium's fresh 179 MB arena that submission took 36-45 ms on the M4 mini with a
// 4 GiB heap (5 ms with plenty free), a stalled frame. mc_pool_add does that work on a background queue instead, for a buffer
// expected to be asked for later: it touches every page from the CPU, a little at a time so the kernel can reclaim memory for it
// in the background rather than in a burst that leaves other threads waiting for free pages, then adds the buffer to a residency
// set the command queue carries, which keeps it wired until it's released (a buffer wired only for one command buffer is
// unwired after it, and its idle pages get compressed again). mc_pool_take / mc_buffer_new hand it out once it's ready.
#define POOL_MAX 8
#define RESIDENT_MAX 32
static struct { id<MTLBuffer> buffer; int ready; } pool[POOL_MAX];
static id<MTLBuffer> resident[RESIDENT_MAX];  // in residentSet: provisioned buffers, pooled or handed out, until released
static os_unfair_lock poolLock = OS_UNFAIR_LOCK_INIT;  // pool and resident; held only for quick scans
static dispatch_queue_t provisionQueue;  // serial: provisioning, and every change to residentSet
static id<MTLResidencySet> residentSet;

static id<MTLBuffer> poolTake(uint64_t minLength, uint64_t maxLength) {
	id<MTLBuffer> best = nil;
	int at = -1;
	os_unfair_lock_lock(&poolLock);
	for (int i = 0; i < POOL_MAX; i++) {
		id<MTLBuffer> b = pool[i].buffer;
		if (b && pool[i].ready && b.length >= minLength && b.length <= maxLength && (!best || b.length < best.length)) {
			best = b;
			at = i;
		}
	}
	if (at >= 0) pool[at].buffer = nil;
	os_unfair_lock_unlock(&poolLock);
	return best;
}

// Provisions a buffer of size bytes for the pool, starting after delayMs. Returns 0 if the pool is full or already holds two
// buffers of this size (nothing allocated).
int mc_pool_add(Ctx *ctx, uint64_t size, int delayMs) {
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		provisionQueue = dispatch_queue_create("mcopt.provision", dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0));
		@autoreleasepool {
			MTLResidencySetDescriptor *d = [[MTLResidencySetDescriptor new] autorelease];
			d.label = @"mcopt provisioned buffers";
			residentSet = [ctx->device newResidencySetWithDescriptor:d error:nil];
		}
		if (residentSet) {
			[ctx->queue addResidencySet:residentSet];
			[residentSet requestResidency];
		}
	});
	int slot = -1, same = 0;
	os_unfair_lock_lock(&poolLock);
	for (int i = 0; i < POOL_MAX; i++) {
		if (!pool[i].buffer) slot = slot < 0 ? i : slot;
		else if (pool[i].buffer.length == size) same++;
	}
	id<MTLBuffer> b = slot < 0 || same >= 2 ? nil : [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared];
	if (b) {
		pool[slot].buffer = b;
		pool[slot].ready = 0;
	}
	os_unfair_lock_unlock(&poolLock);
	if (!b) return 0;
	[b retain];  // for the block: the pool's reference is handed out once ready, maybe before the block ends
	dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t) delayMs * NSEC_PER_MSEC), provisionQueue, ^{
		double t0 = CACurrentMediaTime();
		volatile uint8_t *p = b.contents;
		for (uint64_t o = 0; o < size; o += 16384) {
			p[o] = 0;
			if ((o & ((2u << 20) - 1)) == (2u << 20) - 16384) usleep(1000);  // 2 MiB, then a breather
		}
		double t1 = CACurrentMediaTime();
		int wired = 0;
		if (residentSet) {
			os_unfair_lock_lock(&poolLock);
			for (int i = 0; i < RESIDENT_MAX && !wired; i++) {
				if (!resident[i]) {
					resident[i] = b;
					wired = 1;
				}
			}
			os_unfair_lock_unlock(&poolLock);
			if (wired) {
				[residentSet addAllocation:b];
				[residentSet commit];
			}
		}
		double t2 = CACurrentMediaTime();
		os_unfair_lock_lock(&poolLock);
		pool[slot].ready = 1;
		os_unfair_lock_unlock(&poolLock);
		if (diagOn) {
			fprintf(stderr, "mcopt-metal diag: provisioned %llu at %.6f touch %.3f ms wire %.3f ms%s\n", (unsigned long long) size,
				t0 + diagWallMinusHost, (t1 - t0) * 1e3, (t2 - t1) * 1e3, wired ? "" : " (not kept resident)");
		}
		[b release];
	});
	return 1;
}

// A ready provisioned buffer of minLength to maxLength bytes (the smallest), or nil. The caller owns it (mc_release as usual).
id<MTLBuffer> mc_pool_take(uint64_t minLength, uint64_t maxLength) {
	id<MTLBuffer> b = poolTake(minLength, maxLength);
	if (b && diagOn) fprintf(stderr, "mcopt-metal diag: took provisioned %llu at %.6f\n", (unsigned long long) b.length, CACurrentMediaTime() + diagWallMinusHost);
	return b;
}

// A provisioned buffer (pooled or handed out) leaves the residency set, which unwires it, before it's released: on the
// provisioning queue, as its commit can take a while. Every release of a buffer handle goes through mc_release, so this
// catches them all. Returns 0 (and does nothing) for anything else.
static int poolRelease(id obj) {
	if (!provisionQueue || !obj) return 0;
	int owned = 0;
	os_unfair_lock_lock(&poolLock);
	for (int i = 0; i < RESIDENT_MAX && !owned; i++) owned = resident[i] == obj;
	os_unfair_lock_unlock(&poolLock);
	if (!owned) return 0;
	dispatch_async(provisionQueue, ^{
		[residentSet removeAllocation:obj];
		[residentSet commit];
		os_unfair_lock_lock(&poolLock);
		for (int i = 0; i < RESIDENT_MAX; i++) {
			if (resident[i] == obj) resident[i] = nil;
		}
		os_unfair_lock_unlock(&poolLock);
		[obj release];
	});
	return 1;
}

uint64_t mc_buffer_length(id<MTLBuffer> buffer) { return buffer.length; }

id<MTLBuffer> mc_buffer_new(Ctx *ctx, uint64_t size) {
	// A provisioned buffer at most a quarter bigger (see mc_pool_add) is ready to use; it holds zeros, like a new one.
	if (size >= (1u << 20)) {
		id<MTLBuffer> b = mc_pool_take(size, size + size / 4);
		if (b) return b;
	}
	// Unified memory: shared storage is both the CPU mapping and what the GPU reads. No staging copies.
	if (!diagOn || size < (1u << 20)) return [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared];
	double t0 = CACurrentMediaTime();
	id<MTLBuffer> b = [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared];
	double t1 = CACurrentMediaTime();
	if (size >= (16u << 20)) __atomic_add_fetch(&diagBigBytes, size, __ATOMIC_RELAXED);
	fprintf(stderr, "mcopt-metal diag: alloc %llu at %.6f took %.3f ms\n", (unsigned long long) size, t0 + diagWallMinusHost, (t1 - t0) * 1e3);
	return b;
}
// A private copy of src's first size bytes, made before returning (a one-off at setup, so it waits). For a small index
// buffer an instanced draw rereads every instance: read from shared memory that runs about half speed in some processes,
// from private memory never (rig22).
id<MTLBuffer> mc_buffer_private(Ctx *ctx, id<MTLBuffer> src, uint64_t size) {
	@autoreleasepool {
		id<MTLBuffer> dst = [ctx->device newBufferWithLength:size options:MTLResourceStorageModePrivate];
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
		[b copyFromBuffer:src sourceOffset:0 toBuffer:dst destinationOffset:0 size:size];
		[b endEncoding];
		[cb commit];
		[cb waitUntilCompleted];
		return dst;
	}
}
void *mc_buffer_contents(id<MTLBuffer> buffer) { return buffer.contents; }
uint64_t mc_buffer_gpu_address(id<MTLBuffer> buffer) { return buffer.gpuAddress; }

id<MTLTexture> mc_texture_new(Ctx *ctx, int format, int width, int height, int layers, int mips, int usage, int cube) {
	@autoreleasepool {
		MTLTextureDescriptor *d = [[MTLTextureDescriptor new] autorelease];
		d.textureType = cube ? MTLTextureTypeCube : MTLTextureType2D;
		d.pixelFormat = (MTLPixelFormat) format;
		d.width = width;
		d.height = height;
		d.mipmapLevelCount = mips;
		d.usage = (MTLTextureUsage) usage;
		d.storageMode = MTLStorageModePrivate;
		(void) layers;  // cubes carry their 6 faces implicitly; arrays aren't used by the game yet
		return [ctx->device newTextureWithDescriptor:d];
	}
}

id<MTLTexture> mc_texture_view(id<MTLTexture> texture, int baseMip, int mips) {
	NSUInteger slices = texture.textureType == MTLTextureTypeCube ? 6 : 1;
	return [texture newTextureViewWithPixelFormat:texture.pixelFormat textureType:texture.textureType
		levels:NSMakeRange(baseMip, mips) slices:NSMakeRange(0, slices)];
}

id<MTLTexture> mc_texture_buffer(Ctx *ctx, id<MTLBuffer> buffer, int format, uint64_t offset, uint64_t length, int texelSize) {
	@autoreleasepool {
		NSUInteger texels = length / texelSize;
		MTLTextureDescriptor *d = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:(MTLPixelFormat) format
			width:texels resourceOptions:MTLResourceStorageModeShared usage:MTLTextureUsageShaderRead];
		return [buffer newTextureWithDescriptor:d offset:offset bytesPerRow:texels * texelSize];
	}
}

uint64_t mc_texture_buffer_alignment(Ctx *ctx, int format) {
	return [ctx->device minimumLinearTextureAlignmentForPixelFormat:(MTLPixelFormat) format];
}

id<MTLSamplerState> mc_sampler_new(Ctx *ctx, int addressU, int addressV, int minFilter, int magFilter, int mipFilter, int anisotropy, float maxLod) {
	@autoreleasepool {
		MTLSamplerDescriptor *d = [[MTLSamplerDescriptor new] autorelease];
		d.sAddressMode = (MTLSamplerAddressMode) addressU;
		d.tAddressMode = (MTLSamplerAddressMode) addressV;
		d.minFilter = (MTLSamplerMinMagFilter) minFilter;
		d.magFilter = (MTLSamplerMinMagFilter) magFilter;
		d.mipFilter = (MTLSamplerMipFilter) mipFilter;
		d.maxAnisotropy = anisotropy;
		d.lodMaxClamp = maxLod;
		return [ctx->device newSamplerStateWithDescriptor:d];
	}
}

// ---- pipelines ----

id<MTLLibrary> mc_library_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) copyError(e, err, errCap);
		return lib;
	}
}

/*
 * desc layout (ints):
 *   [0] vertexBufferCount, then per buffer: bufferIndex, stride, stepRate(0 = per vertex)
 *   [.] attributeCount, then per attribute: location, bufferIndex, offset, vertexFormat
 *   [.] colorCount, then per color: pixelFormat (0 = unused), writeMask, blend, srcRGB, dstRGB, opRGB, srcA, dstA, opA
 *   [.] depthPixelFormat (0 = none)
 *   [.] topologyClass (MTLPrimitiveTopologyClass)
 */
id<MTLRenderPipelineState> mc_pipeline_new(Ctx *ctx, id<MTLLibrary> vlib, const char *vname, id<MTLLibrary> flib, const char *fname,
	const int *desc, char *err, int errCap) {
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[vlib newFunctionWithName:[NSString stringWithUTF8String:vname]] autorelease];
		pd.fragmentFunction = [[flib newFunctionWithName:[NSString stringWithUTF8String:fname]] autorelease];
		MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
		int i = 0;
		int buffers = desc[i++];
		for (int b = 0; b < buffers; b++, i += 3) {
			MTLVertexBufferLayoutDescriptor *l = vd.layouts[desc[i]];
			l.stride = desc[i + 1];
			if (desc[i + 2] > 0) {
				l.stepFunction = MTLVertexStepFunctionPerInstance;
				l.stepRate = desc[i + 2];
			}
		}
		int attributes = desc[i++];
		for (int a = 0; a < attributes; a++, i += 4) {
			MTLVertexAttributeDescriptor *ad = vd.attributes[desc[i]];
			ad.bufferIndex = desc[i + 1];
			ad.offset = desc[i + 2];
			ad.format = (MTLVertexFormat) desc[i + 3];
		}
		if (buffers > 0) pd.vertexDescriptor = vd;
		int colors = desc[i++];
		for (int c = 0; c < colors; c++, i += 9) {
			MTLRenderPipelineColorAttachmentDescriptor *cd = pd.colorAttachments[c];
			cd.pixelFormat = (MTLPixelFormat) desc[i];
			cd.writeMask = (MTLColorWriteMask) desc[i + 1];
			cd.blendingEnabled = desc[i + 2] != 0;
			cd.sourceRGBBlendFactor = (MTLBlendFactor) desc[i + 3];
			cd.destinationRGBBlendFactor = (MTLBlendFactor) desc[i + 4];
			cd.rgbBlendOperation = (MTLBlendOperation) desc[i + 5];
			cd.sourceAlphaBlendFactor = (MTLBlendFactor) desc[i + 6];
			cd.destinationAlphaBlendFactor = (MTLBlendFactor) desc[i + 7];
			cd.alphaBlendOperation = (MTLBlendOperation) desc[i + 8];
		}
		pd.depthAttachmentPixelFormat = (MTLPixelFormat) desc[i++];
		pd.inputPrimitiveTopology = (MTLPrimitiveTopologyClass) desc[i++];
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) copyError(e, err, errCap);
		return pso;
	}
}

id<MTLDepthStencilState> mc_depth_state_new(Ctx *ctx, int compare, int write) {
	@autoreleasepool {
		MTLDepthStencilDescriptor *d = [[MTLDepthStencilDescriptor new] autorelease];
		d.depthCompareFunction = (MTLCompareFunction) compare;
		d.depthWriteEnabled = write != 0;
		return [ctx->device newDepthStencilStateWithDescriptor:d];
	}
}

// ---- command encoding ----

static int cpuAhead;  // opt-in (-Dmcopt.cpu.cmdAhead, mc_cpu_flags bit 1)

Enc *mc_enc_new(Ctx *ctx) {
	Enc *enc = calloc(1, sizeof(Enc));
	enc->ctx = ctx;
	return enc;
}

static id<MTLCommandBuffer> cmd(Enc *enc) {
	if (!enc->cmd && enc->cpuNextPending) {  // opt-in (cmdAhead): the buffer made after the last commit
		dispatch_semaphore_wait(enc->cpuNextReady, DISPATCH_TIME_FOREVER);
		enc->cpuNextPending = 0;
		enc->cmd = enc->cpuNext;
		enc->cpuNext = nil;
	}
	if (!enc->cmd) {
		@autoreleasepool {
			enc->cmd = [[enc->ctx->queue commandBuffer] retain];
		}
	}
	return enc->cmd;
}

static void endBlit(Enc *enc) {
	if (enc->blit) {
		[enc->blit endEncoding];
		[enc->blit release];
		enc->blit = nil;
	}
}

#define DEAD_DEPTH (1u << 8)

static void endRender(Enc *enc) {
	if (!enc->render) return;
	for (int i = 0; i < enc->colorCount; i++) {
		// Memoryless attachments (the native shading pipeline's G-buffer) have nothing to store.
		if (enc->colors[i]) [enc->render setColorStoreAction:(enc->dead >> i & 1) || enc->colors[i].storageMode == MTLStorageModeMemoryless
			? MTLStoreActionDontCare : MTLStoreActionStore atIndex:i];
	}
	if (enc->depth) [enc->render setDepthStoreAction:(enc->dead & DEAD_DEPTH) ? MTLStoreActionDontCare : MTLStoreActionStore];
	[enc->render endEncoding];
	[enc->render release];
	enc->render = nil;
	enc->dead = 0;
}

// The texture's contents are dead (a full clear is pending); if it's attached to the open encoder, don't store it.
void mc_discard(Enc *enc, id<MTLTexture> texture) {
	if (!enc->render) return;
	for (int i = 0; i < enc->colorCount; i++) {
		if (enc->colors[i] == texture) enc->dead |= 1u << i;
	}
	if (enc->depth == texture) enc->dead |= DEAD_DEPTH;
}

static id<MTLBlitCommandEncoder> blit(Enc *enc) {
	if (!enc->blit) {
		endRender(enc);
		@autoreleasepool {
			enc->blit = [[cmd(enc) blitCommandEncoder] retain];
		}
	}
	return enc->blit;
}

id<MTLBlitCommandEncoder> mc_blit(Enc *enc) { return blit(enc); }

// The native shading pipeline (mcshade.m) encodes compute work between passes: it ends whatever is open and gets the
// frame's command buffer.
id<MTLCommandBuffer> mc_frame_cmd(Enc *enc) {
	endBlit(enc);
	endRender(enc);
	return cmd(enc);
}

// ---- the pre command buffer (terrain culling) ----

void mc_pre_end_encoders(Enc *enc) {
	if (enc->preBlit) {
		[enc->preBlit endEncoding];
		[enc->preBlit release];
		enc->preBlit = nil;
	}
	if (enc->preCompute) {
		[enc->preCompute endEncoding];
		[enc->preCompute release];
		enc->preCompute = nil;
	}
}

id<MTLCommandBuffer> mc_pre(Enc *enc) {
	if (!enc->pre) {
		@autoreleasepool {
			enc->pre = [[enc->ctx->queue commandBuffer] retain];
		}
	}
	return enc->pre;
}

id<MTLComputeCommandEncoder> mc_pre_compute(Enc *enc) {
	if (!enc->preCompute) {
		mc_pre_end_encoders(enc);
		@autoreleasepool {
			enc->preCompute = [[mc_pre(enc) computeCommandEncoder] retain];
		}
	}
	return enc->preCompute;
}

// A copy that must land before this frame's culling reads the destination (Sodium's geometry uploads and moves).
static id<MTLBlitCommandEncoder> preBlit(Enc *enc) {
	if (!enc->preBlit) {
		mc_pre_end_encoders(enc);
		@autoreleasepool {
			enc->preBlit = [[mc_pre(enc) blitCommandEncoder] retain];
		}
	}
	return enc->preBlit;
}

void mc_pre_blit_buffer(Enc *enc, id<MTLBuffer> src, uint64_t srcOffset, id<MTLBuffer> dst, uint64_t dstOffset, uint64_t size) {
	if (diagOn) diagPreBytes += size;
	[preBlit(enc) copyFromBuffer:src sourceOffset:srcOffset toBuffer:dst destinationOffset:dstOffset size:size];
}

void mc_pre_fill(Enc *enc, id<MTLBuffer> buffer, uint64_t offset, uint64_t length, int value) {
	[preBlit(enc) fillBuffer:buffer range:NSMakeRange(offset, length) value:(uint8_t) value];
}

static void pqSubmit(void);

// Commits everything recorded so far (the pre command buffer first). Returns the retained main command buffer so Java can
// wait on it (frame pacing, fences); it completes after the pre one.
id<MTLCommandBuffer> mc_enc_commit(Enc *enc) {
	double t = 0;
	long n = 0;
	uint64_t big = 0, preBytes = 0, mainBytes = 0;
	if (diagOn) {
		t = CACurrentMediaTime();
		n = diagSubmit++;
		big = __atomic_exchange_n(&diagBigBytes, 0, __ATOMIC_RELAXED);
		preBytes = diagPreBytes;
		mainBytes = diagMainBytes;
		diagPreBytes = diagMainBytes = 0;
	}
	if (enc->pre) {
		mc_pre_end_encoders(enc);
		if (diagOn) [enc->pre addCompletedHandler:^(id<MTLCommandBuffer> cb) { diagReport("pre", n, t, big, preBytes, cb); }];
		[enc->pre commit];
		[enc->pre release];
		enc->pre = nil;
	}
	endBlit(enc);
	endRender(enc);
	id<MTLCommandBuffer> c = cmd(enc);
	if (diagOn) [c addCompletedHandler:^(id<MTLCommandBuffer> cb) { diagReport("main", n, t, big, mainBytes, cb); }];
	[c commit];
	enc->cmd = nil;
	if (cpuAhead && !enc->cpuNextPending) {  // opt-in: queue order is commit order, not creation order
		if (!enc->cpuNextReady) enc->cpuNextReady = dispatch_semaphore_create(0);
		enc->cpuNextPending = 1;
		id<MTLCommandQueue> queue = enc->ctx->queue;
		dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
			@autoreleasepool {
				enc->cpuNext = [[queue commandBuffer] retain];
			}
			dispatch_semaphore_signal(enc->cpuNextReady);
		});
	}
	pqSubmit();  // -Dmcopt.metal.presentQueue: the present this frame encoded (nothing without the flag)
	return c;
}

void mc_cmd_wait(id<MTLCommandBuffer> c) { [c waitUntilCompleted]; }
int mc_cmd_done(id<MTLCommandBuffer> c) { return c.status >= MTLCommandBufferStatusCompleted; }
// Nothing recorded since the last commit: no command buffer open (-Dmcopt.cpu.fence).
int mc_enc_empty(Enc *enc) { return enc->cmd == nil && enc->pre == nil; }
double mc_cmd_gpu_us(id<MTLCommandBuffer> c) { return (c.GPUEndTime - c.GPUStartTime) * 1e6; }
double mc_cmd_gpu_end(id<MTLCommandBuffer> c) { return c.GPUEndTime; }
double mc_cmd_gpu_start(id<MTLCommandBuffer> c) { return c.GPUStartTime; }
double mc_host_seconds(void) { return CACurrentMediaTime(); }
// Opt-in cadence probe. Fixed storage; callbacks never enter Java. Callback time is NOT scanout time.
#define CADENCE_CAPACITY 1048576
static uint64_t cadenceHandler[CADENCE_CAPACITY], cadencePresented[CADENCE_CAPACITY];
void mc_cadence_present(id<CAMetalDrawable> drawable, int slot) {
	if (slot < 0 || slot >= CADENCE_CAPACITY) return;
	[drawable addPresentedHandler:^(id<MTLDrawable> d) {
		__atomic_store_n(&cadencePresented[slot], (uint64_t)(d.presentedTime * 1e9), __ATOMIC_RELEASE);
		__atomic_store_n(&cadenceHandler[slot], (uint64_t)(CACurrentMediaTime() * 1e9), __ATOMIC_RELEASE);
	}];
}
uint64_t mc_cadence_handler(int slot) { return __atomic_load_n(&cadenceHandler[slot], __ATOMIC_ACQUIRE); }
uint64_t mc_cadence_presented(int slot) { return __atomic_load_n(&cadencePresented[slot], __ATOMIC_ACQUIRE); }


// ---- profiling: GPU timestamps at every render encoder's stage boundaries, for one submit at a time ----

static MTLTimestamp profileCpu0, profileGpu0;

int mc_profile_begin(Enc *enc, int maxEncoders) {
	id<MTLDevice> device = enc->ctx->device;
	if (![device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return 0;
	@autoreleasepool {
		for (id<MTLCounterSet> set in device.counterSets) {
			if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
			MTLCounterSampleBufferDescriptor *d = [[MTLCounterSampleBufferDescriptor new] autorelease];
			d.counterSet = set;
			d.storageMode = MTLStorageModeShared;
			d.sampleCount = maxEncoders * 4;
			enc->samples = [device newCounterSampleBufferWithDescriptor:d error:nil];
			enc->sampleCount = 0;
			[device sampleTimestamps:&profileCpu0 gpuTimestamp:&profileGpu0];
			return enc->samples != nil;
		}
	}
	return 0;
}

// Detaches the profiled submit's samples; call just before its commit, read them with mc_profile_read after it completes.
id<MTLCounterSampleBuffer> mc_profile_end(Enc *enc, int *count) {
	id<MTLCounterSampleBuffer> s = enc->samples;
	*count = enc->sampleCount;
	enc->samples = nil;
	return s;
}

// out: per encoder {vertex start, vertex end, fragment start, fragment end} in µs from the first sample (-1 = stage didn't run).
void mc_profile_read(Ctx *ctx, id<MTLCounterSampleBuffer> s, int count, double *out) {
	MTLTimestamp cpu1, gpu1;
	[ctx->device sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
	// Timestamps are in GPU ticks; the CPU side of the calibration pair is in nanoseconds.
	double usPerTick = (double) (cpu1 - profileCpu0) / (double) (gpu1 - profileGpu0) / 1000.0;
	@autoreleasepool {
		const MTLCounterResultTimestamp *ts = [s resolveCounterRange:NSMakeRange(0, count)].bytes;
		uint64_t origin = UINT64_MAX;
		for (int i = 0; i < count; i++) {
			if (ts[i].timestamp != MTLCounterErrorValue && ts[i].timestamp != 0 && ts[i].timestamp < origin) origin = ts[i].timestamp;
		}
		for (int i = 0; i < count; i++) {
			out[i] = ts[i].timestamp == MTLCounterErrorValue || ts[i].timestamp == 0 ? -1 : (double) (ts[i].timestamp - origin) * usPerTick;
		}
	}
	[s release];
}

void mc_blit_buffer(Enc *enc, id<MTLBuffer> src, uint64_t srcOffset, id<MTLBuffer> dst, uint64_t dstOffset, uint64_t size) {
	if (diagOn) diagMainBytes += size;
	[blit(enc) copyFromBuffer:src sourceOffset:srcOffset toBuffer:dst destinationOffset:dstOffset size:size];
}

void mc_blit_fill(Enc *enc, id<MTLBuffer> buffer, uint64_t offset, uint64_t length, int value) {
	[blit(enc) fillBuffer:buffer range:NSMakeRange(offset, length) value:(uint8_t) value];
}

void mc_blit_buffer_to_texture(Enc *enc, id<MTLBuffer> src, uint64_t offset, int bytesPerRow, int bytesPerImage,
	id<MTLTexture> dst, int slice, int mip, int x, int y, int w, int h) {
	[blit(enc) copyFromBuffer:src sourceOffset:offset sourceBytesPerRow:bytesPerRow sourceBytesPerImage:bytesPerImage
		sourceSize:MTLSizeMake(w, h, 1) toTexture:dst destinationSlice:slice destinationLevel:mip destinationOrigin:MTLOriginMake(x, y, 0)];
}

void mc_blit_texture_to_buffer(Enc *enc, id<MTLTexture> src, int mip, int x, int y, int w, int h,
	id<MTLBuffer> dst, uint64_t offset, int bytesPerRow) {
	[blit(enc) copyFromTexture:src sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(x, y, 0) sourceSize:MTLSizeMake(w, h, 1)
		toBuffer:dst destinationOffset:offset destinationBytesPerRow:bytesPerRow destinationBytesPerImage:(NSUInteger) bytesPerRow * h];
}

void mc_blit_texture_to_texture(Enc *enc, id<MTLTexture> src, id<MTLTexture> dst, int mip, int dx, int dy, int sx, int sy, int w, int h) {
	[blit(enc) copyFromTexture:src sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(sx, sy, 0) sourceSize:MTLSizeMake(w, h, 1)
		toTexture:dst destinationSlice:0 destinationLevel:mip destinationOrigin:MTLOriginMake(dx, dy, 0)];
}

static int sameTargets(Enc *enc, int count, id<MTLTexture> const *colors, const float *clears, id<MTLTexture> depth) {
	if (!enc->render || count != enc->colorCount || depth != enc->depth) return 0;
	for (int i = 0; i < count; i++) {
		if (colors[i] != enc->colors[i] || clears[i * 5] != 0) return 0;
	}
	return 1;
}

// opt-in (-Dmcopt.cpu.pass, set by mc_cpu_flags at device creation; off: everything below runs as before).
// bit 0: foldDepthClear finds its pipeline by the attachments' pixel formats in a small table before building the string key
// (the pipeline is the same object: the table only remembers what clearPipelines returned, and clearPipelines never drops an
// entry); mc_render_begin fills one kept MTLRenderPassDescriptor, every field it may hold reset, instead of a new one per pass
// (Metal copies the descriptor when it makes the encoder).
static int cpuPass;

void mc_cpu_flags(int flags) {
	cpuPass = flags & 1;
	cpuAhead = flags >> 1 & 1;
}

#define FOLD_SLOTS 16
static struct {
	Ctx *ctx;
	MTLPixelFormat depth;
	int count;
	MTLPixelFormat colors[MAX_COLORS];
	id<MTLRenderPipelineState> pso;  // not retained: owned by ctx->clearPipelines, which keeps every entry
} foldSlots[FOLD_SLOTS];
static int foldUsed;

static id<MTLRenderPipelineState> foldFind(Enc *enc) {
	MTLPixelFormat depth = enc->depth.pixelFormat;
	for (int s = 0; s < foldUsed; s++) {
		if (foldSlots[s].ctx != enc->ctx || foldSlots[s].depth != depth || foldSlots[s].count != enc->colorCount) continue;
		int i = 0;
		for (; i < enc->colorCount; i++) {
			if (foldSlots[s].colors[i] != (enc->colors[i] ? enc->colors[i].pixelFormat : MTLPixelFormatInvalid)) break;
		}
		if (i == enc->colorCount) return foldSlots[s].pso;
	}
	return nil;
}

static void foldKeep(Enc *enc, id<MTLRenderPipelineState> pso) {
	if (!pso || foldUsed == FOLD_SLOTS) return;
	foldSlots[foldUsed].ctx = enc->ctx;
	foldSlots[foldUsed].depth = enc->depth.pixelFormat;
	foldSlots[foldUsed].count = enc->colorCount;
	for (int i = 0; i < enc->colorCount; i++) foldSlots[foldUsed].colors[i] = enc->colors[i] ? enc->colors[i].pixelFormat : MTLPixelFormatInvalid;
	foldSlots[foldUsed].pso = pso;
	foldUsed++;
}

// Clears the open encoder's depth attachment in place with a depth-only full-screen triangle: cheaper than ending the encoder,
// which would store every attachment and load them all back for the next pass.
static void foldDepthClear(Enc *enc, float depthValue, int width, int height) {
	Ctx *ctx = enc->ctx;
	@autoreleasepool {
		id<MTLRenderPipelineState> pso = cpuPass ? foldFind(enc) : nil;  // opt-in
		if (!pso) {
		NSMutableString *key = [NSMutableString stringWithFormat:@"fold %lu", (unsigned long) enc->depth.pixelFormat];
		for (int i = 0; i < enc->colorCount; i++) [key appendFormat:@" %lu", (unsigned long) (enc->colors[i] ? enc->colors[i].pixelFormat : 0)];
		pso = ctx->clearPipelines[(id) key];
		if (!pso) {
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.vertexFunction = [[ctx->builtins newFunctionWithName:@"clear_vs"] autorelease];
			for (int i = 0; i < enc->colorCount; i++) {
				if (!enc->colors[i]) continue;
				pd.colorAttachments[i].pixelFormat = enc->colors[i].pixelFormat;
				pd.colorAttachments[i].writeMask = MTLColorWriteMaskNone;
			}
			pd.depthAttachmentPixelFormat = enc->depth.pixelFormat;
			pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:nil];
			ctx->clearPipelines[(id) key] = pso;
			[pso release];
		}
		if (cpuPass) foldKeep(enc, pso);
		}
		struct { float color[4]; float depth; float pad[3]; } c = {{0}, depthValue, {0}};
		[enc->render setRenderPipelineState:pso];
		[enc->render setDepthStencilState:ctx->depthWrite];
		[enc->render setCullMode:MTLCullModeNone];
		[enc->render setTriangleFillMode:MTLTriangleFillModeFill];
		[enc->render setDepthBias:0 slopeScale:0 clamp:0];
		[enc->render setScissorRect:(MTLScissorRect) {0, 0, width, height}];
		[enc->render setVertexBytes:&c length:sizeof c atIndex:0];
		[enc->render drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	}
	enc->dead &= ~DEAD_DEPTH;
}

// Opens `render` on rp (with the profiling timestamps, while profiling) at enc's viewport size.
static void openRender(Enc *enc, MTLRenderPassDescriptor *rp) {
	if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
		MTLRenderPassSampleBufferAttachmentDescriptor *s = rp.sampleBufferAttachments[0];
		s.sampleBuffer = enc->samples;
		s.startOfVertexSampleIndex = enc->sampleCount;
		s.endOfVertexSampleIndex = enc->sampleCount + 1;
		s.startOfFragmentSampleIndex = enc->sampleCount + 2;
		s.endOfFragmentSampleIndex = enc->sampleCount + 3;
		enc->sampleCount += 4;
	}
	enc->render = [[cmd(enc) renderCommandEncoderWithDescriptor:rp] retain];
	enc->renderSerial++;
	[enc->render setViewport:(MTLViewport) {0, 0, enc->width, enc->height, 0, 1}];
	[enc->render setFrontFacingWinding:MTLWindingClockwise];  // vertex Y is flipped to keep GL/Vulkan row order, which mirrors winding too
}

// opt-in (cpuPass): enc's kept pass descriptor with every field mc_render_begin or openRender may have set on it
// before back at its default, except the attachments this pass sets anyway (colors it names, depth when it has one).
static MTLRenderPassDescriptor *keptPass(Enc *enc, int count, id<MTLTexture> const *colors, id<MTLTexture> depth) {
	MTLRenderPassDescriptor *rp = enc->cpuPassDesc;
	if (!rp) rp = enc->cpuPassDesc = [[MTLRenderPassDescriptor alloc] init];
	int was = enc->cpuPassColors;
	for (int i = 0; i < was; i++) {
		if (i < count && colors[i]) continue;
		MTLRenderPassColorAttachmentDescriptor *c = rp.colorAttachments[i];
		c.texture = nil;
		c.loadAction = MTLLoadActionDontCare;
		c.storeAction = MTLStoreActionDontCare;
		c.clearColor = MTLClearColorMake(0, 0, 0, 1);
	}
	enc->cpuPassColors = count;
	if (!depth) {
		MTLRenderPassDepthAttachmentDescriptor *d = rp.depthAttachment;
		d.texture = nil;
		d.loadAction = MTLLoadActionDontCare;
		d.storeAction = MTLStoreActionDontCare;
		d.clearDepth = 1.0;
	}
	rp.renderTargetWidth = 0;
	rp.renderTargetHeight = 0;
	rp.defaultRasterSampleCount = 0;
	if (enc->cpuPassSampled) {
		MTLRenderPassSampleBufferAttachmentDescriptor *sb = rp.sampleBufferAttachments[0];
		sb.sampleBuffer = nil;
		sb.startOfVertexSampleIndex = sb.endOfVertexSampleIndex = MTLCounterDontSample;
		sb.startOfFragmentSampleIndex = sb.endOfFragmentSampleIndex = MTLCounterDontSample;
		enc->cpuPassSampled = 0;
	}
	return rp;
}

/*
 * Starts a render pass. colors: `count` texture pointers (NULL = unused slot); clears: per color {doClear, r, g, b, a} as floats.
 * Region clears are expressed as a pass that loads, so partial clears go through mc_r_clear_rect.
 * Returns 0 when it opened a new encoder, 1 when the pass continues the open one (same attachments, nothing to clear),
 * 2 when it continues it after clearing depth in place.
 */
int mc_render_begin(Enc *enc, int count, id<MTLTexture> const *colors, const float *clears,
	id<MTLTexture> depth, int clearDepth, float depthValue, int width, int height) {
	endBlit(enc);
	if (sameTargets(enc, count, colors, clears, depth)) {
		if (!clearDepth) return 1;
		foldDepthClear(enc, depthValue, width, height);
		return 2;
	}
	endRender(enc);
	enc->colorCount = count;
	memcpy(enc->colors, colors, sizeof(id) * count);
	enc->depth = depth;
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = cpuPass ? keptPass(enc, count, colors, depth) : [MTLRenderPassDescriptor renderPassDescriptor];  // opt-in
		for (int i = 0; i < count; i++) {
			if (!colors[i]) continue;
			MTLRenderPassColorAttachmentDescriptor *c = rp.colorAttachments[i];
			c.texture = colors[i];
			const float *cl = clears + i * 5;
			// cl[0]: 0 load, 1 clear, 2 don't care (the pass overwrites every pixel; mcopt.metal.pack's full-screen passes).
			c.loadAction = cl[0] == 2 ? MTLLoadActionDontCare : cl[0] != 0 ? MTLLoadActionClear : MTLLoadActionLoad;
			if (c.loadAction == MTLLoadActionLoad && colors[i].storageMode == MTLStorageModeMemoryless) c.loadAction = MTLLoadActionDontCare;
			c.clearColor = MTLClearColorMake(cl[1], cl[2], cl[3], cl[4]);
			c.storeAction = MTLStoreActionUnknown;  // decided in endRender
		}
		if (depth) {
			rp.depthAttachment.texture = depth;
			rp.depthAttachment.loadAction = clearDepth ? MTLLoadActionClear : MTLLoadActionLoad;
			rp.depthAttachment.clearDepth = depthValue;
			rp.depthAttachment.storeAction = MTLStoreActionUnknown;
		}
		if (count == 0 && !depth) {
			rp.renderTargetWidth = width;
			rp.renderTargetHeight = height;
			rp.defaultRasterSampleCount = 1;
		}
		enc->width = width;
		enc->height = height;
		openRender(enc, rp);
		if (cpuPass && enc->samples) enc->cpuPassSampled = 1;
	}
	return 0;
}

// Terrain occlusion (mcterrain.m) only: ends the open render encoder with every live attachment stored and returns a
// concurrent compute encoder (dispatches overlap unless a barrier orders them) for work that reads them; mc_render_resume
// then reopens the pass on the same attachments with their contents loaded back. The reopened encoder has no state but the
// viewport and winding: the caller rebinds the rest.
id<MTLComputeCommandEncoder> mc_render_suspend(Enc *enc) {
	endRender(enc);
	endBlit(enc);  // MetalTerrain's held arena copies, recorded just before
	@autoreleasepool {
		return [[cmd(enc) computeCommandEncoderWithDispatchType:MTLDispatchTypeConcurrent] retain];
	}
}

void mc_render_resume(Enc *enc, id<MTLComputeCommandEncoder> compute) {
	[compute endEncoding];
	[compute release];
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		for (int i = 0; i < enc->colorCount; i++) {
			if (!enc->colors[i]) continue;
			rp.colorAttachments[i].texture = enc->colors[i];
			rp.colorAttachments[i].loadAction = enc->colors[i].storageMode == MTLStorageModeMemoryless ? MTLLoadActionDontCare : MTLLoadActionLoad;
			rp.colorAttachments[i].storeAction = MTLStoreActionUnknown;
		}
		if (enc->depth) {
			rp.depthAttachment.texture = enc->depth;
			rp.depthAttachment.loadAction = MTLLoadActionLoad;
			rp.depthAttachment.storeAction = MTLStoreActionUnknown;
		}
		openRender(enc, rp);
	}
}

void mc_r_pipeline(Enc *enc, id<MTLRenderPipelineState> pso, id<MTLDepthStencilState> depth, int cull, int wireframe,
	float biasConstant, float biasSlope, int primitive) {
	id<MTLRenderCommandEncoder> r = enc->render;
	[r setRenderPipelineState:pso];
	if (depth) [r setDepthStencilState:depth];
	[r setCullMode:cull ? MTLCullModeBack : MTLCullModeNone];
	[r setTriangleFillMode:wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
	[r setDepthBias:biasConstant slopeScale:biasSlope clamp:0];
	enc->primitive = (MTLPrimitiveType) primitive;
}

void mc_r_buffer(Enc *enc, int index, id<MTLBuffer> buffer, uint64_t offset) {
	[enc->render setVertexBuffer:buffer offset:offset atIndex:index];
	[enc->render setFragmentBuffer:buffer offset:offset atIndex:index];
}

void mc_r_vertex_buffer(Enc *enc, int index, id<MTLBuffer> buffer, uint64_t offset) {
	[enc->render setVertexBuffer:buffer offset:offset atIndex:index];
}

void mc_r_bytes(Enc *enc, int index, const void *bytes, int length) {
	[enc->render setVertexBytes:bytes length:length atIndex:index];
	[enc->render setFragmentBytes:bytes length:length atIndex:index];
}

void mc_r_texture(Enc *enc, int index, id<MTLTexture> texture, id<MTLSamplerState> sampler) {
	[enc->render setVertexTexture:texture atIndex:index];
	[enc->render setFragmentTexture:texture atIndex:index];
	if (sampler) {
		[enc->render setVertexSamplerState:sampler atIndex:index];
		[enc->render setFragmentSamplerState:sampler atIndex:index];
	}
}

void mc_r_scissor(Enc *enc, int x, int y, int w, int h) {
	[enc->render setScissorRect:(MTLScissorRect) {x, y, w, h}];
}

// A buffer the vertex stage reaches through a GPU address stored in another buffer (bindless) must be made resident.
void mc_r_use(Enc *enc, id<MTLBuffer> buffer) {
	[enc->render useResource:buffer usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
}
void mc_r_index(Enc *enc, id<MTLBuffer> buffer, int intIndices) {
	enc->indexBuffer = buffer;
	enc->indexType = intIndices ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
	enc->indexSize = intIndices ? 4 : 2;
}

static void drawFan(Enc *enc, int firstVertex, int vertexCount, int instances, int firstInstance) {
	if (vertexCount < 3) return;
	[enc->render drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(vertexCount - 2) * 3 indexType:MTLIndexTypeUInt32
		indexBuffer:enc->ctx->fanIndices indexBufferOffset:0 instanceCount:instances baseVertex:firstVertex baseInstance:firstInstance];
}

void mc_r_draw(Enc *enc, int vertexCount, int instances, int firstVertex, int firstInstance) {
	if (enc->primitive == FAN_PRIMITIVE) {
		drawFan(enc, firstVertex, vertexCount, instances, firstInstance);
		return;
	}
	[enc->render drawPrimitives:enc->primitive vertexStart:firstVertex vertexCount:vertexCount instanceCount:instances baseInstance:firstInstance];
}

void mc_r_draw_indexed(Enc *enc, int indexCount, int instances, int firstIndex, int baseVertex, int firstInstance) {
	// Indexed fans only come with the game's sequential index buffer, so index i is vertex i.
	if (enc->primitive == FAN_PRIMITIVE) {
		drawFan(enc, baseVertex + firstIndex, indexCount, instances, firstInstance);
		return;
	}
	[enc->render drawIndexedPrimitives:enc->primitive indexCount:indexCount indexType:enc->indexType indexBuffer:enc->indexBuffer
		indexBufferOffset:(NSUInteger) firstIndex * enc->indexSize instanceCount:instances baseVertex:baseVertex baseInstance:firstInstance];
}

// params: drawCount × {firstIndex, indexCount, vertexOffset} (VkMultiDrawIndexedInfoEXT layout).
void mc_r_multi_draw_indexed(Enc *enc, const int *params, int drawCount, int instances, int firstInstance) {
	for (int i = 0; i < drawCount; i++, params += 3) {
		[enc->render drawIndexedPrimitives:enc->primitive indexCount:params[1] indexType:enc->indexType indexBuffer:enc->indexBuffer
			indexBufferOffset:(NSUInteger) params[0] * enc->indexSize instanceCount:instances baseVertex:params[2] baseInstance:firstInstance];
	}
}

// GL-style separate arrays: byte offsets into the index buffer, index counts, base vertices.
void mc_r_multi_draw_indexed_separate(Enc *enc, const uint64_t *offsets, const int *counts, const int *baseVertices, int drawCount) {
	for (int i = 0; i < drawCount; i++) {
		[enc->render drawIndexedPrimitives:enc->primitive indexCount:counts[i] indexType:enc->indexType indexBuffer:enc->indexBuffer
			indexBufferOffset:offsets[i] instanceCount:1 baseVertex:baseVertices[i] baseInstance:0];
	}
}

// params: drawCount × {firstVertex, vertexCount} (VkMultiDrawInfoEXT layout).
void mc_r_multi_draw(Enc *enc, const int *params, int drawCount, int instances, int firstInstance) {
	for (int i = 0; i < drawCount; i++, params += 2) {
		[enc->render drawPrimitives:enc->primitive vertexStart:params[0] vertexCount:params[1] instanceCount:instances baseInstance:firstInstance];
	}
}

void mc_r_multi_draw_separate(Enc *enc, const int *firsts, const int *counts, int drawCount) {
	for (int i = 0; i < drawCount; i++) {
		[enc->render drawPrimitives:enc->primitive vertexStart:firsts[i] vertexCount:counts[i]];
	}
}

void mc_r_draw_indexed_indirect(Enc *enc, id<MTLBuffer> buffer, uint64_t offset, int drawCount) {
	for (int i = 0; i < drawCount; i++) {
		[enc->render drawIndexedPrimitives:enc->primitive indexType:enc->indexType indexBuffer:enc->indexBuffer indexBufferOffset:0
			indirectBuffer:buffer indirectBufferOffset:offset + (uint64_t) i * 20];
	}
}

void mc_r_draw_indirect(Enc *enc, id<MTLBuffer> buffer, uint64_t offset, int drawCount) {
	for (int i = 0; i < drawCount; i++) {
		[enc->render drawPrimitives:enc->primitive indirectBuffer:buffer indirectBufferOffset:offset + (uint64_t) i * 16];
	}
}

void mc_r_debug_push(Enc *enc, const char *label) {
	@autoreleasepool {
		[enc->render pushDebugGroup:[NSString stringWithUTF8String:label]];
	}
}

void mc_r_debug_pop(Enc *enc) { [enc->render popDebugGroup]; }

// Clears part of a color and/or depth texture by drawing a scissored full-screen triangle (Metal can only clear whole attachments).
// depth < 0 leaves the depth texture untouched.
void mc_clear_rect(Enc *enc, id<MTLTexture> color, id<MTLTexture> depthTex, float r, float g, float b, float a, float depth,
	int x, int y, int w, int h, int mip) {
	endBlit(enc);
	endRender(enc);
	Ctx *ctx = enc->ctx;
	@autoreleasepool {
		NSNumber *key = @(((NSUInteger) (color ? color.pixelFormat : 0) << 16) | (depthTex ? depthTex.pixelFormat : 0));
		id<MTLRenderPipelineState> pso = ctx->clearPipelines[key];
		if (!pso) {
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.vertexFunction = [[ctx->builtins newFunctionWithName:@"clear_vs"] autorelease];
			pd.fragmentFunction = color ? [[ctx->builtins newFunctionWithName:@"clear_fs"] autorelease] : nil;
			if (color) pd.colorAttachments[0].pixelFormat = color.pixelFormat;
			if (depthTex) pd.depthAttachmentPixelFormat = depthTex.pixelFormat;
			pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:nil];
			ctx->clearPipelines[key] = pso;
			[pso release];
		}
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		if (color) {
			rp.colorAttachments[0].texture = color;
			rp.colorAttachments[0].level = mip;
			rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		}
		if (depthTex) {
			rp.depthAttachment.texture = depthTex;
			rp.depthAttachment.level = mip;
			rp.depthAttachment.loadAction = MTLLoadActionLoad;
			rp.depthAttachment.storeAction = MTLStoreActionStore;
		}
		id<MTLRenderCommandEncoder> re = [cmd(enc) renderCommandEncoderWithDescriptor:rp];
		struct { float color[4]; float depth; float pad[3]; } c = {{r, g, b, a}, depth < 0 ? 0 : depth, {0}};
		[re setRenderPipelineState:pso];
		[re setDepthStencilState:depth >= 0 ? ctx->depthWrite : ctx->depthKeep];
		[re setScissorRect:(MTLScissorRect) {x, y, w, h}];
		[re setVertexBytes:&c length:sizeof c atIndex:0];
		[re setFragmentBytes:&c length:sizeof c atIndex:0];
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
	}
}

// ---- surface ----

void mc_layer_configure(Ctx *ctx, CAMetalLayer *layer, int width, int height, int vsync) {
	layer.device = ctx->device;
	layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
	layer.framebufferOnly = YES;
	layer.drawableSize = CGSizeMake(width, height);
	// vsync: bit 0 = display sync; bits 8-15 = maximumDrawableCount (0 = the default, 3; -Dmcopt.metal.drawables)
	layer.displaySyncEnabled = (vsync & 1) != 0;
	int drawables = (vsync >> 8) & 0xff;
	layer.maximumDrawableCount = drawables == 2 || drawables == 3 ? drawables : 3;
}

// ---- present pacing ----
// Minecraft renders far more frames than the display shows, and every present costs the copy pass in mc_present plus
// WindowServer work. Paced, only the last frame that can still make the next refresh is presented; the others render in
// full and are dropped before the copy, as the compositor would have dropped them. The refresh grid comes from a display
// link on the main display (presented handlers report no presentedTime on some displays, the rig's among them). The lead
// is the measured encode-to-GPU-done time of presented frames plus the caller's margin for the compositor's latch.
static _Atomic double nextRefresh, refreshPeriod, gpuLatency;
static double pacedFor, lastPace, frameInterval;

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations" // CVDisplayLink: its replacement needs a run loop to deliver on
static CVReturn onRefresh(CVDisplayLinkRef link, const CVTimeStamp *now, const CVTimeStamp *next, CVOptionFlags flags, CVOptionFlags *outFlags, void *user) {
	static double hostSeconds;
	if (hostSeconds == 0) {
		mach_timebase_info_data_t tb;
		mach_timebase_info(&tb);
		hostSeconds = (double) tb.numer / tb.denom / 1e9;
	}
	nextRefresh = next->hostTime * hostSeconds; // host time is CACurrentMediaTime's clock
	refreshPeriod = next->videoTimeScale ? (double) next->videoRefreshPeriod / next->videoTimeScale : 0;
	return kCVReturnSuccess;
}

static void startDisplayLink(void) {
	CVDisplayLinkRef link;
	if (CVDisplayLinkCreateWithCGDisplay(CGMainDisplayID(), &link) != kCVReturnSuccess) return;
	CVDisplayLinkSetOutputCallback(link, onRefresh, NULL);
	CVDisplayLinkStart(link); // runs for the life of the process
}
#pragma clang diagnostic pop

// 1 if this frame should be presented: it is the first to make some refresh, and the next one, allowing it twice the
// usual frame interval, would miss it. Frame times jitter, so with one interval of slack the frame after a skip often came
// in late and ~1 refresh in 11 got nothing new (55.6 presents/s at 60 Hz); two give 59.7 at the same fps.
// -Dmcopt.metal.paceAdapt (opt-in): the margin learns the compositor's latch from scanout times. Every paced present registers
// a presented handler with the refresh it was aimed at (paceTarget). If the frame reached the screen more than half a refresh
// after that, it missed the latch (on the laptop's 120 Hz panel such a frame shows a refresh late, or ~1.9 ms after the next
// refresh, right before its successor: the paired presents of LATENCY.md lt4), and the lead grows by PACE_UP; a frame on time
// shrinks it by PACE_DOWN. That settles at about PACE_DOWN / PACE_UP (2%) late frames, just at the latch deadline. Without
// scanout times (presentedTime 0, as on the mini's display) nothing changes: the margin stays the caller's.
static int paceAdapt;
static _Atomic double paceExtra;  // seconds added to the caller's margin
static double paceTarget;         // the refresh the frame being presented was paced for (render thread)
#define PACE_UP 0.00025
#define PACE_DOWN 0.000005
#define PACE_EXTRA_MAX 0.006
void mc_pace_adapt(int on) { paceAdapt = on; }
double mc_pace_extra_ms(void) { return paceExtra * 1e3; }
static void paceWatch(id<CAMetalDrawable> drawable) {
	if (!paceAdapt || paceTarget <= 0) return;
	double target = paceTarget, period = refreshPeriod;
	[drawable addPresentedHandler:^(id<MTLDrawable> d) {
		double shown = d.presentedTime;
		if (shown <= 0 || period <= 0) return;
		double e = paceExtra;
		e = shown - target > period * 0.5 ? e + PACE_UP : e - PACE_DOWN;
		paceExtra = e < 0 ? 0 : e > PACE_EXTRA_MAX ? PACE_EXTRA_MAX : e;
	}];
}

int mc_pace(double margin) {
	static int started;
	if (!started) {
		started = 1;
		startDisplayLink();
	}
	double now = CACurrentMediaTime(), anchor = nextRefresh, period = refreshPeriod, lead = gpuLatency + margin + paceExtra;
	if (lastPace > 0) frameInterval += (now - lastPace - frameInterval) * 0.1;
	lastPace = now;
	if (period <= 0 || now - anchor > 0.25) return 1; // no refreshes lately (starting, display asleep): present everything
	double target = anchor + ceil((now + lead - anchor) / period) * period;
	if (target < pacedFor + period / 2 || now + 2 * frameInterval + lead < target) return 0;
	pacedFor = target;
	paceTarget = target;
	return 1;
}

// ---- frame limiter wait ----
// Blocks the calling thread for ns on a kernel timer that may not be deferred: NOTE_CRITICAL with zero leeway. The frame
// limiter's own wait (parkNanos) is a normal-urgency timer, which macOS coalesces by up to a quarter of the wait for the
// render thread's latency tier, so a 60 fps cap ran at 51-57 fps and the limiter spun away the rest of each frame.
void mc_sleep_precise(int64_t ns) {
	static __thread int kq = -1;
	if (ns <= 0) return;
	if (kq < 0) kq = kqueue();
	struct kevent64_s ev, out;
	EV_SET64(&ev, 1, EVFILT_TIMER, EV_ADD | EV_ONESHOT, NOTE_NSECONDS | NOTE_CRITICAL | NOTE_LEEWAY, ns, 0, 0, 0);
	if (kq < 0 || kevent64(kq, &ev, 1, &out, 1, 0, NULL) < 0) {
		struct timespec t = {ns / 1000000000, ns % 1000000000};
		nanosleep(&t, NULL);
	}
}

id<CAMetalDrawable> mc_layer_next(CAMetalLayer *layer) {
	@autoreleasepool {
		return [[layer nextDrawable] retain];
	}
}

CAMetalLayer *mc_layer_attach(NSWindow *window) {
	@autoreleasepool {
		NSView *view = [window contentView];
		CAMetalLayer *layer = [[CAMetalLayer alloc] init];
		layer.contentsScale = [window backingScaleFactor];
		[view setLayer:layer];
		[view setWantsLayer:YES];
		return layer;
	}
}

// Draws src into the drawable (flipped back to top-down) and schedules the present on the current command buffer.
void mc_present(Enc *enc, id<CAMetalDrawable> drawable, id<MTLTexture> src) {
	endBlit(enc);
	endRender(enc);
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = drawable.texture;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cmd(enc) renderCommandEncoderWithDescriptor:rp];
		[r setRenderPipelineState:enc->ctx->present];
		[r setFragmentTexture:src atIndex:0];
		[r setFragmentSamplerState:enc->ctx->presentSampler atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
		double encodedAt = CACurrentMediaTime();
		[cmd(enc) addCompletedHandler:^(id<MTLCommandBuffer> b) { gpuLatency += (CACurrentMediaTime() - encodedAt - gpuLatency) * 0.1; }];
		paceWatch(drawable);
		[cmd(enc) presentDrawable:drawable];
	}
}

// ---- present off the frame's command buffer (opt-in: -Dmcopt.metal.presentQueue) ----
// Idea: ByteV0rtex, noahdunnagan/mcopt#2. mc_present encodes the drawable's draw and presentDrawable into the frame's own
// command buffer, so the frame's completion, which the render thread waits on (MAX_IN_FLIGHT retire, the uniform ring's
// fences), is tied to the drawable. Here the frame's command buffer only copies the finished image into a staging slot and
// signals pqEvent; once the frame is committed, a command buffer on a second queue waits for that signal, draws the slot into
// the drawable and presents it. Staging slots are fenced: a slot is busy from the frame that fills it until the present
// command buffer that reads it completes (its completed handler clears the flag). If the next slot is still busy, the frame
// skips its present (returns 0) instead of waiting, so the render thread never blocks here and no slot is overwritten while
// it is read. Single caller (the render thread); the handlers only touch atomics.
#define PQ_SLOTS 3
static id<MTLCommandQueue> pqQueue;
static id<MTLEvent> pqEvent;
static uint64_t pqValue;
static id<MTLTexture> pqStaging[PQ_SLOTS];
static _Atomic int pqBusy[PQ_SLOTS];
static int pqNext;
static id<CAMetalDrawable> pqDrawable;  // retained: encoded by mc_present_queued, presented by pqSubmit after the commit
static int pqSlot;
static uint64_t pqWait;
static _Atomic long pqSkipped;
static Ctx *pqCtx;

// Fills the next staging slot with src in the frame's command buffer and signals pqEvent; the slot index, or -1 if that slot
// is still being read by an earlier present (the caller skips this frame's present).
static int pqFill(Enc *enc, id<MTLTexture> src) {
	endBlit(enc);
	endRender(enc);
	Ctx *ctx = pqCtx = enc->ctx;
	int k = pqNext;
	if (atomic_load(&pqBusy[k])) {
		atomic_fetch_add(&pqSkipped, 1);
		return -1;
	}
	@autoreleasepool {
		if (!pqQueue) {
			pqQueue = [ctx->device newCommandQueue];
			pqEvent = [ctx->device newEvent];
		}
		id<MTLTexture> st = pqStaging[k];
		if (!st || st.width != src.width || st.height != src.height || st.pixelFormat != src.pixelFormat) {
			[st release];  // not busy: the present that read it has completed
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:src.pixelFormat width:src.width height:src.height mipmapped:NO];
			d.usage = MTLTextureUsageShaderRead;
			d.storageMode = MTLStorageModePrivate;
			st = pqStaging[k] = [ctx->device newTextureWithDescriptor:d];
		}
		atomic_store(&pqBusy[k], 1);
		pqNext = (k + 1) % PQ_SLOTS;
		id<MTLBlitCommandEncoder> b = [cmd(enc) blitCommandEncoder];
		[b copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(src.width, src.height, 1)
			toTexture:st destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
		[b endEncoding];
		[cmd(enc) encodeSignalEvent:pqEvent value:++pqValue];
	}
	return k;
}

// Draws staging slot `slot` into `drawable` on a pqQueue command buffer that first waits for the slot's fill, presents it,
// and frees the slot when the GPU is done with it.
static void pqEncodePresent(id<CAMetalDrawable> drawable, int slot, uint64_t wait, double queuedAt) {
	Ctx *ctx = pqCtx;
	id<MTLCommandBuffer> p = [pqQueue commandBuffer];
	[p encodeWaitForEvent:pqEvent value:wait];
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = drawable.texture;
	rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> r = [p renderCommandEncoderWithDescriptor:rp];
	[r setRenderPipelineState:ctx->present];
	[r setFragmentTexture:pqStaging[slot] atIndex:0];
	[r setFragmentSamplerState:ctx->presentSampler atIndex:0];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[r endEncoding];
	[p addCompletedHandler:^(id<MTLCommandBuffer> cb) {
		gpuLatency += (CACurrentMediaTime() - queuedAt - gpuLatency) * 0.1;
		atomic_store(&pqBusy[slot], 0);
	}];
	paceWatch(drawable);
	[p presentDrawable:drawable];
	[p commit];
}

int mc_present_queued(Enc *enc, id<CAMetalDrawable> drawable, id<MTLTexture> src) {
	int k = pqFill(enc, src);
	if (k < 0) return 0;
	if (pqDrawable) {  // a present encoded but not yet submitted (two in one frame): the newer one wins, free the older slot
		[pqDrawable release];
		atomic_store(&pqBusy[pqSlot], 0);
	}
	pqDrawable = [drawable retain];
	pqSlot = k;
	pqWait = pqValue;
	return 1;
}

// -Dmcopt.metal.presentQueue=acquire: the drawable is acquired on the present side too, so the render thread never waits in
// nextDrawable. The frame hands its filled slot to pqWorker (a serial queue at user-interactive QoS) after its commit; the
// worker acquires the drawable, registers the scanout probe and encodes the present. One hand-off is held at a time: a
// newer frame's replaces one the worker hasn't taken yet (the older slot is freed, counted in pqDropped).
static dispatch_queue_t pqWorker;
static _Atomic uint64_t pqHandoff;  // fill value << 2 | slot; 0 = none
static _Atomic int pqScheduled;
static _Atomic long pqDropped;
static CAMetalLayer *pqLayer;
static long pqCadence[PQ_SLOTS];
static double pqQueuedAt[PQ_SLOTS];
static uint64_t pqAcq;  // this frame's hand-off, published by pqSubmit after the commit
void mc_cadence_present(id<CAMetalDrawable> drawable, int slot);

int mc_present_queued_acquire(Enc *enc, CAMetalLayer *layer, id<MTLTexture> src, long cadence) {
	int k = pqFill(enc, src);
	if (k < 0) return 0;
	if (pqAcq) atomic_store(&pqBusy[pqAcq & 3], 0);  // two presents in one frame: the newer one wins
	pqLayer = layer;
	pqCadence[k] = cadence;
	pqQueuedAt[k] = CACurrentMediaTime();
	pqAcq = pqValue << 2 | (uint64_t) k;
	return 1;
}

static void pqWork(void) {
	for (;;) {
		atomic_store(&pqScheduled, 0);
		uint64_t h = atomic_exchange(&pqHandoff, 0);
		if (!h) return;
		int slot = (int) (h & 3);
		@autoreleasepool {
			id<CAMetalDrawable> drawable = [pqLayer nextDrawable];
			if (!drawable) {  // window hidden: drop this present
				atomic_store(&pqBusy[slot], 0);
				continue;
			}
			if (pqCadence[slot] >= 0) mc_cadence_present(drawable, (int) pqCadence[slot]);
			pqEncodePresent(drawable, slot, h >> 2, pqQueuedAt[slot]);
		}
	}
}

static void pqPublish(void) {
	uint64_t old = atomic_exchange(&pqHandoff, pqAcq);
	pqAcq = 0;
	if (old) {
		atomic_store(&pqBusy[old & 3], 0);
		atomic_fetch_add(&pqDropped, 1);
	}
	if (!atomic_exchange(&pqScheduled, 1)) {
		if (!pqWorker) pqWorker = dispatch_queue_create("mcopt.present", dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
		dispatch_async(pqWorker, ^{ pqWork(); });
	}
}

long mc_present_dropped(void) { return atomic_load(&pqDropped); }

// After the frame's command buffer is committed: the present command buffer on pqQueue (or the hand-off to pqWorker).
static void pqSubmit(void) {
	if (pqAcq) pqPublish();
	if (!pqDrawable) return;
	@autoreleasepool {
		pqEncodePresent(pqDrawable, pqSlot, pqWait, CACurrentMediaTime());
	}
	[pqDrawable release];
	pqDrawable = nil;
}

long mc_present_skipped(void) { return atomic_load(&pqSkipped); }

// ---- MetalFX temporal upscaling ----

typedef struct {
	id<MTLFXTemporalScaler> scaler;
	id<MTLTexture> motion;  // input size, RG16Float, written by motion_cs
	id<MTLTexture> output;  // output size, what the scaler writes; copied into the game's full-size target
} Fx;

Fx *mc_fx_new(Ctx *ctx, int inW, int inH, int outW, int outH, int colorFormat, int depthFormat, char *err, int errCap) {
	@autoreleasepool {
		if (![MTLFXTemporalScalerDescriptor supportsDevice:ctx->device]) {
			strlcpy(err, "MetalFX temporal scaling isn't supported on this GPU", errCap);
			return NULL;
		}
		MTLFXTemporalScalerDescriptor *d = [[MTLFXTemporalScalerDescriptor new] autorelease];
		d.colorTextureFormat = d.outputTextureFormat = (MTLPixelFormat) colorFormat;
		d.depthTextureFormat = (MTLPixelFormat) depthFormat;
		d.motionTextureFormat = MTLPixelFormatRG16Float;
		d.inputWidth = inW;
		d.inputHeight = inH;
		d.outputWidth = outW;
		d.outputHeight = outH;
		id<MTLFXTemporalScaler> scaler = [d newTemporalScalerWithDevice:ctx->device];
		if (!scaler) {
			snprintf(err, errCap, "MetalFX refused %dx%d -> %dx%d, color format %d, depth format %d", inW, inH, outW, outH, colorFormat, depthFormat);
			return NULL;
		}
		MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float width:inW height:inH mipmapped:NO];
		t.storageMode = MTLStorageModePrivate;
		t.usage = MTLTextureUsageShaderWrite | scaler.motionTextureUsage;
		Fx *fx = calloc(1, sizeof(Fx));
		fx->scaler = scaler;
		fx->motion = [ctx->device newTextureWithDescriptor:t];
		t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat) colorFormat width:outW height:outH mipmapped:NO];
		t.storageMode = MTLStorageModePrivate;
		t.usage = scaler.outputTextureUsage;
		fx->output = [ctx->device newTextureWithDescriptor:t];
		return fx;
	}
}

void mc_fx_free(Fx *fx) {
	[fx->scaler release];
	[fx->motion release];
	[fx->output release];
	free(fx);
}

// reproject: column-major 4x4 taking this frame's unjittered clip space to last frame's. Jitter is in input pixels;
// motionSign flips the motion vectors' direction (they're written as last frame's position minus this frame's).
void mc_fx_upscale(Enc *enc, Fx *fx, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> dst, const float *reproject,
	float jitterX, float jitterY, float motionSign, int reset) {
	endBlit(enc);
	endRender(enc);
	id<MTLFXTemporalScaler> s = fx->scaler;
	struct { simd_float4x4 m; simd_float2 size; } r;
	memcpy(&r.m, reproject, sizeof r.m);
	r.size = simd_make_float2(s.inputWidth, s.inputHeight);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = [cmd(enc) computeCommandEncoder];
		[c setComputePipelineState:enc->ctx->motion];
		[c setTexture:depth atIndex:0];
		[c setTexture:fx->motion atIndex:1];
		[c setBytes:&r length:sizeof r atIndex:0];
		[c dispatchThreads:MTLSizeMake(s.inputWidth, s.inputHeight, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
	}
	s.colorTexture = color;
	s.depthTexture = depth;
	s.motionTexture = fx->motion;
	s.outputTexture = fx->output;
	s.inputContentWidth = s.inputWidth;
	s.inputContentHeight = s.inputHeight;
	s.jitterOffsetX = jitterX;
	s.jitterOffsetY = jitterY;
	s.motionVectorScaleX = motionSign * s.inputWidth;
	s.motionVectorScaleY = motionSign * s.inputHeight;
	s.depthReversed = YES;
	s.reset = reset != 0;
	[s encodeToCommandBuffer:cmd(enc)];
	[blit(enc) copyFromTexture:fx->output toTexture:dst];
}
