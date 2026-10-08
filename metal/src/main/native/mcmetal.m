// Thin C surface over Metal for the Java backend (called through java.lang.foreign).
// Objects cross the boundary as retained pointers; Java owns them and hands them back to mc_release.
// Built without ARC so ownership is explicit. Hot encoder calls allocate nothing, so they need no autorelease pool.
#include <stdatomic.h>
#import "mcmetal.h"
#import <CoreVideo/CoreVideo.h>
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
	"fragment float4 clear_fs(constant Clear &c [[buffer(0)]]) { return c.color; }\n";

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
static int gcOn;  // -Dmcopt.metal.gpuCount (GPU counting, measurement only: see 'GPU counting' below)
static char gcLabel[256];  // the next pipeline's name while counting (mc_gpucount_label), used once
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

// -Dmcopt.metal.residentAnim: sprite animation frame textures in a residency set of their own that the queue carries, so they stay wired
// between the ticks that sample them (else their idle pages get compressed and the next tick's first pass waits for them). add 1 adds,
// 0 removes (before the texture's release). Returns 0 if residency sets aren't available.
static id<MTLResidencySet> animSet;
int mc_texture_resident(Ctx *ctx, id<MTLTexture> texture, int add) {
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		@autoreleasepool {
			MTLResidencySetDescriptor *d = [[MTLResidencySetDescriptor new] autorelease];
			d.label = @"mcopt animation frames";
			animSet = [ctx->device newResidencySetWithDescriptor:d error:nil];
		}
		if (animSet) [ctx->queue addResidencySet:animSet];
	});
	if (!animSet || !texture) return 0;
	if (add) [animSet addAllocation:texture];
	else [animSet removeAllocation:texture];
	[animSet commit];
	if (add) [animSet requestResidency];
	return 1;
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
	// Whole-buffer uniform bindings read the MSL struct's 16-byte-aligned size: allocate a multiple of 16 (zeros past the
	// caller's size, which the Java side keeps as the buffer's logical size).
	size = (size + 15) & ~(uint64_t) 15;
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
		if (gcOn) {  // -Dmcopt.metal.gpuCount: the game's pipeline name (mc_gpucount_label) names the per-pipeline counts
			pd.label = gcLabel[0] ? [NSString stringWithUTF8String:gcLabel] : [NSString stringWithFormat:@"%s|%s", vname, fname];
			gcLabel[0] = 0;
		}
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
		if (write) d.label = @"w";  // (mc_r_pipeline marks the open encoder's depth written: mc_tl_restart)
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

