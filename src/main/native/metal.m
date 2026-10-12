// Metal terrain for the 1.8.9 port (mcopt.legacy.metal.MetalTerrain).
//
// 1.8.9 draws its world with OpenGL 2.1, which macOS runs on top of Metal through a translation layer. Every chunk
// section is one glDrawArrays call, and at a far render distance the driver's per-draw CPU work is most of the frame.
// Here the opaque terrain layers (solid, cutout mipped, cutout) are drawn by Metal instead: three instanced draws that
// pull their vertices from shared buffers, into IOSurface-backed textures (the color, and the window depth packed into
// a second BGRA8 texture). A small GLSL pass then writes both into the game's framebuffer, color and depth, so the rest
// of the frame (entities, water, particles, the hand) draws and depth-tests against the terrain as it does in vanilla.
//
// Everything here runs on the client thread with the game's GL context current.
#define GL_SILENCE_DEPRECATION
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/gl.h>
#import <OpenGL/glext.h>
#include <jni.h>
#include <string.h>

#define RING 3  // composite targets in flight: GL may still be reading one while Metal draws the next
#define UNIT_QUADS 64  // the most quads in a draw unit
static int unitQuads = UNIT_QUADS;  // quads per draw unit as MetalTerrain cuts them
#define UNIT_BYTES 32
#define QUAD_BYTES 64  // MetalTerrain.QUAD_BYTES: 4 vertices of 16 bytes
#define PAGE_QUADS (1 << 21)  // MetalTerrain.PAGE_QUADS
#define HIZ_LEVELS 12  // terrain.metal's

// the camera in blocks, as terrain.metal's Camera
typedef struct {
	int x, y, z;
	float fx, fy, fz;
	float pad0, pad1;
} Camera;

// the frame constants, as terrain.metal's Frame
typedef struct {
	float mv[16], proj[16];
	float fogColor[4];
	float fogStart, fogEnd, fogDensity;
	int fogMode;  // 0 off, 1 linear, 2 exp, 3 exp2
	int radial, alphaFunc;
	float alphaRef, pad;
	Camera cam;
	float mvp[16];  // proj x mv
} Frame;

// the occlusion test's constants, as terrain.metal's CullFrame
typedef struct {
	float mvp[16];
	float width, height;
	uint32_t levels, pad;
	Camera cam;
} CullFrame;

_Static_assert(sizeof(Frame) == 272, "Frame must match terrain.metal");
_Static_assert(sizeof(CullFrame) == 112, "CullFrame must match terrain.metal");

typedef struct {
	IOSurfaceRef surface[2];  // color, packed depth
	id<MTLTexture> color, depth;
	GLuint glColor, glDepth;
	GLuint fence;
	int fenced;
} Target;

static id<MTLDevice> device;
static id<MTLCommandQueue> queue;
static id<MTLRenderPipelineState> solidPipe, cutoutPipe, depthPipe, minPipe;
static id<MTLComputePipelineState> splitPipe, hizFirstPipe, hizDownPipe, testPipe, spinPipe;
static id<MTLDepthStencilState> depthState, equalState;
static id<MTLSamplerState> mipSampler, flatSampler, lightSampler;
static id<MTLBuffer> quadIndices;
static id<MTLTexture> atlas, lightmap, depthBuffer;
static id<MTLTexture> drawColor, drawDepth;  // dev 512: private targets drawn into, then copied to the shared ones
static GLint atlasGl = -1;

static NSMutableArray<id<MTLBuffer>> *pages;  // vertex pages (the Java side's allocator hands out ranges)
static id<MTLBuffer> units[3];  // this frame's draw units, per layer
// Units are drawn in BUCKETS draws: by distance (4 bands), nearest first, then by quad count (up to 8, 16, ..., 64; see
// append in terrain.metal).
#define BUCKETS 32
static id<MTLBuffer> lists[3][2];  // per layer, the unit indices of the first and the second draw, one region per bucket
static id<MTLBuffer> drawArgs;  // per layer, draw and bucket, the indirect draw arguments (5 uints)
static id<MTLBuffer> seen;  // per unit id (a quad index over all pages), 1 when the unit was visible last frame
static id<MTLTexture> hiz[HIZ_LEVELS];
static id<MTLBuffer> identity[3];  // dev, culling off: 0, 1, 2, ... as a unit list
static int hizLevels;

static Target targets[RING];
static id<MTLCommandBuffer> pending, pendingFirst;  // the frame's last and first command buffers
static id<MTLCommandBuffer> retiring;  // composited once scheduled, the GPU maybe still drawing it
static int targetW, targetH, frameNo;

static GLuint program;
static GLint heightLoc;
static int nvFogDistance;
static char error[512];
// Dev experiments: 1 scissor to one pixel, 2 solid layer only, 4 no triangles (NaN positions before the vertex fetch;
// with 16384 the magenta is kept), 8 glFinish first, 16 no occlusion culling, 32 no second render pass, 64 packed depth
// not kept, 128 no alpha test, 256 alpha-tested layers with a depth pass, 512 private targets copied to the shared
// ones, 1024 minimal alpha-test shader, 2048 stage timestamps, 4096 no composite (its cost), 8192 composite once the GPU finished, 16384 race test
// (magenta target, GPU delay), 32768 no wait before the composite (the race test's control).
static int devFlags;
static char dumpPath[1024];  // dev: write the next frame's draw (frame constants, units, their quads) here
static double statEncode, statWait, statGpu, statFirst, statComposite;  // seconds, since the last nStats
static double statDrawn[6];  // units drawn per layer and pass, since the last nStats
// dev 2048: GPU timestamps at stage boundaries (sample pairs: the first cull, the first draw's vertex and fragment
// stages, the pyramid and second cull, the second draw's vertex and fragment stages), and their seconds since nStats
static id<MTLCounterSampleBuffer> stageBuf;
static double statStage[6], statSlots;  // and the quad slots drawn (with the padding)
static MTLTimestamp calCpu, calGpu;

#include <mach/mach_time.h>
static double now(void) {
	static mach_timebase_info_data_t tb;
	if (!tb.denom) mach_timebase_info(&tb);
	return (double)mach_absolute_time() * tb.numer / tb.denom / 1e9;
}

// a compute pass that samples the stage timestamps at index and index + 1 (dev 2048)
static MTLComputePassDescriptor *stagePass(NSUInteger index) {
	MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
	if (stageBuf) {
		d.sampleBufferAttachments[0].sampleBuffer = stageBuf;
		d.sampleBufferAttachments[0].startOfEncoderSampleIndex = index;
		d.sampleBufferAttachments[0].endOfEncoderSampleIndex = index + 1;
	}
	return d;
}

