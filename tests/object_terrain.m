#import "mcmetal.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void *mco_new(Ctx *, const char *, int, int, int, char *, int);
extern int mco_object_ready(void *, Enc *, char *, int);
extern int mco_object_draw(void *, Enc *, const void *, const void *, int, id, id, id, id, const int64_t *, id, id, id, id, char *, int);
extern void mco_cull(void *, Enc *, const void *, int, id, id, id, id, id, int, id, id, id, int, id, int);
extern int mco_draw(void *, Enc *, int, const void *, int, id, id, id, id, id, int64_t, const int64_t *, id, id, id, id, id, char *, int);

typedef struct {
	int32_t x, y, z, uploadMs;
	uint32_t start, count, pad0, pad1;
} TestSection;

typedef struct {
	uint32_t start, slot, meta;
	int32_t plane;
	uint32_t lo, hi, pad0, pad1;
} TestRecord;

typedef struct {
	float position[3];
	uint8_t color[4];
	float uv[2];
	int16_t light[2];
} TestVertex;

typedef struct {
	uint16_t position[3];
	uint8_t light[2], color[4];
	uint16_t uv[2];
} CompactVertex;

typedef struct {
	Ctx ctx;
	void *own;
	id<MTLBuffer> arena, sections, records, mask, args, lists, scratch;
	id<MTLBuffer> projection, globals, terrain, fog;
	id<MTLTexture> atlas, light;
	id<MTLSamplerState> sampler;
	uint8_t cull[144], draw[80];
	int64_t uniforms[8];
	int compact, cpuClip, recordCount, quadRecords, exactPositions;
} Fixture;

static void fail(const char *message) {
	fprintf(stderr, "%s\n", message);
	exit(1);
}

static id<MTLBuffer> buffer(Fixture *f, NSUInteger size) {
	id<MTLBuffer> b = [f->ctx.device newBufferWithLength:size options:MTLResourceStorageModeShared];
	if (!b) fail("buffer allocation failed");
	memset(b.contents, 0, size);
	return b;
}

static void identity(void *data) {
	float *m = data;
	memset(m, 0, 64);
	for (int i = 0; i < 4; i++) m[i * 5] = 1;
}

static id<MTLTexture> texture(Fixture *f, MTLPixelFormat format, NSUInteger size, MTLTextureUsage usage) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:size height:size mipmapped:NO];
	d.usage = usage;
	d.storageMode = MTLStorageModeShared;
	id<MTLTexture> t = [f->ctx.device newTextureWithDescriptor:d];
	if (!t) fail("texture allocation failed");
	return t;
}

static void setup(Fixture *f, NSString *source, int encoding, int cpuClip) {
	memset(f, 0, sizeof *f);
	f->ctx.device = MTLCreateSystemDefaultDevice();
	f->ctx.queue = [f->ctx.device newCommandQueue];
	f->compact = encoding != 0;
	f->quadRecords = encoding == 2 || encoding == 4;
	f->exactPositions = encoding >= 3;
	f->cpuClip = cpuClip;
	if (f->quadRecords) source = [@"#define OWN_QREC 1\n" stringByAppendingString:source];
	if (f->exactPositions) source = [@"#define OWN_EXACT_POS 1\n" stringByAppendingString:source];
	char error[16384] = {0};
	f->own = mco_new(&f->ctx, source.UTF8String, f->compact, 0, cpuClip, error, sizeof error);
	if (!f->own) fail(error);
	f->arena = buffer(f, 2 * 1024 * 1024);
	f->sections = buffer(f, 64 * sizeof(TestSection));
	f->records = buffer(f, 64 * sizeof(TestRecord));
	f->mask = buffer(f, 8);
	f->args = buffer(f, 256);
	f->lists = buffer(f, 512);
	f->scratch = buffer(f, 4096);
	f->projection = buffer(f, 64);
	f->globals = buffer(f, 48);
	f->terrain = buffer(f, 80);
	f->fog = buffer(f, 40);
	identity(f->projection.contents);
	identity(f->terrain.contents);
	((int32_t *)f->terrain.contents)[16] = 2;
	((int32_t *)f->terrain.contents)[17] = 2;
	float *fog = f->fog.contents;
	fog[4] = fog[6] = 100;
	fog[5] = fog[7] = 200;
	f->atlas = texture(f, MTLPixelFormatRGBA8Unorm, 2, MTLTextureUsageShaderRead);
	f->light = texture(f, MTLPixelFormatRGBA8Unorm, 2, MTLTextureUsageShaderRead);
	uint32_t atlas[4] = {0xff5090d0, 0xff30b070, 0xffd07050, 0xffa050b0};
	uint32_t light[4] = {0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff};
	[f->atlas replaceRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0 withBytes:atlas bytesPerRow:8];
	[f->light replaceRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0 withBytes:light bytesPerRow:8];
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterNearest;
	sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	f->sampler = [f->ctx.device newSamplerStateWithDescriptor:sd];
	[sd release];
	f->uniforms[0] = (int64_t)f->projection;
	f->uniforms[2] = (int64_t)f->globals;
	f->uniforms[4] = (int64_t)f->terrain;
	f->uniforms[6] = (int64_t)f->fog;
}