// The encoder log: main command buffers, pre command buffers and blit encoders opened since the last read (mc_enc_counters).
static int encCounters[3];
void mc_enc_counters(int *out) {
	for (int i = 0; i < 3; i++) {
		out[i] = encCounters[i];
		encCounters[i] = 0;
	}
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
			encCounters[0]++;
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

// Measurement (-Dmcopt.metal.trace=N's frame only): every render encoder's attachments with their load action when it opens and
// the store action endRender picks when it closes (MetalEncoder switches this on for the traced submit).
static int rpLog = 0;
void mc_set_rp_log(int on) { rpLog = on; }
static const char *rpLoad(MTLLoadAction a) { return a == MTLLoadActionLoad ? "load" : a == MTLLoadActionClear ? "clear" : "dontCare"; }
static void rpLogTex(const char *what, int i, id<MTLTexture> t, const char *action) {
	printf("mcopt-metal rp   %s%d %s %lux%lu fmt %lu %s %s\n", what, i, t.label ? t.label.UTF8String : "?", (unsigned long) t.width,
		(unsigned long) t.height, (unsigned long) t.pixelFormat, t.storageMode == MTLStorageModeMemoryless ? "memoryless" : "memory", action);
}

// ---- GPU counting, measurement only (-Dmcopt.metal.gpuCount[=PATH]) ----------------------------------
// Per render encoder, and per debug group inside one: the samples that pass the depth/stencil test as the draws are submitted
// (a visibility-result counter in counting mode: depth-test traffic in submission order, an upper bound; NOT fragment shader
// invocations, helper lanes, unique pixels or cycles: these TBDR GPUs' hidden surface removal shades less, and the query may
// itself constrain it), and draws and primitives per pipeline. Direct draws count from their
// arguments; indirect draws from their GPU-written arguments, copied back by a blit right after the encoder ends. Both arms
// draw through this encoder, whatever encodes the draws (here, the own renderer, the alpha's terrain): while counting,
// enc->render is a proxy that counts each draw and forwards everything to the real encoder. One JSON line per segment
// (encoder + debug group) per frame goes to PATH (default gpucount.jsonl in the working directory, the game dir).
// Off (no flag): none of this runs; enc->render is the plain encoder and pass descriptors and pipelines are as before.
#include <objc/runtime.h>
static FILE *gcOut;
static long gcFrame, gcEvery = 1;
static int gcGroups = 1;  // a new visibility slot at each debug group push/pop (-Dmcopt.metal.gpuCount.groups=false: one per encoder)
static os_unfair_lock gcLock = OS_UNFAIR_LOCK_INIT;
#define GC_SLOTS 2048

typedef struct { id pso; long draws; double prims; } GcPso;
typedef struct { id buf; uint64_t off; int indexed, pso; MTLPrimitiveType type; } GcInd;

static double gcPrims(MTLPrimitiveType t, double n) {
	switch (t) {
	case MTLPrimitiveTypeTriangle: return floor(n / 3);
	case MTLPrimitiveTypeTriangleStrip: return n >= 3 ? n - 2 : 0;
	case MTLPrimitiveTypeLine: return floor(n / 2);
	case MTLPrimitiveTypeLineStrip: return n >= 2 ? n - 1 : 0;
	default: return n;
	}
}

@interface McGcSeg : NSObject {
@public
	int enc, seg, slot, w, h;
	NSString *group;
	long draws, indirect, icb;
	double prims;
	GcPso *pso; int npso, cpso;
	GcInd *ind; int nind, cind;
	id<MTLBuffer> args;  // the indirect draws' arguments, copied back (20 bytes each)
}
@end
@implementation McGcSeg
- (void)dealloc {
	for (int i = 0; i < npso; i++) [pso[i].pso release];
	for (int i = 0; i < nind; i++) [ind[i].buf release];
	free(pso); free(ind);
	[group release]; [args release];
	[super dealloc];
}
@end

@interface McGcCB : NSObject {  // one per command buffer that has counted encoders
@public
	id<MTLCommandBuffer> cmd;  // retained while this is the current record, so its address can't come back as another's
	id<MTLBuffer> vis;  // GC_SLOTS visibility counts
	int slots, encoders, conflict;
	long frame;
	NSMutableArray *segs;
}
@end
@implementation McGcCB
- (void)dealloc { [cmd release]; [vis release]; [segs release]; [super dealloc]; }
@end

@interface McGcEnc : NSProxy {  // the counting stand-in for enc->render
@public
	id<MTLRenderCommandEncoder> real;
	id<MTLCommandBuffer> cmd;  // not retained: only used while encoding, before the commit
	McGcCB *cb;
	McGcSeg *seg;
	id pso;  // the current pipeline (retained by the encoder anyway)
	NSMutableArray *groups;
	int enc, segs, w, h;
}
@end

static McGcSeg *gcSegment(McGcEnc *p) {
	McGcSeg *s = [McGcSeg new];
	s->enc = p->enc;
	s->seg = p->segs++;
	s->w = p->w;
	s->h = p->h;
	s->group = [[p->groups componentsJoinedByString:@"/"] retain];
	s->slot = p->cb->slots < GC_SLOTS ? p->cb->slots++ : -1;
	[p->real setVisibilityResultMode:s->slot >= 0 ? MTLVisibilityResultModeCounting : MTLVisibilityResultModeDisabled
		offset:s->slot >= 0 ? (NSUInteger) s->slot * 8 : 0];
	[p->cb->segs addObject:s];
	[s release];  // the cb's list keeps it
	return s;
}

static int gcPsoIndex(McGcSeg *s, id pso) {
	for (int i = 0; i < s->npso; i++) if (s->pso[i].pso == pso) return i;
	if (s->npso == s->cpso) {
		s->cpso = s->cpso ? s->cpso * 2 : 8;
		s->pso = realloc(s->pso, sizeof(GcPso) * s->cpso);
	}
	s->pso[s->npso] = (GcPso) {[pso retain], 0, 0};
	return s->npso++;
}

static void gcDraw(McGcEnc *p, MTLPrimitiveType t, NSUInteger count, NSUInteger instances) {
	McGcSeg *s = p->seg;
	double n = gcPrims(t, (double) count) * (double) instances;
	int i = gcPsoIndex(s, p->pso);
	s->draws++; s->prims += n;
	s->pso[i].draws++; s->pso[i].prims += n;
}

static void gcIndirect(McGcEnc *p, MTLPrimitiveType t, id<MTLBuffer> buf, NSUInteger off, int indexed) {
	McGcSeg *s = p->seg;
	if (s->nind == s->cind) {
		s->cind = s->cind ? s->cind * 2 : 16;
		s->ind = realloc(s->ind, sizeof(GcInd) * s->cind);
	}
	s->ind[s->nind++] = (GcInd) {[buf retain], off, indexed, gcPsoIndex(s, p->pso), t};
	s->draws++; s->indirect++;
}

@implementation McGcEnc
- (id)forwardingTargetForSelector:(SEL)sel { return real; }
- (NSMethodSignature *)methodSignatureForSelector:(SEL)sel { return [(NSObject *) real methodSignatureForSelector:sel]; }
- (void)forwardInvocation:(NSInvocation *)inv { [inv invokeWithTarget:real]; }
- (BOOL)respondsToSelector:(SEL)sel { return [real respondsToSelector:sel]; }
- (BOOL)conformsToProtocol:(Protocol *)proto { return [real conformsToProtocol:proto]; }
- (void)dealloc { [real release]; [cb release]; [groups release]; [super dealloc]; }
- (void)setRenderPipelineState:(id<MTLRenderPipelineState>)state { pso = state; [real setRenderPipelineState:state]; }
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n {
	gcDraw(self, t, n, 1);
	[real drawPrimitives:t vertexStart:s vertexCount:n];
}
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n instanceCount:(NSUInteger)k {
	gcDraw(self, t, n, k);
	[real drawPrimitives:t vertexStart:s vertexCount:n instanceCount:k];
}
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n instanceCount:(NSUInteger)k baseInstance:(NSUInteger)b {
	gcDraw(self, t, n, k);
	[real drawPrimitives:t vertexStart:s vertexCount:n instanceCount:k baseInstance:b];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)io {
	gcDraw(self, t, n, 1);
	[real drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib indexBufferOffset:io];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)io
	instanceCount:(NSUInteger)k {
	gcDraw(self, t, n, k);
	[real drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib indexBufferOffset:io instanceCount:k];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)io
	instanceCount:(NSUInteger)k baseVertex:(NSInteger)bv baseInstance:(NSUInteger)bi {
	gcDraw(self, t, n, k);
	[real drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib indexBufferOffset:io instanceCount:k baseVertex:bv baseInstance:bi];
}
- (void)drawPrimitives:(MTLPrimitiveType)t indirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)o {
	gcIndirect(self, t, b, o, 0);
	[real drawPrimitives:t indirectBuffer:b indirectBufferOffset:o];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)io
	indirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)o {
	gcIndirect(self, t, b, o, 1);
	[real drawIndexedPrimitives:t indexType:it indexBuffer:ib indexBufferOffset:io indirectBuffer:b indirectBufferOffset:o];
}
- (void)executeCommandsInBuffer:(id<MTLIndirectCommandBuffer>)icb withRange:(NSRange)r {
	seg->icb += r.length;  // counts unknown on the CPU: reported as icb draws
	[real executeCommandsInBuffer:icb withRange:r];
}
- (void)pushDebugGroup:(NSString *)g {
	[real pushDebugGroup:g];
	if (!gcGroups) return;  // one segment (one visibility slot) per encoder
	[groups addObject:g ? g : @"?"];
	seg = gcSegment(self);
}
- (void)popDebugGroup {
	[real popDebugGroup];
	if (!gcGroups) return;
	if (groups.count) [groups removeLastObject];
	seg = gcSegment(self);
}
- (void)setVisibilityResultMode:(MTLVisibilityResultMode)m offset:(NSUInteger)o {
	cb->conflict = 1;  // someone else uses visibility results in this encoder: its counts would mix with ours
	[real setVisibilityResultMode:m offset:o];
}
@end