static jstring jstr(JNIEnv *env, NSString *why) {
	return (*env)->NewStringUTF(env, why.UTF8String);
}

static GLuint compileShader(GLenum type, const char *src) {
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, NULL);
	glCompileShader(s);
	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		glGetShaderInfoLog(s, sizeof error, NULL, error);
		glDeleteShader(s);
		return 0;
	}
	return s;
}

// The composite: where Metal drew terrain, its color and its depth (the 24-bit window depth in r, g, b) go into the
// bound framebuffer; elsewhere (sky) nothing is written. Metal's rows run top-down, GL's bottom-up.
static const char *COMPOSITE_VS = "#version 120\nvoid main() { gl_Position = gl_Vertex; }\n";
static const char *COMPOSITE_FS =
	"#version 120\n"
	"#extension GL_ARB_texture_rectangle : enable\n"
	"uniform sampler2DRect color;\n"
	"uniform sampler2DRect depth;\n"
	"uniform float height;\n"
	"void main() {\n"
	"	vec2 p = vec2(gl_FragCoord.x, height - gl_FragCoord.y);\n"
	"	vec3 d = floor(texture2DRect(depth, p).rgb * 255.0 + 0.5);\n"
	"	float z = d.r * 65536.0 + d.g * 256.0 + d.b;\n"
	"	if (z >= 16777215.0) discard;\n"
	"	gl_FragColor = texture2DRect(color, p);\n"
	"	gl_FragDepth = z / 16777215.0;\n"
	"}\n";

static NSString *initComposite(void) {
	GLuint vs = compileShader(GL_VERTEX_SHADER, COMPOSITE_VS);
	if (!vs) return [NSString stringWithFormat:@"composite vertex shader: %s", error];
	GLuint fs = compileShader(GL_FRAGMENT_SHADER, COMPOSITE_FS);
	if (!fs) return [NSString stringWithFormat:@"composite fragment shader: %s", error];
	program = glCreateProgram();
	glAttachShader(program, vs);
	glAttachShader(program, fs);
	glLinkProgram(program);
	glDeleteShader(vs);
	glDeleteShader(fs);
	GLint ok = 0;
	glGetProgramiv(program, GL_LINK_STATUS, &ok);
	if (!ok) {
		glGetProgramInfoLog(program, sizeof error, NULL, error);
		return [NSString stringWithFormat:@"composite link: %s", error];
	}
	GLint prev = 0;
	glGetIntegerv(GL_CURRENT_PROGRAM, &prev);
	glUseProgram(program);
	glUniform1i(glGetUniformLocation(program, "color"), 0);
	glUniform1i(glGetUniformLocation(program, "depth"), 1);
	heightLoc = glGetUniformLocation(program, "height");
	glUseProgram(prev);
	return nil;
}

JNIEXPORT jstring JNICALL Java_mcopt_legacy_metal_MetalTerrain_nInit(JNIEnv *env, jclass c, jstring source) {
	@autoreleasepool {
		if (!CGLGetCurrentContext()) return jstr(env, @"no current GL context");
		device = MTLCreateSystemDefaultDevice();
		if (!device) return jstr(env, @"no Metal device");
		if (![device supportsFamily:MTLGPUFamilyMetal3]) return jstr(env, @"GPU without Metal 3");
		queue = [device newCommandQueue];

		const char *src = (*env)->GetStringUTFChars(env, source, NULL);
		if (!src) return jstr(env, @"out of memory");
		NSString *text = [NSString stringWithUTF8String:src];
		(*env)->ReleaseStringUTFChars(env, source, src);
		MTLCompileOptions *opts = [MTLCompileOptions new];
		opts.languageVersion = MTLLanguageVersion3_0;
		// No invariance: VOut.pos is not [[invariant]], so dev 256's depth pass and its shading at equal depth may differ
		// by a bit and drop pixels. The default path draws each layer once and doesn't care.
		opts.preserveInvariance = NO;
		NSError *err = nil;
		id<MTLLibrary> lib = [device newLibraryWithSource:text options:opts error:&err];
		if (!lib) return jstr(env, [NSString stringWithFormat:@"shader: %@", err.localizedDescription]);

		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"terrain_vs"];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
		pd.colorAttachments[1].pixelFormat = MTLPixelFormatBGRA8Unorm;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs_solid"];
		solidPipe = [device newRenderPipelineStateWithDescriptor:pd error:&err];
		if (!solidPipe) return jstr(env, [NSString stringWithFormat:@"solid pipeline: %@", err.localizedDescription]);
		pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs_cutout"];
		cutoutPipe = [device newRenderPipelineStateWithDescriptor:pd error:&err];
		if (!cutoutPipe) return jstr(env, [NSString stringWithFormat:@"cutout pipeline: %@", err.localizedDescription]);
		pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs_cutout_min"];
		minPipe = [device newRenderPipelineStateWithDescriptor:pd error:&err];
		if (!minPipe) return jstr(env, [NSString stringWithFormat:@"min pipeline: %@", err.localizedDescription]);
		pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs_depth"];
		pd.colorAttachments[0].writeMask = MTLColorWriteMaskNone;
		pd.colorAttachments[1].writeMask = MTLColorWriteMaskNone;
		depthPipe = [device newRenderPipelineStateWithDescriptor:pd error:&err];
		if (!depthPipe) return jstr(env, [NSString stringWithFormat:@"depth pipeline: %@", err.localizedDescription]);

		NSString *kernels[5] = {@"cull_split", @"cull_hiz_first", @"cull_hiz_down", @"cull_test", @"dev_spin"};
		id<MTLComputePipelineState> __strong *slots[5] = {&splitPipe, &hizFirstPipe, &hizDownPipe, &testPipe, &spinPipe};
		for (int k = 0; k < 5; k++) {
			*slots[k] = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:kernels[k]] error:&err];
			if (!*slots[k]) return jstr(env, [NSString stringWithFormat:@"%@: %@", kernels[k], err.localizedDescription]);
		}
		drawArgs = [device newBufferWithLength:6 * BUCKETS * 5 * 4 options:MTLResourceStorageModeShared];

		MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
		dd.depthCompareFunction = MTLCompareFunctionLessEqual;  // the game's glDepthFunc(GL_LEQUAL)
		dd.depthWriteEnabled = YES;
		depthState = [device newDepthStencilStateWithDescriptor:dd];
		dd.depthCompareFunction = MTLCompareFunctionEqual;  // shading the alpha-tested layers after their depth pass
		dd.depthWriteEnabled = NO;
		equalState = [device newDepthStencilStateWithDescriptor:dd];

		// the block atlas as GL samples it: nearest, with GL_NEAREST_MIPMAP_LINEAR for the mipped layers
		MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
		sd.minFilter = MTLSamplerMinMagFilterNearest;
		sd.magFilter = MTLSamplerMinMagFilterNearest;
		sd.sAddressMode = MTLSamplerAddressModeRepeat;
		sd.tAddressMode = MTLSamplerAddressModeRepeat;
		sd.mipFilter = MTLSamplerMipFilterLinear;
		mipSampler = [device newSamplerStateWithDescriptor:sd];
		sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
		flatSampler = [device newSamplerStateWithDescriptor:sd];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		lightSampler = [device newSamplerStateWithDescriptor:sd];

		uint16_t idx[UNIT_QUADS * 6];
		for (int q = 0; q < UNIT_QUADS; q++) {
			uint16_t v = (uint16_t)(q * 4);
			uint16_t *o = idx + q * 6;
			o[0] = v; o[1] = v + 1; o[2] = v + 2; o[3] = v + 2; o[4] = v + 3; o[5] = v;
		}
		quadIndices = [device newBufferWithBytes:idx length:sizeof idx options:MTLResourceStorageModeShared];

		MTLTextureDescriptor *ld = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
			width:16 height:16 mipmapped:NO];
		lightmap = [device newTextureWithDescriptor:ld];

		pages = [NSMutableArray new];

		const char *ext = (const char *)glGetString(GL_EXTENSIONS);
		nvFogDistance = ext && strstr(ext, "GL_NV_fog_distance") != NULL;

		NSString *why = initComposite();
		if (why) return jstr(env, why);
		return NULL;
	}
}