static void scene(Fixture *f, int records, int quads) {
	f->recordCount = records;
	memset(f->cull, 0, sizeof f->cull);
	memset(f->draw, 0, sizeof f->draw);
	memset(f->sections.contents, 0, f->sections.length);
	memset(f->records.contents, 0, f->records.length);
	memset(f->globals.contents, 0, f->globals.length);
	memset(f->mask.contents, 255, f->mask.length);
	float planes[16] = {1, 0, 0, 1, -1, 0, 0, 1, 0, 1, 0, 1, 0, -1, 0, 1};
	memcpy(f->cull, planes, sizeof planes);
	((int32_t *)f->cull)[28] = 16;
	((uint32_t *)f->cull)[29] = 64;
	((uint32_t *)f->cull)[30] = 64;
	((int32_t *)f->cull)[32] = 64;
	((int32_t *)f->draw)[1] = 2000;
	((int32_t *)f->draw)[3] = f->cpuClip;
	identity(f->draw + 16);
	for (int record = 0; record < records; record++) {
		((TestSection *)f->sections.contents)[record] = (TestSection){0, 0, 0, 0, record, 1, 0, 0};
		((TestRecord *)f->records.contents)[record] = (TestRecord){512 + record * 64, record, (quads - 1) | (6 << 6), 0, 0x0f0f0f, 0x111111, 0, 0};
		for (int q = 0; q < quads; q++) {
			float x = -0.875f + (q % 8) * 0.21875f;
			float y = -0.875f + (q / 8) * 0.21875f;
			float corners[4][2] = {{x, y}, {x + 0.1875f, y}, {x + 0.1875f, y + 0.1875f}, {x, y + 0.1875f}};
			for (int c = 0; c < 4; c++) {
				NSUInteger at = ((f->quadRecords ? 8192 : 512) + record * 64 + q) * 4 + c;
				float z = 0.25f + (record % 32) / 64.0f;
				uint8_t rgba[4] = {255, (uint8_t)(180 + record), (uint8_t)(140 + q), 255};
				if (f->compact) {
					CompactVertex v = {{(uint16_t)((corners[c][0] + 8) * 2048), (uint16_t)((corners[c][1] + 8) * 2048), (uint16_t)((z + 8) * 2048)}, {240, 240}, {0}, {(c == 1 || c == 2) ? 49152 : 16384, c >= 2 ? 49152 : 16384}};
					memcpy(v.color, rgba, 4);
					if (f->exactPositions) {
						((float *)f->arena.contents)[record] = z + 0.000013f;
						v.position[2] = 61440 + record;
					}
					((CompactVertex *)f->arena.contents)[at] = v;
				} else {
					TestVertex v = {{corners[c][0], corners[c][1], z}, {0}, {(c == 1 || c == 2) ? 0.75f : 0.25f, c >= 2 ? 0.75f : 0.25f}, {240, 240}};
					memcpy(v.color, rgba, 4);
					((TestVertex *)f->arena.contents)[at] = v;
				}
			}
			if (f->quadRecords) {
				uint32_t *w = (uint32_t *)f->arena.contents + (512 + record * 64 + q) * 10;
				CompactVertex *v = (CompactVertex *)f->arena.contents + (8192 + record * 64 + q) * 4;
				memset(w, 0, 40);
				if (q & 1) {
					w[0] = 0x80000000u;
					w[1] = 8192 + record * 64 + q;
				} else {
					w[0] = (1u << 20) | (1u << 3) | (3u << 6) | (2u << 9) | (1u << 14) | (3u << 16) | (2u << 18);
					for (int axis = 0; axis < 3; axis++) w[1 + axis] = v[0].position[axis] | (uint32_t)v[2].position[axis] << 16;
					w[4] = v[0].uv[0] | (uint32_t)v[2].uv[0] << 16;
					w[5] = v[0].uv[1] | (uint32_t)v[2].uv[1] << 16;
					memcpy(&w[6], v[0].color, 4);
					w[7] = 0xffffffffu;
					w[8] = w[9] = 0xf0f0f0f0u;
				}
			}
		}
	}
}