static void gcEscape(char *out, size_t cap, NSString *s) {
	const char *c = s ? s.UTF8String : "";
	size_t j = 0;
	for (; *c && j + 2 < cap; c++) {
		if (*c == '"' || *c == '\\') out[j++] = '\\';
		out[j++] = (unsigned char) *c < 0x20 ? ' ' : *c;
	}
	out[j] = 0;
}

static void gcReport(McGcCB *g, id<MTLCommandBuffer> done) {
	const uint64_t *vis = g->vis.contents;
	double ms = (CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970) * 1000;
	double gpuMs = (done.GPUEndTime - done.GPUStartTime) * 1000;
	os_unfair_lock_lock(&gcLock);
	for (McGcSeg *s in g->segs) {
		if (s->args) {  // the indirect draws' GPU-written arguments
			const uint32_t *a = s->args.contents;
			for (int i = 0; i < s->nind; i++) {
				const uint32_t *r = a + i * 5;
				double n = gcPrims(s->ind[i].type, (double) r[0]) * (double) r[1];
				s->prims += n;
				s->pso[s->ind[i].pso].prims += n;
				s->pso[s->ind[i].pso].draws++;
			}
		}
		char group[512], pso[256];
		gcEscape(group, sizeof group, s->group);
		fprintf(gcOut, "{\"frame\":%ld,\"t\":%.1f,\"gpuMs\":%.3f,\"enc\":%d,\"seg\":%d,\"group\":\"%s\",\"w\":%d,\"h\":%d,\"samples\":%lld,"
			"\"draws\":%ld,\"indirect\":%ld,\"icb\":%ld,\"prims\":%.0f%s,\"pso\":[", g->frame, ms, gpuMs, s->enc, s->seg, group, s->w, s->h,
			s->slot >= 0 ? (long long) vis[s->slot] : -1LL, s->draws, s->indirect, s->icb, s->prims, g->conflict ? ",\"conflict\":1" : "");
		for (int i = 0; i < s->npso; i++) {
			id<MTLRenderPipelineState> p = s->pso[i].pso;
			gcEscape(pso, sizeof pso, p ? (p.label ? p.label : [NSString stringWithFormat:@"pso@%p", p]) : @"none");
			fprintf(gcOut, "%s[\"%s\",%ld,%.0f,\"%p\"]", i ? "," : "", pso, s->pso[i].draws, s->pso[i].prims, (void *) p);
		}
		fprintf(gcOut, "]}\n");
	}
	fflush(gcOut);
	os_unfair_lock_unlock(&gcLock);
}