JNIEXPORT jstring JNICALL Java_mcopt_legacy_metal_MetalTerrain_nDevice(JNIEnv *env, jclass c) {
	return jstr(env, device ? device.name : @"none");
}

// A new vertex page of the given size; returns its contents (the Java side writes vertices straight into it) and puts
// its GPU address in addr[0]. NULL if Metal can't make it.
JNIEXPORT jobject JNICALL Java_mcopt_legacy_metal_MetalTerrain_nPage(JNIEnv *env, jclass c, jint bytes, jlongArray addr) {
	id<MTLBuffer> b = [device newBufferWithLength:(NSUInteger)bytes options:MTLResourceStorageModeShared];
	if (!b) return NULL;
	id<MTLBuffer> s = [device newBufferWithLength:(pages.count + 1) * PAGE_QUADS options:MTLResourceStorageModeShared];
	if (!s) return NULL;
	// the frame in flight writes the visibility it found into seen: let it finish so the copy has it (rare, a page
	// is 2M quads)
	if (pending) [pending waitUntilCompleted];
	if (retiring) [retiring waitUntilCompleted];
	[pages addObject:b];
	if (seen) memcpy(s.contents, seen.contents, seen.length);
	seen = s;
	jlong a = (jlong)b.gpuAddress;
	(*env)->SetLongArrayRegion(env, addr, 0, 1, &a);
	return (*env)->NewDirectByteBuffer(env, b.contents, (jlong)bytes);
}

// A layer's draw-unit buffer, at least the given size (contents kept on growth).
JNIEXPORT jobject JNICALL Java_mcopt_legacy_metal_MetalTerrain_nUnits(JNIEnv *env, jclass c, jint layer, jint bytes) {
	id<MTLBuffer> u = units[layer];
	if (!u || u.length < (NSUInteger)bytes) {
		// all three made before any is swapped in, so a failure leaves the old ones (still the Java side's) in place
		id<MTLBuffer> b = [device newBufferWithLength:(NSUInteger)bytes options:MTLResourceStorageModeShared];
		NSUInteger listBytes = (NSUInteger)bytes / UNIT_BYTES * 4 * BUCKETS;
		id<MTLBuffer> l0 = [device newBufferWithLength:listBytes options:MTLResourceStorageModePrivate];
		id<MTLBuffer> l1 = [device newBufferWithLength:listBytes options:MTLResourceStorageModePrivate];
		if (!b || !l0 || !l1) return NULL;
		if (u) memcpy(b.contents, u.contents, u.length);
		units[layer] = u = b;
		lists[layer][0] = l0;
		lists[layer][1] = l1;
	}
	return (*env)->NewDirectByteBuffer(env, u.contents, (jlong)u.length);
}

// The block atlas was (re)allocated as GL texture glId, levels 0..maxLevel.
JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nAtlas(JNIEnv *env, jclass c, jint glId, jint maxLevel, jint w, jint h) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
		width:(NSUInteger)w height:(NSUInteger)h mipmapped:maxLevel > 0];
	d.mipmapLevelCount = (NSUInteger)maxLevel + 1;
	atlas = [device newTextureWithDescriptor:d];
	atlasGl = glId;
}

// The atlas's sprite updates since the last frame, as blits: queued on the same queue ahead of the next frame, they
// wait for the frame in flight to finish reading the atlas, and the client thread doesn't.
static id<MTLCommandBuffer> atlasCb;
static id<MTLBlitCommandEncoder> atlasBlit;
// the sprite pixels on their way: a few staging buffers taken in turn, one per batch, each reused once the GPU is done
// with the batch that last used it (frames ago)
#define STAGES 3
#define STAGE_BYTES (1 << 20)
static id<MTLBuffer> stage[STAGES];
static id<MTLCommandBuffer> stageCb[STAGES];
static int stageAt;
static NSUInteger stageUsed;

static void flushAtlas(void) {
	if (!atlasCb) return;
	[atlasBlit endEncoding];
	[atlasCb commit];
	stageCb[stageAt] = atlasCb;
	atlasBlit = nil;
	atlasCb = nil;
}