static NSData *render(Fixture *f, bool objects) {
	Enc enc = {0};
	enc.ctx = &f->ctx;
	enc.cmd = [f->ctx.queue commandBuffer];
	enc.colors[0] = texture(f, MTLPixelFormatRGBA8Unorm, 128, MTLTextureUsageRenderTarget);
	enc.depth = texture(f, MTLPixelFormatDepth32Float, 128, MTLTextureUsageRenderTarget);
	enc.colorCount = 1;
	enc.width = enc.height = 128;
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = enc.colors[0];
	pass.colorAttachments[0].loadAction = MTLLoadActionClear;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
	pass.depthAttachment.texture = enc.depth;
	pass.depthAttachment.loadAction = MTLLoadActionClear;
	pass.depthAttachment.storeAction = MTLStoreActionStore;
	pass.depthAttachment.clearDepth = 0;
	enc.render = [enc.cmd renderCommandEncoderWithDescriptor:pass];
	char error[16384] = {0};
	if (objects && mco_object_ready(f->own, &enc, error, sizeof error) != 1) fail(error);
	uint8_t cull[144];
	memcpy(cull, f->cull, sizeof cull);
	if (objects) ((uint32_t *)cull)[31] |= 2;
	mco_cull(f->own, &enc, cull, sizeof cull, f->sections, f->records, f->args, f->lists, f->mask, 64,
		f->scratch, f->scratch, f->scratch, 0, f->scratch, 0);
	if (objects) {
		if (mco_object_draw(f->own, &enc, cull, f->draw, f->recordCount, f->arena, f->sections, f->records, f->mask,
			f->uniforms, f->atlas, f->sampler, f->light, f->sampler, error, sizeof error) < 0) fail(error);
	} else {
		if (mco_draw(f->own, &enc, 0, f->draw, sizeof f->draw, f->arena, f->sections, f->records, f->lists, f->args, 0,
			f->uniforms, f->atlas, f->sampler, f->light, f->sampler, nil, error, sizeof error) < 0) fail(error);
	}
	uint8_t cutoutDraw[80];
	memcpy(cutoutDraw, f->draw, sizeof cutoutDraw);
	((uint32_t *)cutoutDraw)[0] = 64;
	if (mco_draw(f->own, &enc, 1, cutoutDraw, sizeof cutoutDraw, f->arena, f->sections, f->records, f->lists, f->args, 20,
		f->uniforms, f->atlas, f->sampler, f->light, f->sampler, nil, error, sizeof error) < 0) fail(error);
	[enc.render endEncoding];
	[enc.pre commit];
	[enc.cmd commit];
	[enc.cmd waitUntilCompleted];
	if (enc.cmd.status == MTLCommandBufferStatusError) fail(enc.cmd.error.description.UTF8String);
	if (enc.pre.status == MTLCommandBufferStatusError) fail(enc.pre.error.description.UTF8String);
	if (objects && ((uint32_t *)f->args.contents)[1] != 0) fail("opaque records leaked into compute draw list");
	NSMutableData *pixels = [NSMutableData dataWithLength:128 * 128 * 8];
	[enc.colors[0] getBytes:pixels.mutableBytes bytesPerRow:128 * 4 fromRegion:MTLRegionMake2D(0, 0, 128, 128) mipmapLevel:0];
	[enc.depth getBytes:(uint8_t *)pixels.mutableBytes + 128 * 128 * 4 bytesPerRow:128 * 4 fromRegion:MTLRegionMake2D(0, 0, 128, 128) mipmapLevel:0];
	[enc.colors[0] release];
	[enc.depth release];
	[enc.pre release];
	return pixels;
}

