// The opaque terrain layers of 1.8.9, drawn by Metal (src/main/native/metal.m). The shading restates the fixed-function
// GL state the game sets for chunk layers: atlas texture x vertex color x lightmap (texture unit 1, its texture matrix
// scale 1/256 translate 8), the alpha test for the cutout layers, then the fog.
#include <metal_stdlib>
using namespace metal;

// 1.8.9's BLOCK vertex as MetalTerrain packs it: the section-relative position in 1/2048 blocks from 8 below the
// section's origin, the lightmap uv (2 bytes), color RGBA8, atlas uv in 1/65536 (exact for texel corners of atlases up
// to 65536 texels wide)
struct Vtx {
	packed_ushort3 pos;
	uchar2 lm;
	uchar4 color;
	ushort2 uv;
};

// a draw unit: up to 64 consecutive quads of one section and layer, all facing one way (or not along an axis), the
// section's origin in blocks, the unit's id (its first quad's index over all pages, for the visibility it had last
// frame), its quad count (0: an unused slot) and facing (bits 8-10: +X, -X, +Y, -Y, +Z, -Z, other), and the box around
// its quads in quarter blocks from 16 blocks below the section's origin
struct Unit {
	device const Vtx *quads;
	int sx, sy, sz;
	uint id;
	ushort count;
	uchar lo[3], hi[3];
};

// the camera in blocks, as a whole part and a fraction, so section offsets stay exact far from the origin
struct Camera {
	int x, y, z;
	float fx, fy, fz;
	float pad0, pad1;
};

static float3 origin(Unit u, Camera c) {
	return float3(int3(u.sx, u.sy, u.sz) - int3(c.x, c.y, c.z)) - float3(c.fx, c.fy, c.fz);
}

struct Frame {
	float4x4 mv, proj;
	float4 fogColor;
	float fogStart, fogEnd, fogDensity;
	int fogMode;  // 0 off, 1 linear, 2 exp, 3 exp2
	int radial, alphaFunc;
	float alphaRef, pad;
	Camera cam;
	float4x4 mvp;  // proj x mv
};

struct VOut {
	float4 pos [[position]];
	float2 uv;
	half4 color;  // the varyings in half where its precision is plenty (8-bit color, lightmap coordinates): the tiler
	              // writes every vertex's out to memory
	half2 lm;
	float fogDist;
};

struct FOut {
	float4 color [[color(0)]];
	float4 depth [[color(1)]];  // the window depth, 24 bits in r, g, b, for the GL composite's gl_FragDepth
};

vertex VOut terrain_vs(uint vid [[vertex_id]], uint iid [[instance_id]], device const Unit *units [[buffer(0)]],
	device const uint *list [[buffer(1)]], constant Frame &f [[buffer(2)]]) {
	VOut o;
	Unit u = units[list[iid]];
	uint q = vid >> 2;
	if (q >= (u.count & 0xFFu) || f.pad != 0.0) {
		o.pos = float4(NAN);  // past the unit's last quad: NaN positions drop the triangles before setup
		o.color = half4(0.0);
		o.uv = float2(0.0);
		o.lm = half2(0.0);
		o.fogDist = 0.0;
		return o;
	}
	Vtx v = u.quads[vid];
	// BuiltChunk's own matrix (scale 1.000001 about the section's center, against seams), then the section's offset
	float3 p = (float3(v.pos) * (1.0 / 2048.0) - 16.0) * 1.000001 + 8.0 + origin(u, f.cam);
	float4 clip = f.mvp * float4(p, 1.0);
	clip.z = (clip.z + clip.w) * 0.5;  // GL's depth range -1..1 to Metal's 0..1: the same window depth as GL's
	o.pos = clip;
	o.color = half4(v.color) / 255.0h;
	o.uv = float2(v.uv) * (1.0 / 65536.0);
	o.lm = (half2(v.lm) + 8.0h) / 256.0h;
	if (f.radial) o.fogDist = length((f.mv * float4(p, 1.0)).xyz);
	else o.fogDist = abs(dot(float4(f.mv[0][2], f.mv[1][2], f.mv[2][2], f.mv[3][2]), float4(p, 1.0)));
	return o;
}

static float4 shade(VOut in, texture2d<float> atlas, sampler s, texture2d<float> lightmap, sampler ls) {
	return atlas.sample(s, in.uv) * float4(in.color) * lightmap.sample(ls, float2(in.lm));
}