// TextureUtil.method_7027: sprite pixels (one array per mip level, ARGB ints, which are BGRA bytes) going into the
// texture bound on the active unit. Mirrored when that texture is the block atlas.
JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nAtlasSub(JNIEnv *env, jclass c, jobjectArray levels, jint w, jint h,
	jint x, jint y) {
	if (!atlas) return;
	GLint bound = 0;
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound);
	if (bound != atlasGl) return;
	jsize n = (*env)->GetArrayLength(env, levels);
	for (jsize m = 0; m < n && (NSUInteger)m < atlas.mipmapLevelCount; m++) {
		int lw = w >> m, lh = h >> m;
		if (lw <= 0 || lh <= 0) break;
		jintArray arr = (jintArray)(*env)->GetObjectArrayElement(env, levels, m);
		if (!arr) continue;
		int lx = x >> m, ly = y >> m;
		int fits = lx >= 0 && ly >= 0 && (NSUInteger)(lx + lw) <= atlas.width >> m && (NSUInteger)(ly + lh) <= atlas.height >> m;
		if (fits && (*env)->GetArrayLength(env, arr) >= lw * lh) {
			NSUInteger bytes = (NSUInteger)lw * lh * 4;
			if (!atlasCb) {
				stageAt = (stageAt + 1) % STAGES;
				if (stageCb[stageAt]) [stageCb[stageAt] waitUntilCompleted];
				stageCb[stageAt] = nil;
				if (!stage[stageAt]) stage[stageAt] = [device newBufferWithLength:STAGE_BYTES options:MTLResourceStorageModeShared];
				stageUsed = 0;
				atlasCb = [queue commandBuffer];
				atlasBlit = [atlasCb blitCommandEncoder];
			}
			// past the batch's staging buffer (a big texture pack's sprite): a buffer of its own
			id<MTLBuffer> staging = stage[stageAt];
			NSUInteger offset = (stageUsed + 255) & ~(NSUInteger)255;
			if (!staging || offset + bytes > staging.length) {
				staging = [device newBufferWithLength:bytes options:MTLResourceStorageModeShared];
				offset = 0;
			} else {
				stageUsed = offset + bytes;
			}
			jint *px = staging ? (*env)->GetPrimitiveArrayCritical(env, arr, NULL) : NULL;
			if (px) {
				memcpy((char *)staging.contents + offset, px, bytes);
				(*env)->ReleasePrimitiveArrayCritical(env, arr, px, JNI_ABORT);
				[atlasBlit copyFromBuffer:staging sourceOffset:offset sourceBytesPerRow:(NSUInteger)lw * 4
					sourceBytesPerImage:bytes sourceSize:MTLSizeMake((NSUInteger)lw, (NSUInteger)lh, 1) toTexture:atlas
					destinationSlice:0 destinationLevel:(NSUInteger)m destinationOrigin:MTLOriginMake((NSUInteger)lx, (NSUInteger)ly, 0)];
			}
		}
		(*env)->DeleteLocalRef(env, arr);
	}
}

static void freeTargets(void) {
	for (int i = 0; i < RING; i++) {
		Target *t = &targets[i];
		if (t->glColor) glDeleteTextures(1, &t->glColor);
		if (t->glDepth) glDeleteTextures(1, &t->glDepth);
		if (t->fence) glDeleteFencesAPPLE(1, &t->fence);
		for (int k = 0; k < 2; k++) if (t->surface[k]) CFRelease(t->surface[k]);
		t->color = nil;
		t->depth = nil;
		t->surface[0] = t->surface[1] = NULL;
		t->glColor = t->glDepth = t->fence = 0;
		t->fenced = 0;
	}
	depthBuffer = nil;
	drawColor = drawDepth = nil;
	for (int k = 0; k < HIZ_LEVELS; k++) hiz[k] = nil;
	hizLevels = 0;
	targetW = targetH = 0;
}

static id<MTLTexture> makeSurface(int w, int h, IOSurfaceRef *surface, GLuint *gl) {
	NSDictionary *p = @{
		(id)kIOSurfaceWidth: @(w), (id)kIOSurfaceHeight: @(h), (id)kIOSurfaceBytesPerElement: @4,
		(id)kIOSurfacePixelFormat: @((uint32_t)'BGRA')
	};
	*surface = IOSurfaceCreate((__bridge CFDictionaryRef)p);
	if (!*surface) return nil;
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
		width:(NSUInteger)w height:(NSUInteger)h mipmapped:NO];
	d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	id<MTLTexture> tex = [device newTextureWithDescriptor:d iosurface:*surface plane:0];
	if (!tex) return nil;
	glGenTextures(1, gl);
	glBindTexture(GL_TEXTURE_RECTANGLE_ARB, *gl);
	CGLError e = CGLTexImageIOSurface2D(CGLGetCurrentContext(), GL_TEXTURE_RECTANGLE_ARB, GL_RGBA, w, h, GL_BGRA,
		GL_UNSIGNED_INT_8_8_8_8_REV, *surface, 0);
	glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_RECTANGLE_ARB, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	return e == kCGLNoError ? tex : nil;
}

static int makeTargets(int w, int h) {
	freeTargets();
	GLint prevRect = 0;
	glGetIntegerv(GL_TEXTURE_BINDING_RECTANGLE_ARB, &prevRect);
	int ok = 1;
	for (int i = 0; i < RING && ok; i++) {
		Target *t = &targets[i];
		t->color = makeSurface(w, h, &t->surface[0], &t->glColor);
		t->depth = makeSurface(w, h, &t->surface[1], &t->glDepth);
		ok = t->color && t->depth;
		glGenFencesAPPLE(1, &t->fence);
	}
	glBindTexture(GL_TEXTURE_RECTANGLE_ARB, (GLuint)prevRect);
	if (!ok) {
		freeTargets();
		return 0;
	}
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
		width:(NSUInteger)w height:(NSUInteger)h mipmapped:NO];
	d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
	d.storageMode = MTLStorageModePrivate;
	depthBuffer = [device newTextureWithDescriptor:d];
	if (!depthBuffer) return 0;
	MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
		width:(NSUInteger)w height:(NSUInteger)h mipmapped:NO];
	cd.usage = MTLTextureUsageRenderTarget;
	cd.storageMode = MTLStorageModePrivate;
	if (devFlags & 512) {
		drawColor = [device newTextureWithDescriptor:cd];
		drawDepth = [device newTextureWithDescriptor:cd];
		if (!drawColor || !drawDepth) return 0;
	}
	// the depth pyramid: level k (1..) is ceil(w / 2^k) x ceil(h / 2^k), down to one texel or HIZ_LEVELS levels
	// (whichever comes first), and at least one level even for a one-pixel window
	hizLevels = 0;
	for (int lw = w, lh = h; (lw > 1 || lh > 1 || hizLevels == 0) && hizLevels < HIZ_LEVELS;) {
		lw = (lw + 1) / 2;
		lh = (lh + 1) / 2;
		MTLTextureDescriptor *hd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
			width:(NSUInteger)lw height:(NSUInteger)lh mipmapped:NO];
		hd.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
		hd.storageMode = MTLStorageModePrivate;
		hiz[hizLevels] = [device newTextureWithDescriptor:hd];
		if (!hiz[hizLevels]) return 0;
		hizLevels++;
	}
	for (int k = hizLevels; k < HIZ_LEVELS; k++) hiz[k] = hiz[hizLevels - 1];
	targetW = w;
	targetH = h;
	return 1;
}