// Opening a render encoder while counting: the pass gets this command buffer's visibility buffer (rp), and the new encoder
// is wrapped (returned retained, the plain one released). gcCurrent (retained) is the record of the command buffer being
// encoded (render thread only); mc_enc_commit drops it, and the completion handler's block keeps it until the report.
static McGcCB *gcCurrent;
static void gcBeforeOpen(Enc *enc, MTLRenderPassDescriptor *rp, id<MTLCommandBuffer> c) {
	if (!gcCurrent || gcCurrent->cmd != c) {
		[gcCurrent release];
		McGcCB *g = [McGcCB new];
		g->cmd = [c retain];
		g->vis = [enc->ctx->device newBufferWithLength:GC_SLOTS * 8 options:MTLResourceStorageModeShared];
		memset(g->vis.contents, 0, GC_SLOTS * 8);
		g->segs = [NSMutableArray new];
		g->frame = gcFrame;
		[c addCompletedHandler:^(id<MTLCommandBuffer> done) { gcReport(g, done); }];  // the copied block retains g (MRC)
		gcCurrent = g;
	}
	rp.visibilityResultBuffer = gcCurrent->vis;
}

static id<MTLRenderCommandEncoder> gcWrap(Enc *enc, id<MTLRenderCommandEncoder> real, id<MTLCommandBuffer> c) {
	McGcEnc *p = [McGcEnc alloc];  // NSProxy: no -init
	p->real = real;  // takes over the caller's retain
	p->cmd = c;
	p->cb = [gcCurrent retain];
	p->groups = [NSMutableArray new];
	p->enc = gcCurrent->encoders++;
	p->w = enc->width;
	p->h = enc->height;
	p->pso = nil;
	p->segs = 0;
	p->seg = gcSegment(p);
	return (id<MTLRenderCommandEncoder>) p;
}

// After the counted encoder ended: copy each indirect draw's arguments into a shared buffer, read when the command buffer is done.
static void gcAfterEnd(id<MTLRenderCommandEncoder> render) {
	if (object_getClass(render) != [McGcEnc class]) return;
	McGcEnc *p = (McGcEnc *) render;
	id<MTLBlitCommandEncoder> b = nil;
	for (McGcSeg *s in p->cb->segs) {
		if (s->enc != p->enc || !s->nind || s->args) continue;
		s->args = [[p->real device] newBufferWithLength:(NSUInteger) s->nind * 20 options:MTLResourceStorageModeShared];
		memset(s->args.contents, 0, (size_t) s->nind * 20);
		if (!b) b = [p->cmd blitCommandEncoder];
		for (int i = 0; i < s->nind; i++)
			[b copyFromBuffer:s->ind[i].buf sourceOffset:s->ind[i].off toBuffer:s->args destinationOffset:(NSUInteger) i * 20
				size:s->ind[i].indexed ? 20 : 16];
	}
	[b endEncoding];
}

void mc_gpucount_label(const char *name) { snprintf(gcLabel, sizeof gcLabel, "%s", name ? name : ""); }

void mc_gpucount_enable(const char *path, int every, int groups) {
	if (gcOn) return;
	gcEvery = every > 0 ? every : 1;
	gcGroups = groups;
	gcOut = fopen(path && *path ? path : "gpucount.jsonl", "a");
	if (!gcOut) { fprintf(stderr, "mcopt-metal: gpuCount: can't open %s\n", path ? path : "gpucount.jsonl"); return; }
	gcOn = 1;
	fprintf(stderr, "mcopt-metal: gpuCount on (depth-passing samples + draws/primitives per pipeline, per render encoder%s, every %ld frame(s)): %s\n",
		gcGroups ? " and debug group" : "", gcEvery, path && *path ? path : "gpucount.jsonl");
}
// ---- end of GPU counting ---------------------------------------------------------------------------------------------------