static FOut finish(float4 c, VOut in, constant Frame &f) {
	if (f.fogMode != 0) {
		float d = in.fogDist, k;
		if (f.fogMode == 1) k = (f.fogEnd - d) / (f.fogEnd - f.fogStart);
		else if (f.fogMode == 2) k = exp(-f.fogDensity * d);
		else k = exp(-(f.fogDensity * d) * (f.fogDensity * d));
		c.rgb = mix(f.fogColor.rgb, c.rgb, clamp(k, 0.0, 1.0));
	}
	uint z = uint(clamp(in.pos.z, 0.0, 1.0) * 16777215.0 + 0.5);
	FOut o;
	o.color = c;
	o.depth = float4(float((z >> 16) & 255u), float((z >> 8) & 255u), float(z & 255u), 255.0) / 255.0;
	return o;
}

fragment FOut terrain_fs_solid(VOut in [[stage_in]], constant Frame &f [[buffer(0)]], texture2d<float> atlas [[texture(0)]],
	texture2d<float> lightmap [[texture(1)]], sampler s [[sampler(0)]], sampler ls [[sampler(1)]]) {
	return finish(shade(in, atlas, s, lightmap, ls), in, f);
}

// GL's alpha test (the lightmap's alpha is always 1, so the atlas and the vertex color decide it)
static bool alphaPasses(float a, constant Frame &f) {
	float r = f.alphaRef;
	bool pass;
	switch (f.alphaFunc) {
		case 512: pass = false; break;    // GL_NEVER
		case 513: pass = a < r; break;    // GL_LESS
		case 514: pass = a == r; break;   // GL_EQUAL
		case 515: pass = a <= r; break;   // GL_LEQUAL
		case 516: pass = a > r; break;    // GL_GREATER
		case 517: pass = a != r; break;   // GL_NOTEQUAL
		case 518: pass = a >= r; break;   // GL_GEQUAL
		default: pass = true; break;      // GL_ALWAYS
	}
	return pass;
}

// dev (mcopt.metal.depthPass): the alpha-tested layers drawn twice, first only their depth, dropping the pixels that
// fail the test, then shaded with the depth test at equal and no discard. It measured slower than one pass: the cost
// is in the discarding pixels themselves, not in their shading.
fragment void terrain_fs_depth(VOut in [[stage_in]], constant Frame &f [[buffer(0)]], texture2d<float> atlas [[texture(0)]],
	sampler s [[sampler(0)]]) {
	if (!alphaPasses(atlas.sample(s, in.uv).a * float(in.color.a), f)) discard_fragment();
}

// dev (mcopt.metal.minCutout): the alpha test alone, no lightmap, fog or packed depth
fragment FOut terrain_fs_cutout_min(VOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]]) {
	float4 t = atlas.sample(s, in.uv);
	if (t.a < 0.1) discard_fragment();
	FOut o;
	o.color = t;
	o.depth = float4(0.0);  // nearest possible: these pixels win the composite's depth test regardless
	return o;
}

// the alpha-tested layers, the test first so failing pixels skip the lightmap and the fog
fragment FOut terrain_fs_cutout(VOut in [[stage_in]], constant Frame &f [[buffer(0)]], texture2d<float> atlas [[texture(0)]],
	texture2d<float> lightmap [[texture(1)]], sampler s [[sampler(0)]], sampler ls [[sampler(1)]]) {
	float4 t = atlas.sample(s, in.uv) * float4(in.color);
	if (!alphaPasses(t.a, f)) discard_fragment();
	return finish(t * lightmap.sample(ls, float2(in.lm)), in, f);
}

// ---- occlusion culling ----
// Each frame first draws the units that were visible last frame, then builds a depth pyramid from that (each texel the
// farthest depth of the pixels under it), tests every unit's box against it, and draws the units that are visible now
// but were not drawn yet. A unit is skipped only when its whole box lies behind terrain this frame already drew.

#define HIZ_LEVELS 12

struct CullFrame {
	float4x4 mvp;
	float width, height;
	uint levels, pad;
	Camera cam;
};