static int checks;

static void compare(Fixture *f, const char *name, int expectedVisible) {
	NSData *baseline = render(f, false);
	NSData *objects = render(f, true);
	NSUInteger differences = 0, visible = 0;
	const uint8_t *a = baseline.bytes, *b = objects.bytes;
	for (NSUInteger i = 0; i < baseline.length; i++) differences += a[i] != b[i];
	for (NSUInteger i = 0; i < 128 * 128; i++) visible += b[i * 4 + 3] != 0;
	printf("%s compact=%d cpuClip=%d qrec=%d exact=%d visible=%lu differingBytes=%lu\n", name, f->compact, f->cpuClip,
		f->quadRecords, f->exactPositions, visible, differences);
	if (differences || (expectedVisible >= 0 && (visible != 0) != expectedVisible)) fail("render comparison failed");
	checks++;
}

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc != 2) fail("usage: object_terrain path/to/terrain.metal");
		NSError *error = nil;
		NSString *source = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:&error];
		if (!source) fail(error.description.UTF8String);
		for (int encoding = 0; encoding < 5; encoding++) for (int cpuClip = 0; cpuClip < 2; cpuClip++) {
			Fixture f;
			setup(&f, source, encoding, cpuClip);
			int sizes[] = {1, 15, 16, 17, 31, 32, 33, 63, 64};
			for (int i = 0; i < 9; i++) {
				scene(&f, 1, sizes[i]);
				compare(&f, "meshlet-boundary", 1);
			}
			scene(&f, 35, 64);
			compare(&f, "object-group-tail", 1);
			scene(&f, 0, 1);
			compare(&f, "empty-scene", 0);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].count = 0;
			compare(&f, "unloaded-section", 0);
			scene(&f, 1, 64);
			((uint32_t *)f.cull)[31] = 1;
			((uint32_t *)f.mask.contents)[0] = 0;
			compare(&f, "section-mask", 0);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].x = 1024;
			compare(&f, "outside-distance", 0);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].x = 32;
			compare(&f, "outside-frustum", 0);
			scene(&f, 1, 64);
			((TestRecord *)f.records.contents)[0].meta = 63 | (4 << 6);
			((TestRecord *)f.records.contents)[0].plane = 128;
			compare(&f, "back-facing", 0);
			scene(&f, 2, 64);
			((TestRecord *)f.records.contents)[1].meta |= 1 << 9;
			compare(&f, "cutout-retained", 1);
			scene(&f, 1, 64);
			((TestRecord *)f.records.contents)[0].meta |= 2 << 9;
			compare(&f, "translucent-excluded", 0);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].x = 29999984;
			((int32_t *)f.globals.contents)[0] = 29999984;
			((int32_t *)f.cull)[16] = 29999984;
			((int32_t *)f.cull)[24] = 29999984 >> 4;
			compare(&f, "world-border-relative", 1);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].count = 0;
			((TestRecord *)f.records.contents)[0].slot = 0xffffffffu;
			f.recordCount = 64;
			compare(&f, "stale-records-and-unused-capacity", 0);
			scene(&f, 1, 17);
			compare(&f, "section-republished", 1);
			scene(&f, 1, 64);
			((TestSection *)f.sections.contents)[0].x = -29999984;
			((int32_t *)f.globals.contents)[0] = -29999984;
			((int32_t *)f.cull)[16] = -29999984;
			((int32_t *)f.cull)[24] = -29999984 >> 4;
			compare(&f, "negative-world-border", 1);
		}
		printf("PASS %d exact color-and-depth comparisons\n", checks);
	}
	return 0;
}