static void endRender(Enc *enc) {
	if (!enc->render) return;
	if (rpLog) {
		printf("mcopt-metal rp end #%d\n", enc->renderSerial);
		for (int i = 0; i < enc->colorCount; i++)
			if (enc->colors[i]) rpLogTex("color", i, enc->colors[i], (enc->dead >> i & 1) || enc->colors[i].storageMode == MTLStorageModeMemoryless ? "store dontCare" : "store");
		if (enc->depth) rpLogTex("depth", 0, enc->depth, (enc->dead & DEAD_DEPTH) ? "store dontCare" : "store");
		fflush(stdout);
	}
	for (int i = 0; i < enc->colorCount; i++) {
		// Memoryless attachments (the native shading pipeline's G-buffer) have nothing to store.
		if (enc->colors[i]) [enc->render setColorStoreAction:(enc->dead >> i & 1) || enc->colors[i].storageMode == MTLStorageModeMemoryless
			? MTLStoreActionDontCare : MTLStoreActionStore atIndex:i];
	}
	if (enc->depth) [enc->render setDepthStoreAction:(enc->dead & DEAD_DEPTH) ? MTLStoreActionDontCare : MTLStoreActionStore];
	if (enc->renderGroup >= 0 && enc->renderGroup < 512 && enc->sampleInfo[enc->renderGroup][0]) {
		size_t len = strlen(enc->sampleInfo[enc->renderGroup]);
		snprintf(enc->sampleInfo[enc->renderGroup] + len, 48 - len, " S%d]", enc->colors[0] ? ((enc->dead & 1) ? 0 : 1) : 1);
	}
	enc->renderGroup = -1;
	[enc->render endEncoding];
	if (gcOn) gcAfterEnd(enc->render);  // -Dmcopt.metal.gpuCount: the indirect draws' arguments, copied back
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
		encCounters[2]++;
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

static int lastComputeGroup = -1;

int mc_last_compute_group(void) {
	return lastComputeGroup;
}

id<MTLComputeCommandEncoder> mc_profiled_compute(Enc *enc, id<MTLCommandBuffer> c, MTLDispatchType type) {
	@autoreleasepool {
		lastComputeGroup = -1;
		if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
			// (as mcs_compute_begin: start and end in the "vertex" slots of the encoder's group of 4)
			MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
			d.dispatchType = type;
			d.sampleBufferAttachments[0].sampleBuffer = enc->samples;
			d.sampleBufferAttachments[0].startOfEncoderSampleIndex = enc->sampleCount;
			d.sampleBufferAttachments[0].endOfEncoderSampleIndex = enc->sampleCount + 1;
			lastComputeGroup = enc->sampleCount / 4;
			int group = enc->sampleCount / 4;
			enc->sampleCount += 4;
			id<MTLComputeCommandEncoder> ce = [[c computeCommandEncoderWithDescriptor:d] retain];
			if (group < 512) {
				enc->sampleEnc[group] = [ce retain];
				enc->sampleKind[group] = c == enc->pre ? 'P' : 'C';
			}
			return ce;
		}
		return [[c computeCommandEncoderWithDispatchType:type] retain];
	}
}

// A blit encoder on c, timestamped like mc_profiled_compute when the encoder log is on (kind 'B'). Retained: the caller releases it.
id<MTLBlitCommandEncoder> mc_profiled_blit(Enc *enc, id<MTLCommandBuffer> c) {
	@autoreleasepool {
		if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
			MTLBlitPassDescriptor *d = [MTLBlitPassDescriptor blitPassDescriptor];
			d.sampleBufferAttachments[0].sampleBuffer = enc->samples;
			d.sampleBufferAttachments[0].startOfEncoderSampleIndex = enc->sampleCount;
			d.sampleBufferAttachments[0].endOfEncoderSampleIndex = enc->sampleCount + 1;
			int group = enc->sampleCount / 4;
			enc->sampleCount += 4;
			id<MTLBlitCommandEncoder> be = [[c blitCommandEncoderWithDescriptor:d] retain];
			if (group < 512) {
				enc->sampleEnc[group] = [be retain];
				enc->sampleKind[group] = 'B';
			}
			return be;
		}
		return [[c blitCommandEncoder] retain];
	}
}

id<MTLCommandBuffer> mc_pre(Enc *enc) {
	if (!enc->pre) {
		@autoreleasepool {
			enc->pre = [[enc->ctx->queue commandBuffer] retain];
			encCounters[1]++;
		}
	}
	return enc->pre;
}