// Where a unit's box lands this frame: 0 nothing to draw (unused slot, all its quads face away, or off screen), 1 it
// reaches the camera plane (no screen rectangle), 2 on screen inside the window rectangle [a, b] at nearest depth zmin.
static int place(Unit u, constant CullFrame &cf, thread int2 &a, thread int2 &b, thread float &zmin) {
	uint n = u.count & 0xFFu, facing = u.count >> 8;
	if (n == 0) return 0;
	float3 o = origin(u, cf.cam);
	float3 lo = float3(u.lo[0], u.lo[1], u.lo[2]) * 0.25 - 16.0 + o;
	float3 hi = float3(u.hi[0], u.hi[1], u.hi[2]) * 0.25 - 16.0 + o;
	// a face toward +axis is seen only from beyond its plane; the camera is at 0
	switch (facing) {
		case 0: if (lo.x >= 0.0) return 0; break;
		case 1: if (hi.x <= 0.0) return 0; break;
		case 2: if (lo.y >= 0.0) return 0; break;
		case 3: if (hi.y <= 0.0) return 0; break;
		case 4: if (lo.z >= 0.0) return 0; break;
		case 5: if (hi.z <= 0.0) return 0; break;
		default: break;
	}
	float2 smin = float2(INFINITY), smax = float2(-INFINITY);
	zmin = INFINITY;
	uint behind = 0;
	// outside one clip plane with every corner: off screen
	uint outside = 0x3F;
	for (uint c = 0; c < 8; c++) {
		float3 p = float3(c & 1 ? hi.x : lo.x, c & 2 ? hi.y : lo.y, c & 4 ? hi.z : lo.z);
		float4 q = cf.mvp * float4(p, 1.0);
		uint out = (q.x < -q.w ? 1u : 0u) | (q.x > q.w ? 2u : 0u) | (q.y < -q.w ? 4u : 0u) | (q.y > q.w ? 8u : 0u)
			| (q.z < -q.w ? 16u : 0u) | (q.z > q.w ? 32u : 0u);
		outside &= out;
		if (q.w <= 0.001) {
			behind++;
			continue;
		}
		float3 nd = q.xyz / q.w;
		smin = min(smin, nd.xy);
		smax = max(smax, nd.xy);
		zmin = min(zmin, (nd.z + 1.0) * 0.5);
	}
	if (outside != 0) return 0;
	if (behind != 0) return 1;  // the box reaches the camera plane: no screen rectangle to test
	// window pixels; Metal's rows run top-down
	float x0 = (smin.x * 0.5 + 0.5) * cf.width, x1 = (smax.x * 0.5 + 0.5) * cf.width;
	float y0 = (0.5 - smax.y * 0.5) * cf.height, y1 = (0.5 - smin.y * 0.5) * cf.height;
	if (x1 < 0.0 || y1 < 0.0 || x0 >= cf.width || y0 >= cf.height || zmin > 1.0) return 0;
	a = int2(max(floor(float2(x0, y0)), 0.0));
	b = int2(min(floor(float2(x1, y1)), float2(cf.width - 1.0, cf.height - 1.0)));
	return 2;
}

// The camera's distance to a unit's box, in blocks.
static float distance(Unit u, constant CullFrame &cf) {
	float3 o = origin(u, cf.cam);
	float3 lo = float3(u.lo[0], u.lo[1], u.lo[2]) * 0.25 - 16.0 + o;
	float3 hi = float3(u.hi[0], u.hi[1], u.hi[2]) * 0.25 - 16.0 + o;
	return length(max(max(lo, -hi), 0.0));
}

// A unit to a draw list, into its bucket's region. The buckets go by distance (under 8, 16, 32 blocks, then the rest),
// drawn nearest first so that the depth test drops the alpha-tested pixels behind nearer ones before they are shaded,
// and within one by quad count (up to 8, 16, ..., 64), so a draw runs few vertices past the units' last quads.
static void append(uint i, Unit u, constant CullFrame &cf, device uint *list, device atomic_uint *args, uint cap) {
	uint n = u.count & 0xFFu;
	float d = distance(u, cf);
	uint band = d < 8.0 ? 0 : d < 16.0 ? 1 : d < 32.0 ? 2 : 3;
	uint k = band * 8 + (n - 1) / 8;  // up to 8, 16, ..., 64 quads: at most 7 padding quads per unit
	uint at = atomic_fetch_add_explicit(&args[k * 5 + 1], 1, memory_order_relaxed);
	list[k * cap + at] = i;
}