static void readFrame(Frame *f) {
	memset(f, 0, sizeof *f);
	glGetFloatv(GL_MODELVIEW_MATRIX, f->mv);
	glGetFloatv(GL_PROJECTION_MATRIX, f->proj);
	if (glIsEnabled(GL_FOG)) {
		GLint mode = 0;
		glGetIntegerv(GL_FOG_MODE, &mode);
		f->fogMode = mode == GL_LINEAR ? 1 : mode == GL_EXP ? 2 : mode == GL_EXP2 ? 3 : 0;
		glGetFloatv(GL_FOG_START, &f->fogStart);
		glGetFloatv(GL_FOG_END, &f->fogEnd);
		glGetFloatv(GL_FOG_DENSITY, &f->fogDensity);
		glGetFloatv(GL_FOG_COLOR, f->fogColor);
		if (nvFogDistance) {
			GLint dm = 0;
			glGetIntegerv(0x855A, &dm);  // GL_FOG_DISTANCE_MODE_NV
			f->radial = dm == 0x855B;  // GL_EYE_RADIAL_NV
		}
	}
	GLint func = GL_ALWAYS;
	glGetIntegerv(GL_ALPHA_TEST_FUNC, &func);
	f->alphaFunc = func;
	glGetFloatv(GL_ALPHA_TEST_REF, &f->alphaRef);
}

static void composite(Target *t, int w, int h) {
	GLint prevProgram = 0, prevActive = 0;
	glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
	glGetIntegerv(GL_ACTIVE_TEXTURE, &prevActive);
	glPushAttrib(GL_ENABLE_BIT | GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT | GL_POLYGON_BIT);
	glDisable(GL_BLEND);
	glDisable(GL_ALPHA_TEST);
	glDisable(GL_CULL_FACE);
	glDisable(GL_FOG);
	glDisable(GL_STENCIL_TEST);
	glDisable(GL_SCISSOR_TEST);
	glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
	glEnable(GL_DEPTH_TEST);
	glDepthFunc(GL_ALWAYS);
	glDepthMask(GL_TRUE);
	glUseProgram(program);
	glUniform1f(heightLoc, (float)h);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_RECTANGLE_ARB, t->glDepth);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_RECTANGLE_ARB, t->glColor);
	glBegin(GL_TRIANGLE_STRIP);
	glVertex2f(-1, -1);
	glVertex2f(1, -1);
	glVertex2f(-1, 1);
	glVertex2f(1, 1);
	glEnd();
	glPopAttrib();
	glUseProgram((GLuint)prevProgram);
	glActiveTexture((GLenum)prevActive);
	glSetFenceAPPLE(t->fence);
	t->fenced = 1;
}

// dev: the frame's draw as a file for the standalone bench. Layout: "MCD1", int w, h, the Frame, then per layer an int
// unit count and per unit (int count, int sx, sy, sz (the section origin in blocks), count quads of 64 bytes).
static void dumpFrame(const Frame *f, int w, int h, const jint *counts) {
	FILE *out = fopen(dumpPath, "wb");
	dumpPath[0] = 0;
	if (!out) return;
	fwrite("MCD1", 1, 4, out);
	fwrite(&w, 4, 1, out);
	fwrite(&h, 4, 1, out);
	fwrite(f, sizeof *f, 1, out);
	for (int layer = 0; layer < 3; layer++) {
		int n = counts[layer];
		fwrite(&n, 4, 1, out);
		const char *u = units[layer].contents;
		for (int i = 0; i < n; i++) {
			uint64_t addr;
			uint16_t n16;
			int o[3];
			memcpy(&addr, u + i * UNIT_BYTES, 8);
			memcpy(o, u + i * UNIT_BYTES + 8, 12);
			memcpy(&n16, u + i * UNIT_BYTES + 24, 2);
			uint32_t count = n16 & 0xFF;
			const char *src = NULL;
			for (id<MTLBuffer> p in pages) {
				if (addr >= p.gpuAddress && addr < p.gpuAddress + p.length) src = (const char *)p.contents + (addr - p.gpuAddress);
			}
			if (!src) count = 0;
			fwrite(&count, 4, 1, out);
			fwrite(o, 4, 3, out);
			if (count) fwrite(src, QUAD_BYTES, count, out);
		}
	}
	fclose(out);
}

// Starts drawing this frame's opaque terrain (each layer's units from its own buffer) into the next target, with the
// GL camera, projection and fog state current at the call. nFinish later composites it; the client thread does other
// work in between. 0 submitted (or nothing to draw yet), else an error code and the caller falls back to GL: 1 not set
// up (no atlas or unit buffers), 2 empty viewport, 3 the targets couldn't be made, 5 a frame still pending or retiring.
static Target *pendingTarget;
static int pendingW, pendingH;
static double pendingT0, pendingT1;
static int pendingStages;  // dev 2048: which of the six stage timings this frame has