id<MTLComputeCommandEncoder> mc_pre_compute(Enc *enc) {
	if (!enc->preCompute) {
		mc_pre_end_encoders(enc);
		enc->preCompute = mc_profiled_compute(enc, mc_pre(enc), MTLDispatchTypeSerial);
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
	if (gcOn) {  // -Dmcopt.metal.gpuCount: the next frame's encoders get a new record and frame index
		gcFrame++;
		[gcCurrent release];
		gcCurrent = nil;
	}
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
			// calibrated once: the rate in mc_profile_read only gets better with a longer baseline, and the encoder log's absolute times
			// (mc_profile_read_abs) stay comparable across submits
			if (profileGpu0 == 0) [device sampleTimestamps:&profileCpu0 gpuTimestamp:&profileGpu0];
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

// The encoder log (-Dmcopt.own.int.encLog): samples in µs since the first profiled submit's calibration (-1 = didn't run), so encoders of
// different submits compare; releases s.
void mc_profile_read_abs(Ctx *ctx, id<MTLCounterSampleBuffer> s, int count, double *out) {
	MTLTimestamp cpu1, gpu1;
	[ctx->device sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
	double usPerTick = (double) (cpu1 - profileCpu0) / (double) (gpu1 - profileGpu0) / 1000.0;
	@autoreleasepool {
		const MTLCounterResultTimestamp *ts = [s resolveCounterRange:NSMakeRange(0, count)].bytes;
		for (int i = 0; i < count; i++) {
			out[i] = ts[i].timestamp == MTLCounterErrorValue || ts[i].timestamp == 0 ? -1 : (double) (int64_t) (ts[i].timestamp - profileGpu0) * usPerTick;
		}
	}
	[s release];
}

// Metal memory the device has allocated (bytes): -Dmcopt.metal.atlasDouble logs it around making the second atlas copy.
uint64_t mc_device_allocated(Ctx *ctx) { return ctx->device.currentAllocatedSize; }

int mc_profile_count(Enc *enc) { return enc->samples ? enc->sampleCount : -1; }

// The encoder log: each profiled group's kind (R render, C compute, P compute in the pre buffer) and Metal label into out (stride bytes
// a group, "K:label"), then lets the encoders go. Call before mc_profile_end.
void mc_profile_labels(Enc *enc, char *out, int stride, int max) {
	int n = enc->sampleCount / 4;
	for (int g = 0; g < n && g < 512; g++) {
		id e = enc->sampleEnc[g];
		if (g < max) {
			NSString *label = e ? [(id<MTLCommandEncoder>) e label] : nil;
			snprintf(out + (size_t) g * stride, (size_t) stride, "%c:%s%s", enc->sampleKind[g] ? enc->sampleKind[g] : '?', label ? label.UTF8String : "",
				enc->sampleInfo[g]);
		}
		if (e) [e release];
		enc->sampleEnc[g] = nil;
		enc->sampleKind[g] = 0;
		enc->sampleInfo[g][0] = 0;
	}
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
	enc->tlDepthWritten = 1;
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
	int renderGroup = enc->samples && rp.sampleBufferAttachments[0].sampleBuffer == enc->samples ? enc->sampleCount / 4 - 1 : -1;
	int counted = gcOn && gcFrame % gcEvery == 0;  // -Dmcopt.metal.gpuCount: see GPU counting above; nothing without the flag
	if (counted) gcBeforeOpen(enc, rp, cmd(enc));
	else if (gcOn) rp.visibilityResultBuffer = nil;  // a frame between counted ones (a kept descriptor may still name the buffer)
	enc->render = [[cmd(enc) renderCommandEncoderWithDescriptor:rp] retain];
	if (renderGroup >= 0 && renderGroup < 512) {
		enc->sampleEnc[renderGroup] = [enc->render retain];  // (the encoder itself, before any counting wrapper)
		enc->sampleKind[renderGroup] = 'R';
		id<MTLTexture> t0 = rp.colorAttachments[0].texture ?: rp.depthAttachment.texture;
		snprintf(enc->sampleInfo[renderGroup], 48, " [%dx%d L%d%s", t0 ? (int) t0.width : 0, t0 ? (int) t0.height : 0,
			(int) (rp.colorAttachments[0].texture ? rp.colorAttachments[0].loadAction : rp.depthAttachment.loadAction), rp.depthAttachment.texture ? " +depth" : "");
	}
	if (counted) enc->render = gcWrap(enc, enc->render, cmd(enc));
	enc->renderGroup = renderGroup;
	enc->renderSerial++;
	if (rpLog) {
		printf("mcopt-metal rp open #%d\n", enc->renderSerial);
		for (int i = 0; i < 8; i++)
			if (rp.colorAttachments[i].texture) rpLogTex("color", i, rp.colorAttachments[i].texture, rpLoad(rp.colorAttachments[i].loadAction));
		if (rp.depthAttachment.texture) rpLogTex("depth", 0, rp.depthAttachment.texture, rpLoad(rp.depthAttachment.loadAction));
		fflush(stdout);
	}
	[enc->render setViewport:(MTLViewport) {0, 0, enc->width, enc->height, 0, 1}];
	[enc->render setFrontFacingWinding:MTLWindingClockwise];  // vertex Y is flipped to keep GL/Vulkan row order, which mirrors winding too
	enc->tlDepthCleared = rp.depthAttachment.texture && rp.depthAttachment.loadAction == MTLLoadActionClear;
	enc->tlDepthClearValue = (float) rp.depthAttachment.clearDepth;
	enc->tlDepthWritten = 0;
}

// mc_tl_restart's attachment guard: 0 when the open render encoder is the standard pass a restart is known to keep exact (every colour a
// memory-backed single-sample 2D texture, depth a memory-backed single-sample Depth32Float, nothing marked dead: a pending discard would
// store don't-care and the reopened encoder would load garbage); else why not: 1 nothing open, 2 no colour, 3 a colour memoryless,
// 4 a colour multisampled or not 2D, 5 no depth, 6 depth not Depth32Float, 7 depth memoryless or multisampled, 8 a pending discard.
// (The backend binds no resolve or stencil attachments.)
int mc_tl_restart_ok(Enc *enc) {
	if (!enc->render) return 1;
	int colours = 0;
	for (int i = 0; i < enc->colorCount; i++) {
		id<MTLTexture> t = enc->colors[i];
		if (!t) continue;
		colours++;
		if (t.storageMode == MTLStorageModeMemoryless) return 3;
		if (t.sampleCount != 1 || t.textureType != MTLTextureType2D) return 4;
	}
	if (!colours) return 2;
	if (!enc->depth) return 5;
	if (enc->depth.pixelFormat != MTLPixelFormatDepth32Float) return 6;
	if (enc->depth.storageMode == MTLStorageModeMemoryless || enc->depth.sampleCount != 1) return 7;
	if (enc->dead) return 8;
	return 0;
}

// mc_tl_restart: the open render encoder ended and reopened on the same attachments, colours loaded back, so the draws before and
// after are passes of their own (a pass's fragments start after all its vertex work; -Dmcopt.own.tl.a1Split splits phase A's first
// part this way). mode 2: when the encoder opened with its depth cleared and nothing in it wrote depth since, the depth still holds
// that clear value: it isn't stored and the new encoder clears it to the same value. Otherwise (mode 1) depth is stored and loaded.
// Returns 0 nothing open, 1 reopened (depth kept), 2 reopened (depth cleared again).
int mc_tl_restart(Enc *enc, int mode) {
	if (!enc->render || mc_tl_restart_ok(enc) != 0) return 0;  // (not a supported pass: the caller's draws stay in this encoder)
	int dead = mode == 2 && enc->depth && enc->tlDepthCleared && !enc->tlDepthWritten;
	float v = enc->tlDepthClearValue;
	if (dead) enc->dead |= DEAD_DEPTH;
	endRender(enc);
	endBlit(enc);
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
			rp.depthAttachment.loadAction = dead ? MTLLoadActionClear : MTLLoadActionLoad;
			rp.depthAttachment.clearDepth = v;
			rp.depthAttachment.storeAction = MTLStoreActionUnknown;
		}
		openRender(enc, rp);
	}
	return 1 + dead;
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

// -Dmcopt.metal.passTest=N,MODE (measurement only): N empty render passes at the head of the frame (every 20th), profiled like the frame's
// own (encoder log). MODE bits: 1 a depth attachment too, 2 each pass in its own command buffer (committed at once), 4 the passes target
// mips 0..N-1 of one texture (else N separate 256x32 textures), 8 load instead of clear, 16 the test textures untracked (no hazard tracking).
static id<MTLTexture> ptColor[16], ptDepth[16], ptMipColor, ptMipDepth;
static int ptMode = -1;
static id<MTLTexture> ptTexture(Ctx *ctx, MTLPixelFormat format, int w, int h, int mips, int untracked) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:mips > 1];
	if (mips > 1) d.mipmapLevelCount = mips;
	d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	d.storageMode = MTLStorageModePrivate;
	if (untracked) d.hazardTrackingMode = MTLHazardTrackingModeUntracked;
	return [ctx->device newTextureWithDescriptor:d];
}
void mc_pass_test(Enc *enc, int n, int mode) {
	if (n <= 0) return;
	if (n > 8) n = 8;
	Ctx *ctx = enc->ctx;
	@autoreleasepool {
		if (ptMode != mode) {
			int un = (mode & 16) != 0;
			for (int i = 0; i < 16; i++) {
				[ptColor[i] release]; [ptDepth[i] release];
				ptColor[i] = ptTexture(ctx, MTLPixelFormatRGBA8Unorm, 256, 32, 1, un);
				ptDepth[i] = ptTexture(ctx, MTLPixelFormatDepth32Float, 256, 32, 1, un);
			}
			[ptMipColor release]; [ptMipDepth release];
			ptMipColor = ptTexture(ctx, MTLPixelFormatRGBA8Unorm, 2048, 2048, 9, un);
			ptMipDepth = nil;
			ptMode = mode;
		}
		endRender(enc);
		endBlit(enc);
		for (int i = 0; i < n; i++) {
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			id<MTLTexture> c = (mode & 4) ? ptMipColor : ptColor[i];
			rp.colorAttachments[0].texture = c;
			if (mode & 4) rp.colorAttachments[0].level = i;
			rp.colorAttachments[0].loadAction = (mode & 8) ? MTLLoadActionLoad : MTLLoadActionClear;
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			if ((mode & 1) && !(mode & 4)) {
				rp.depthAttachment.texture = ptDepth[i];
				rp.depthAttachment.loadAction = MTLLoadActionClear;
				rp.depthAttachment.storeAction = MTLStoreActionStore;
			}
			int group = -1;
			if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
				MTLRenderPassSampleBufferAttachmentDescriptor *sd = rp.sampleBufferAttachments[0];
				sd.sampleBuffer = enc->samples;
				sd.startOfVertexSampleIndex = enc->sampleCount;
				sd.endOfVertexSampleIndex = enc->sampleCount + 1;
				sd.startOfFragmentSampleIndex = enc->sampleCount + 2;
				sd.endOfFragmentSampleIndex = enc->sampleCount + 3;
				group = enc->sampleCount / 4;
				enc->sampleCount += 4;
			}
			id<MTLCommandBuffer> cb = (mode & 2) ? [ctx->queue commandBuffer] : cmd(enc);
			id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
			re.label = @"passtest";
			if (group >= 0 && group < 512) {
				enc->sampleEnc[group] = [re retain];
				enc->sampleKind[group] = 'R';
				snprintf(enc->sampleInfo[group], 48, " [%dx%d m%d L%d%s S1]", (int) c.width >> ((mode & 4) ? i : 0), (int) c.height >> ((mode & 4) ? i : 0),
					(mode & 4) ? i : 0, (mode & 8) ? 1 : 2, rp.depthAttachment.texture ? " +depth" : "");
			}
			[re endEncoding];
			if (mode & 2) [cb commit];
		}
	}
}