// units visible last frame (and still in view) go to the first draw's list
kernel void cull_split(uint i [[thread_position_in_grid]], device const Unit *units [[buffer(0)]], constant uint &count [[buffer(1)]],
	device const uchar *seen [[buffer(2)]], device uint *list [[buffer(3)]], device atomic_uint *args [[buffer(4)]],
	constant CullFrame &cf [[buffer(5)]], constant uint &cap [[buffer(6)]]) {
	if (i >= count) return;
	Unit u = units[i];
	if ((u.count & 0xFFu) == 0 || !seen[u.id]) return;
	int2 a, b;
	float zmin;
	if (place(u, cf, a, b, zmin) == 0) return;
	append(i, u, cf, list, args, cap);
}

// level 1 of the pyramid from the depth buffer: the farthest of each 2x2 block (and of the odd last row and column)
kernel void cull_hiz_first(uint2 p [[thread_position_in_grid]], depth2d<float, access::read> src [[texture(0)]],
	texture2d<float, access::write> dst [[texture(1)]]) {
	if (p.x >= dst.get_width() || p.y >= dst.get_height()) return;
	uint w = src.get_width(), h = src.get_height();
	float z = 0.0;
	for (uint y = p.y * 2; y < min(p.y * 2 + 2, h); y++)
		for (uint x = p.x * 2; x < min(p.x * 2 + 2, w); x++) z = max(z, src.read(uint2(x, y)));
	dst.write(float4(z), p);
}

kernel void cull_hiz_down(uint2 p [[thread_position_in_grid]], texture2d<float, access::read> src [[texture(0)]],
	texture2d<float, access::write> dst [[texture(1)]]) {
	if (p.x >= dst.get_width() || p.y >= dst.get_height()) return;
	uint w = src.get_width(), h = src.get_height();
	float z = 0.0;
	for (uint y = p.y * 2; y < min(p.y * 2 + 2, h); y++)
		for (uint x = p.x * 2; x < min(p.x * 2 + 2, w); x++) z = max(z, src.read(uint2(x, y)).r);
	dst.write(float4(z), p);
}

// Every unit against the pyramid: its visibility is kept for the next frame, and a unit visible now that the first draw
// skipped goes to the second draw's list.
kernel void cull_test(uint i [[thread_position_in_grid]], device const Unit *units [[buffer(0)]], constant uint &count [[buffer(1)]],
	device uchar *seen [[buffer(2)]], device uint *list [[buffer(3)]], device atomic_uint *args [[buffer(4)]],
	constant CullFrame &cf [[buffer(5)]], constant uint &cap [[buffer(6)]],
	array<texture2d<float, access::read>, HIZ_LEVELS> hiz [[texture(0)]]) {
	if (i >= count) return;
	Unit u = units[i];
	if ((u.count & 0xFFu) == 0) return;
	int2 a, b;
	float zmin;
	int where = place(u, cf, a, b, zmin);
	bool visible = where == 1;
	if (where == 2) {
		// the pyramid level where the rectangle covers at most 2x2 texels (level 1 halves the window), or the
		// coarsest level when the pyramid stops short of that (windows over 4096 pixels)
		uint level = 1;
		int2 ta = a >> 1, tb = b >> 1;
		while ((tb.x - ta.x > 1 || tb.y - ta.y > 1) && level < cf.levels) {
			level++;
			ta = a >> level;
			tb = b >> level;
		}
		texture2d<float, access::read> t = hiz[level - 1];
		int2 lim = int2(t.get_width(), t.get_height()) - 1;
		ta = min(ta, lim);
		tb = min(tb, lim);
		float far = 0.0;
		for (int y = ta.y; y <= tb.y; y++)
			for (int x = ta.x; x <= tb.x; x++) far = max(far, t.read(uint2(x, y)).r);
		visible = zmin <= far;
	}
	bool was = seen[u.id] != 0;
	seen[u.id] = visible ? 1 : 0;
	if (visible && !was) append(i, u, cf, list, args, cap);
}

// dev (mcopt.metal.raceTest): busy work ahead of the frame's draw
kernel void dev_spin(uint i [[thread_position_in_grid]], device uint *o [[buffer(0)]], constant uint &n [[buffer(1)]]) {
	uint x = i;
	for (uint k = 0; k < n; k++) x = x * 1664525u + 1013904223u;
	o[i] = x;
}