JNIEXPORT jint JNICALL Java_mcopt_legacy_metal_MetalTerrain_nSubmit(JNIEnv *env, jclass c, jint solid, jint mipped, jint cutout,
	jintArray lightPixels, jdouble camX, jdouble camY, jdouble camZ) {
	@autoreleasepool {
		if (pending || retiring) return 5;  // the last frame's work was never finished or retired
		if (!seen) return 0;  // no meshes yet: nothing to draw
		flushAtlas();
		if (!atlas || !units[0] || !units[1] || !units[2]) return 1;
		if (devFlags & 8) glFinish();  // dev: the GPU free of GL's work before timing Metal's
		double t0 = now();
		GLint vp[4];
		glGetIntegerv(GL_VIEWPORT, vp);
		int w = vp[2], h = vp[3];
		if (w <= 0 || h <= 0) return 2;
		if (w != targetW || h != targetH) {
			if (!makeTargets(w, h)) return 3;
		}
		Target *t = &targets[frameNo % RING];
		if (t->fenced) glFinishFenceAPPLE(t->fence);
		if (devFlags & 16384) {
			// dev race test: the target magenta before Metal draws, so a composite that reads it early shows
			IOSurfaceLock(t->surface[0], 0, NULL);
			uint32_t *px = IOSurfaceGetBaseAddress(t->surface[0]);
			size_t n = IOSurfaceGetAllocSize(t->surface[0]) / 4;
			for (size_t k = 0; k < n; k++) px[k] = 0xFFFF00FF;
			IOSurfaceUnlock(t->surface[0], 0, NULL);
		}

		if (lightPixels && (*env)->GetArrayLength(env, lightPixels) >= 256) {
			jint *px = (*env)->GetPrimitiveArrayCritical(env, lightPixels, NULL);
			if (px) {
				[lightmap replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:px bytesPerRow:64];
				(*env)->ReleasePrimitiveArrayCritical(env, lightPixels, px, JNI_ABORT);
			}
		}

		Frame f;
		readFrame(&f);
		double fx = floor(camX), fy = floor(camY), fz = floor(camZ);
		f.cam = (Camera){(int)fx, (int)fy, (int)fz, (float)(camX - fx), (float)(camY - fy), (float)(camZ - fz), 0, 0};
		if (devFlags & 4) f.pad = 1;
		if (dumpPath[0]) {
			jint dc[3] = {solid, mipped, cutout};
			dumpFrame(&f, w, h, dc);
		}

		jint counts[3] = {solid, mipped, cutout};
		if (devFlags & 2) counts[1] = counts[2] = 0;  // dev: no alpha-tested layers
		int cull = !(devFlags & 16);
		uint32_t *args = drawArgs.contents;
		for (int k = 0; k < 6 * BUCKETS; k++) {
			uint32_t *a = args + k * 5;
			int bucket = k % BUCKETS, quads = 8 * ((bucket & 7) + 1);
			a[0] = (uint32_t)(quads < unitQuads ? quads : unitQuads) * 6;
			// culling off: everything in the first draw's last bucket
			a[1] = cull || bucket != BUCKETS - 1 || (k / BUCKETS) & 1 ? 0 : (uint32_t)counts[k / BUCKETS >> 1];
			a[2] = a[3] = a[4] = 0;
		}
		CullFrame cf;
		for (int col = 0; col < 4; col++)
			for (int row = 0; row < 4; row++) {
				float v = 0;
				for (int k = 0; k < 4; k++) v += f.proj[k * 4 + row] * f.mv[col * 4 + k];
				cf.mvp[col * 4 + row] = v;
			}
		memcpy(f.mvp, cf.mvp, sizeof f.mvp);
		cf.width = (float)w;
		cf.height = (float)h;
		cf.levels = (uint32_t)hizLevels;
		cf.pad = 0;
		cf.cam = f.cam;

		static int stageTried;
		if ((devFlags & 2048) && !stageBuf && !stageTried) {
			stageTried = 1;
			for (id<MTLCounterSet> set in device.counterSets) {
				if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
				if (![device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) break;
				MTLCounterSampleBufferDescriptor *d = [MTLCounterSampleBufferDescriptor new];
				d.counterSet = set;
				d.sampleCount = 12;
				d.storageMode = MTLStorageModeShared;
				stageBuf = [device newCounterSampleBufferWithDescriptor:d error:nil];
				[device sampleTimestamps:&calCpu gpuTimestamp:&calGpu];
			}
		}

		id<MTLCommandBuffer> cb = [queue commandBuffer];
		NSUInteger tg = splitPipe.threadExecutionWidth;
		if (devFlags & 16384) {
			// and a few milliseconds of GPU work first
			static id<MTLBuffer> spinOut;
			if (!spinOut) spinOut = [device newBufferWithLength:65536 * 4 options:MTLResourceStorageModePrivate];
			id<MTLComputeCommandEncoder> se = [cb computeCommandEncoder];
			uint32_t iters = 200000;
			[se setComputePipelineState:spinPipe];
			[se setBuffer:spinOut offset:0 atIndex:0];
			[se setBytes:&iters length:4 atIndex:1];
			[se dispatchThreads:MTLSizeMake(65536, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)];
			[se endEncoding];
		}
		if (cull) {
			id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoderWithDescriptor:stagePass(0)];
			[ce setComputePipelineState:splitPipe];
			[ce setBuffer:seen offset:0 atIndex:2];
			for (int layer = 0; layer < 3; layer++) {
				if (counts[layer] <= 0) continue;
				uint32_t n = (uint32_t)counts[layer];
				[ce setBuffer:units[layer] offset:0 atIndex:0];
				[ce setBytes:&n length:4 atIndex:1];
				uint32_t cap = (uint32_t)(lists[layer][0].length / 4 / BUCKETS);
				[ce setBuffer:lists[layer][0] offset:0 atIndex:3];
				[ce setBuffer:drawArgs offset:(NSUInteger)(layer * 2) * BUCKETS * 20 atIndex:4];
				[ce setBytes:&cf length:sizeof cf atIndex:5];
				[ce setBytes:&cap length:4 atIndex:6];
				[ce dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)];
			}
			[ce endEncoding];
		} else {
			// culling off: the first draw's lists are every unit in order
			for (int layer = 0; layer < 3; layer++) {
				if (counts[layer] <= 0) continue;
				if (!identity[layer] || identity[layer].length < (NSUInteger)counts[layer] * 4) {
					identity[layer] = [device newBufferWithLength:units[layer].length / UNIT_BYTES * 4 options:MTLResourceStorageModeShared];
					uint32_t *ix = identity[layer].contents;
					for (NSUInteger k = 0; k < identity[layer].length / 4; k++) ix[k] = (uint32_t)k;
				}
			}
		}

		for (int pass = 0; pass < (cull ? 2 : 1); pass++) {
			if (pass == 1 && (devFlags & 32)) break;  // dev: no second render pass (its units show a frame late)
			MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
			MTLLoadAction load = pass == 0 ? MTLLoadActionClear : MTLLoadActionLoad;
			if ((devFlags & 16384) && (devFlags & 4)) load = MTLLoadActionLoad;  // dev: the race test's magenta kept
			int priv = (devFlags & 512) && drawColor;
			rp.colorAttachments[0].texture = priv ? drawColor : t->color;
			rp.colorAttachments[0].loadAction = load;
			rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
			rp.colorAttachments[0].storeAction = MTLStoreActionStore;
			rp.colorAttachments[1].texture = priv ? drawDepth : t->depth;
			rp.colorAttachments[1].loadAction = load;
			rp.colorAttachments[1].clearColor = MTLClearColorMake(1, 1, 1, 1);
			rp.colorAttachments[1].storeAction = MTLStoreActionStore;
			if (devFlags & 64) rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;  // dev: the packed depth's cost
			if (devFlags & 64) rp.colorAttachments[1].storeAction = MTLStoreActionDontCare;
			rp.depthAttachment.texture = depthBuffer;
			rp.depthAttachment.loadAction = load;
			rp.depthAttachment.clearDepth = 1.0;
			rp.depthAttachment.storeAction = pass == 0 && cull ? MTLStoreActionStore : MTLStoreActionDontCare;
			if (stageBuf) {
				MTLRenderPassSampleBufferAttachmentDescriptor *a = rp.sampleBufferAttachments[0];
				a.sampleBuffer = stageBuf;
				a.startOfVertexSampleIndex = 2 + pass * 6;
				a.endOfVertexSampleIndex = 3 + pass * 6;
				a.startOfFragmentSampleIndex = 4 + pass * 6;
				a.endOfFragmentSampleIndex = 5 + pass * 6;
			}

			id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
			[e setViewport:(MTLViewport){0, 0, w, h, 0, 1}];
			[e setFrontFacingWinding:MTLWindingCounterClockwise];
			[e setCullMode:MTLCullModeBack];
			[e setDepthStencilState:depthState];
			if (devFlags & 1) [e setScissorRect:(MTLScissorRect){0, 0, 1, 1}];  // dev: vertex cost alone
			for (id<MTLBuffer> p in pages) [e useResource:p usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
			[e setVertexBytes:&f length:sizeof f atIndex:2];
			[e setFragmentBytes:&f length:sizeof f atIndex:0];
			[e setFragmentTexture:atlas atIndex:0];
			[e setFragmentTexture:lightmap atIndex:1];
			[e setFragmentSamplerState:lightSampler atIndex:1];
			// the solid layer, then the alpha-tested ones (dev 256: their depth first, then their shading at equal depth,
			// which measured slower; dev 128: no alpha test)
			int depthPass = (devFlags & 256) && !(devFlags & 128);
			for (int step = 0; step < (depthPass ? 5 : 3); step++) {
				int layer = step < 3 ? step : step - 2;
				if (counts[layer] <= 0) continue;
				id<MTLRenderPipelineState> pipe = layer == 0 || (devFlags & 128) ? solidPipe : cutoutPipe;
				if (depthPass && layer > 0) pipe = step < 3 ? depthPipe : solidPipe;
				if ((devFlags & 1024) && layer > 0) pipe = minPipe;
				[e setRenderPipelineState:pipe];
				if (depthPass && step == 3) [e setDepthStencilState:equalState];
				[e setFragmentSamplerState:layer == 2 ? flatSampler : mipSampler atIndex:0];
				[e setVertexBuffer:units[layer] offset:0 atIndex:0];
				NSUInteger cap = lists[layer][0].length / 4 / BUCKETS;
				for (int bucket = 0; bucket < BUCKETS; bucket++) {
					if (cull) [e setVertexBuffer:lists[layer][pass] offset:bucket * cap * 4 atIndex:1];
					else if (bucket == BUCKETS - 1) [e setVertexBuffer:identity[layer] offset:0 atIndex:1];
					else continue;
					[e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:quadIndices
						indexBufferOffset:0 indirectBuffer:drawArgs
						indirectBufferOffset:((NSUInteger)(layer * 2 + pass) * BUCKETS + bucket) * 20];
				}
			}
			[e endEncoding];

			if (pass == 0 && cull) {
				// the first draw goes to the GPU now, while the rest is encoded
				[cb commit];
				pendingFirst = cb;
				cb = [queue commandBuffer];
				id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoderWithDescriptor:stagePass(6)];
				[ce setComputePipelineState:hizFirstPipe];
				[ce setTexture:depthBuffer atIndex:0];
				[ce setTexture:hiz[0] atIndex:1];
				[ce dispatchThreads:MTLSizeMake(hiz[0].width, hiz[0].height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
				[ce setComputePipelineState:hizDownPipe];
				for (int k = 1; k < hizLevels; k++) {
					[ce memoryBarrierWithScope:MTLBarrierScopeTextures];
					[ce setTexture:hiz[k - 1] atIndex:0];
					[ce setTexture:hiz[k] atIndex:1];
					[ce dispatchThreads:MTLSizeMake(hiz[k].width, hiz[k].height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
				}
				[ce memoryBarrierWithScope:MTLBarrierScopeTextures];
				[ce setComputePipelineState:testPipe];
				[ce setBuffer:seen offset:0 atIndex:2];
				[ce setBytes:&cf length:sizeof cf atIndex:5];
				[ce setTextures:hiz withRange:NSMakeRange(0, HIZ_LEVELS)];
				for (int layer = 0; layer < 3; layer++) {
					if (counts[layer] <= 0) continue;
					uint32_t n = (uint32_t)counts[layer];
					[ce setBuffer:units[layer] offset:0 atIndex:0];
					[ce setBytes:&n length:4 atIndex:1];
					uint32_t cap = (uint32_t)(lists[layer][1].length / 4 / BUCKETS);
					[ce setBuffer:lists[layer][1] offset:0 atIndex:3];
					[ce setBuffer:drawArgs offset:(NSUInteger)(layer * 2 + 1) * BUCKETS * 20 atIndex:4];
					[ce setBytes:&cap length:4 atIndex:6];
					[ce dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)];
				}
				[ce endEncoding];
			}
		}
		if ((devFlags & 512) && drawColor) {
			id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
			[be copyFromTexture:drawColor toTexture:t->color];
			[be copyFromTexture:drawDepth toTexture:t->depth];
			[be endEncoding];
		}
		[cb commit];
		if (!cull) pendingFirst = cb;
		pending = cb;
		pendingTarget = t;
		pendingW = w;
		pendingH = h;
		pendingT0 = t0;
		pendingT1 = now();
		pendingStages = !cull ? 0x06 : (devFlags & 32) ? 0x07 : 0x3F;
		return 0;
	}
}

// the finished frame's times and counts, for nStats
static void record(id<MTLCommandBuffer> cb) {
	statGpu += cb.GPUEndTime - pendingFirst.GPUStartTime;
	statFirst += pendingFirst.GPUEndTime - pendingFirst.GPUStartTime;
	pendingFirst = nil;
	if (stageBuf) {
		NSData *d = [stageBuf resolveCounterRange:NSMakeRange(0, 12)];
		const MTLCounterResultTimestamp *r = d.bytes;
		MTLTimestamp c2, g2;
		[device sampleTimestamps:&c2 gpuTimestamp:&g2];
		// GPU ticks to seconds from the CPU clock and the GPU's sampled together. The CPU timestamps read as
		// nanoseconds here (the stage sums came out equal to the command buffers' GPU time that way).
		double k = g2 > calGpu ? (double)(c2 - calCpu) / (double)(g2 - calGpu) / 1e9 : 0;
		for (int s = 0; d && s < 6; s++) {
			if (!(pendingStages >> s & 1)) continue;  // a stage this frame skipped keeps an old sample
			uint64_t a = r[s * 2].timestamp, b = r[s * 2 + 1].timestamp;
			if (a && b && a != MTLCounterErrorValue && b != MTLCounterErrorValue && b > a) statStage[s] += (b - a) * k;
		}
	}
	const uint32_t *args = drawArgs.contents;
	for (int k = 0; k < 6 * BUCKETS; k++) {
		if ((k / BUCKETS & 1) && !(pendingStages & 0x38)) continue;  // no second draw (dev 32): its lists went unused
		statDrawn[k / BUCKETS] += args[k * 5 + 1];
		statSlots += (double)args[k * 5 + 1] * args[k * 5] / 6;
	}
	frameNo++;
}

// Waits for the submitted frame and, when draw is set, composites it into the bound GL framebuffer. 0 drawn (or nothing
// was submitted), 1 drawn while the GPU may still be on it (nRetire waits for that), else an error code.
JNIEXPORT jint JNICALL Java_mcopt_legacy_metal_MetalTerrain_nFinish(JNIEnv *env, jclass c, jboolean draw) {
	@autoreleasepool {
		id<MTLCommandBuffer> cb = pending;
		if (!cb) return 0;
		pending = nil;
		// Composited once scheduled: the GL composite's reads of the IOSurfaces run after Metal's writes (the race test,
		// dev 16384, shows none read early even with no wait at all). Waiting for the GPU to finish instead leaves it
		// idle while the client thread draws the rest of the frame, and the clock it then runs at makes that wait
		// take longer than the draw does at full speed. Dev 8192: wait for the finish.
		int early = draw && !(devFlags & 8192);
		double t1 = now();
		if (early && (devFlags & 32768)) {}  // dev: no wait at all (the race test's control)
		else if (early) [cb waitUntilScheduled];
		else [cb waitUntilCompleted];
		double t2 = now();
		if (cb.status == MTLCommandBufferStatusError) {
			pendingFirst = nil;
			return 4;
		}
		if (draw && !(devFlags & 4096)) composite(pendingTarget, pendingW, pendingH);
		double t3 = now();
		statEncode += pendingT1 - pendingT0;
		statWait += t2 - t1;
		statComposite += t3 - t2;
		if (early) {
			retiring = cb;
			return 1;
		}
		record(cb);
		return 0;
	}
}

// Waits until the GPU is done with a frame nFinish composited early.
JNIEXPORT jint JNICALL Java_mcopt_legacy_metal_MetalTerrain_nRetire(JNIEnv *env, jclass c) {
	@autoreleasepool {
		id<MTLCommandBuffer> cb = retiring;
		if (!cb) return 0;
		retiring = nil;
		[cb waitUntilCompleted];
		if (cb.status == MTLCommandBufferStatusError) {
			pendingFirst = nil;
			return 4;
		}
		record(cb);
		return 0;
	}
}

JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nDev(JNIEnv *env, jclass c, jint flags) {
	devFlags = flags & 0xFFFFFF;
	int unit = flags >> 24;
	if (unit > 0 && unit <= UNIT_QUADS) unitQuads = unit;
}

// Dev: the next frame's draw goes to this file (see dumpFrame).
JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nDump(JNIEnv *env, jclass c, jstring path) {
	const char *p = (*env)->GetStringUTFChars(env, path, NULL);
	if (!p) return;
	strlcpy(dumpPath, p, sizeof dumpPath);
	(*env)->ReleaseStringUTFChars(env, path, p);
}

// Since the last call: milliseconds encoding, waiting for the GPU, GPU time and compositing, then units drawn per layer
// and pass (6), the first command buffer's GPU milliseconds, and with room for 18, the six stage timings (ms, dev
// 2048) and the quad slots drawn.
JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nStats(JNIEnv *env, jclass c, jdoubleArray out) {
	if ((*env)->GetArrayLength(env, out) < 11) return;
	jdouble v[11] = {statEncode * 1e3, statWait * 1e3, statGpu * 1e3, statComposite * 1e3};
	for (int k = 0; k < 6; k++) {
		v[4 + k] = statDrawn[k];
		statDrawn[k] = 0;
	}
	v[10] = statFirst * 1e3;
	(*env)->SetDoubleArrayRegion(env, out, 0, 11, v);
	if ((*env)->GetArrayLength(env, out) >= 18) {
		jdouble st[6];
		for (int k = 0; k < 6; k++) {
			st[k] = statStage[k] * 1e3;
			statStage[k] = 0;
		}
		(*env)->SetDoubleArrayRegion(env, out, 11, 6, st);
		jdouble q = statSlots;
		statSlots = 0;
		(*env)->SetDoubleArrayRegion(env, out, 17, 1, &q);
	}
	statEncode = statWait = statGpu = statFirst = statComposite = 0;
}

// Drops the composite targets (the GL context is going away or Metal is being turned off).
JNIEXPORT void JNICALL Java_mcopt_legacy_metal_MetalTerrain_nShutdown(JNIEnv *env, jclass c) {
	if (pending) [pending waitUntilCompleted];
	pending = nil;
	if (retiring) [retiring waitUntilCompleted];
	retiring = nil;
	pendingFirst = nil;
	pendingTarget = NULL;
	flushAtlas();
	for (int k = 0; k < STAGES; k++) {
		if (stageCb[k]) [stageCb[k] waitUntilCompleted];
		stageCb[k] = nil;
		stage[k] = nil;
	}
	stageBuf = nil;
	freeTargets();
	if (program) glDeleteProgram(program);
	program = 0;
}