// Terrain occlusion (mcterrain.m) only: ends the open render encoder with every live attachment stored and returns a
// concurrent compute encoder (dispatches overlap unless a barrier orders them) for work that reads them; mc_render_resume
// then reopens the pass on the same attachments with their contents loaded back. The reopened encoder has no state but the
// viewport and winding: the caller rebinds the rest.
id<MTLComputeCommandEncoder> mc_render_suspend(Enc *enc) {
	if (rpLog) printf("mcopt-metal rp suspend (a split: compute between the pass's encoders)\n");
	endRender(enc);
	endBlit(enc);  // MetalTerrain's held arena copies, recorded just before
	return mc_profiled_compute(enc, cmd(enc), MTLDispatchTypeConcurrent);
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
	if (depth && depth.label.length) enc->tlDepthWritten = 1;  // (a writing depth state: mc_depth_state_new labels it)
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
	// A uniform block's std140 size isn't always a multiple of 16, but the translated MSL struct is (its alignment): bind the
	// struct's size, zero-padded. Every member keeps its bytes; only the struct's tail padding was outside (Metal API validation:
	// "has space for N bytes, but argument has a length(N+4..8)").
	int padded = (length + 15) & ~15;
	uint8_t tmp[4096];
	if (padded != length && padded <= (int) sizeof tmp) {
		memcpy(tmp, bytes, length);
		memset(tmp + length, 0, padded - length);
		bytes = tmp;
		length = padded;
	}
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
		// The pacer's lead is the frame's GPU time, measured here as mc_present does: the present command buffer completes
		// only once the display releases its drawable, so timing that one would add up to a refresh or two of scanout.
		double encodedAt = CACurrentMediaTime();
		[cmd(enc) addCompletedHandler:^(id<MTLCommandBuffer> cb) { gpuLatency += (CACurrentMediaTime() - encodedAt - gpuLatency) * 0.1; }];
	}
	return k;
}

// Draws staging slot `slot` into `drawable` on a pqQueue command buffer that first waits for the slot's fill, presents it,
// and frees the slot when the GPU is done with it.
static void pqEncodePresent(id<CAMetalDrawable> drawable, int slot, uint64_t wait) {
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
	[p addCompletedHandler:^(id<MTLCommandBuffer> cb) { atomic_store(&pqBusy[slot], 0); }];
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
static uint64_t pqAcq;  // this frame's hand-off, published by pqSubmit after the commit
void mc_cadence_present(id<CAMetalDrawable> drawable, int slot);

int mc_present_queued_acquire(Enc *enc, CAMetalLayer *layer, id<MTLTexture> src, long cadence) {
	int k = pqFill(enc, src);
	if (k < 0) return 0;
	if (pqAcq) atomic_store(&pqBusy[pqAcq & 3], 0);  // two presents in one frame: the newer one wins
	pqLayer = layer;
	pqCadence[k] = cadence;
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
			pqEncodePresent(drawable, slot, h >> 2);
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
		pqEncodePresent(pqDrawable, pqSlot, pqWait);
	}
	[pqDrawable release];
	pqDrawable = nil;
}

long mc_present_skipped(void) { return atomic_load(&pqSkipped); }
