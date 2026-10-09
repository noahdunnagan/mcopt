// Our own near terrain (mcopt.metal.own, -Dmcopt.own=true): GPU cull of every compiled section, then one instanced indirect
// draw per layer that pulls its vertices from the arena. The shading is vanilla's core/terrain (terrain.vsh / terrain.fsh and
// their includes fog, globals, sample_lightmap, texture_sampling), restated for vertex pulling.
#include <metal_stdlib>
using namespace metal;

// vanilla's BLOCK vertex as the section compiler writes it: Position RGB32F, Color RGBA8 unorm, UV0 RG32F, UV2 RG16 sint
struct Vtx {
	packed_float3 pos;
	uchar4 color;
	packed_float2 uv0;
	short2 uv2;
};

// a section slot: render origin (blocks), upload time (ms since the renderer's base), its records [recStart, recStart + recCount)
struct Section {
	int x, y, z;
	int uploadMs;
	uint recStart, recCount;
	uint pad0, pad1;      // pad1 (-Dmcopt.own.mesh.tieGroups): the section's block in the tie-group buffer (OwnTieGroups), 0 = none
};

// a draw unit: up to 64 consecutive quads of one section, layer and facing bucket
// meta: bits 0-5 count - 1, 6-8 bucket (0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z, 6 any), 9-10 layer (0 solid, 1 cutout, 2 translucent)
// translucent units: quadStart is the first entry of the section's sorted quad order (u32 index into the arena), plane its
// first quad (the order's entries are quads from there)
// plane: the bucket's plane bound in 1/256 blocks, section-relative (+ buckets: lowest plane, - buckets: highest)
// lo, hi: the unit's bounds in whole blocks, section-relative + 16, x | y << 8 | z << 16
struct Rec {
	uint quadStart, slot, meta;
	int plane;
	uint lo, hi;
	uint pad0, pad1;
};

struct CullFrame {
	float4 planes[4];     // left, right, bottom, top of the view frustum, camera-relative world space (xyz . p + w >= 0 inside)
	int4 camBlock;        // camera block position
	float4 camOffset;     // block position - camera position (vanilla's CameraOffset)
	int4 camSection;      // camera section (x, y, z)
	int viewDistance;     // chunks
	uint sectionCount;
	uint listCap;         // per layer
	int useMask;          // 1: only sections set in mask (vanilla's visible sections this frame)
	int run;              // quads per instance (draw unit size)
	int occ;              // 1: two-phase occlusion (lists A = visible last frame, T = every unit tested in phase B)
	int fat;              // 1: the lists hold whole instances (Inst) for the vertex stage, not record indices
	int fatT;             // 1 (with occ 2, -Dmcopt.own.quads.fatT): each tested unit's TUnit also written (buffer 10, same position)
};

// a tested unit as the per-quad path's phase A and B read it, written by the cull next to its tested-list entry: the record's
// fields they use and its section's origin, so they read one entry instead of index -> record -> section in turn
struct TUnit {
	uint quadStart, meta, lo, hi;
	int x, y, z;
	uint rec;
};

// a fat list entry: what the vertex stage needs of a unit and its section, in one load
struct Inst {
	uint quadStart, meta;
	int x, y, z;
	int uploadMs;
	uint pad0, pad1;
};

static void putInst(device Inst *fat, uint at, Rec rec, device const Section *sections) {
	Section s = sections[rec.slot];
	Inst e;
	e.quadStart = rec.quadStart;
	e.meta = rec.meta;
	e.x = s.x;
	e.y = s.y;
	e.z = s.z;
	e.uploadMs = s.uploadMs;
	e.pad0 = uint(rec.plane);
	e.pad1 = 0;
	fat[at] = e;
}

// args layout (uints): [0..4] A solid, [5..9] A cutout, [10..14] B solid, [15..19] B cutout, [20] T count,
// [21..23] phase B threadgroups
#define ARGS_T 20
#define ARGS_DISPATCH 21

struct DrawFrame {
	uint listBase;        // the layer's first entry in lists
	int nowMs;
	int fadeMs;
	int clipOnCpu;        // 1: clip below is ProjMat * ModelViewMat, multiplied once on the CPU (-Dmcopt.own.cpuClip)
	float4x4 clip;
};

// vanilla's std140 uniform blocks
struct Projection { float4x4 ProjMat; };
// (vanilla's Fog block is 40 bytes (std140) and is bound as such: packed_float4 keeps this struct at 40, not float4's 48)
struct Fog {
	packed_float4 FogColor;
	float FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogSkyEnd, FogCloudsEnd;
};
struct Globals {
	packed_int3 CameraBlockPos;
	float GlintAlpha;
	packed_float3 CameraOffset;
	float GameTime;
	float2 ScreenSize;
	int MenuBlurRadius;
	int UseRgss;
};
struct Terrain {
	float4x4 ModelViewMat;
	int2 TextureSize;
};

// ---- cull ----

kernel void own_reset(constant CullFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	device uint *a = args + i * 5;
	a[0] = f.run * 6;  // indexCount: one draw unit
	a[1] = 0;       // instanceCount
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
	if (i == 0) {
		args[ARGS_T] = 0;
		args[ARGS_DISPATCH] = 0;
		args[ARGS_DISPATCH + 1] = 1;
		args[ARGS_DISPATCH + 2] = 1;
	}
}

// this frame's visibility bits, cleared before phase B sets them
kernel void own_clear_vis(device uint *vis [[buffer(6)]], constant uint &words [[buffer(7)]], uint i [[thread_position_in_grid]]) {
	if (i < words) vis[i] = 0;
}

constant float FACE_MARGIN = 1.0 / 64.0;

// ---- solid-section occlusion (-Dmcopt.own.solidOcc): fully opaque sections rasterized as boxes before the pass ----

// columns: per chunk column (toroidal grid), its coordinates and a bit per section that is all opaque full cubes
struct SolidCol {
	int x, z;
	uint lo, hi;
};

struct SolidOcc {
	float4x4 clip;        // projection * view rotation (camera-relative world to clip, before the backend's y flip)
	int4 camBlock;
	float4 camOffset;
	int4 camSection;
	uint2 size;           // the occluder depth's size (texels)
	uint mips;
	uint on;
	int rd;               // chunks
	int minY;             // the level's lowest section y
	int levels;           // sections in a column (<= 64)
	int grid;             // the column grid's side (power of two)
};

// A box (camera-relative) wholly behind the opaque sections' depth (only real opaque blocks occlude, so water and glass never
// hide anything). Conservative: false whenever unsure.
static bool solidHidden(constant SolidOcc &p, texture2d<float, access::read> occ, float3 lo, float3 hi) {
	if (p.on == 0) return false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (int c = 0; c < 8; c++) {
		float3 v = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);
		float4 q = p.clip * float4(v, 1.0);
		if (q.w <= 0.05) return false;
		float3 n = q.xyz / q.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;  // (the frustum test's business)
	float2 sz = float2(p.size);
	uint2 a = uint2(clamp((mn * 0.5 + 0.5) * sz, float2(0), sz - 1)), b = uint2(clamp((mx * 0.5 + 0.5) * sz, float2(0), sz - 1));
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(p.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, p.size.x >> level), max(1u, p.size.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, occ.read(uint2(x, y), level).x);
	return zmax < far - 1e-6;
}

kernel void own_cull(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]],
	device const uint *prevVis [[buffer(8)]], device uint *tested [[buffer(9)]], device Inst *fat [[buffer(10)]], constant SolidOcc &so [[buffer(12)]],
	texture2d<float, access::read> soTex [[texture(1)]], uint i [[thread_position_in_grid]]) {
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	// vanilla's distance rule (SectionOcclusionGraph.isInViewDistance, ChunkTrackingView.isWithinDistance; vertical: getRelativeFrom)
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	for (int p = 0; p < 4; p++) {
		float4 pl = f.planes[p];
		float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return;
	}
	float3 cam = -o;
	for (uint r = s.recStart; r < s.recStart + s.recCount; r++) {
		Rec rec = recs[r];
		uint bucket = (rec.meta >> 6) & 7;
		float plane = float(rec.plane) / 256.0;
		bool vis;
		switch (bucket) {
			case 0: vis = cam.x > plane - FACE_MARGIN; break;
			case 1: vis = cam.x < plane + FACE_MARGIN; break;
			case 2: vis = cam.y > plane - FACE_MARGIN; break;
			case 3: vis = cam.y < plane + FACE_MARGIN; break;
			case 4: vis = cam.z > plane - FACE_MARGIN; break;
			case 5: vis = cam.z < plane + FACE_MARGIN; break;
			default: vis = true; break;
		}
		uint layer = (rec.meta >> 9) & 3;
		if (!vis || layer > 1) continue;  // translucent units are drawn in vanilla's order from the CPU's list
		if (so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
			o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16))) continue;
		bool was = true;
		if (f.occ == 2) {
			// the per-quad path: every unit the cull passes goes to T; its quads are listed and tested there
			uint t = atomic_fetch_add_explicit(&args[ARGS_T], 1, memory_order_relaxed);
			if (t < f.listCap) tested[t] = r;
			continue;
		}
		if (f.occ != 0) {
			was = (prevVis[r >> 5] & (1u << (r & 31))) != 0;
			uint t = atomic_fetch_add_explicit(&args[ARGS_T], 1, memory_order_relaxed);
			if (t < f.listCap) tested[t] = r | (was ? 0x80000000u : 0u);
		}
		if (!was) continue;
		uint at = atomic_fetch_add_explicit(&args[layer * 5 + 1], 1, memory_order_relaxed);
		if (at < f.listCap) {
			if (f.fat != 0) putInst(fat, layer * f.listCap + at, rec, sections);
			else lists[layer * f.listCap + at] = r;
		}
	}
}

// The cull a thread per record (-Dmcopt.own.cullR; dispatched over the record arena's capacity): a record is live when its
// section slot's range holds it. Section tests (distance rule, mask, frustum box) per record, then the facing test, then the
// appends: one atomic per SIMD group and list (lists or, for the per-quad paths, T), not one per record.
// cullAppend's position for this lane (0xFFFFFFFF: not appended)
static uint cullAppendAt(device atomic_uint *counter, device uint *dst, uint cap, bool want, uint value) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return 0xFFFFFFFFu;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	if (!want || base + before >= cap) return 0xFFFFFFFFu;
	dst[base + before] = value;
	return base + before;
}

static void cullAppend(device atomic_uint *counter, device uint *dst, uint cap, bool want, uint value) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	if (want && base + before < cap) dst[base + before] = value;
}

kernel void own_cull_r(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]],
	device uint *tested [[buffer(9)]], constant SolidOcc &so [[buffer(12)]], texture2d<float, access::read> soTex [[texture(1)]],
	constant uint &recCap [[buffer(13)]], device TUnit *tfat [[buffer(10)]], uint r [[thread_position_in_grid]]) {
	bool want = false;
	uint layer = 0;
	Rec rec;
	Section s;
	if (r < recCap) {
		rec = recs[r];
		uint slot = rec.slot;
		if (slot < f.sectionCount && (f.useMask == 0 || (mask[slot >> 5] & (1u << (slot & 31))) != 0)) {
			s = sections[slot];
			if (s.recCount != 0 && r >= s.recStart && r < s.recStart + s.recCount) {
				int3 sec = int3(s.x, s.y, s.z) >> 4;
				int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
				float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
				bool in = dx * dx + dz * dz < f.viewDistance * f.viewDistance && abs(sec.y - f.camSection.y) <= f.viewDistance;
				for (int p = 0; p < 4 && in; p++) {
					float4 pl = f.planes[p];
					float3 v = o + select(float3(0), float3(16), pl.xyz > 0);
					if (dot(pl.xyz, v) + pl.w < -0.5) in = false;
				}
				layer = (rec.meta >> 9) & 3;
				if (in && layer <= 1) {
					float3 cam = -o;
					uint bucket = (rec.meta >> 6) & 7;
					float plane = float(rec.plane) / 256.0;
					bool vis;
					switch (bucket) {
						case 0: vis = cam.x > plane - FACE_MARGIN; break;
						case 1: vis = cam.x < plane + FACE_MARGIN; break;
						case 2: vis = cam.y > plane - FACE_MARGIN; break;
						case 3: vis = cam.y < plane + FACE_MARGIN; break;
						case 4: vis = cam.z > plane - FACE_MARGIN; break;
						case 5: vis = cam.z < plane + FACE_MARGIN; break;
						default: vis = true; break;
					}
					want = vis && !(so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
						o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16)));
				}
			}
		}
	}
	if (f.occ == 2 && f.fatT != 0) {
		uint at = cullAppendAt(&args[ARGS_T], tested, f.listCap, want, r);
		if (at != 0xFFFFFFFFu) tfat[at] = TUnit {rec.quadStart, rec.meta, rec.lo, rec.hi, s.x, s.y, s.z, r};
	} else if (f.occ == 2) {
		cullAppend(&args[ARGS_T], tested, f.listCap, want, r);
	} else {
		cullAppend(&args[0 * 5 + 1], lists, f.listCap, want && layer == 0, r);
		cullAppend(&args[1 * 5 + 1], lists + f.listCap, f.listCap, want && layer == 1, r);
	}
}

kernel void own_finish(constant CullFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 2) return;
	args[i * 5 + 1] = min(args[i * 5 + 1], f.listCap);
	if (i == 0) {
		args[ARGS_T] = min(args[ARGS_T], f.listCap);
		args[ARGS_DISPATCH] = (args[ARGS_T] + 63) / 64;
	}
}

// ---- frag variants: the classified cull (-Dmcopt.own.frag.*) ----
// The same tests as own_cull (distance rule, section frustum, facing), then per unit optionally its own box against the frustum, and
// a list per class: list id = (layer * classes + class) * 2 + lean, listCap entries each at lists + id * listCap, its draw's 5 indexed
// arguments at args + id * 5. class: the size class (quads - 1) / sizeQuads, so a unit draws sizeQuads * (class + 1) quads, not the
// whole unit size; with flag 16 a cutout unit's class is instead its section's distance bin (drawn near to far); lean: the section is
// faded in and wholly nearer than the render-distance fog start (see F_LEAN). A section's units keep their order within each list (one
// thread appends them in turn) and share their lists' classes but size.
struct FragFrame {
	float rdStart;        // vanilla's FogRenderDistanceStart
	int nowMs;            // DrawFrame.nowMs
	int fadeMs;           // DrawFrame.fadeMs
	uint flags;           // 1 lean class, 2 size classes, 4 unit box frustum test, 8 solid units listed in reverse (see F_REVERSE),
	                      // 16 cutout units in distance bins, 32 per-bucket stats, 64 unit occlusion (uocc, see frag_uocc_test),
	                      // 256 uocc tests the mesher's two sub-boxes in the record, 512 marks for the frame after next (two sets of marks)
	                      // 1024 phase A in two banks by section distance (nearBlocks; lists counts both banks)
	                      // 2048 (with 1024, -Dmcopt.own.frag.nearOnly) the farther bank stays empty: those units are tested, drawn in B if visible
	                      // 4096 (-Dmcopt.own.frag.newInA) uocc marks with history: the test marks every unit it tests (frame * 2, + 1 if
	                      // visible), and phase A takes the units visible at their last test plus those their mark set has no test for
	                      // 8192 (with 4096 and 1024, -Dmcopt.own.frag.newInA2) those untested units go to phase A's later bank (A2: its
	                      // vertex work under F(A1), its fragment work under the test), not to the part before the test
	                      // 16384 (measurement, -Dmcopt.own.frag.dropSolidA) no solid unit in phase A (fragInAL)
	                      // 32768 (-Dmcopt.own.frag.bRedraw) phase B redraws phase A's cutout partners of its solid units (fragRedraw)
	                      // 1 << 20 (-Dmcopt.own.frag.tieClose) promotion closure over tie components (fragTiePromote; tieRing at buffer 30)
	                      // (-Dmcopt.own.mesh.subbox: 4-bit inward offsets in lo / hi bits 24-31 and pad1)
	uint sizes;           // size classes (1 without flag 2)
	uint sizeQuads;       // quads per size class step
	uint lists;           // 2 * classes * 2
	uint classes;         // classes per layer: the larger of sizes and (flag 16) the distance bins
	float binBlocks;      // a distance bin's depth in blocks (flag 16)
	uint run;             // quads per unit
	uint epoch;           // flag 64: this frame's number; a unit whose occVis holds it was visible at the last test (phase A)
	uint tCap;            // flag 64: tList entries
	float nearBlocks;     // flag 1024 (-Dmcopt.own.frag.splitNear): phase A's units of sections this far or farther go to the second bank
	                      // of lists (id + lists / 2), drawn after the test; the nearer ones before it (the pyramid's depth)
	uint recCap;          // records occVis holds; flag 32768 (-Dmcopt.own.frag.bRedraw): a uint2 per record follows them in its buffer
	                      // (fragRedraw: the cull's mark of a cutout unit it listed for phase A, epoch * 2, and its list id), then a uint
	                      // per record, the epoch at a section's first record if the section has phase A cutout this frame; flag
	                      // F_TIE_CLOSE: a uint per record after those (TIE_PROMO: the frame a unit was promoted and appended to phase
	                      // A), then one more (TIE_SEEN, -Dmcopt.own.frag.tieCloseVerify)
	uint capLimit;        // (fault injection, -Dmcopt.own.frag.capFault=N) phase A and B lists hold at most N entries; 0 = their capacity
};

// a list's entries this frame (fault injection: capLimit)
static uint fragCap(constant CullFrame &f, constant FragFrame &g) {
	return g.capLimit != 0 ? min(f.listCap, g.capLimit) : f.listCap;
}

// uocc: is unit mark v phase A's this frame? Without flag 4096 a mark is the frame a visible unit is drawn in phase A. With it (newInA),
// every tested unit is marked (frame * 2, + 1 if visible), and a unit with no test for this frame in its mark set (it was outside the
// frustum, or its section was meshed since) is drawn in phase A too, instead of waiting for the test and phase B.
static bool fragInA(uint v, constant FragFrame &g) {
	return (g.flags & 4096) != 0 ? (v == g.epoch * 2 + 1 || (v >> 1) != g.epoch) : v == g.epoch;
}

// fragInA for a unit of layer `layer` (0 solid, 1 cutout). Flag 16384 (measurement, -Dmcopt.own.frag.dropSolidA / probe key
// frag.dropSolidA): no solid unit is phase A's, so every visible solid unit comes through the test and phase B, after phase A's
// cutout. That is the disocclusion case for every solid unit at once: a solid base drawn after its coincident cutout overlay
// (grass_block_side under its overlay), which frag.bRedraw (fragRedraw) must resolve as vanilla does.
static bool fragInAL(uint v, constant FragFrame &g, uint layer) {
	return fragInA(v, g) && ((g.flags & 16384) == 0 || layer != 0);
}

// uocc's phase A bank of a unit fragInA passes (flag 1024: aBank by section distance); flag 8192: a unit there for want of a test goes
// to the later bank whatever its distance
static uint fragBank(uint v, constant FragFrame &g, uint aBank) {
	return (g.flags & 8192) != 0 && (g.flags & 1024) != 0 && v != g.epoch * 2 + 1 ? g.lists / 2 : aBank;
}

// -Dmcopt.own.frag.tieClose (FragFrame flag F_TIE_CLOSE, with uocc): vanilla draws all of SOLID, then all of CUTOUT, each in order,
// with GREATER_EQUAL, so of two quads at the same depth the later one wins. uocc keeps that within a phase, not across them: a
// grass_block_side overlay (cutout) drawn in phase A and its base (solid, identical corners) newly visible, drawn in phase B after it,
// wins where vanilla's overlay does. Promotion closure: if any unit of a tie component is phase A's by its mark, every unit of the
// component the cull passes is drawn in phase A too, at its place in the ordered lists; so a component's drawn units are all in one
// phase, and each phase keeps vanilla's order (A: solid lists, then cutout lists; B the same). The components (OwnTerrain, from
// OwnTieGroups' tolerant corner groups, no winding split, a superset of the possible ties) are rings over the records: tieRing[r] the
// next record of r's component, TIE_NONE for a unit in none, TIE_ALWAYS for every unit of a component too large to walk (always phase
// A's when it passes the cull, so never split either). The unit test never lists a promoted unit for phase B too: the cull leaves occVis (the marks every walk reads) alone and records the promotion apart, in
// the TIE_PROMO region (occVis + recCap * 4, this frame's number), only once the unit's phase A append has succeeded; the test,
// a later dispatch, reads it. Frames whose components aren't known for every section, that have a cross-section candidate, or whose
// coordinates can reach TOL's float32 scope draw without uocc (OwnTerrain.tieCloseFallback).
#define F_TIE_CLOSE (1u << 20)
// FragFrame flags bits 24-25 (-Dmcopt.own.frag.vGroup, set by mcown.m's cull): log2 of the units per instance of phase A's class draws
#define F_VGROUP_SHIFT 24u
#define F_VGROUP_MASK (3u << 24)
// FragFrame flag bit 26 (-Dmcopt.own.frag.vArgsCopy, measurement): the finishes write the same grouped-argument copy with 2^0 units an
// instance (the class draws' own arguments, in mcown.m's private buffer) and the draws read it: the args' memory, not the instance shape
#define F_VARGS_COPY (1u << 26)
#define TIE_NONE 0xFFFFFFFFu
#define TIE_ALWAYS 0xFFFFFFFEu
#define TIE_RING_MAX 16u
// counters after phase B's arguments (bArgs + lists * 5; frag_reset clears them): 6 units promoted, 7 their quads; 8 phase A list
// entries lost to capacity, 9 tList entries lost, 10 phase B entries lost (all three must stay 0: lists are sized so they can't);
// (tieCloseVerify) 11 records in both phase A's and phase B's submitted lists, 12 records twice in phase A's (both must be 0)
#define TIE_CNT 6u
#define CAP_CNT 8u
#define VERIFY_CNT 11u
#define TIE_PROMO 4u
#define TIE_SEEN 5u

// r (not phase A's by its own mark) is promoted: another unit of its tie component is phase A's by its mark
static bool fragTiePromote(constant FragFrame &g, device const uint *ring, device uint *occVis, uint r) {
	uint m = ring[r];
	if (m == TIE_NONE) return false;
	if (m == TIE_ALWAYS) return true;
	for (uint k = 0; k < TIE_RING_MAX && m != r; k++) {
		if (fragInA(occVis[m], g)) return true;
		m = ring[m];
	}
	return m != r;  // (a ring longer than the bound, which OwnTerrain never writes: promoted, as TIE_ALWAYS)
}

// -Dmcopt.own.frag.a1Exact (mcown.m: mco_frag_a1_exact, bound to the cull kernels at 22 / 26 / 27): A1's lists (layer 0 of the nearer
// bank, ids 0 .. k.x - 1) also get a per-quad table, written by the cull as it appends: list id's region of the table (k.z entries at
// id * k.z) receives each appended unit's real quads ((meta & 63) + 1), entry = record << 6 | quad, in the order the list receives the
// unit (the same ordered append), so a section's units keep their record order there as in the list. frag_finish turns each region
// into a draw (A1Draws) over the table with F_QFLAT: the same units, the same in-section order, the same quads as the class draw, without
// its padding. A region holds its list's worst case (listCap units of 64 quads), so only a list past listCap can overflow it (then it
// draws nothing; the class draw drops those units too). k: {lists, mode (1 table, 2 count only), region entries, 0}; cnt: per list its
// blocks (reset by frag_reset).
struct A1Bind {
	uint maskLo, maskHi, mode, region;  // the lists with a table (bit id of the 64-bit mask), mode 1 table / 2 count only, region blocks
};

// (a1Exact / a2Exact) list id has a table
static bool a1In(constant A1Bind &k, uint id) {
	return id < 32 ? ((k.maskLo >> id) & 1u) != 0 : id < 64 && ((k.maskHi >> (id - 32)) & 1u) != 0;
}
// a table entry stands for a block of 2^A1_SH quads of a unit (entry = record << 6 | block): the cull writes 2^A1_SH times fewer
// entries; a unit's last block may be partly past its quads (degenerate there, as a class draw's padding)
#ifndef A1_SH
#define A1_SH 2
#endif

// one A1 unit's table entries from its region offset at (mode 1; at + quads past the region: nothing written, the list falls back)
static void a1Write(device uint *tab, constant A1Bind &k, uint id, uint at, uint r, uint blocks) {
	if (k.mode != 1 || at + blocks > k.region) return;
	device uint *t = tab + id * k.region + at;
	for (uint q = 0; q < blocks; q++) t[q] = r << 6 | q;
}

kernel void frag_reset(constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]], device uint *bArgs [[buffer(15)]],
	device uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], constant A1Bind &a1 [[buffer(27)]], uint i [[thread_position_in_grid]]) {
	if (a1In(a1, i)) {
		a1Cnt[i] = 0;
		a1Cnt[64 + i] = 0;  // (the list's real quads, telemetry)
	}
	if (a1.mode != 0 && i < 24) a1Draws[640 + i] = 0;  // (the effective-mode telemetry, frag_finish)
	// the per-bucket stats (flag 32), the occlusion's emitted-quad counters (F_OCC), the unit occlusion's T count and test dispatch,
	// the facing tally (frag_face_stats)
	if (i < 32) args[g.lists * 8 + i] = 0;  // (29-31: a1Exact's stats, frag_finish)
	if ((g.flags & 64) != 0 && i == 0) bArgs[g.lists * 5] = 0;  // (the stats' phase-B quad count)
	if ((g.flags & 64) != 0 && i == 1) {
		bArgs[g.lists * 5 + 4] = 0;  // (bRedraw's units and quads, fragRedraw)
		bArgs[g.lists * 5 + 5] = 0;
	}
	if ((g.flags & 64) != 0 && i == 2) {
		for (uint k = TIE_CNT; k <= VERIFY_CNT + 1; k++) bArgs[g.lists * 5 + k] = 0;  // (tieClose, capacity and verify counters)
	}
	uint ib = (g.flags & 1024) != 0 ? i % (g.lists / 2) : i;  // (flag 1024: two banks of the same lists)
	if ((g.flags & 64) != 0 && i < g.lists) {
		// (flag 64: phase B's draws, the same classes)
		device uint *b = bArgs + i * 5;
		uint cls = (ib >> 1) % g.classes, layer = (ib >> 1) / g.classes;
		b[0] = (layer == 1 && (g.flags & 16) != 0) || (g.flags & 2) == 0 ? g.run * 6 : (min(cls, g.sizes - 1) + 1) * g.sizeQuads * 6;
		b[1] = 0;
		b[2] = 0;
		b[3] = 0;
		b[4] = 0;
	}
	if (i >= g.lists) return;
	device uint *a = args + i * 5;
	uint cls = (ib >> 1) % g.classes, layer = (ib >> 1) / g.classes;
	a[0] = (layer == 1 && (g.flags & 16) != 0) || (g.flags & 2) == 0 ? g.run * 6 : (min(cls, g.sizes - 1) + 1) * g.sizeQuads * 6;
	a[1] = 0;
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
}

static bool fragBoxIn(constant CullFrame &f, float3 lo, float3 hi) {
	for (int p = 0; p < 4; p++) {
		float4 pl = f.planes[p];
		float3 v = select(lo, hi, pl.xyz > 0);
		if (dot(pl.xyz, v) + pl.w < -0.5) return false;
	}
	return true;
}

kernel void frag_cull(constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]], constant FragFrame &g [[buffer(11)]],
	constant SolidOcc &so [[buffer(12)]], device uint *occVis [[buffer(13)]], device uint2 *tList [[buffer(14)]],
	device atomic_uint *a1Cnt [[buffer(22)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	device atomic_uint *bArgs [[buffer(15)]], device const uint *tieRing [[buffer(30)]],
	texture2d<float, access::read> soTex [[texture(1)]], uint i [[thread_position_in_grid]]) {
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (!fragBoxIn(f, o, o + 16)) return;
	float3 cam = -o;
	uint end = s.recStart + s.recCount;
	uint lean = 0;
	uint bin = min(g.classes - 1, uint(length(o + 8.0) / g.binBlocks));
	// (flag 1024: phase A's units of a section this far or farther go to the second bank, drawn after the test)
	uint aBank = (g.flags & 1024) != 0 && length(o + 8.0) >= g.nearBlocks ? g.lists / 2 : 0;
	if ((g.flags & 1) != 0 && g.nowMs - s.uploadMs >= g.fadeMs) {
		// the section's vertices lie in the union of its units' boxes; the cylindrical distance's largest value over a box is at a corner
		uint3 lo = uint3(255), hi = uint3(0);
		for (uint r = s.recStart; r < end; r++) {
			Rec rec = recs[r];
			lo = min(lo, uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255));
			hi = max(hi, uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255));
		}
		float3 a = abs(o + float3(int3(lo) - 16)), b = abs(o + float3(int3(hi) - 16));
		float3 m = max(a, b);
		float cyl = max(length(m.xz), m.y);
		lean = cyl + 1.0 < g.rdStart ? 1 : 0;
	}
	// flag 8: two passes, solid units last to first, then cutout units first to last; else one pass in order
	bool reverse = (g.flags & 8) != 0;
	uint funnelIn = 0, funnelFacing = 0;  // (stats: the section's solid + cutout quads, and those in units that pass the facing test)
	for (uint k = 0; k < (reverse ? 2 : 1) * s.recCount; k++) {
		uint pass = k / s.recCount, r = reverse && pass == 0 ? end - 1 - k : s.recStart + k % s.recCount;
		Rec rec = recs[r];
		uint layer = (rec.meta >> 9) & 3;
		if (reverse && layer != pass) continue;
		if (layer <= 1) funnelIn += (rec.meta & 63) + 1;
		uint bucket = (rec.meta >> 6) & 7;
		float plane = float(rec.plane) / 256.0;
		bool vis;
		switch (bucket) {
			case 0: vis = cam.x > plane - FACE_MARGIN; break;
			case 1: vis = cam.x < plane + FACE_MARGIN; break;
			case 2: vis = cam.y > plane - FACE_MARGIN; break;
			case 3: vis = cam.y < plane + FACE_MARGIN; break;
			case 4: vis = cam.z > plane - FACE_MARGIN; break;
			case 5: vis = cam.z < plane + FACE_MARGIN; break;
			default: vis = true; break;
		}
		if (!vis || layer > 1) continue;
		funnelFacing += (rec.meta & 63) + 1;
		// (solid-section occlusion)
		if (so.on != 0 && solidHidden(so, soTex, o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16),
			o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16))) continue;
		if ((g.flags & 4) != 0) {
			float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
			float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
			if (!fragBoxIn(f, lo, hi)) continue;
		}
		if ((g.flags & 32) != 0) {
			// stats: units and quads listed per facing bucket, after the lists' draw and mesh arguments
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2], 1, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2 + 1], (rec.meta & 63) + 1, memory_order_relaxed);
		}
		// (flags 128, the mesher's bucketClass: the class of the bucket's largest unit, so a bucket's units draw in record order)
		uint cq = (g.flags & 128) != 0 ? (rec.meta >> 11) & 63 : rec.meta & 63;
		uint cls = layer == 1 && (g.flags & 16) != 0 ? bin : (g.flags & 2) != 0 ? min(cq / g.sizeQuads, g.sizes - 1) : 0;
		uint id = (layer * g.classes + cls) * 2 + lean;
		bool promoted = false;
		if ((g.flags & 64) != 0) {
			// unit occlusion: every candidate is tested after phase A (tList, with its list); only the units visible at the last test
			// are drawn in phase A (their occVis holds this frame's number)
			uint t = atomic_fetch_add_explicit(&args[g.lists * 8 + 18], 1, memory_order_relaxed);
			if (t < g.tCap) tList[t] = uint2(r, id);
			else atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 1], 1, memory_order_relaxed);
			bool inA = fragInAL(occVis[r], g, layer);
			promoted = !inA && (g.flags & F_TIE_CLOSE) != 0 && fragTiePromote(g, tieRing, occVis, r);
			if (!inA && !promoted) continue;
			if ((g.flags & 2048) != 0 && aBank != 0) continue;  // (flag 2048: a farther unit is only tested; drawn in phase B if it passes)
			if (promoted) {
				// (tieClose: drawn in phase A for its component; recorded in TIE_PROMO below once its append succeeds)
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT], 1, memory_order_relaxed);
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT + 1], (rec.meta & 63) + 1, memory_order_relaxed);
			}
			// (stats: phase A's quads, in the slot the mesh occlusion's counters use; the two never run together)
			if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&args[g.lists * 8 + 14], (rec.meta & 63) + 1, memory_order_relaxed);
			if ((g.flags & 32768) != 0 && layer == 1) {
				// (bRedraw: this cutout unit is phase A's this frame, in list id; fragRedraw finds it from a solid unit of phase B)
				occVis[g.recCap + 2 * r] = g.epoch * 2;
				occVis[g.recCap + 2 * r + 1] = id;
				occVis[g.recCap * 3 + s.recStart] = g.epoch;  // (the section has phase A cutout this frame: fragRedraw walks it)
			}
			id += fragBank(occVis[r], g, aBank);
		}
		uint at = atomic_fetch_add_explicit(&args[id * 5 + 1], 1, memory_order_relaxed);
		if (at < fragCap(f, g)) {
			lists[id * f.listCap + at] = r;
			if (promoted) occVis[g.recCap * TIE_PROMO + r] = g.epoch;  // (tieClose: drawn in phase A this frame)
		} else if ((g.flags & 64) != 0) {
			atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT], 1, memory_order_relaxed);
		}
		if (a1In(a1, id)) {
			uint nb = ((rec.meta & 63) + (1u << A1_SH)) >> A1_SH;
			uint qa = atomic_fetch_add_explicit(&a1Cnt[id], nb, memory_order_relaxed);
			atomic_fetch_add_explicit(&a1Cnt[64 + id], (rec.meta & 63) + 1, memory_order_relaxed);
			a1Write(a1Tab, a1, id, qa, r, nb);
		}
	}
	if ((g.flags & 32) != 0) {
		// stats, the culling funnel: [27] quads of the sections passed in (solid + cutout, all facings), [28] after the facing test
		atomic_fetch_add_explicit(&args[g.lists * 8 + 27], funnelIn, memory_order_relaxed);
		atomic_fetch_add_explicit(&args[g.lists * 8 + 28], funnelFacing, memory_order_relaxed);
	}
}

// The classified cull with a SIMD group per section and its lanes over the section's records (-Dmcopt.own.int.cullR): the record
// parallelism of a thread-per-record cull with frag_cull's order kept: the 32 lanes take the section's records 32 at a time,
// and each such chunk's appends go out in record order (one atomic per list per chunk, lanes placed by their prefix), chunk after
// chunk from the same lane, so a section's units keep their order inside every list exactly as frag_cull's one thread appends
// them (sections interleave as in frag_cull). The same tests in the same order: mask, distance rule, section box, lean (the union of
// the section's unit boxes, reduced across the lanes), facing, solid occlusion, unit box, uocc. Flags 8 and 16 keep frag_cull.
static uint fragOrderedAppend(device atomic_uint *counter, bool want) {
	uint mine = want ? 1u : 0u;
	uint total = simd_sum(mine);
	if (total == 0) return 0xFFFFFFFFu;
	uint before = simd_prefix_exclusive_sum(mine);
	uint base = 0;
	if (simd_is_first()) base = atomic_fetch_add_explicit(counter, total, memory_order_relaxed);
	base = simd_broadcast_first(base);
	return want ? base + before : 0xFFFFFFFFu;
}

// W lanes a section (frag_cull_s: 32; frag_cull_s16 / frag_cull_s8: 2 / 4 sections a SIMD group, fewer idle lanes on short sections).
// Order: in each pass of the record loop a section's lanes hold consecutive records in lane order and the prefix sums run in lane
// order, so each list still gets a section's units in record order; sections sharing a SIMD group interleave, as threads do in frag_cull.
#define FRAG_CULL_S_PARAMS constant CullFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], \
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device const uint *mask [[buffer(5)]], constant FragFrame &g [[buffer(11)]], \
	constant SolidOcc &so [[buffer(12)]], device uint *occVis [[buffer(13)]], device uint2 *tList [[buffer(14)]], \
	device atomic_uint *a1Cnt [[buffer(22)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]], \
	device atomic_uint *bArgs [[buffer(15)]], device const uint *tieRing [[buffer(30)]], \
	texture2d<float, access::read> soTex [[texture(1)]], uint gid [[thread_position_in_grid]], uint lane [[thread_index_in_simdgroup]]
#define FRAG_CULL_S_ARGS f, sections, recs, args, lists, mask, g, so, occVis, tList, a1Cnt, a1Tab, a1, bArgs, tieRing, soTex, gid, lane
template <ushort W> static void fragCullS(constant CullFrame &f, device const Section *sections, device const Rec *recs, device atomic_uint *args, device uint *lists,
	device const uint *mask, constant FragFrame &g, constant SolidOcc &so, device uint *occVis, device uint2 *tList,
	device atomic_uint *a1Cnt, device uint *a1Tab, constant A1Bind &a1, device atomic_uint *bArgs, device const uint *tieRing,
	texture2d<float, access::read> soTex, uint gid, uint lane) {
	uint i = gid / W, sl = lane % W;  // the section (uniform across its W lanes; 32 / W sections a SIMD group, the grid is sectionCount * W)
	if (i >= f.sectionCount) return;
	if (f.useMask != 0 && (mask[i >> 5] & (1u << (i & 31))) == 0) return;
	Section s = sections[i];
	if (s.recCount == 0) return;
	int3 sec = int3(s.x, s.y, s.z) >> 4;
	int dx = max(0, abs(sec.x - f.camSection.x) - 1), dz = max(0, abs(sec.z - f.camSection.z) - 1);
	if (dx * dx + dz * dz >= f.viewDistance * f.viewDistance) return;
	if (abs(sec.y - f.camSection.y) > f.viewDistance) return;
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (!fragBoxIn(f, o, o + 16)) return;
	float3 cam = -o;
	uint end = s.recStart + s.recCount;
	uint lean = 0;
	uint aBank = (g.flags & 1024) != 0 && length(o + 8.0) >= g.nearBlocks ? g.lists / 2 : 0;  // (flag 1024, as frag_cull)
	if ((g.flags & 1) != 0 && g.nowMs - s.uploadMs >= g.fadeMs) {
		uint3 lo = uint3(255), hi = uint3(0);
		for (uint r = s.recStart + sl; r < end; r += W) {
			Rec rec = recs[r];
			lo = min(lo, uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255));
			hi = max(hi, uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255));
		}
		if (W == 32) {
			lo = uint3(simd_min(lo.x), simd_min(lo.y), simd_min(lo.z));
			hi = uint3(simd_max(hi.x), simd_max(hi.y), simd_max(hi.z));
		} else {
			// over this section's W lanes only (xor within a W-aligned run; all W lanes are here: the section's tests so far are uniform across them)
			for (ushort k = W / 2; k > 0; k >>= 1) {
				lo = min(lo, simd_shuffle_xor(lo, k));
				hi = max(hi, simd_shuffle_xor(hi, k));
			}
		}
		float3 a = abs(o + float3(int3(lo) - 16)), b = abs(o + float3(int3(hi) - 16));
		float3 m = max(a, b);
		float cyl = max(length(m.xz), m.y);
		lean = cyl + 1.0 < g.rdStart ? 1 : 0;
	}
	uint funnelIn = 0, funnelFacing = 0;
	for (uint base = s.recStart; base < end; base += W) {
		uint r = base + sl;
		bool live = r < end, want = false, tested = false, promoted = false;
		uint id = 0, bucket = 0, quads = 0, bank = aBank;
		if (live) {
			Rec rec = recs[r];
			uint layer = (rec.meta >> 9) & 3;
			quads = (rec.meta & 63) + 1;
			if (layer <= 1) funnelIn += quads;
			bucket = (rec.meta >> 6) & 7;
			float plane = float(rec.plane) / 256.0;
			bool vis;
			switch (bucket) {
				case 0: vis = cam.x > plane - FACE_MARGIN; break;
				case 1: vis = cam.x < plane + FACE_MARGIN; break;
				case 2: vis = cam.y > plane - FACE_MARGIN; break;
				case 3: vis = cam.y < plane + FACE_MARGIN; break;
				case 4: vis = cam.z > plane - FACE_MARGIN; break;
				case 5: vis = cam.z < plane + FACE_MARGIN; break;
				default: vis = true; break;
			}
			if (vis && layer <= 1) {
				funnelFacing += quads;
				float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
				float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
				if (!(so.on != 0 && solidHidden(so, soTex, lo, hi)) && ((g.flags & 4) == 0 || fragBoxIn(f, lo, hi))) {
					uint cq = (g.flags & 128) != 0 ? (rec.meta >> 11) & 63 : rec.meta & 63;
					uint cls = (g.flags & 2) != 0 ? min(cq / g.sizeQuads, g.sizes - 1) : 0;
					id = (layer * g.classes + cls) * 2 + lean;
					tested = (g.flags & 64) != 0;
					bool inA = fragInAL(occVis[r], g, layer);
					promoted = tested && !inA && (g.flags & F_TIE_CLOSE) != 0 && fragTiePromote(g, tieRing, occVis, r);
					want = !tested || ((inA || promoted) && ((g.flags & 2048) == 0 || aBank == 0));  // (flag 2048: farther units only tested)
					promoted = promoted && want;
					if (promoted) {
						// (tieClose, as frag_cull: phase A's for its component; TIE_PROMO once its append succeeds)
						atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT], 1, memory_order_relaxed);
						atomic_fetch_add_explicit(&bArgs[g.lists * 5 + TIE_CNT + 1], quads, memory_order_relaxed);
					}
					if (tested && want) bank = fragBank(occVis[r], g, aBank);
					if (tested && want && layer == 1 && (g.flags & 32768) != 0) {
						// (bRedraw: as frag_cull, the cutout unit's phase A mark and list id)
						occVis[g.recCap + 2 * r] = g.epoch * 2;
						occVis[g.recCap + 2 * r + 1] = id;
						occVis[g.recCap * 3 + s.recStart] = g.epoch;  // (as frag_cull; lanes store the same value)
					}
				}
			}
		}
		if ((g.flags & 32) != 0 && (want || tested)) {
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2], 1, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + bucket * 2 + 1], quads, memory_order_relaxed);
			if (tested && want) atomic_fetch_add_explicit(&args[g.lists * 8 + 14], quads, memory_order_relaxed);
		}
		if ((g.flags & 64) != 0) {
			uint t = fragOrderedAppend(&args[g.lists * 8 + 18], tested);
			if (tested && t < g.tCap) tList[t] = uint2(r, id);
			else if (tested) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 1], 1, memory_order_relaxed);
			id += bank;  // (phase A's lists: the section's bank; flag 8192: an untested unit's the later one)
		}
		// this chunk's appends in record order: one round per distinct list among the wanting lanes
		bool pending = want;
		while (simd_any(pending)) {
			uint lid = simd_min(pending ? id : 0xFFFFFFFFu);
			bool mine = pending && id == lid;
			uint at = fragOrderedAppend(&args[lid * 5 + 1], mine);
			if (mine && at < fragCap(f, g)) {
				lists[lid * f.listCap + at] = r;
				if (promoted) occVis[g.recCap * TIE_PROMO + r] = g.epoch;  // (tieClose: drawn in phase A this frame)
			} else if (mine && (g.flags & 64) != 0) {
				atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT], 1, memory_order_relaxed);
			}
			// (the list entries stay: a list whose table region overflows draws its class draw instead)
			if (a1In(a1, lid)) {
				// (a1Exact: the wanting lanes' blocks in the list's table region, in lane order as their list entries would be: a
				// section's units in record order)
				uint mq = mine ? (quads + (1u << A1_SH) - 1) >> A1_SH : 0u, pre = simd_prefix_exclusive_sum(mq), total = simd_sum(mq);
				uint rbase = 0;
				uint realq = simd_sum(mine ? quads : 0u);
				if (simd_is_first()) {
					rbase = atomic_fetch_add_explicit(&a1Cnt[lid], total, memory_order_relaxed);
					atomic_fetch_add_explicit(&a1Cnt[64 + lid], realq, memory_order_relaxed);
				}
				rbase = simd_broadcast_first(rbase);
				// (each wanting lane writes its own blocks: at most 16 a unit)
				if (mine && a1.mode == 1 && rbase + total <= a1.region)
					for (uint j = 0; j < mq; j++) a1Tab[lid * a1.region + rbase + pre + j] = r << 6 | j;
			}
			pending = pending && !mine;
		}
	}
	if ((g.flags & 32) != 0) {
		uint fin = simd_sum(funnelIn), ffa = simd_sum(funnelFacing);
		if (simd_is_first()) {
			atomic_fetch_add_explicit(&args[g.lists * 8 + 27], fin, memory_order_relaxed);
			atomic_fetch_add_explicit(&args[g.lists * 8 + 28], ffa, memory_order_relaxed);
		}
	}
}

kernel void frag_cull_s(FRAG_CULL_S_PARAMS) { fragCullS<32>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s16(FRAG_CULL_S_PARAMS) { fragCullS<16>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s8(FRAG_CULL_S_PARAMS) { fragCullS<8>(FRAG_CULL_S_ARGS); }
kernel void frag_cull_s4(FRAG_CULL_S_PARAMS) { fragCullS<4>(FRAG_CULL_S_ARGS); }

// =verify (-Dmcopt.own.int.cullR=verify): an order-free signature of every list (count, sum, xor, sum of hashed entries), a thread per
// possible entry; set 0 for frag_cull's lists, set 1 for frag_cull_r's: sig[(id * 2 + set) * 4 + 0..3]
kernel void frag_cull_sig(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const uint *args [[buffer(3)]],
	device const uint *lists [[buffer(4)]], device atomic_uint *sig [[buffer(16)]], constant uint &set [[buffer(17)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= min(args[id * 5 + 1], f.listCap)) return;
	uint v = lists[id * f.listCap + at];
	device atomic_uint *q = sig + (id * 2 + set) * 4;
	atomic_fetch_add_explicit(&q[0], 1u, memory_order_relaxed);
	atomic_fetch_add_explicit(&q[1], v, memory_order_relaxed);
	atomic_fetch_xor_explicit(&q[2], v, memory_order_relaxed);
	atomic_fetch_add_explicit(&q[3], (v ^ 0x9e3779b9u) * 2654435761u, memory_order_relaxed);
}

// (and each list's mesh draw arguments, {units, 1, 1} at args + lists * 5 + id * 3)
// A1Draws (a1Exact / a2Exact, per list with a table): the table draw {count, instances, 0, base vertex, 0} (base vertex 4 x the region's
// first entry) and the class draw's fallback (its own arguments with no instances, unless the region or the list overflowed: then the
// table draw has none). Effective-mode telemetry at A1Draws + 640, per group (0 A1 = nearer solid, 1 A2 farther solid, 2 A2 cutout
// both banks), 8 words: lists drawn from the table, lists that fell back (region full), (list past listCap), table blocks, units, real
// quads, the class draws' quad slots, the table draws' quad slots; reset by frag_reset.
// (G: frag_finish_g, -Dmcopt.own.frag.vGroup / vArgsCopy, chosen by mcown.m; frag_finish is the base's kernel unchanged)
template <bool G>
static void fragFinish(constant CullFrame &f, constant FragFrame &g, device uint *args, device const uint *a1Cnt, device uint *a1Draws,
	device uint *a1Tab, constant A1Bind &a1, device uint *lists, device uint *gArgs, uint i) {
	if (i >= g.lists) return;
	uint raw = args[i * 5 + 1], n = min(raw, fragCap(f, g));
	args[i * 5 + 1] = n;
	if (G && (g.flags & (F_VGROUP_MASK | F_VARGS_COPY)) != 0) {
		// (-Dmcopt.own.frag.vGroup, F_VGROUP: the list's class draw as instances of 2^sh units, own_vs F_GROUP) its draw arguments in
		// gArgs (args itself unchanged: every other reader keeps the unit count), and the last instance's units past n as sentinels.
		// mcown.m sets the flag only when listCap is a multiple of 2^sh, so the tail stays inside the list's own region.
		uint sh = (g.flags & F_VGROUP_MASK) >> F_VGROUP_SHIFT, inst = (n + (1u << sh) - 1) >> sh;
		device uint *ga = gArgs + i * 5;
		ga[0] = args[i * 5] << sh;
		ga[1] = inst;
		ga[2] = 0;
		ga[3] = 0;
		ga[4] = 0;
		for (uint e = n; e < (inst << sh); e++) lists[i * f.listCap + e] = 0xFFFFFFFFu;
	}
	if (a1In(a1, i)) {
		// (a1Cnt: blocks of 2^A1_SH quads; the table draw: instances of 64 quad slots, the last one's tail sentinel entries)
		uint q = a1Cnt[i], inst = ((q << A1_SH) + 63) / 64;
		bool full = (inst * 64 >> A1_SH) > a1.region, over = full || raw > f.listCap;
		if (a1.mode == 1 && !over)
			for (uint e = q; e < (inst * 64 >> A1_SH); e++) a1Tab[i * a1.region + e] = 0xFFFFFFFFu;
		device uint *d = a1Draws + i * 10;
		d[0] = 64 * 6;
		d[1] = over ? 0 : inst;
		d[2] = 0;
		d[3] = (i * a1.region << A1_SH) * 4;
		d[4] = 0;
		d[5] = args[i * 5];
		d[6] = over ? n : 0;
		d[7] = 0;
		d[8] = 0;
		d[9] = 0;
		uint hl = (g.flags & 1024) != 0 ? g.lists / 2 : g.lists, ib = i % hl;
		uint grp = (ib >> 1) / g.classes == 1 ? 2u : i >= hl ? 1u : 0u;
		device atomic_uint *tm = (device atomic_uint *) (a1Draws + 640 + grp * 8);
		if (n > 0 || q > 0) atomic_fetch_add_explicit(&tm[full ? 1 : over ? 2 : 0], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[3], q, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[4], n, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[5], a1Cnt[64 + i], memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[6], (args[i * 5] / 6) * n, memory_order_relaxed);
		atomic_fetch_add_explicit(&tm[7], over ? (args[i * 5] / 6) * n : inst * 64, memory_order_relaxed);
		if ((g.flags & 32) != 0 && grp == 0) {
			device atomic_uint *st = (device atomic_uint *) (args + g.lists * 8);
			atomic_fetch_add_explicit(&st[29], q << A1_SH, memory_order_relaxed);  // (A1's block slots: quads + the blocks' padding)
			atomic_fetch_add_explicit(&st[30], n, memory_order_relaxed);
			if (over) atomic_fetch_add_explicit(&st[31], 1, memory_order_relaxed);
		}
	}
	device uint *m = args + g.lists * 5 + i * 3;
	m[0] = n;
	m[1] = 1;
	m[2] = 1;
	if (i == 0 && (g.flags & 64) != 0) {
		// the unit test's dispatch: 64 tested units a threadgroup
		uint t = min(args[g.lists * 8 + 18], g.tCap);
		args[g.lists * 8 + 18] = t;
		args[g.lists * 8 + 19] = (t + 63) / 64;
		args[g.lists * 8 + 20] = 1;
		args[g.lists * 8 + 21] = 1;
	}
}

kernel void frag_finish(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]],
	device const uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	uint i [[thread_position_in_grid]]) {
	fragFinish<false>(f, g, args, a1Cnt, a1Draws, a1Tab, a1, args, args, i);
}

kernel void frag_finish_g(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *args [[buffer(3)]],
	device const uint *a1Cnt [[buffer(22)]], device uint *a1Draws [[buffer(24)]], device uint *a1Tab [[buffer(26)]], constant A1Bind &a1 [[buffer(27)]],
	device uint *lists [[buffer(4)]], device uint *gArgs [[buffer(28)]], uint i [[thread_position_in_grid]]) {
	fragFinish<true>(f, g, args, a1Cnt, a1Draws, a1Tab, a1, lists, gArgs, i);
}

// ---- unit occlusion (-Dmcopt.own.frag.uocc): two-phase, a unit's box against the pyramid of what phase A drew ----
// Frame N's test writes occVis[r] = N + 1 for every visible unit; frame N + 1's cull (it runs anyway) lists exactly those for phase A.
// No list build of its own, no clears (frame numbers), one split; the pyramid is ownPyramid's tiled one. A unit is tested once, by its
// whole-block box: no vertex is fetched.
struct FragTest {
	float4x4 clip;        // projection * view rotation (camera-relative world to clip, before the backend's y flip)
	uint2 screen;         // the pass's depth size
	uint2 hiz0;           // the pyramid's mip 0 size
	uint mips;            // 0: no pyramid (every unit visible)
	uint fine;            // the finer test (-Dmcopt.own.frag.fine=k): k levels below the one where the box spans <= 2 x 2 texels, every
	                      // texel its rectangle covers read (<= (2^k + 2)^2); 0: own_q_b's 2 x 2 at that level
	uint hull;            // 1 (-Dmcopt.own.frag.hull): of the rectangle's texels, only those overlapping the convex hull of the box's 8
	                      // projected corners (a texel the hull touches counts: still conservative)
	uint debug;           // measurement (-Dmcopt.own.frag.uoccDebug): bit 0 every visible unit also goes to phase B, even one marked as
	                      // drawn in phase A (a second draw of the same geometry changes nothing); bit 1 every tested unit counts as visible;
	                      // bit 2 every tested unit marked for phase A and none sent to B (all drawn in phase A from the next frame)
	uint minLevel;        // (-Dmcopt.own.frag.pyrSkip / pyrAuto) the pyramid's levels below this one are not built: tests read this
	                      // level or coarser (its texels the farthest of more pixels: only more conservative)
};

static float fragCross(float2 o, float2 a, float2 b) {
	return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
}

// the convex hull of the 8 points p (sorted in place), counter-clockwise in h with h[k] == h[0]; returns k (< 3: degenerate)
static uint fragHull(thread float2 *p, thread float2 *h) {
	for (uint i = 1; i < 8; i++) {
		float2 v = p[i];
		int j = int(i) - 1;
		while (j >= 0 && (p[j].x > v.x || (p[j].x == v.x && p[j].y > v.y))) {
			p[j + 1] = p[j];
			j--;
		}
		p[j + 1] = v;
	}
	uint k = 0;
	for (uint i = 0; i < 8; i++) {
		while (k >= 2 && fragCross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
		h[k++] = p[i];
	}
	for (int i = 6, t = int(k) + 1; i >= 0; i--) {
		while (int(k) >= t && fragCross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
		h[k++] = p[i];
	}
	return k - 1;
}

// a camera-relative box against the pyramid (own_q_b's test on its 8 corners): false only if wholly behind it
static bool fragTestBox(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	float2 pts[8];
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.05) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
		pts[c] = (n.xy * 0.5 + 0.5) * float2(ft.screen);  // (pixels, as the rectangle counts them)
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen), float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen), float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext));
	// (the finer test: the farthest depth over a region about the box's own size, not up to 4x it: far boxes behind a near ridge
	// no longer take in the sky beside it)
	level = level > ft.fine ? level - ft.fine : 0;
	level = max(level, ft.minLevel);
	uint2 lsize = uint2(max(1u, ft.hiz0.x >> level), max(1u, ft.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float2 hp[16];
	uint hn = ft.hull != 0 ? fragHull(pts, hp) : 0;
	float cell = float(2u << level);  // pixels a texel covers at this level (mip 0: 2 x 2)
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++) {
		for (uint x = la.x; x <= lb.x; x++) {
			if (hn >= 3) {
				// the texel's square wholly outside one hull edge: none of the box can be there (the rectangle covers the other axes)
				float2 q0 = float2(x, y) * cell, q1 = q0 + cell;
				bool outside = false;
				for (uint e = 0; e < hn && !outside; e++) {
					float2 ea = hp[e], eb = hp[e + 1];
					outside = fragCross(ea, eb, q0) < 0 && fragCross(ea, eb, float2(q1.x, q0.y)) < 0 && fragCross(ea, eb, q1) < 0
						&& fragCross(ea, eb, float2(q0.x, q1.y)) < 0;
				}
				if (outside) continue;
			}
			far = min(far, hiz.read(uint2(x, y), level).x);
		}
	}
	return zmax >= far - 1e-6;
}

// The pyramid's mip 0 by a render pass (-Dmcopt.own.frag.rpyr): a fragment per mip-0 texel, the farthest of the 2 x 2 depth pixels it
// covers, texels past the depth's edge 1.0 (as the tiled pyramid pads). Reading the pass's depth in a fragment stage rather than a
// compute dispatch lets the next frame's level pass start its vertex work before this read is done.
struct PyrOut {
	float4 pos [[position]];
};

vertex PyrOut own_pyr_vs(uint vid [[vertex_id]]) {
	PyrOut o;
	float2 p = float2((vid << 1) & 2, vid & 2);
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	return o;
}

fragment float own_pyr_fs(PyrOut in [[stage_in]], depth2d<float, access::read> depth [[texture(0)]]) {
	uint2 t = uint2(in.pos.xy);
	uint dw = depth.get_width(), dh = depth.get_height();
	float m = 1.0;
	for (uint y = t.y * 2; y < min(t.y * 2 + 2, dh); y++)
		for (uint x = t.x * 2; x < min(t.x * 2 + 2, dw); x++) m = min(m, depth.read(uint2(x, y)));
	return m;
}

// one thread per tested unit (tList): visible -> occVis = N + 1 (phase A next frame); visible and not drawn in phase A -> phase B's list
// bRedraw (FragFrame flag 32768): solid unit rec just went to phase B, which draws it after phase A's cutout. Vanilla draws all of SOLID
// before all of CUTOUT with GREATER_EQUAL, so a cutout quad wins its exact depth tie with a solid one (grass_block_side_overlay over
// grass_block_side: identical corners, same block). Phase B's cutout pass therefore draws again every cutout unit phase A drew that may hold
// such a partner: the same section, the same facing bucket (a coincident quad faces the same way), the boxes overlapping. Each is claimed
// once (its mark epoch * 2 -> epoch * 2 + 1), so another solid unit of phase B never adds it twice. Idempotent where nothing changed: the
// same pipeline, vertices and frame give the same depth and colour, and the cutout layer does not blend.
static void fragRedraw(constant CullFrame &f, constant FragFrame &g, device const Section *sections, device const Rec *recs, device uint *occVis,
	device atomic_uint *bArgs, device uint *listsB, Rec rec) {
	if ((g.flags & 32768) == 0 || ((rec.meta >> 9) & 3) != 0) return;
	Section s = sections[rec.slot];
	if (occVis[g.recCap * 3 + s.recStart] != g.epoch) return;  // (no phase A cutout in this section this frame: e.g. a new section)
	device atomic_uint *am = (device atomic_uint *) (occVis + g.recCap);
	uint bucket = (rec.meta >> 6) & 7, inA = g.epoch * 2;
	uint3 lo = uint3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255), hi = uint3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255);
	// (the section's cutout run from Section.pad0, OwnTerrain.setSlot: bit 31 valid, bits 16-30 its units, bits 0-15 the solid units before
	// it; else every record)
	uint k0 = s.recStart, k1 = s.recStart + s.recCount;
	if ((s.pad0 & 0x80000000u) != 0) {
		k0 = s.recStart + (s.pad0 & 0xFFFFu);
		k1 = min(k1, k0 + ((s.pad0 >> 16) & 0x7FFFu));
	}
	for (uint k = k0; k < k1; k++) {
		Rec q = recs[k];
		if (((q.meta >> 9) & 3) != 1 || ((q.meta >> 6) & 7) != bucket) continue;
		uint3 ql = uint3(q.lo & 255, (q.lo >> 8) & 255, (q.lo >> 16) & 255), qh = uint3(q.hi & 255, (q.hi >> 8) & 255, (q.hi >> 16) & 255);
		if (any(ql > hi) || any(lo > qh)) continue;
		uint expected = inA;
		bool won = false;
		while (!won) {
			won = atomic_compare_exchange_weak_explicit(&am[2 * k], &expected, inA + 1, memory_order_relaxed, memory_order_relaxed);
			if (!won && expected != inA) break;  // (not phase A's this frame, or already claimed)
			expected = inA;
		}
		if (!won) continue;
		uint id = atomic_load_explicit(&am[2 * k + 1], memory_order_relaxed);
		if (id >= g.lists) continue;
		uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
		if (at >= f.listCap) continue;
		listsB[id * f.listCap + at] = k;
		// (counts: the units and quads redrawn, after B's arguments and testFast's checks; reset by frag_reset)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 4], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 5], (q.meta & 63) + 1, memory_order_relaxed);
	}
}

kernel void frag_uocc_test(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], constant FragTest &ft [[buffer(17)]],
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]],
	device uint *occVis [[buffer(13)]], device const uint2 *tList [[buffer(14)]], device atomic_uint *bArgs [[buffer(15)]],
	device uint *listsB [[buffer(16)]], texture2d<float, access::read> hiz [[texture(0)]], uint i [[thread_position_in_grid]]) {
	// (args read-only: an encoder that writes it would hold every later pass that reads phase A's draw arguments, e.g. splitA's A2)
	if (i >= min(args[g.lists * 8 + 18], g.tCap)) return;  // (entries past the capacity were never written)
	uint2 e = tList[i];
	uint r = e.x, id = e.y;
	// this test's mark: the frame it is for (the next, or with pipe the one after), with flag 4096 times 2 + visible
	bool hist = (g.flags & 4096) != 0;
	uint mark = g.epoch + ((g.flags & 512) != 0 ? 2 : 1);
	if (hist) mark = mark * 2 + 1;
	if ((ft.debug & 4) != 0) {
		// (measurement: every tested unit marked for phase A, none to B: from the next frame all draw in phase A, in C's list order)
		occVis[r] = mark;
		return;
	}
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool vis;
	if ((g.flags & 256) != 0 && ((rec.lo >> 24) | (rec.hi >> 24) | rec.pad1) != 0) {
		// the mesher's two sub-boxes (OwnQuads.subboxes): A = [U.lo + (n0, n1, n2), U.hi - (n3, n4, n5)], B = [U.lo + (n6, n7, n8),
		// U.hi - (n9, n10, n11)], n0..n3 in lo / hi bits 24-31, n4..n11 the nibbles of pad1
		int3 ul = int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16, uh = int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16;
		uint p = rec.pad1;
		int3 aLo = ul + int3((rec.lo >> 24) & 15, rec.lo >> 28, (rec.hi >> 24) & 15), aHi = uh - int3(rec.hi >> 28, p & 15, (p >> 4) & 15);
		int3 bLo = ul + int3((p >> 8) & 15, (p >> 12) & 15, (p >> 16) & 15), bHi = uh - int3((p >> 20) & 15, (p >> 24) & 15, p >> 28);
		vis = fragTestBox(o + float3(aLo), o + float3(aHi), ft, hiz) || fragTestBox(o + float3(bLo), o + float3(bHi), ft, hiz);
	} else {
		vis = fragTestBox(lo, hi, ft, hiz);
	}
	if ((ft.debug & 2) != 0) vis = true;  // (measurement: nothing culled; units not drawn in phase A still go to B, after the split)
	if (!vis) {
		if (hist) occVis[r] = mark - 1;  // (flag 4096: tested, hidden)
		return;
	}
	uint old = occVis[r];
	occVis[r] = mark;
	// (drawn in phase A, as the cull decided from the same mark; flag 2048: a farther section's unit never is)
	bool farOnly = (g.flags & 2048) != 0 && length(o + 8.0) >= g.nearBlocks;
	// (tieClose: or promoted and appended to phase A by this frame's cull)
	bool promoted = (g.flags & F_TIE_CLOSE) != 0 && occVis[g.recCap * TIE_PROMO + r] == g.epoch;
	if ((fragInAL(old, g, (rec.meta >> 9) & 3) || promoted) && (ft.debug & 1) == 0 && !farOnly) return;
	uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
	if (at >= fragCap(f, g)) {
		if ((g.flags & F_TIE_CLOSE) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 2], 1, memory_order_relaxed);
		return;
	}
	listsB[id * f.listCap + at] = r;
	if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5], (rec.meta & 63) + 1, memory_order_relaxed);  // (stats: phase B's quads, after B's arguments)
	fragRedraw(f, g, sections, recs, occVis, bArgs, listsB, rec);
}

// ---- testFast (-Dmcopt.own.frag.testFast): frag_uocc_test's unit test with less work, never a unit fewer ----
// fragTestBox without the hull (testFast is off with -Dmcopt.own.frag.hull: no pts / hp arrays, fewer registers) and with two exits:
// (1) coarse reject: the <= 2 x 2 texels of the level before the fine shift are ancestors of every texel the fine rectangle reads (each
// texel is the farthest of those it covers, the odd edges' 3-wide texels included), so their farthest depth is no nearer than the fine
// one; (2) the fine loop stops once the box is in front (far only falls). The library is built with fast math, so the same expression
// may round differently here than in fragTestBox (verify mode saw 12 of 3.7e8 verdicts differ without margins): every bound here is
// widened so this test can only pass more boxes than fragTestBox, never fewer - FAST_TEST_PX pixels on the rectangle and the frustum
// (projection rounding is ~1e-3 pixel), 0.001 on the w cut, FAST_TEST_DZ on the depth compare (a few ulps at 1.0).
// G (-Dmcopt.own.frag.testGather): the texels read four at a time by gathers from the pyramid's level views (lv), the ones outside the
// rectangle masked out: the same texels, the same minimum.
constant float FAST_TEST_DZ = 5e-7;
constant float FAST_TEST_PX = 1.0 / 32.0;

// the farthest depth of the texels [la, lb] of one level (lsize texels): read one by one, or (G) four at a time; returns early once
// zm >= far - 1e-6 (STOP)
template <bool G, bool STOP>
static float fragFar(uint level, uint2 la, uint2 lb, uint2 lsize, float zm, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv) {
	float far = 1.0;
	if (G) {
		constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
		float2 inv = 1.0 / float2(lsize);
		for (uint y = la.y; y <= lb.y; y += 2) {
			for (uint x = la.x; x <= lb.x; x += 2) {
				// (gather order: w (x, y), z (x + 1, y), x (x, y + 1), y (x + 1, y + 1))
				float4 q = lv[level].gather(nearestClamp, float2(x + 1, y + 1) * inv);
				bool r = x + 1 <= lb.x, d = y + 1 <= lb.y;
				float m = min(q.w, r ? q.z : 1.0);
				if (d) m = min(m, min(q.x, r ? q.y : 1.0));
				far = min(far, m);
				if (STOP && zm >= far - 1e-6) return far;
			}
		}
		return far;
	}
	for (uint y = la.y; y <= lb.y; y++) {
		for (uint x = la.x; x <= lb.x; x++) {
			far = min(far, hiz.read(uint2(x, y), level).x);
			if (STOP && zm >= far - 1e-6) return far;
		}
	}
	return far;
}

template <bool G>
static bool fragTestBoxFast(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.051) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	float2 px = FAST_TEST_PX * 2.0 / float2(ft.screen);
	if (mx.x < -1 - px.x || mx.y < -1 - px.y || mn.x > 1 + px.x || mn.y > 1 + px.y) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen) - FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen) + FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint coarse = ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext));
	uint level = max(coarse > ft.fine ? coarse - ft.fine : 0, ft.minLevel);
	coarse = max(coarse, level);
	float zm = zmax + FAST_TEST_DZ;
	if (level < coarse) {
		uint2 csize = uint2(max(1u, ft.hiz0.x >> coarse), max(1u, ft.hiz0.y >> coarse));
		float cf = fragFar<G, false>(coarse, min(a >> coarse, csize - 1), min(b >> coarse, csize - 1), csize, zm, hiz, lv);
		if (!(zm >= cf - 1e-6)) return false;
	}
	uint2 lsize = uint2(max(1u, ft.hiz0.x >> level), max(1u, ft.hiz0.y >> level));
	float far = fragFar<G, true>(level, min(a >> level, lsize - 1), min(b >> level, lsize - 1), lsize, zm, hiz, lv);
	return zm >= far - 1e-6;
}

// (-Dmcopt.own.frag.testBox, with testFast and msub) the whole unit box's coarse texels: false only if every box inside it fails
// fragTestBox. Margins cover float error between the box's and a sub-box's projections: 1 pixel on the rectangle, 1e-5 on zmax,
// and no reject when a corner is within 1e-3 of the w cut (a sub-box corner would then be all but on it).
static bool fragBoxMayPass(float3 lo, float3 hi, constant FragTest &ft, texture2d<float, access::read> hiz) {
	if (ft.mips == 0) return true;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = ft.clip * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.051) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	float2 px = FAST_TEST_PX * 2.0 / float2(ft.screen);
	if (mx.x < -1 - px.x || mx.y < -1 - px.y || mn.x > 1 + px.x || mn.y > 1 + px.y) return false;
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(ft.screen) - FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(ft.screen) + FAST_TEST_PX, float2(0), float2(ft.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint coarse = max(ext == 0 ? 0 : min(ft.mips - 1, 32 - clz(ext)), ft.minLevel);
	uint2 csize = uint2(max(1u, ft.hiz0.x >> coarse), max(1u, ft.hiz0.y >> coarse));
	uint2 ca = min(a >> coarse, csize - 1), cb = min(b >> coarse, csize - 1);
	float cf = 1.0;
	for (uint y = ca.y; y <= cb.y; y++)
		for (uint x = ca.x; x <= cb.x; x++) cf = min(cf, hiz.read(uint2(x, y), coarse).x);
	return zmax + 1e-5 >= cf - 1e-6;
}

// frag_uocc_test with fragTestBoxFast (debug bits 0-2 as there); BOX (frag_uocc_test_fb, -Dmcopt.own.frag.testBox): with sub-boxes,
// the whole box's fragBoxMayPass first. debug bit 3 (measurement, -Dmcopt.own.frag.uoccDebug=8): also fragTestBox's verdict;
// bArgs[lists * 5 + 1] counts the units it passes and this test doesn't (must be 0), + 2 the units tested, + 3 the units this test
// passes and it doesn't (the margins' extra units) (cumulative: never reset).
template <bool BOX, bool G>
static void fragUoccTestF(constant CullFrame &f, constant FragFrame &g, constant FragTest &ft, device const Section *sections,
	device const Rec *recs, device const uint *args, device uint *occVis, device const uint2 *tList, device atomic_uint *bArgs,
	device uint *listsB, texture2d<float, access::read> hiz, array<texture2d<float>, 12> lv, uint i) {
	if (i >= min(args[g.lists * 8 + 18], g.tCap)) return;  // (entries past the capacity were never written)
	uint2 e = tList[i];
	uint r = e.x, id = e.y;
	// this test's mark, as frag_uocc_test (flag 4096: frame * 2 + visible)
	bool hist = (g.flags & 4096) != 0;
	uint mark = g.epoch + ((g.flags & 512) != 0 ? 2 : 1);
	if (hist) mark = mark * 2 + 1;
	if ((ft.debug & 4) != 0) {
		occVis[r] = mark;
		return;
	}
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool vis, sub = (g.flags & 256) != 0 && ((rec.lo >> 24) | (rec.hi >> 24) | rec.pad1) != 0;
	float3 aL, aH, bL, bH;
	if (sub) {
		int3 ul = int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16, uh = int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16;
		uint p = rec.pad1;
		int3 aLo = ul + int3((rec.lo >> 24) & 15, rec.lo >> 28, (rec.hi >> 24) & 15), aHi = uh - int3(rec.hi >> 28, p & 15, (p >> 4) & 15);
		int3 bLo = ul + int3((p >> 8) & 15, (p >> 12) & 15, (p >> 16) & 15), bHi = uh - int3((p >> 20) & 15, (p >> 24) & 15, p >> 28);
		aL = o + float3(aLo); aH = o + float3(aHi); bL = o + float3(bLo); bH = o + float3(bHi);
		vis = (!BOX || fragBoxMayPass(lo, hi, ft, hiz)) && (fragTestBoxFast<G>(aL, aH, ft, hiz, lv) || fragTestBoxFast<G>(bL, bH, ft, hiz, lv));
	} else {
		vis = fragTestBoxFast<G>(lo, hi, ft, hiz, lv);
	}
	if ((ft.debug & 8) != 0) {
		bool slow = sub ? fragTestBox(aL, aH, ft, hiz) || fragTestBox(bL, bH, ft, hiz) : fragTestBox(lo, hi, ft, hiz);
		if (slow && !vis) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 1], 1, memory_order_relaxed);  // (must stay 0: a unit lost)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 2], 1, memory_order_relaxed);
		if (vis && !slow) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + 3], 1, memory_order_relaxed);  // (the margins' extra units)
	}
	if ((ft.debug & 2) != 0) vis = true;
	if (!vis) {
		if (hist) occVis[r] = mark - 1;  // (flag 4096: tested, hidden)
		return;
	}
	uint old = occVis[r];
	occVis[r] = mark;
	bool farOnly = (g.flags & 2048) != 0 && length(o + 8.0) >= g.nearBlocks;
	// (tieClose: or promoted and appended to phase A by this frame's cull)
	bool promoted = (g.flags & F_TIE_CLOSE) != 0 && occVis[g.recCap * TIE_PROMO + r] == g.epoch;
	if ((fragInAL(old, g, (rec.meta >> 9) & 3) || promoted) && (ft.debug & 1) == 0 && !farOnly) return;
	uint at = atomic_fetch_add_explicit(&bArgs[id * 5 + 1], 1, memory_order_relaxed);
	if (at >= fragCap(f, g)) {
		if ((g.flags & F_TIE_CLOSE) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5 + CAP_CNT + 2], 1, memory_order_relaxed);
		return;
	}
	listsB[id * f.listCap + at] = r;
	if ((g.flags & 32) != 0) atomic_fetch_add_explicit(&bArgs[g.lists * 5], (rec.meta & 63) + 1, memory_order_relaxed);
	fragRedraw(f, g, sections, recs, occVis, bArgs, listsB, rec);
}

#define FRAG_UOCC_TEST_PARAMS constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], constant FragTest &ft [[buffer(17)]], \
	device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]], \
	device uint *occVis [[buffer(13)]], device const uint2 *tList [[buffer(14)]], device atomic_uint *bArgs [[buffer(15)]], \
	device uint *listsB [[buffer(16)]], texture2d<float, access::read> hiz [[texture(0)]], array<texture2d<float>, 12> lv [[texture(1)]], \
	uint i [[thread_position_in_grid]]
kernel void frag_uocc_test_f(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<false, false>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }
kernel void frag_uocc_test_fb(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<true, false>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }
kernel void frag_uocc_test_fg(FRAG_UOCC_TEST_PARAMS) { fragUoccTestF<false, true>(f, g, ft, sections, recs, args, occVis, tList, bArgs, listsB, hiz, lv, i); }

kernel void frag_uocc_finish(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *bArgs [[buffer(15)]],
	uint i [[thread_position_in_grid]]) {
	if (i >= g.lists) return;
	bArgs[i * 5 + 1] = min(bArgs[i * 5 + 1], fragCap(f, g));
}

// (frag_uocc_finish with -Dmcopt.own.frag.vGroup / vArgsCopy, chosen by mcown.m)
kernel void frag_uocc_finish_g(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device uint *bArgs [[buffer(15)]],
	device uint *listsB [[buffer(16)]], device uint *gArgs [[buffer(28)]], uint i [[thread_position_in_grid]]) {
	if (i >= g.lists) return;
	uint n = min(bArgs[i * 5 + 1], fragCap(f, g));
	bArgs[i * 5 + 1] = n;
	if ((g.flags & (F_VGROUP_MASK | F_VARGS_COPY)) != 0) {
		// (-Dmcopt.own.frag.vGroup: phase B's class draws grouped as phase A's, frag_finish; mcown.m checks listCap's multiple)
		uint sh = (g.flags & F_VGROUP_MASK) >> F_VGROUP_SHIFT, inst = (n + (1u << sh) - 1) >> sh;
		device uint *ga = gArgs + i * 5;
		ga[0] = bArgs[i * 5] << sh;
		ga[1] = inst;
		ga[2] = 0;
		ga[3] = 0;
		ga[4] = 0;
		for (uint e = n; e < (inst << sh); e++) listsB[i * f.listCap + e] = 0xFFFFFFFFu;
	}
}

// -Dmcopt.own.frag.tieCloseVerify (mcown.m, after frag_uocc_finish): from the lists actually submitted, a record in both phase A's
// and phase B's lists ([VERIFY_CNT]) or twice in phase A's ([VERIFY_CNT + 1]) this frame; both must stay 0. A: a thread per list
// entry stamps TIE_SEEN with this frame's number (an exchange: a second stamp is a duplicate); B: a thread per B entry checks it.
kernel void frag_tie_verify_a(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const uint *args [[buffer(3)]],
	device const uint *lists [[buffer(4)]], device atomic_uint *occVis [[buffer(13)]], device atomic_uint *bArgs [[buffer(15)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= min(args[id * 5 + 1], fragCap(f, g))) return;
	uint r = lists[id * f.listCap + at];
	if (atomic_exchange_explicit(&occVis[g.recCap * TIE_SEEN + r], g.epoch, memory_order_relaxed) == g.epoch)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + VERIFY_CNT + 1], 1, memory_order_relaxed);
}

kernel void frag_tie_verify_b(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device atomic_uint *occVis [[buffer(13)]],
	device atomic_uint *bArgs [[buffer(15)]], device const uint *listsB [[buffer(16)]], uint i [[thread_position_in_grid]]) {
	uint id = i / f.listCap, at = i % f.listCap;
	if (id >= g.lists || at >= atomic_load_explicit(&bArgs[id * 5 + 1], memory_order_relaxed)) return;
	uint r = listsB[id * f.listCap + at];
	if (atomic_load_explicit(&occVis[g.recCap * TIE_SEEN + r], memory_order_relaxed) == g.epoch)
		atomic_fetch_add_explicit(&bArgs[g.lists * 5 + VERIFY_CNT], 1, memory_order_relaxed);
}

// ---- phase B: the depth pyramid of what phase A drew, and every tested unit against it ----

// mip 0 of the pyramid: each texel the farthest (reversed Z: smallest) depth of the 2 x 2 pixels it covers
kernel void own_hiz0(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	constant uint &scale [[buffer(2)]], uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height(), dw = depth.get_width(), dh = depth.get_height();
	if (p.x >= w || p.y >= h) return;
	float m = 1.0;
	for (uint y = p.y * scale; y < min(p.y * scale + scale, dh); y++)
		for (uint x = p.x * scale; x < min(p.x * scale + scale, dw); x++) m = min(m, depth.read(uint2(x, y)));
	out.write(float4(m), p);
}

// mip n from mip n - 1: the smallest of the texels it covers (3 wide where the previous mip is odd, so none is lost)
kernel void own_hizn(texture2d<float, access::read> prev [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height(), pw = prev.get_width(), ph = prev.get_height();
	if (p.x >= w || p.y >= h) return;
	uint x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw), y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	float m = 1.0;
	for (uint y = p.y * 2; y < y1; y++)
		for (uint x = p.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
	out.write(float4(m), p);
}

struct OccFrame {
	float4x4 clip;        // projection * view rotation (camera-relative world to clip, before the backend's y flip)
	int4 camBlock;
	float4 camOffset;
	uint2 screen;         // pixels
	uint2 hiz0;           // mip 0 size
	uint mips;
	uint listCap;
	uint visWords;
	uint fat;
};

kernel void own_occ(constant OccFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device atomic_uint *args [[buffer(3)]], device uint *lists [[buffer(4)]], device atomic_uint *vis [[buffer(6)]],
	device const uint *tested [[buffer(9)]], device Inst *fat [[buffer(10)]], texture2d<float, access::read> hiz [[texture(0)]],
	uint i [[thread_position_in_grid]]) {
	if (i >= atomic_load_explicit(&args[ARGS_T], memory_order_relaxed)) return;
	uint t = tested[i], r = t & 0x7fffffffu;
	bool drawnA = (t >> 31) != 0;
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	bool visible = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (int c = 0; c < 8 && !visible; c++) {
		float3 v = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);
		float4 q = f.clip * float4(v, 1.0);
		if (q.w <= 0.05) {
			visible = true;  // reaches the camera plane: no screen rectangle to test
			break;
		}
		float3 n = q.xyz / q.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);  // reversed Z: the nearest corner is the largest
	}
	if (!visible) {
		// the backend's rows run as GL's (flipped vertex Y): row = (ndc.y * 0.5 + 0.5) * height
		float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) {
			visible = false;
		} else {
			uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 la = a >> level, lb = b >> level;
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			la = min(la, lsize - 1);  // (the level is capped at the last mip: then a box's start can lie past it too)
			lb = min(lb, lsize - 1);
			float far = 1.0;
			for (uint y = la.y; y <= lb.y; y++)
				for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
			visible = zmax >= far - 1e-6;
		}
	}
	if (!visible) return;
	atomic_fetch_or_explicit(&vis[r >> 5], 1u << (r & 31), memory_order_relaxed);
	if (drawnA) return;
	uint layer = (rec.meta >> 9) & 1;
	uint at = atomic_fetch_add_explicit(&args[(2 + layer) * 5 + 1], 1, memory_order_relaxed);
	if (at < f.listCap) {
		if (f.fat != 0) putInst(fat, (2 + layer) * f.listCap + at, rec, sections);
		else lists[(2 + layer) * f.listCap + at] = r;
	}
}

kernel void own_finish_b(constant OccFrame &f [[buffer(0)]], device uint *args [[buffer(3)]], uint i [[thread_position_in_grid]]) {
	if (i >= 2) return;
	args[(2 + i) * 5 + 1] = min(args[(2 + i) * 5 + 1], f.listCap);
}

// ---- draw ----

// frag variants (-Dmcopt.own.frag.*), all off unless set:
// F_HALF: the vertex colour (8-bit colour x 8-bit lightmap) crosses to the fragment stage as half4 (within 1 LSB of the output)
// F_LEAN: units of sections that are faded in and wholly inside the render-distance fog start (the cull's lean class): the
//   cylindrical distance (render-distance fog 0 there) and the chunk visibility (1 there) are not interpolated at all
// F_NONIDX: probe only: 6 vertex invocations a quad, no index buffer (measures the index reuse)
constant bool F_HALF_SET [[function_constant(9)]];
constant bool F_LEAN_SET [[function_constant(10)]];
constant bool F_NONIDX_SET [[function_constant(11)]];
// F_REVERSE: a unit's quads drawn last to first (with the cull's reversed unit order and a strict depth test: vanilla's depth ties, where
// the later quad wins, resolved alike while drawing first-wins)
constant bool F_REVERSE_SET [[function_constant(12)]];
constant bool F_REVERSE = is_function_constant_defined(F_REVERSE_SET) && F_REVERSE_SET;
// F_TIGHT: the unit's record and the compact vertex each in one 16-byte load (the compiler otherwise splits the record into two
// load rounds around the padding test and the vertex into four loads), and no default outputs for padding lanes (their triangles
// lie outside the clip volume, so nothing reads them). The same values reach the same arithmetic: outputs are bit-identical.
constant bool F_TIGHT_SET [[function_constant(13)]];
constant bool F_TIGHT = is_function_constant_defined(F_TIGHT_SET) && F_TIGHT_SET;
// F_POSONLY: probe only: the vertex stage outputs the position alone (with own_fs_pos), the geometry's floor with no varyings
constant bool F_POSONLY_SET [[function_constant(14)]];
constant bool F_POSONLY = is_function_constant_defined(F_POSONLY_SET) && F_POSONLY_SET;
// F_OCCA / F_OCCB (mesh shading only, -Dmcopt.own.frag.occ): two-phase occlusion inside the mesh stage. Each unit record keeps a
// 32-bit mask of its quads that were visible at its last phase B (masks, by record index). Phase A emits the masked quads that pass
// the clip and facing tests; then the pass is split once and the depth pyramid built (ownPyramid); phase B tests every quad
// of the same units against it, emits the visible ones phase A did not draw and stores the unit's new mask. A stale mask (a record
// reused, a unit not drawn for a while) only moves quads between the phases: phase B draws exactly what is visible and was not drawn.
constant bool F_OCCA_SET [[function_constant(15)]];
constant bool F_OCCB_SET [[function_constant(16)]];
constant bool F_OCCA = is_function_constant_defined(F_OCCA_SET) && F_OCCA_SET;
constant bool F_OCCB = is_function_constant_defined(F_OCCB_SET) && F_OCCB_SET;
constant bool F_OCC = F_OCCA || F_OCCB;
constant bool F_HALF = is_function_constant_defined(F_HALF_SET) && F_HALF_SET;
constant bool F_LEAN = is_function_constant_defined(F_LEAN_SET) && F_LEAN_SET;
constant bool F_NONIDX = is_function_constant_defined(F_NONIDX_SET) && F_NONIDX_SET;
constant bool F_VARY = !F_POSONLY;
constant bool F_FLOATCOLOR = !F_HALF && F_VARY;
constant bool F_HALFCOLOR = F_HALF && F_VARY;
constant bool F_RICH = !F_LEAN && F_VARY;
// F_FOGVERT (measurement, frag.aKind=fogvert, kind bit 14): in lean pipelines (render-distance fog exactly 0 there) the vertex stage
// computes the environmental fog value and passes it in sphericalVertexDistance's place; the fragment stage only mixes. Where no clamp
// of linear_fog_value is active over a triangle, the interpolated value is the same affine function of the interpolated distance:
// exact up to float rounding. F_FOGVERT_ALL (frag.aKind=fogvertall, bit 15): every pipeline (rich ones with the render-distance max
// per vertex too: not exact; the saving's upper bound).
constant bool F_FOGVERT_SET [[function_constant(18)]];
constant bool F_FOGVERT_ALL_SET [[function_constant(19)]];
constant bool F_FOGVERT = is_function_constant_defined(F_FOGVERT_SET) && F_FOGVERT_SET;
constant bool F_FOGVERT_ALL = is_function_constant_defined(F_FOGVERT_ALL_SET) && F_FOGVERT_ALL_SET;
constant bool F_FV = F_FOGVERT && F_VARY && (F_LEAN || F_FOGVERT_ALL);
// F_FOGVERT_T (measurement, frag.tKind=fogvert, kind bit 13; our translucent draw, one pipeline, order unchanged): the vertex stage tests
// its section's whole box (render-distance fog start not reached, the environmental fog end not reached, its start <= 0) and passes a
// flat in-class flag with the environmental fog value per vertex; the fragment stage uses that value in class (exact there: the
// render-distance term is 0 and the environmental one has no active clamp) and vanilla's per-pixel fog outside it.
constant bool F_FOGVERT_T_SET [[function_constant(20)]];
constant bool F_FVT = is_function_constant_defined(F_FOGVERT_T_SET) && F_FOGVERT_T_SET && F_VARY;
constant bool F_FOGVERT_ANY = F_FV || F_FVT;
// F_QFLAT (-Dmcopt.own.frag.a1Exact, kind bit 12; A1's solid draws): the draw runs over the cull's per-quad table (frag_a1_write) bound in
// the lists' place, entry = unit record << 6 | quad; vertex_id >> 2 (the draw's base vertex is 4 x its first entry) picks the entry, so
// each unit issues exactly its quads, in list order, instead of its class's padded count.
constant bool F_QFLAT_SET [[function_constant(21)]];
constant bool F_QFLAT = is_function_constant_defined(F_QFLAT_SET) && F_QFLAT_SET;
// F_GROUP (-Dmcopt.own.frag.vGroup, kind bit 11, set by mcown.m on phase A's class draws): each instance draws 2^grp.z consecutive
// units of the list (each still its class's grp.x quad slots), not one: the same primitives in the same order (instance-major, unit
// after unit), fewer, larger instances. grp.y: ceil(2^21 / grp.x) (vertex_id / 4 / grp.x as a multiply, exact below 2^10 slots).
// frag_finish's sentinel entries (0xFFFFFFFF) fill the last instance past the list's units: degenerate, as padding.
constant bool F_GROUP_SET [[function_constant(25)]];
constant bool F_GROUP = is_function_constant_defined(F_GROUP_SET) && F_GROUP_SET;

// vanilla's linear_fog_value (the same as the fragment stage's below), for F_FV's per-vertex fog
static float linearFogV(float vertexDistance, float fogStart, float fogEnd) {
	if (vertexDistance <= fogStart) return 0.0;
	else if (vertexDistance >= fogEnd) return 1.0;
	return (vertexDistance - fogStart) / (fogEnd - fogStart);
}
// the visibility oracle (-Dmcopt.own.oracle, debug only): own_vs stores each drawn unit's record index once a sample frame
// into oracleKept and passes it flat to own_fs_oracle, which (depth EQUAL against the frame's final depth, early tests, no colour)
// marks the units that own at least one visible pixel
constant bool F_ORACLE_SET [[function_constant(17)]];
constant bool F_ORACLE = is_function_constant_defined(F_ORACLE_SET) && F_ORACLE_SET;

struct VOut {
	float4 pos [[position]];
	float sphericalVertexDistance [[function_constant(F_VARY)]];
	float cylindricalVertexDistance [[function_constant(F_RICH)]];
	float4 vertexColor [[function_constant(F_FLOATCOLOR)]];
	half4 vertexColorH [[function_constant(F_HALFCOLOR)]];
	float2 texCoord0 [[function_constant(F_VARY)]];
	float chunkVisibility [[function_constant(F_RICH)]];
	uint oracleUnit [[flat]] [[function_constant(F_ORACLE)]];
	float fogF [[function_constant(F_FVT)]];
	uint fogIn [[flat]] [[function_constant(F_FVT)]];
};

static void putColor(thread VOut &o, float4 c) {
	if (F_POSONLY) return;
	if (F_HALF) o.vertexColorH = half4(c);
	else o.vertexColor = c;
}

static float4 getColor(VOut in) {
	if (F_POSONLY) return float4(1);
	if (F_HALF) return float4(in.vertexColorH);
	return in.vertexColor;
}

static void putRich(thread VOut &o, float cyl, float cv) {
	if (F_RICH) {
		o.cylindricalVertexDistance = cyl;
		o.chunkVisibility = cv;
	}
}

// -Dmcopt.own.mesh.exactPos (OWN_EXACT_POS, prepended by OwnTerrain): a position code >= 61440 indexes the exact coordinate in the
// table at the start of the arena (OwnPosTable); without it, codes decode as before
#ifdef OWN_EXACT_POS
static float ownPosCode(device const void *arena, uint c) {
	return c >= 61440u ? ((device const float *) arena)[c - 61440u] : float(c) / 2048.0 - 8.0;
}
#define OWN_POS3(arena, a, b, c) float3(ownPosCode(arena, uint(a)), ownPosCode(arena, uint(b)), ownPosCode(arena, uint(c)))
#else
#define OWN_POS3(arena, a, b, c) (float3(a, b, c) / 2048.0 - 8.0)
#endif

// the compact vertex (-Dmcopt.own.compact): position 3 x u16 ((p + 8) * 2048, section-relative), light 2 x u8, colour RGBA8,
// atlas UV 2 x u16 (u * 65536: exact for the game's power-of-two atlas)
struct CVtx {
	ushort px, py, pz;
	uchar lu, lv;
	uchar4 color;
	ushort u, v;
};

constant bool COMPACT [[function_constant(1)]];
constant bool TRANSLUCENT [[function_constant(4)]];
constant bool QUADS [[function_constant(8)]];  // the per-quad path: vertex_id is the arena vertex, the slot from quadOwner
// probe only: 0 vanilla's vertex stage, 1 without the lightmap fetch, 2 position only (no other varying computed)
constant int VS_MODE [[function_constant(7)]];
constant bool FAT [[function_constant(2)]];
constant bool CPU_CLIP [[function_constant(3)]];

static VOut ownVertex(uint vi, int3 origin, int uploadMs, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj,
	constant Globals &g, constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel);
static VOut ownShade(Vtx v, int3 origin, int uploadMs, constant DrawFrame &df, constant Projection &proj, constant Globals &g,
	constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel);

// -Dmcopt.own.mesh.qrec (OWN_QREC, prepended by OwnTerrain; OwnQrec): every solid / cutout quad id is a 40-byte record id, and
// its corner c is decoded into exactly the compact vertex it replaces. Words: 0 corner selections (bits 3c + axis: x / y / z takes
// the second code; bits 12 + 2c + k: u / v the second), bit 20 colour mode, bit 31 stub (word 1 then indexes the quad's 64-byte
// copy); 1-3 x, y, z codes (first | second << 16), 4-5 u, v codes, 6 tint (compact colour order), 7 grey byte per corner (mode 0:
// channel = grey * tint / 255 in integers, as ARGB.multiply, alpha the tint's; mode 1: rgb the tint's, alpha = grey), 8-9 light
// lu, lv bytes per corner (16 bits each). Translucent quads stay 64 bytes (own_vs' TRANSLUCENT fetch).
static Vtx ownQrec(device const void *arenaRaw, uint r, uint c) {
	device const uint2 *w = ((device const uint2 *) arenaRaw) + r * 5;
	uint2 w01 = w[0];
	Vtx v;
	if ((w01.x & 0x80000000u) != 0) {
		CVtx k = ((device const CVtx *) arenaRaw)[w01.y * 4 + c];
		v.pos = packed_float3(OWN_POS3(arenaRaw, k.px, k.py, k.pz));
		v.color = k.color;
		v.uv0 = packed_float2(float2(k.u, k.v) / 65536.0);
		v.uv2 = short2(k.lu, k.lv);
		return v;
	}
	uint2 w23 = w[1], w45 = w[2], w67 = w[3], w89 = w[4];
	uint sel = w01.x;
	uint ps = sel >> (3 * c), us = sel >> (12 + 2 * c);
	uint x = (w01.y >> ((ps & 1) * 16)) & 0xffff, y = (w23.x >> (((ps >> 1) & 1) * 16)) & 0xffff, z = (w23.y >> (((ps >> 2) & 1) * 16)) & 0xffff;
	uint u = (w45.x >> ((us & 1) * 16)) & 0xffff, vv = (w45.y >> (((us >> 1) & 1) * 16)) & 0xffff;
	uint tint = w67.x, grey = (w67.y >> (8 * c)) & 255;
	uint light = ((c < 2 ? w89.x : w89.y) >> ((c & 1) * 16)) & 0xffff;
	v.pos = packed_float3(OWN_POS3(arenaRaw, x, y, z));
	if ((sel & (1u << 20)) != 0) v.color = uchar4(uchar(tint), uchar(tint >> 8), uchar(tint >> 16), uchar(grey));
	else v.color = uchar4(uchar(grey * (tint & 255) / 255), uchar(grey * ((tint >> 8) & 255) / 255), uchar(grey * ((tint >> 16) & 255) / 255), uchar(tint >> 24));
	v.uv0 = packed_float2(float2(u, vv) / 65536.0);
	v.uv2 = short2(light & 255, light >> 8);
	return v;
}

// the position alone of record r's corner c (as ownQrec decodes it)
static float3 ownQrecPos(device const void *arenaRaw, uint r, uint c) {
	device const uint2 *w = ((device const uint2 *) arenaRaw) + r * 5;
	uint2 w01 = w[0];
	if ((w01.x & 0x80000000u) != 0) {
		CVtx k = ((device const CVtx *) arenaRaw)[w01.y * 4 + c];
		return OWN_POS3(arenaRaw, k.px, k.py, k.pz);
	}
	uint2 w23 = w[1];
	uint ps = w01.x >> (3 * c);
	uint x = (w01.y >> ((ps & 1) * 16)) & 0xffff, y = (w23.x >> (((ps >> 1) & 1) * 16)) & 0xffff, z = (w23.y >> (((ps >> 2) & 1) * 16)) & 0xffff;
	return OWN_POS3(arenaRaw, x, y, z);
}

#ifdef OWN_QREC
constant bool OWN_REC = true;
#else
constant bool OWN_REC = false;
#endif

vertex VOut own_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant DrawFrame &df [[buffer(20)]],
	device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]], device const Rec *recs [[buffer(18)]],
	device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]], constant Globals &g [[buffer(22)]],
	constant Terrain &t [[buffer(23)]], device const uint *qlist [[buffer(25)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]],
	device uint *oracleKept [[buffer(26), function_constant(F_ORACLE)]], constant uint &oracleFrame [[buffer(27), function_constant(F_ORACLE)]],
	constant Fog &fogV [[buffer(24), function_constant(F_FOGVERT_ANY)]], constant uint4 &grp [[buffer(29), function_constant(F_GROUP)]]
#ifdef OWN_VCOUNT
	, device atomic_uint *vcount [[buffer(28)]]
#endif
	) {
	VOut o;
	uint quadStart, meta, base;
#ifdef OWN_TPROBE
	uint tOff = 0;
#endif
	// the per-quad path's arena vertex: from the index buffer (vertex_id), or with F_NONIDX from its quad list, 6 a quad
	uint qv = QUADS && F_NONIDX ? qlist[vid / 6] * 4 + ((0x032210u >> ((vid % 6) * 4)) & 3) : vid;
	int3 origin;
	int uploadMs;
	uint unitRec = 0xffffffffu;
	// (F_QFLAT: the table entry, instances of 64 quads over the list's region: the draw's base vertex is 4 x the region's first entry;
	// 0xFFFFFFFF past the list's quads, a degenerate quad)
	// (F_QFLAT with listBase bit 31: the list's own class draw, the overflow fallback, through the same pipeline)
	bool qtab = F_QFLAT && (df.listBase >> 31) == 0;
	uint lb = df.listBase & 0x7FFFFFFFu;
	uint qslot = (vid >> 2) + iid * 64;
	uint qe = qtab ? lists[lb + (qslot >> A1_SH)] : 0u;
	uint gUnit = F_GROUP ? ((vid >> 2) * grp.y) >> 21 : 0u, gQuad = F_GROUP ? (vid >> 2) - gUnit * grp.x : 0u;
	if (F_ORACLE && !QUADS && !FAT && !qtab) unitRec = lists[lb + iid];
	if (QUADS) {
		Section s = sections[((device const uint *) lists)[qv >> 2]];  // (lists is quadOwner here: the slot of every arena quad)
		quadStart = 0;
		meta = 63;
		origin = int3(s.x, s.y, s.z);
		uploadMs = s.uploadMs;
		base = 0;
	} else if (F_TIGHT) {
		uint4 r4 = ((device const uint4 *) recs)[lists[df.listBase + iid] * 2];  // Rec: quadStart, slot, meta, plane | lo, hi, pad
		uint4 s4 = ((device const uint4 *) sections)[r4.y * 2];                 // Section: x, y, z, uploadMs | recStart, recCount, pad
		quadStart = r4.x;
		meta = r4.z;
		origin = int3(as_type<int>(s4.x), as_type<int>(s4.y), as_type<int>(s4.z));
		uploadMs = as_type<int>(s4.w);
		base = r4.w;
	} else if (FAT) {
		Inst e = ((device const Inst *) lists)[df.listBase + iid];
		quadStart = e.quadStart;
		meta = e.meta;
		origin = int3(e.x, e.y, e.z);
		uploadMs = e.uploadMs;
		base = e.pad0;
	} else {
#ifdef OWN_TFLAT
		// -Dmcopt.own.mesh.tflat: translucent draws one non-instanced indexed draw over a per-quad list, entry = unit record << 6 | quad
		uint le = F_GROUP ? lists[lb + (iid << grp.z) + gUnit] : qtab || TRANSLUCENT ? 0u : lists[lb + iid];
		if (F_GROUP && le == 0xFFFFFFFFu) {
			o.pos = float4(0, 0, -2, 1);  // (a sentinel past the list's units: outside the clip volume, as padding)
			o.sphericalVertexDistance = 0;
			putColor(o, float4(0));
			o.texCoord0 = float2(0);
			putRich(o, 0, 1);
			return o;
		}
		Rec rec = recs[qtab ? (qe == 0xFFFFFFFFu ? 0u : qe >> 6) : TRANSLUCENT ? lists[lb + (vid >> 2)] >> 6 : le];
#else
		uint le = F_GROUP ? lists[lb + (iid << grp.z) + gUnit] : qtab ? 0u : lists[lb + iid];
		if (F_GROUP && le == 0xFFFFFFFFu) {
			o.pos = float4(0, 0, -2, 1);  // (a sentinel past the list's units: outside the clip volume, as padding)
			o.sphericalVertexDistance = 0;
			putColor(o, float4(0));
			o.texCoord0 = float2(0);
			putRich(o, 0, 1);
			return o;
		}
		Rec rec = recs[qtab ? (qe == 0xFFFFFFFFu ? 0u : qe >> 6) : le];
#endif
		Section s = sections[rec.slot];
		quadStart = rec.quadStart;
		meta = rec.meta;
		origin = int3(s.x, s.y, s.z);
		uploadMs = s.uploadMs;
		base = uint(rec.plane);
#ifdef OWN_TPROBE
		tOff = rec.pad1;
#endif
	}
	// F_NONIDX: vertex_id runs 6 a quad in vanilla's index order {0, 1, 2, 2, 3, 0}
	uint quad = QUADS ? 0 : F_NONIDX ? vid / 6 : qtab ? (qe == 0xFFFFFFFFu ? 64u : (qe & 63) << A1_SH | (qslot & ((1u << A1_SH) - 1))) : F_GROUP ? gQuad : vid >> 2;
#ifdef OWN_TFLAT
	if (TRANSLUCENT && !FAT && !F_TIGHT) quad = lists[df.listBase + (vid >> 2)] & 63;
#endif
	uint corner = F_NONIDX ? (0x032210u >> ((vid % 6) * 4)) & 3 : vid & 3;
#ifdef OWN_VCOUNT
	{
		// (measurement, -Dmcopt.own.vcount) per path (8 counters: opaque instanced, translucent, per-quad, non-indexed): invocations,
		// corner-1 invocations (a quad's corner 1 is one index, so this counts issued quads), invocations past the unit's quads,
		// corner-1 invocations past them (padding quads), then corner 0, 2, 3 invocations
		uint k = (QUADS ? 16u : F_NONIDX ? 24u : TRANSLUCENT ? 8u : 0u);
		bool pad = !QUADS && quad > (meta & 63);
		atomic_fetch_add_explicit(&vcount[k], 1, memory_order_relaxed);
		if (corner == 1) atomic_fetch_add_explicit(&vcount[k + 1], 1, memory_order_relaxed);
		if (pad) atomic_fetch_add_explicit(&vcount[k + 2], 1, memory_order_relaxed);
		if (pad && corner == 1) atomic_fetch_add_explicit(&vcount[k + 3], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&vcount[k + 4 + (corner == 0 ? 0u : corner == 2 ? 1u : corner == 3 ? 2u : 3u)], 1, memory_order_relaxed);
	}
#endif
	if (quad > (meta & 63)) {
		o.pos = float4(0, 0, -2, 1);  // past the quads of this unit: outside the clip volume
		if (F_ORACLE) o.oracleUnit = unitRec;
		if (F_TIGHT || F_POSONLY) return o;  // (its other outputs are never read)
		o.sphericalVertexDistance = 0;
		putColor(o, float4(0));
		o.texCoord0 = float2(0);
		putRich(o, 0, 1);
		return o;
	}
	if (F_REVERSE) quad = (meta & 63) - quad;
	// (translucent: base + the order entry; meta bit 18, -Dmcopt.own.mesh.tsorted: the unit's quads are a copy in sorted order, read directly)
	uint vi = QUADS ? qv : TRANSLUCENT && (meta & (1u << 18)) == 0 ? (base + ((device const uint *) arenaRaw)[quadStart + quad]) * 4 + corner
		: (quadStart + quad) * 4 + corner;
#ifdef OWN_TPROBE
	// (probe 2: no order read: the unit's quads in mesh order from its offset in the mesh, record +28)
	if (OWN_TPROBE == 2 && TRANSLUCENT && !QUADS) vi = (base + tOff + quad) * 4 + corner;
#endif
	float3 rel;
	if (F_ORACLE) {
		VOut ov = ownVertex(vi, origin, uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		ov.oracleUnit = unitRec;
		if (quad == 0 && corner == 0 && unitRec != 0xffffffffu) oracleKept[unitRec] = oracleFrame;
		return ov;
	}
	VOut ov = ownVertex(vi, origin, uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
	if (F_FV) {
		// (F_FV: the fog value itself per vertex, in the spherical distance's place; with F_FOGVERT_ALL in rich pipelines also the
		// render-distance term's max, as the fragment stage would take it)
		float fv = linearFogV(ov.sphericalVertexDistance, fogV.FogEnvironmentalStart, fogV.FogEnvironmentalEnd);
		if (F_RICH) fv = max(fv, linearFogV(ov.cylindricalVertexDistance, fogV.FogRenderDistanceStart, fogV.FogRenderDistanceEnd));
		ov.sphericalVertexDistance = fv;
	}
	if (F_FVT) {
		// (the section's 16-block box, camera-relative: the cull's lean test, here on the whole box)
		// three exact classes (1 block margins): A wholly inside the render-distance fog start (that term 0, the environmental one
		// unclamped); B wholly inside the render-distance band with that term above the environmental one everywhere (both unclamped:
		// the max is the render-distance term, affine); C wholly beyond the render-distance end (fog 1 everywhere)
		float3 so = float3(origin - int3(g.CameraBlockPos)) + float3(g.CameraOffset);
		float3 m = max(abs(so), abs(so + 16.0));
		float3 n = select(min(abs(so), abs(so + 16.0)), float3(0), so < 0.0 && so + 16.0 > 0.0);
		float cylMax = max(length(m.xz), m.y), cylMin = max(length(n.xz), n.y), sphMax = length(m);
		float rs = fogV.FogRenderDistanceStart, re = fogV.FogRenderDistanceEnd, es = fogV.FogEnvironmentalStart, ee = fogV.FogEnvironmentalEnd;
		bool a = cylMax + 1.0 < rs && sphMax < ee && es <= 0.0;
		bool b = cylMin - 1.0 >= rs && cylMax + 1.0 <= re && es <= 0.0 && sphMax < ee && (cylMin - 1.0 - rs) / (re - rs) >= sphMax / ee;
		bool c = cylMin - 1.0 >= re;
		ov.fogIn = a || b || c ? 1 : 0;
		ov.fogF = max(linearFogV(ov.sphericalVertexDistance, es, ee), linearFogV(ov.cylindricalVertexDistance, rs, re));
	}
	return ov;
}

// one arena vertex as vanilla's terrain.vsh shades it (own_vs, own_mesh); rel: its section-relative position
static VOut ownVertex(uint vi, int3 origin, int uploadMs, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj,
	constant Globals &g, constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel) {
	Vtx v;
	if (OWN_REC && COMPACT && !TRANSLUCENT) {
		v = ownQrec(arenaRaw, vi >> 2, vi & 3);  // (solid / cutout: vi = record id * 4 + corner)
	} else if (COMPACT && F_TIGHT) {
		// CVtx as one uint4: px | py << 16, pz | lu << 16 | lv << 24, colour, u | v << 16
		uint4 w = ((device const uint4 *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, ushort(w.x), ushort(w.x >> 16), ushort(w.y)));
		v.color = as_type<uchar4>(w.z);
		v.uv0 = packed_float2(float2(ushort(w.w), ushort(w.w >> 16)) / 65536.0);
		v.uv2 = short2(uchar(w.y >> 16), uchar(w.y >> 24));
	} else if (COMPACT) {
		CVtx c = ((device const CVtx *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, c.px, c.py, c.pz));
		v.color = c.color;
		v.uv0 = packed_float2(float2(c.u, c.v) / 65536.0);
		v.uv2 = short2(c.lu, c.lv);
	} else {
		v = ((device const Vtx *) arenaRaw)[vi];
	}
	return ownShade(v, origin, uploadMs, df, proj, g, t, lightmap, lightSampler, rel);
}

// a vertex as vanilla's terrain.vsh shades it (ownVertex's fetched one, or own_vs' from a quad record)
static VOut ownShade(Vtx v, int3 origin, int uploadMs, constant DrawFrame &df, constant Projection &proj, constant Globals &g,
	constant Terrain &t, texture2d<float> lightmap, sampler lightSampler, thread float3 &rel) {
	VOut o;
	rel = float3(v.pos);
	int3 cbp = int3(g.CameraBlockPos);
	float3 pos = float3(v.pos) + float3(origin - cbp) + float3(g.CameraOffset);
	if (CPU_CLIP) o.pos = df.clip * float4(pos, 1.0);
	else o.pos = (proj.ProjMat * t.ModelViewMat) * float4(pos, 1.0);
	o.pos.y = -o.pos.y;  // the backend's row order (SPIRV-Cross flip_vert_y)
	if (F_POSONLY) return o;
#ifdef OWN_TPROBE
	// (-Dmcopt.own.tprobe, measurement only, translucent draw only: 1 position-only work, 4 constant colour / no lightmap / no fog
	// distance (uv kept), 5 no rich varyings computed)
	if (TRANSLUCENT && (OWN_TPROBE == 1 || OWN_TPROBE == 4)) {
		o.sphericalVertexDistance = 0;
		putColor(o, float4(0.6, 0.7, 1.0, 0.6));
		o.texCoord0 = OWN_TPROBE == 1 ? float2(0) : float2(v.uv0);
		putRich(o, 0, 1);
		return o;
	}
#endif
	if (VS_MODE == 2) {
		o.sphericalVertexDistance = 0;
		putColor(o, float4(1));
		o.texCoord0 = float2(0);
		putRich(o, 0, 1);
		return o;
	}
	o.sphericalVertexDistance = length(pos);
	float2 luv = clamp(float2(int2(v.uv2)) / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));
	putColor(o, VS_MODE == 1 ? float4(v.color) / 255.0 : float4(v.color) / 255.0 * lightmap.sample(lightSampler, luv, level(0.0)));
	o.texCoord0 = float2(v.uv0);
#ifdef OWN_TPROBE
	if (TRANSLUCENT && OWN_TPROBE == 5) {
		putRich(o, 0, 1);
		return o;
	}
#endif
	if (F_RICH) {
		int elapsed = df.nowMs - uploadMs;
		float visibility = elapsed >= df.fadeMs ? 1.0 : float(elapsed) / float(df.fadeMs);
		const float chunkFullyVisibleRange = 16.0;
		float dist = length(pos);
		putRich(o, max(length(pos.xz), abs(pos.y)), mix(1.0, visibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0)));
	}
	return o;
}

// ---- frag variants: mesh shading (probe option "mesh", -Dmcopt.own.frag.mesh) ----
// A mesh threadgroup per listed unit, a thread per quad: its four vertices as own_vs shades them, emitted only if the rasterizer
// could draw some of it: not wholly outside one side of the clip volume (|x|, |y| <= w; a half-space test, valid for any w) and, for a
// bucketed quad, not behind its own plane (the cull's facing test, per quad). The kept quads are packed, so neither the unit's
// padding nor the dropped quads cost vertex, primitive or tile work. Quads keep their order (vanilla's depth ties).
using OwnMeshT = metal::mesh<VOut, void, 128, 64, metal::topology::triangle>;

// F_OCC: the occlusion's parameters (mco_draw), the pyramid's fields as ownPyramid fills them
struct FragOcc {
	uint2 screen;         // pixels (the pass's depth)
	uint2 hiz0;           // the pyramid's mip 0 size (half the pass, padded to 32 with -Dmcopt.own.hizTile)
	uint mips;            // 0: no pyramid this frame (everything that passes the clip and facing tests counts as visible)
	uint stats;           // 1: add the emitted quads to occStats[slot]
	uint slot;            // phase * 2 + layer
	uint pad;
};

// a vertex's clip position exactly as ownVertex computes it (the same expression), without its other outputs
static float4 ownClip(uint vi, int3 origin, constant DrawFrame &df, device const void *arenaRaw, constant Projection &proj, constant Globals &g,
	constant Terrain &t, thread float3 &rel) {
	float3 p;
	if (OWN_REC && COMPACT) {
		p = ownQrecPos(arenaRaw, vi >> 2, vi & 3);
	} else if (COMPACT) {
		CVtx c = ((device const CVtx *) arenaRaw)[vi];
		p = OWN_POS3(arenaRaw, c.px, c.py, c.pz);
	} else {
		p = float3(((device const Vtx *) arenaRaw)[vi].pos);
	}
	rel = p;
	int3 cbp = int3(g.CameraBlockPos);
	float3 pos = p + float3(origin - cbp) + float3(g.CameraOffset);
	float4 o = CPU_CLIP ? df.clip * float4(pos, 1.0) : (proj.ProjMat * t.ModelViewMat) * float4(pos, 1.0);
	o.y = -o.y;
	return o;
}

// a quad (its corners' clip positions, y flipped as drawn) against the pyramid, as own_q_b tests it: false only if wholly behind it
static bool fragOccVisible(float4 p0, float4 p1, float4 p2, float4 p3, constant FragOcc &fo, texture2d<float, access::read> hiz) {
	if (fo.mips == 0) return true;
	float4 p[4] = {p0, p1, p2, p3};
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 4; c++) {
		if (p[c].w <= 0.05) return true;
		float3 n = p[c].xyz / p[c].w;
		n.y = -n.y;  // (rows as own_q_b counts them, from the unflipped clip)
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return true;  // (the clip test drops these first)
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(fo.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, fo.hiz0.x >> level), max(1u, fo.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
	return zmax >= far - 1e-6;
}

// a unit's box (camera-relative world space, whole blocks) against the pyramid, the same test on its 8 corners: false only if wholly
// behind it
static bool fragOccBox(float3 lo, float3 hi, constant DrawFrame &df, constant Projection &proj, constant Terrain &t, constant FragOcc &fo,
	texture2d<float, access::read> hiz) {
	if (fo.mips == 0) return true;
	float4x4 m = CPU_CLIP ? df.clip : proj.ProjMat * t.ModelViewMat;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint c = 0; c < 8; c++) {
		float4 h = m * float4(select(lo, hi, bool3((c & 1) != 0, (c & 2) != 0, (c & 4) != 0)), 1.0);
		if (h.w <= 0.05) return true;
		float3 n = h.xyz / h.w;
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	if (mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1) return false;  // wholly outside the screen: nothing of it can be visible
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(fo.screen), float2(0), float2(fo.screen) - 1);
	uint2 a = uint2(smin) / 2, b = uint2(smax) / 2;
	uint ext = max(b.x - a.x, b.y - a.y);
	uint level = ext == 0 ? 0 : min(fo.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, fo.hiz0.x >> level), max(1u, fo.hiz0.y >> level));
	uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
	float far = 1.0;
	for (uint y = la.y; y <= lb.y; y++)
		for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
	return zmax >= far - 1e-6;
}

[[mesh]] void own_mesh(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const Rec *recs [[buffer(18)]], device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]],
	constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]], device uint *masks [[buffer(26), function_constant(F_OCC)]],
	constant FragOcc &fo [[buffer(27), function_constant(F_OCC)]], device atomic_uint *occStats [[buffer(28), function_constant(F_OCC)]],
	texture2d<float, access::read> hiz [[texture(18), function_constant(F_OCCB)]]) {
	uint r = lists[df.listBase + tg];
	Rec rec = recs[r];
	Section s = sections[rec.slot];
	int3 origin = int3(s.x, s.y, s.z);
	bool keep = q <= (rec.meta & 63);
	VOut v[4];
	if (F_OCCB) {
		// the unit's box first (the record's whole-block bounds: no vertex fetch); a unit wholly behind the pyramid keeps no quad
		float3 o = float3(origin - int3(g.CameraBlockPos)) + float3(g.CameraOffset);
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		if (!fragOccBox(lo, hi, df, proj, t, fo, hiz)) {
			if (q == 0) {
				masks[r] = 0;
				out.set_primitive_count(0);
			}
			return;
		}
	}
	if (F_OCC) {
		// the tests on positions alone; the full vertices only for the quads emitted
		uint mask = masks[r];
		bool drawnA = ((mask >> q) & 1) != 0;
		if (F_OCCA) keep = keep && drawnA;
		bool vis = false;
		if (keep) {
			float3 rel[4];
			float4 p[4];
			for (uint c = 0; c < 4; c++) p[c] = ownClip((rec.quadStart + q) * 4 + c, origin, df, arenaRaw, proj, g, t, rel[c]);
			bool gone = (p[0].x > p[0].w && p[1].x > p[1].w && p[2].x > p[2].w && p[3].x > p[3].w)
				|| (p[0].x < -p[0].w && p[1].x < -p[1].w && p[2].x < -p[2].w && p[3].x < -p[3].w)
				|| (p[0].y > p[0].w && p[1].y > p[1].w && p[2].y > p[2].w && p[3].y > p[3].w)
				|| (p[0].y < -p[0].w && p[1].y < -p[1].w && p[2].y < -p[2].w && p[3].y < -p[3].w);
			uint bucket = (rec.meta >> 6) & 7;
			if (bucket < 6) {
				float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
				uint axis = bucket >> 1;
				float plane = rel[0][axis];
				gone = gone || ((bucket & 1) == 0 ? cam[axis] <= plane - FACE_MARGIN : cam[axis] >= plane + FACE_MARGIN);
			}
			vis = !gone && (F_OCCA || fragOccVisible(p[0], p[1], p[2], p[3], fo, hiz));
		}
		keep = F_OCCA ? vis : vis && !drawnA;
		if (F_OCCB) {
			uint now = uint(static_cast<simd_vote::vote_t>(simd_ballot(vis)));
			if (q == 0) masks[r] = now;
		}
		if (keep) {
			float3 rel;
			for (uint c = 0; c < 4; c++) v[c] = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		}
	} else if (keep) {
		float3 rel[4];
		for (uint c = 0; c < 4; c++) v[c] = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel[c]);
		float4 p0 = v[0].pos, p1 = v[1].pos, p2 = v[2].pos, p3 = v[3].pos;
		bool gone = (p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
			|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w);
		uint bucket = (rec.meta >> 6) & 7;
		if (bucket < 6) {
			float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
			uint axis = bucket >> 1;
			float plane = rel[0][axis];
			gone = gone || ((bucket & 1) == 0 ? cam[axis] <= plane - FACE_MARGIN : cam[axis] >= plane + FACE_MARGIN);
		}
		keep = !gone;
	}
	uint at = simd_prefix_exclusive_sum(keep ? 1u : 0u);
	uint kept = simd_sum(keep ? 1u : 0u);
	if (F_REVERSE) at = kept - 1 - at;
	if (q == 0) out.set_primitive_count(kept * 2);
	if (F_OCC && q == 0 && fo.stats != 0) atomic_fetch_add_explicit(&occStats[fo.slot], kept, memory_order_relaxed);
	if (keep) {
		for (uint c = 0; c < 4; c++) out.set_vertex(at * 4 + c, v[c]);
		out.set_index(at * 6 + 0, at * 4 + 0);
		out.set_index(at * 6 + 1, at * 4 + 1);
		out.set_index(at * 6 + 2, at * 4 + 2);
		out.set_index(at * 6 + 3, at * 4 + 2);
		out.set_index(at * 6 + 4, at * 4 + 3);
		out.set_index(at * 6 + 5, at * 4 + 0);
	}
}

// own_mesh4: the same with a thread per vertex (128 a unit, as many as own_vs runs, so latency hides as well); a quad's four corners
// sit in four neighbouring lanes of one SIMD group, its tests read them with shuffles, the packing counts quads per SIMD group.
[[mesh]] void own_mesh4(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint t [[thread_index_in_threadgroup]],
	uint lane [[thread_index_in_simdgroup]], uint sg [[simdgroup_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const Rec *recs [[buffer(18)]], device const uint *lists [[buffer(19)]], constant Projection &proj [[buffer(21)]],
	constant Globals &g [[buffer(22)]], constant Terrain &t2 [[buffer(23)]], texture2d<float> lightmap [[texture(17)]],
	sampler lightSampler [[sampler(15)]]) {
	threadgroup uint counts[4];
	Rec rec = recs[lists[df.listBase + tg]];
	Section s = sections[rec.slot];
	int3 origin = int3(s.x, s.y, s.z);
	uint q = t >> 2, c = t & 3;
	bool live = q <= (rec.meta & 63);
	VOut v;
	float3 rel = float3(0);
	if (live) v = ownVertex((rec.quadStart + q) * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t2, lightmap, lightSampler, rel);
	float4 p = live ? v.pos : float4(0, 0, 0, 1);
	ushort l0 = ushort(lane & ~3u);
	float4 p0 = simd_shuffle(p, l0), p1 = simd_shuffle(p, ushort(l0 + 1)), p2 = simd_shuffle(p, ushort(l0 + 2)), p3 = simd_shuffle(p, ushort(l0 + 3));
	float3 r0 = simd_shuffle(rel, l0);
	bool gone = (p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
		|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w);
	uint bucket = (rec.meta >> 6) & 7;
	if (bucket < 6) {
		float3 cam = float3(int3(g.CameraBlockPos) - origin) - float3(g.CameraOffset);
		uint axis = bucket >> 1;
		gone = gone || ((bucket & 1) == 0 ? cam[axis] <= r0[axis] - FACE_MARGIN : cam[axis] >= r0[axis] + FACE_MARGIN);
	}
	bool keep = live && !gone;
	uint vote = keep && c == 0 ? 1u : 0u;
	uint pre = simd_prefix_exclusive_sum(vote), tot = simd_sum(vote);
	if (lane == 0) counts[sg] = tot;
	threadgroup_barrier(mem_flags::mem_threadgroup);
	uint base = 0, kept = 0;
	for (uint i = 0; i < 4; i++) {
		base += i < sg ? counts[i] : 0;
		kept += counts[i];
	}
	uint at = base + simd_shuffle(pre, l0);
	if (F_REVERSE) at = kept - 1 - at;
	if (t == 0) out.set_primitive_count(kept * 2);
	if (keep) {
		out.set_vertex(at * 4 + c, v);
		if (c == 0) {
			out.set_index(at * 6 + 0, at * 4 + 0);
			out.set_index(at * 6 + 1, at * 4 + 1);
			out.set_index(at * 6 + 2, at * 4 + 2);
			out.set_index(at * 6 + 3, at * 4 + 2);
			out.set_index(at * 6 + 4, at * 4 + 3);
			out.set_index(at * 6 + 5, at * 4 + 0);
		}
	}
}

constant bool SAMPLE_LEVEL [[function_constant(6)]];

static float4 sampleNearestG(texture2d<float> source, sampler s, float2 uv, float2 pixelSize, float2 du, float2 dv, float2 texelScreenSize) {
	float2 uvTexelCoords = uv / pixelSize;
	float2 texelCenter = round(uvTexelCoords) - 0.5f;
	float2 texelOffset = uvTexelCoords - texelCenter;
	texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
	texelOffset = clamp(texelOffset, 0.0f, 1.0f);
	uv = (texelCenter + texelOffset) * pixelSize;
	if (SAMPLE_LEVEL) {
		// -Dmcopt.own.level / probe "level": the level of detail the gradients give, computed here (isotropic: the longer of the
		// two footprints in texels), sampled at that level: one plain sample instead of a gradient sample
		float2 size = float2(source.get_width(), source.get_height());
		float rho = max(length(du * size), length(dv * size));
		return source.sample(s, uv, level(max(0.0f, log2(rho))));
	}
	return source.sample(s, uv, gradient2d(du, dv));
}

static float4 sampleNearest(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv);
	float2 dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	return sampleNearestG(source, s, uv, pixelSize, du, dv, texelScreenSize);
}

#ifdef OWN_RGSS_VANILLA
#define RGSS_EARLY 0
#else
#define RGSS_EARLY 1
#endif
#ifdef OWN_RGSS_TRI
#define RGSS_TRI 1
#else
#define RGSS_TRI 0
#endif

static float4 sampleRGSS(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv);
	float2 dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
	float minPixelSize = min(pixelSize.x, pixelSize.y);
	float transitionStart = minPixelSize * 1.0;
	float transitionEnd = minPixelSize * 2.0;
	float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
	// the result below is mix(nearest, rgss, blendFactor): at 0 (a texel at least a pixel wide, most of a near view) that is the
	// nearest sample exactly, so the RGSS samples are skipped (-Dmcopt.own.rgssEarly=false: always taken, as vanilla)
	float4 nearestColor;
	if (RGSS_TRI) {
		// (at 1 the mix is the RGSS colour, up to a float rounding step: the nearest sample is skipped there too)
		if (blendFactor < 1.0f) nearestColor = sampleNearestG(source, s, uv, pixelSize, du, dv, texelScreenSize);
	} else {
		// (the nearest sample is taken first, once, so a SIMD group mixing near and far fragments doesn't pay for it twice)
		nearestColor = sampleNearestG(source, s, uv, pixelSize, du, dv, texelScreenSize);
	}
	if (RGSS_EARLY && blendFactor <= 0.0f) return nearestColor;
	float duLength = length(du);
	float dvLength = length(dv);
	float minDerivative = min(duLength, dvLength);
	float maxDerivative = max(duLength, dvLength);
	float effectiveDerivative = sqrt(minDerivative * maxDerivative);
	float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
	const float2 offsets[4] = {float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125)};
	float4 rgssColor;
	if (RGSS_TRI) {
		// -Dmcopt.own.rgssTri (opt-in): the atlas sampler's mip filter is linear, so one sample at the exact level blends the
		// two levels around it as the manual mix below does (up to the hardware's level-fraction precision): 4 samples, not 8
		rgssColor = float4(0.0);
		for (int i = 0; i < 4; ++i) rgssColor += source.sample(s, uv + offsets[i] * pixelSize, level(mipLevelExact));
		rgssColor *= 0.25;
		if (blendFactor >= 1.0f) return rgssColor;
	} else {
		float mipLevelLow = floor(mipLevelExact);
		float mipLevelHigh = mipLevelLow + 1.0;
		float mipBlend = fract(mipLevelExact);
		float4 rgssColorLow = float4(0.0);
		float4 rgssColorHigh = float4(0.0);
		for (int i = 0; i < 4; ++i) {
			float2 sampleUV = uv + offsets[i] * pixelSize;
			rgssColorLow += source.sample(s, sampleUV, level(mipLevelLow));
			rgssColorHigh += source.sample(s, sampleUV, level(mipLevelHigh));
		}
		rgssColorLow *= 0.25;
		rgssColorHigh *= 0.25;
		rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
	}
	return mix(nearestColor, rgssColor, blendFactor);
}

static float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
	if (vertexDistance <= fogStart) return 0.0;
	else if (vertexDistance >= fogEnd) return 1.0;
	return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

constant bool CUTOUT [[function_constant(0)]];
constant float CUTOUT_LIMIT [[function_constant(5)]];  // vanilla's ALPHA_CUTOUT: 0.5 cutout, 0.1 translucent

fragment float4 own_fs(VOut in [[stage_in]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], constant Fog &fog [[buffer(24)]],
	texture2d<float> atlas [[texture(16)]], sampler atlasSampler [[sampler(14)]]) {
#ifdef OWN_TPROBE
	// (probes 1 and 3: the translucent fragment stage returns a constant, blending kept; translucent = cutout at limit 0.1)
	if ((OWN_TPROBE == 1 || OWN_TPROBE == 3) && CUTOUT && CUTOUT_LIMIT < 0.2) return float4(0.2, 0.3, 0.8, 0.5);
	// (probe 6: one plain sample at the interpolated uv times the colour, no AA-nearest math, no fog; probe 7: the full sample and
	// colour, no fog and no fade mix)
	if (OWN_TPROBE == 6 && CUTOUT && CUTOUT_LIMIT < 0.2) return atlas.sample(atlasSampler, in.texCoord0) * getColor(in);
	if (OWN_TPROBE == 7 && CUTOUT && CUTOUT_LIMIT < 0.2)
		return sampleNearest(atlas, atlasSampler, in.texCoord0, 1.0f / float2(t.TextureSize)) * getColor(in);
#endif
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
	// F_LEAN: the chunk visibility is 1 (mix(x, y, 1) is y), the render-distance fog value 0 (max(e, 0) is e for e >= 0)
	if (F_RICH) color = mix(fog.FogColor * float4(1, 1, 1, color.a), color, in.chunkVisibility);
	if (CUTOUT && color.a < CUTOUT_LIMIT) discard_fragment();
	float fogValue;
	if (F_FVT && in.fogIn != 0) {
		fogValue = in.fogF;
	} else {
		fogValue = F_FV ? in.sphericalVertexDistance : linear_fog_value(in.sphericalVertexDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd);
		if (F_RICH && !F_FV) fogValue = max(fogValue, linear_fog_value(in.cylindricalVertexDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
	}
	return float4(mix(color.rgb, fog.FogColor.rgb, fogValue * fog.FogColor.a), color.a);
}

// The per-quad path's lists drawn by mesh shading (-Dmcopt.own.frag.qmesh, with -Dmcopt.own.quads): a threadgroup per 32 listed quads
// (list df.listBase: its count and start from qargs), a thread per quad: its owner section looked up once per quad (not per vertex),
// its four vertices as own_vs shades them, quads wholly outside one side of the clip volume dropped, the rest packed in list order.
[[mesh]] void own_mesh_q(OwnMeshT out, uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]],
	constant DrawFrame &df [[buffer(20)]], device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]],
	device const uint *qa [[buffer(18)]], device const uint *owner [[buffer(19)]], device const uint *qIdx [[buffer(25)]],
	constant Projection &proj [[buffer(21)]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]],
	texture2d<float> lightmap [[texture(17)]], sampler lightSampler [[sampler(15)]]) {
	uint list = df.listBase;
	uint n = qa[list * 5] / 6, start = qa[list * 5 + 2];
	uint k = tg * 32 + q;
	bool keep = k < n;
	VOut v[4];
	if (keep) {
		uint quad = qIdx[start + k * 6] >> 2;
		Section s = sections[owner[quad]];
		int3 origin = int3(s.x, s.y, s.z);
		float3 rel;
		for (uint c = 0; c < 4; c++) v[c] = ownVertex(quad * 4 + c, origin, s.uploadMs, df, arenaRaw, proj, g, t, lightmap, lightSampler, rel);
		float4 p0 = v[0].pos, p1 = v[1].pos, p2 = v[2].pos, p3 = v[3].pos;
		keep = !((p0.x > p0.w && p1.x > p1.w && p2.x > p2.w && p3.x > p3.w) || (p0.x < -p0.w && p1.x < -p1.w && p2.x < -p2.w && p3.x < -p3.w)
			|| (p0.y > p0.w && p1.y > p1.w && p2.y > p2.w && p3.y > p3.w) || (p0.y < -p0.w && p1.y < -p1.w && p2.y < -p2.w && p3.y < -p3.w));
	}
	uint at = simd_prefix_exclusive_sum(keep ? 1u : 0u);
	uint kept = simd_sum(keep ? 1u : 0u);
	if (q == 0) out.set_primitive_count(kept * 2);
	if (keep) {
		for (uint c = 0; c < 4; c++) out.set_vertex(at * 4 + c, v[c]);
		out.set_index(at * 6 + 0, at * 4 + 0);
		out.set_index(at * 6 + 1, at * 4 + 1);
		out.set_index(at * 6 + 2, at * 4 + 2);
		out.set_index(at * 6 + 3, at * 4 + 2);
		out.set_index(at * 6 + 4, at * 4 + 3);
		out.set_index(at * 6 + 5, at * 4 + 0);
	}
}

// probe only (frag option posonly): no varyings at all
// the visibility oracle's fragment stage (F_ORACLE; depth EQUAL against the final depth, tested before the shader runs, nothing
// written but the mark): the unit of a fragment that survives owns a visible pixel
[[early_fragment_tests]] fragment void own_fs_oracle(VOut in [[stage_in]], device uint *oracleVis [[buffer(26)]], constant uint &oracleFrame [[buffer(27)]]) {
	if (F_ORACLE && in.oracleUnit != 0xffffffffu) oracleVis[in.oracleUnit] = oracleFrame;
}

fragment float4 own_fs_pos(VOut in [[stage_in]]) {
	return float4(1);
}

// measurement only (-Dmcopt.own.frag.aKind=tex, fragment kind 8): own_fs's texture sample times the vertex colour, without the fade
// mix and the fog
fragment float4 own_fs_tex(VOut in [[stage_in]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]],
	texture2d<float> atlas [[texture(16)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	return (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
}

// measurement only (-Dmcopt.own.frag.aKind=nofade, fragment kind 9): own_fs without the fade mix (both fogs kept): the shader a
// fully faded-in unit outside the lean class needs (its chunk visibility is 1, so the mix is the identity there)
fragment float4 own_fs_nofade(VOut in [[stage_in]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], constant Fog &fog [[buffer(24)]],
	texture2d<float> atlas [[texture(16)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
	float fogValue = linear_fog_value(in.sphericalVertexDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd);
	if (F_RICH) fogValue = max(fogValue, linear_fog_value(in.cylindricalVertexDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
	return float4(mix(color.rgb, fog.FogColor.rgb, fogValue * fog.FogColor.a), color.a);
}

// measurement only (-Dmcopt.own.frag.aKind=nofog, fragment kind 10): own_fs without the fog (the fade mix kept)
fragment float4 own_fs_nofog(VOut in [[stage_in]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], constant Fog &fog [[buffer(24)]],
	texture2d<float> atlas [[texture(16)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float4 color = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize))
		* getColor(in);
	if (F_RICH) color = mix(fog.FogColor * float4(1, 1, 1, color.a), color, in.chunkVisibility);
	return color;
}

// probe only (-Dmcopt.own.probeModes=...flat): the same geometry with a trivial fragment stage, to split the terrain's cost
fragment float4 own_fs_flat(VOut in [[stage_in]]) {
	return getColor(in);
}

// the cutout depth pre-pass (-Dmcopt.own.prepass): the same alpha decision as own_fs's cutout (vanilla's colour.a after the
// fade mix, against 0.5), depth only
// (alpha only: the fade mix's alpha is mix(FogColor.a * a, a, visibility))
fragment void own_fs_alpha(VOut in [[stage_in]], constant Globals &g [[buffer(22)]], constant Terrain &t [[buffer(23)]], constant Fog &fog [[buffer(24)]],
	texture2d<float> atlas [[texture(16)]], sampler atlasSampler [[sampler(14)]]) {
	float2 pixelSize = 1.0f / float2(t.TextureSize);
	float a = (g.UseRgss == 1 ? sampleRGSS(atlas, atlasSampler, in.texCoord0, pixelSize) : sampleNearest(atlas, atlasSampler, in.texCoord0, pixelSize)).a
		* getColor(in).a;
	if (F_RICH) a = mix(fog.FogColor.a * a, a, in.chunkVisibility);
	if (a < CUTOUT_LIMIT) discard_fragment();
}

// ---- the per-quad path (-Dmcopt.own.quads): visibility per quad, index lists built on the GPU ----

// qargs (uints): four indexed draws of 5 (A solid, A cutout, B solid, B cutout), then [20..22] the quad threads' threadgroups,
// [23] spare. Each phase's indices are one region of qcap entries: solid from the front, cutout from the back.
struct QuadFrame {
	float4x4 clip;
	int4 camBlock;
	float4 camOffset;
	uint2 screen;
	uint2 hiz0;
	uint mips;
	uint qcap;            // index entries per phase region
	uint run;             // quads per unit
	uint compact;
	uint listCap;         // T capacity
	uint frame;           // frame counter (amortised re-tests)
	uint hizScale;        // pixels per mip-0 texel of the pyramid (a power of two, set where the pyramid is built)
	uint coarse;          // phase B: a unit whose box spans at most this many mip-0 texels decides for all its quads (0: off)
	uint unitStagger;     // phase B: the 1-in-4 re-test of phase-A quads picks whole units (a SIMD group skips together), not quads
	uint units;           // phase B in two passes (-Dmcopt.own.quads.units): a thread per unit first, then a thread per quad of the units left
	uint qlist;           // the lists hold a quad per entry, not its six indices (-Dmcopt.own.quads.list: drawn without an index buffer)
	uint box;             // phase B tests each quad's bounds (boxes, 8 bytes a quad) instead of its four arena vertices (-Dmcopt.own.quads.box)
	uint debug;           // measurement only (-Dmcopt.own.quads.debug): 4 = no 1-in-4 stagger (every quad re-tested every frame); own_q_b stops after 1 the record read, 2 the unit test, 3 the visible
	                      // bits (no appends); the frame's visible set and lists B are then wrong (picture not checked)
	uint flat;            // phase B's reads batched (own_q_bf, -Dmcopt.own.quads.flat; needs box)
	uint centre;          // phase B: a quad whose screen rectangle holds no pixel centre is not visible (it rasterizes no fragment;
	                      // -Dmcopt.own.quads.centre)
	uint qmesh;           // 1 (-Dmcopt.own.frag.qmesh): own_q_finish also writes each list's mesh dispatch at qargs [32 + list * 3]
	uint gate;            // own_q_bf reads only the texels a lane needs (-Dmcopt.own.quads.gate): the unit's on lanes 0-3, a quad's
	                      // only when it gets tested, and only the columns / rows its rectangle covers
	uint alu;             // own_q_bf with fewer instructions (-Dmcopt.own.quads.alu): shifts for the pyramid scale (a power of two) in
	                      // place of integer division, and a quad's box corners along an axis it is flat on skipped (duplicates)
	uint fatT;            // phase A and own_q_bf read the cull's TUnit entries (buffer 10) instead of index -> record -> section
	uint lazy;            // own_q_bf: a SIMD group with nothing to test this frame (every lane past the unit or keeping its bit) skips
	                      // the unit test, the projections and the reads (-Dmcopt.own.quads.lazy)
};

kernel void own_q_reset(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	device uint *a = qa + i * 5;
	a[0] = 0;
	a[1] = 1;
	a[2] = 0;
	a[3] = 0;
	a[4] = 0;
	if (i == 0) {
		qa[20] = 0;
		qa[21] = 1;
		qa[22] = 1;
		qa[24] = 0;
		qa[25] = 1;
		qa[26] = 1;
		qa[28] = 0;
		qa[29] = 0;
		qa[30] = 1;
		qa[31] = 1;
	}
}

// after the record cull: the quad threads' dispatch (T units x run quads)
kernel void own_q_size(constant QuadFrame &f [[buffer(0)]], device const uint *args [[buffer(3)]], device uint *qa [[buffer(11)]],
	uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	uint t = min(args[ARGS_T], f.listCap);
	qa[20] = (t * f.run + 63) / 64;  // phase B and the prefilter: a thread per quad
	qa[24] = (t + 63) / 64;          // phase A: a thread per unit
}

// Appends this thread's quad (want) to its layer's list of the phase, one atomic per SIMD group and layer: every thread of the
// group must call it (no early return before), so the group can sum its lanes' counts.
static void putQuads(device uint *idx, device atomic_uint *qa, uint phase, bool want, uint layer, uint quad, uint qcap, uint qlist) {
	for (uint l = 0; l < 2; l++) {
		uint mine = want && layer == l ? 6u : 0u;
		uint total = simd_sum(mine);
		if (total == 0) continue;
		uint before = simd_prefix_exclusive_sum(mine);
		uint base = 0;
		if (simd_is_first()) base = atomic_fetch_add_explicit(&qa[(phase * 2 + l) * 5], total, memory_order_relaxed);
		base = simd_broadcast_first(base);
		if (mine == 0) continue;
		uint at = base + before;
		if (at + 6 > qcap) continue;
		uint pos = l == 0 ? at : qcap - at - 6;
		if (qlist != 0) {
			// (counts stay in indices, six a quad, so the draws' arguments are the same; entry = index position / 6)
			idx[(phase * qcap + pos) / 6] = quad;
			continue;
		}
		device uint *o = idx + phase * qcap + pos;
		uint b = quad * 4;
		o[0] = b;
		o[1] = b + 1;
		o[2] = b + 2;
		o[3] = b + 2;
		o[4] = b + 3;
		o[5] = b;
	}
}

// phase A: the quads of every unit the cull passed that were visible last frame. A thread per unit: its quads' bits (one or two
// words of prevVis, consecutive quads), one reservation per SIMD group and layer, then the unit's visible quads written.
kernel void own_q_a(constant QuadFrame &f [[buffer(0)]], device const Rec *recs [[buffer(2)]], device const uint *args [[buffer(3)]],
	device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]], device uint *idx [[buffer(13)]],
	device atomic_uint *qa [[buffer(11)]], device const TUnit *tfat [[buffer(10)]], uint t [[thread_position_in_grid]]) {
	uint bits = 0, start = 0, layer = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		uint meta;
		if (f.fatT != 0) {
			TUnit u = tfat[t];
			start = u.quadStart;
			meta = u.meta;
		} else {
			Rec rec = recs[tested[t]];
			start = rec.quadStart;
			meta = rec.meta;
		}
		Rec rec;
		rec.meta = meta;
		layer = (rec.meta >> 9) & 1;
		uint count = (rec.meta & 63) + 1, w = start >> 5, sh = start & 31;
		ulong pair = ulong(prevVis[w]) | (sh + count > 32 ? ulong(prevVis[w + 1]) << 32 : 0ul);
		bits = uint(pair >> sh) & (count >= 32 ? 0xFFFFFFFFu : (1u << count) - 1u);
	}
	uint n = popcount(bits) * 6;
	for (uint l = 0; l < 2; l++) {
		uint mine = layer == l ? n : 0u;
		uint total = simd_sum(mine);
		if (total == 0) continue;
		uint before = simd_prefix_exclusive_sum(mine);
		uint base = 0;
		if (simd_is_first()) base = atomic_fetch_add_explicit(&qa[l * 5], total, memory_order_relaxed);
		base = simd_broadcast_first(base);
		if (mine == 0) continue;
		uint at = base + before;
		if (at + mine > f.qcap) continue;
		uint pos = l == 0 ? at : f.qcap - at - mine;
		if (f.qlist != 0) {
			device uint *e = idx + pos / 6;
			for (uint b = bits; b != 0; b &= b - 1) *e++ = start + ctz(b);
			continue;
		}
		device uint *o = idx + pos;
		for (uint b = bits; b != 0; b &= b - 1) {
			uint v = (start + ctz(b)) * 4;
			o[0] = v;
			o[1] = v + 1;
			o[2] = v + 2;
			o[3] = v + 2;
			o[4] = v + 3;
			o[5] = v;
			o += 6;
		}
	}
}

static float3 quadCorner(device const void *arena, uint compact, uint v) {
	if (OWN_REC) return ownQrecPos(arena, v >> 2, v & 3);  // (solid / cutout quad ids are record ids)
	if (compact != 0) {
		device const ushort *c = (device const ushort *) ((device const uchar *) arena + v * 16);
		return OWN_POS3(arena, c[0], c[1], c[2]);
	}
	device const float *p = (device const float *) ((device const uchar *) arena + v * 28);
	return float3(p[0], p[1], p[2]);
}

// Whether the screen rectangle of NDC corners mn..mx holds a pixel centre (k + 0.5 on both axes; the y flip maps centres
// onto centres). A quad without one rasterizes no fragment. A small margin keeps the answer on the safe side at edges.
static bool holdsCentre(constant QuadFrame &f, float2 mn, float2 mx) {
	float2 pmin = (mn * 0.5 + 0.5) * float2(f.screen), pmax = (mx * 0.5 + 0.5) * float2(f.screen);
	return all(floor(pmax - 0.5 + 1e-3) >= ceil(pmin - 0.5 - 1e-3));
}

// one quad against the pyramid: true unless its screen rectangle lies wholly behind the pyramid's depth there
static bool quadTest(constant QuadFrame &f, device const void *arena, device const uint2 *boxes, texture2d<float, access::read> hiz, float3 o,
	uint quad) {
	bool visible = false, behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	if (f.box != 0) {
		// the quad's bounds (quarter blocks from -8, never smaller than the quad): eight corners from one projected corner and
		// three projected edges (the projection is linear)
		uint2 b = boxes[quad];
		float3 lo = o + float3(b.x & 255, (b.x >> 8) & 255, (b.x >> 16) & 255) * 0.25 - 8.0;
		float3 ext = float3(b.y & 255, (b.y >> 8) & 255, (b.y >> 16) & 255) * 0.25 - 8.0 + o - lo;
		float4 h0 = f.clip * float4(lo, 1.0);
		float4 ex = f.clip[0] * ext.x, ey = f.clip[1] * ext.y, ez = f.clip[2] * ext.z;
		for (uint c = 0; c < 8; c++) {
			float4 h = h0 + ((c & 1) ? ex : 0.0) + ((c & 2) ? ey : 0.0) + ((c & 4) ? ez : 0.0);
			if (h.w <= 0.05) behind = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
	} else {
		for (uint c = 0; c < 4; c++) {
			float4 h = f.clip * float4(o + quadCorner(arena, f.compact, quad * 4 + c), 1.0);
			if (h.w <= 0.05) behind = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
	}
	if (behind) {
		visible = true;
	} else if (!(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
		float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
		uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
		uint ext = max(b.x - a.x, b.y - a.y);
		uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
		uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
		uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
		float far = 1.0;
		for (uint y = la.y; y <= lb.y; y++)
			for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
		visible = zmax >= far - 1e-6 && (f.centre == 0 || holdsCentre(f, mn, mx));
	}
	return visible;
}

// phase B: every unit's quads against the pyramid of phase A's depth; visible ones are this frame's visible set, and those not
// drawn in phase A are listed for phase B
kernel void own_q_b(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]],
	device const void *arena [[buffer(15)]], device const uint2 *boxes [[buffer(17)]], texture2d<float, access::read> hiz [[texture(0)]],
	uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false, vis = false;
	uint quad = 0, layer = 0;
	bool live = t < min(args[ARGS_T], f.listCap);
	Rec rec = recs[live ? tested[t] : 0];
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	if (f.debug == 1) {
		if (live && o.x == 12345.0) atomic_store_explicit(&curVis[0], rec.quadStart, memory_order_relaxed);  // (keeps the reads)
		return;
	}
	// the unit's box first, once per SIMD group (a group is one unit: run 32): lanes 0-7 project its corners
	bool unitHidden = false, unitDecides = false;
	if (f.run == 32) {
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		uint c = q & 7;
		float4 h = f.clip * float4((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z, 1.0);
		bool near = h.w <= 0.05;
		float3 n = h.xyz / max(h.w, 1e-6);
		bool anyNear = simd_any(near && q < 8);
		float2 mn = float2(simd_min(q < 8 ? n.x : 1e30), simd_min(q < 8 ? n.y : 1e30));
		float2 mx = float2(simd_max(q < 8 ? n.x : -1e30), simd_max(q < 8 ? n.y : -1e30));
		float zmax = simd_max(q < 8 ? n.z : 0.0);
		if (!anyNear && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
			float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
			// lanes read the up to four texels, the group takes their minimum
			uint2 tx = uint2(la.x + (q & 1), la.y + ((q >> 1) & 1));
			float v = q < 4 && tx.x <= lb.x && tx.y <= lb.y ? hiz.read(tx, level).x : 1.0;
			float far = simd_min(v);
			unitHidden = zmax < far - 1e-6;
			// small on screen: its quads would read the same texels; the unit's verdict stands for them (visible -> all visible)
			unitDecides = f.coarse != 0 && ext <= f.coarse;
		}
	}
	if (f.debug == 2) {
		if (live && unitHidden && o.x == 12345.0) atomic_store_explicit(&curVis[0], rec.quadStart, memory_order_relaxed);
		return;
	}
	if (live && !unitHidden) {
		quad = rec.quadStart + q;
		layer = (rec.meta >> 9) & 1;
		bool was = (prevVis[quad >> 5] & (1u << (quad & 31))) != 0;
		// drawn in phase A: keep its bit three frames in four without testing (drawing it again is never wrong), test on the fourth
		uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
		if (q <= (rec.meta & 63) && was && f.debug != 4 && ((stagger + f.frame) & 3) != 0) {
			vis = true;
		} else if (q <= (rec.meta & 63) && unitDecides) {
			vis = true;
			want = !was;
		} else if (q <= (rec.meta & 63)) {
			bool visible = quadTest(f, arena, boxes, hiz, o, quad);
			if (visible) {
				vis = true;
				want = !was;
			}
		}
	}
	// this frame's visibility bits, one atomic per SIMD group and word (a group's lanes are consecutive quads of one unit)
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);  // the lowest word still pending
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	if (f.debug == 3) {
		if (want && o.x == 12345.0) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

// phase B, flat (-Dmcopt.own.quads.flat, with -Dmcopt.own.quads.box): own_q_b's decisions with its memory reads issued together
// rather than one after another. The test is latency-bound (a SIMD group waits on tested -> record -> section -> unit texels ->
// prevVis / quad -> quad texels -> atomics in turn); here the quad's bits and bounds are read with the section, and the unit's
// and the quad's texels in one batch, every lane, before anything is decided.
static void texelRect(constant QuadFrame &f, float2 mn, float2 mx, thread uint &level, thread uint2 &la, thread uint2 &lb) {
	float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
	float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
	uint2 a, b;
	if (f.alu != 0) {
		uint sh = ctz(f.hizScale);
		a = uint2(smin) >> sh;
		b = uint2(smax) >> sh;
	} else {
		a = uint2(smin) / f.hizScale;
		b = uint2(smax) / f.hizScale;
	}
	uint ext = max(b.x - a.x, b.y - a.y);
	level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
	uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
	la = min(a >> level, lsize - 1);
	lb = min(b >> level, lsize - 1);
}

kernel void own_q_bf(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]],
	device const void *arena [[buffer(15)]], device const uint2 *boxes [[buffer(17)]], device const TUnit *tfat [[buffer(10)]],
	texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool live = t < min(args[ARGS_T], f.listCap);
	Rec rec;
	int3 origin;
	if (f.fatT != 0) {
		// (one read: the record's fields and the section's origin, written by the cull)
		TUnit u = tfat[live ? t : 0];
		rec.quadStart = u.quadStart;
		rec.meta = u.meta;
		rec.lo = u.lo;
		rec.hi = u.hi;
		origin = int3(u.x, u.y, u.z);
	} else {
		rec = recs[live ? tested[t] : 0];
	}
	uint quad = rec.quadStart + q;
	bool mine = live && q <= (rec.meta & 63);
	uint qi = mine ? quad : rec.quadStart;  // (lanes past the unit read its first quad: in bounds, unused)
	// these three don't depend on each other: one wait
	if (f.fatT == 0) {
		Section s = sections[rec.slot];
		origin = int3(s.x, s.y, s.z);
	}
	uint prev = prevVis[qi >> 5];
	uint2 b = boxes[qi];
	float3 o = float3(origin - f.camBlock.xyz) + f.camOffset.xyz;
	if (f.debug == 5) {  // (measurement: the loads only)
		if (live && o.x + float(prev) + float(b.x) == 12345.5) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	bool was = (prev & (1u << (qi & 31))) != 0;
	uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
	bool skip = was && f.debug != 4 && ((stagger + f.frame) & 3) != 0;
	bool vis = false, want = false;
	// (lazy: a group whose every lane is past the unit or keeps its bit untested this frame skips the tests: the bits stand)
	if (f.lazy != 0 && simd_all(!mine || skip)) {
		vis = mine;
	} else {
	// the unit's box: lanes 0-7 its corners, the group's min / max
	float3 ulo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
	float3 uhi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
	uint c8 = q & 7;
	float4 uh = f.clip * float4((c8 & 1) ? uhi.x : ulo.x, (c8 & 2) ? uhi.y : ulo.y, (c8 & 4) ? uhi.z : ulo.z, 1.0);
	float3 un = uh.xyz / max(uh.w, 1e-6);
	bool uNear = simd_any(uh.w <= 0.05 && q < 8);
	float2 umn = float2(simd_min(q < 8 ? un.x : 1e30), simd_min(q < 8 ? un.y : 1e30));
	float2 umx = float2(simd_max(q < 8 ? un.x : -1e30), simd_max(q < 8 ? un.y : -1e30));
	float uz = simd_max(q < 8 ? un.z : 0.0);
	bool uOn = !uNear && !(umx.x < -1 || umx.y < -1 || umn.x > 1 || umn.y > 1);
	uint ul;
	uint2 ula, ulb;
	texelRect(f, umn, umx, ul, ula, ulb);
	// the quad's bounds: eight corners from one projected corner and three edges
	float3 lo = o + float3(b.x & 255, (b.x >> 8) & 255, (b.x >> 16) & 255) * 0.25 - 8.0;
	float3 ext = float3(b.y & 255, (b.y >> 8) & 255, (b.y >> 16) & 255) * 0.25 - 8.0 + o - lo;
	float4 h0 = f.clip * float4(lo, 1.0);
	float4 ex = f.clip[0] * ext.x, ey = f.clip[1] * ext.y, ez = f.clip[2] * ext.z;
	bool behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	// (alu: an axis whose bounds are equal adds no new corner; a facing unit's quads share it, so the group branches as one)
	uint flatAxes = f.alu == 0 ? 0u : ((b.x & 255) == (b.y & 255) ? 1u : 0u) | (((b.x >> 8) & 255) == ((b.y >> 8) & 255) ? 2u : 0u)
		| (((b.x >> 16) & 255) == ((b.y >> 16) & 255) ? 4u : 0u);
	for (uint c = 0; c < 8; c++) {
		if ((c & flatAxes) != 0) continue;
		float4 h = h0 + ((c & 1) ? ex : 0.0) + ((c & 2) ? ey : 0.0) + ((c & 4) ? ez : 0.0);
		if (h.w <= 0.05) behind = true;
		float3 n = h.xyz / max(h.w, 1e-6);
		mn = min(mn, n.xy);
		mx = max(mx, n.xy);
		zmax = max(zmax, n.z);
	}
	bool qOn = !behind && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1);
	uint ql;
	uint2 qla, qlb;
	texelRect(f, mn, mx, ql, qla, qlb);
	if (f.debug == 6) {  // (measurement: loads + projections, no texel reads)
		if (live && float(qla.x + ula.y + ql + ul) + zmax + uz == 12345.5 && qOn && uOn) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	// all texels in one batch: the unit's (lane's share: lanes 0-3, one each) and the quad's 2 x 2 (clamped to its rectangle)
	uint2 ut = min(uint2(ula.x + (q & 1), ula.y + ((q >> 1) & 1)), ulb);
	float uv = 1.0, q00 = 1.0, q10 = 1.0, q01 = 1.0, q11 = 1.0;
	if (f.gate == 0) {
		uv = hiz.read(ut, ul).x;
		q00 = hiz.read(qla, ql).x;
		q10 = hiz.read(uint2(min(qla.x + 1, qlb.x), qla.y), ql).x;
		q01 = hiz.read(uint2(qla.x, min(qla.y + 1, qlb.y)), ql).x;
		q11 = hiz.read(min(qla + 1, qlb), ql).x;
	} else {
		// (the same texels' minimum: a column or row past the rectangle would only repeat one already read)
		if (q < 4 && uOn) uv = hiz.read(ut, ul).x;
		if (mine && !skip && qOn) {
			q00 = hiz.read(qla, ql).x;
			if (qlb.x > qla.x) q10 = hiz.read(uint2(qla.x + 1, qla.y), ql).x;
			if (qlb.y > qla.y) q01 = hiz.read(uint2(qla.x, qla.y + 1), ql).x;
			if (qlb.x > qla.x && qlb.y > qla.y) q11 = hiz.read(qla + 1, ql).x;
		}
	}
	float ufar = simd_min(q < 4 ? uv : 1.0);
	// (a rectangle wider than 2 x 2 texels only if its level was clamped at the top: never hidden then)
	bool unitHidden = uOn && all(ulb - ula <= 1) && uz < ufar - 1e-6;
	float qfar = min(min(q00, q10), min(q01, q11));
	bool quadVisible = !qOn ? behind : (any(qlb - qla > 1) || zmax >= qfar - 1e-6) && (f.centre == 0 || holdsCentre(f, mn, mx));
	if (mine && !unitHidden) {
		if (skip) {
			vis = true;
		} else if (quadVisible) {
			vis = true;
			want = !was;
		}
	}
	}
	uint layer = (rec.meta >> 9) & 1;
	if (f.debug == 7) {  // (measurement: everything but the visible bits and list appends)
		if (vis && want && o.x == 12345.5) atomic_store_explicit(&curVis[0], quad, memory_order_relaxed);
		return;
	}
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

// phase B in two passes (-Dmcopt.own.quads.units), first: a thread per unit the cull passed. Its box against the pyramid; a
// hidden unit is done (its quads stay out of this frame's visible set). With unitStagger, a unit whose quads were all drawn in
// phase A and isn't due its re-test keeps their bits here. The rest are listed for own_q_bq.
kernel void own_q_bu(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device const uint *prevVis [[buffer(12)]],
	device atomic_uint *qa [[buffer(11)]], device atomic_uint *curVis [[buffer(14)]], device uint *units [[buffer(16)]],
	texture2d<float, access::read> hiz [[texture(0)]], uint t [[thread_position_in_grid]]) {
	bool pass = false;
	uint r = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		r = tested[t];
		Rec rec = recs[r];
		Section s = sections[rec.slot];
		float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
		float3 lo = o + float3(int3(rec.lo & 255, (rec.lo >> 8) & 255, (rec.lo >> 16) & 255) - 16);
		float3 hi = o + float3(int3(rec.hi & 255, (rec.hi >> 8) & 255, (rec.hi >> 16) & 255) - 16);
		bool near = false;
		float2 mn = float2(1e30), mx = float2(-1e30);
		float zmax = 0;
		for (uint c = 0; c < 8; c++) {
			float4 h = f.clip * float4((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z, 1.0);
			if (h.w <= 0.05) near = true;
			float3 n = h.xyz / max(h.w, 1e-6);
			mn = min(mn, n.xy);
			mx = max(mx, n.xy);
			zmax = max(zmax, n.z);
		}
		bool hidden = false;
		if (!near && !(mx.x < -1 || mx.y < -1 || mn.x > 1 || mn.y > 1)) {
			float2 smin = clamp((mn * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			float2 smax = clamp((mx * 0.5 + 0.5) * float2(f.screen), float2(0), float2(f.screen) - 1);
			uint2 a = uint2(smin) / f.hizScale, b = uint2(smax) / f.hizScale;
			uint ext = max(b.x - a.x, b.y - a.y);
			uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
			uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
			uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
			// the same up to four texels the one-pass kernel's lanes read
			float far = 1.0;
			for (uint y = la.y; y <= min(lb.y, la.y + 1); y++)
				for (uint x = la.x; x <= min(lb.x, la.x + 1); x++) far = min(far, hiz.read(uint2(x, y), level).x);
			hidden = zmax < far - 1e-6;
		}
		if (!hidden) {
			uint start = rec.quadStart, count = (rec.meta & 63) + 1, w = start >> 5, sh = start & 31;
			uint mask = count >= 32 ? 0xFFFFFFFFu : (1u << count) - 1u;
			ulong pair = ulong(prevVis[w]) | (sh + count > 32 ? ulong(prevVis[w + 1]) << 32 : 0ul);
			if (f.unitStagger != 0 && (uint(pair >> sh) & mask) == mask && (((start >> 5) + f.frame) & 3) != 0) {
				ulong m = ulong(mask) << sh;
				atomic_fetch_or_explicit(&curVis[w], uint(m), memory_order_relaxed);
				if (uint(m >> 32) != 0) atomic_fetch_or_explicit(&curVis[w + 1], uint(m >> 32), memory_order_relaxed);
			} else {
				pass = true;
			}
		}
	}
	cullAppend(&qa[28], units, f.listCap, pass, r);
}

// the second pass's size: a thread per quad of the listed units
kernel void own_q_size2(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	qa[29] = (min(qa[28], f.listCap) * f.run + 63) / 64;
}

// phase B in two passes, second: a thread per quad of the units own_q_bu listed (the per-quad part of own_q_b)
kernel void own_q_bq(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *prevVis [[buffer(12)]], device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]],
	device atomic_uint *curVis [[buffer(14)]], device const void *arena [[buffer(15)]], device const uint *units [[buffer(16)]],
	device const uint2 *boxes [[buffer(17)]], texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false, vis = false;
	uint quad = 0, layer = 0;
	if (t < min(atomic_load_explicit(&qa[28], memory_order_relaxed), f.listCap)) {
		Rec rec = recs[units[t]];
		if (q <= (rec.meta & 63)) {
			Section s = sections[rec.slot];
			float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
			quad = rec.quadStart + q;
			layer = (rec.meta >> 9) & 1;
			bool was = (prevVis[quad >> 5] & (1u << (quad & 31))) != 0;
			uint stagger = f.unitStagger != 0 ? rec.quadStart >> 5 : quad;
			if (was && f.debug != 4 && ((stagger + f.frame) & 3) != 0) {
				vis = true;
			} else if (quadTest(f, arena, boxes, hiz, o, quad)) {
				vis = true;
				want = !was;
			}
		}
	}
	uint word = quad >> 5, bit = vis ? 1u << (quad & 31) : 0u;
	bool pending = vis;
	for (uint i = 0; i < 32 && simd_any(pending); i++) {
		uint lead = simd_min(pending ? word : 0xFFFFFFFFu);
		uint bits = simd_or(pending && word == lead ? bit : 0u);
		if (simd_is_first() && bits != 0) atomic_fetch_or_explicit(&curVis[lead], bits, memory_order_relaxed);
		if (word == lead) pending = false;
	}
	putQuads(idx, qa, 1, want, layer, quad, f.qcap, f.qlist);
}

// the four draws' final arguments: index counts clamped to the regions, cutout's start at the back of its region
kernel void own_q_finish(constant QuadFrame &f [[buffer(0)]], device uint *qa [[buffer(11)]], uint i [[thread_position_in_grid]]) {
	if (i >= 4) return;
	uint phase = i / 2, layer = i % 2;
	device uint *a = qa + i * 5;
	uint n = min(a[0], f.qcap / 6 * 6);
	a[0] = n;
	a[2] = phase * f.qcap + (layer == 0 ? 0 : f.qcap - n);
	if (f.qmesh != 0) {
		// (own_mesh_q: 32 quads a threadgroup)
		device uint *m = qa + 32 + i * 3;
		m[0] = (n / 6 + 31) / 32;
		m[1] = 1;
		m[2] = 1;
	}
}

kernel void own_clear_words(device uint *w [[buffer(14)]], constant uint &words [[buffer(7)]], uint i [[thread_position_in_grid]]) {
	if (i < words) w[i] = 0;
}

// The pyramid's first five levels in one pass (-Dmcopt.own.hizTile): a 16 x 16 threadgroup reduces a 32 x 32 block of depth
// pixels to its mip 0 (16 x 16) .. mip 4 (1 x 1) texels in threadgroup memory. The pyramid is padded to a multiple of 32 texels
// at mip 0; texels past the depth's edge hold 1.0 (nearest: they never lower a minimum, and only real pixels count).
kernel void own_hiz_tile(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> m0 [[texture(1)]],
	texture2d<float, access::write> m1 [[texture(2)]], texture2d<float, access::write> m2 [[texture(3)]], texture2d<float, access::write> m3 [[texture(4)]],
	texture2d<float, access::write> m4 [[texture(5)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;  // this thread's mip 0 texel
	float m = 1.0;
	for (uint y = p.y * 2; y < min(p.y * 2 + 2, dh); y++)
		for (uint x = p.x * 2; x < min(p.x * 2 + 2, dw); x++) m = min(m, depth.read(uint2(x, y)));
	m0.write(float4(m), p);
	t[lid.y][lid.x] = m;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			uint2 q = gid * n + lid;
			if (level == 1) m1.write(float4(v), q);
			else if (level == 2) m2.write(float4(v), q);
			else if (level == 3) m3.write(float4(v), q);
			else m4.write(float4(v), q);
		}
	}
}

// own_hiz_tile with mip 0 gathered (-Dmcopt.own.hizGather): one texture fetch per 2 x 2 pixels instead of four reads
kernel void own_hiz_tile_g(depth2d<float> depth [[texture(0)]], texture2d<float, access::write> m0 [[texture(1)]],
	texture2d<float, access::write> m1 [[texture(2)]], texture2d<float, access::write> m2 [[texture(3)]], texture2d<float, access::write> m3 [[texture(4)]],
	texture2d<float, access::write> m4 [[texture(5)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;  // this thread's mip 0 texel
	// the 2 x 2 pixels in one gather at their shared corner (clamped at the edges: an edge pixel repeated, so the same minimum);
	// a block wholly past the depth's edge stays 1.0 like own_hiz_tile's
	constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
	float m = 1.0;
	if (p.x * 2 < dw && p.y * 2 < dh) {
		float4 g = depth.gather(nearestClamp, (float2(p * 2) + 1.0) / float2(dw, dh));
		m = min(min(g.x, g.y), min(g.z, g.w));
	}
	m0.write(float4(m), p);
	t[lid.y][lid.x] = m;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			uint2 q = gid * n + lid;
			if (level == 1) m1.write(float4(v), q);
			else if (level == 2) m2.write(float4(v), q);
			else if (level == 3) m3.write(float4(v), q);
			else m4.write(float4(v), q);
		}
	}
}

// own_hiz_tile_g starting k levels up (-Dmcopt.own.frag.pyrSkip / pyrAuto, uocc only): each thread the farthest depth of a
// (2 << k)^2 pixel block, (1 << k)^2 gathers, written as level k; the group's levels k + 1 .. k + 4 as own_hiz_tile_g. The pyramid is
// padded to a multiple of 32 << k texels at mip 0, so these levels halve exactly; levels below k are not written (the tests read
// level k or coarser). Blocks past the depth's edge stay 1.0, as there.
kernel void own_hiz_tile_gs(depth2d<float> depth [[texture(0)]], array<texture2d<float, access::write>, 5> m [[texture(1)]],
	constant uint &k [[buffer(0)]], uint2 lid [[thread_position_in_threadgroup]], uint2 gid [[threadgroup_position_in_grid]]) {
	threadgroup float t[16][16];
	uint dw = depth.get_width(), dh = depth.get_height();
	uint2 p = gid * 16 + lid;  // this thread's level-k texel
	uint s = 2u << k;          // pixels a level-k texel covers (each axis)
	constexpr sampler nearestClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
	float v0 = 1.0;
	for (uint gy = 0; gy < s; gy += 2) {
		for (uint gx = 0; gx < s; gx += 2) {
			uint2 q = p * s + uint2(gx, gy);
			if (q.x >= dw || q.y >= dh) continue;  // (wholly past the edge: no pixel there; a pair straddling it repeats the edge pixel)
			float4 g = depth.gather(nearestClamp, (float2(q) + 1.0) / float2(dw, dh));
			v0 = min(v0, min(min(g.x, g.y), min(g.z, g.w)));
		}
	}
	m[0].write(float4(v0), p);
	t[lid.y][lid.x] = v0;
	for (uint level = 1, n = 8; level <= 4; level++, n >>= 1) {
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < n && lid.y < n) v = min(min(t[lid.y * 2][lid.x * 2], t[lid.y * 2][lid.x * 2 + 1]), min(t[lid.y * 2 + 1][lid.x * 2], t[lid.y * 2 + 1][lid.x * 2 + 1]));
		threadgroup_barrier(mem_flags::mem_threadgroup);
		if (lid.x < n && lid.y < n) {
			t[lid.y][lid.x] = v;
			m[level].write(float4(v), gid * n + lid);
		}
	}
}

// further levels of the padded pyramid: each texel the smallest of the 2 x 2 it covers (sizes halve exactly down to 1)
// The pyramid's top levels in one threadgroup of 32 x 32 threads (-Dmcopt.own.hizTop): out[0] from prev, each further level from the
// one before it in threadgroup memory; out[0] is at most 32 x 32 texels. Where a level's size is odd, its last texel also takes the
// level below's last row / column (3 wide), so no texel goes uncovered (as own_hiz_pad and own_hizn).
static float hizCell(uint2 p, uint w, uint h, uint pw, uint ph, thread uint &x1, thread uint &y1) {
	x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw);
	y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	return 1.0;
}

kernel void own_hiz_top(texture2d<float, access::read> prev [[texture(0)]], array<texture2d<float, access::write>, 11> out [[texture(1)]],
	constant uint &levels [[buffer(0)]], uint2 lid [[thread_position_in_threadgroup]]) {
	threadgroup float t[32][32];
	uint pw = prev.get_width(), ph = prev.get_height();
	uint w = out[0].get_width(), h = out[0].get_height();
	float m = 1.0;
	if (lid.x < w && lid.y < h) {
		uint x1, y1;
		hizCell(lid, w, h, pw, ph, x1, y1);
		for (uint y = lid.y * 2; y < y1; y++)
			for (uint x = lid.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
		out[0].write(float4(m), lid);
	}
	t[lid.y][lid.x] = m;
	for (uint k = 1; k < levels; k++) {
		pw = w;
		ph = h;
		w = out[k].get_width();
		h = out[k].get_height();
		threadgroup_barrier(mem_flags::mem_threadgroup);
		float v = 1.0;
		if (lid.x < w && lid.y < h) {
			uint x1, y1;
			hizCell(lid, w, h, pw, ph, x1, y1);
			for (uint y = lid.y * 2; y < y1; y++)
				for (uint x = lid.x * 2; x < x1; x++) v = min(v, t[y][x]);
		}
		threadgroup_barrier(mem_flags::mem_threadgroup);
		t[lid.y][lid.x] = v;
		if (lid.x < w && lid.y < h) out[k].write(float4(v), lid);
	}
}

kernel void own_hiz_pad(texture2d<float, access::read> prev [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 p [[thread_position_in_grid]]) {
	uint w = out.get_width(), h = out.get_height();
	if (p.x >= w || p.y >= h) return;
	uint pw = prev.get_width(), ph = prev.get_height();
	// the last texel of a row / column also takes the level below's last one where that level is odd (3 wide): otherwise it would
	// be in no texel of this level, and a test reading this level there would see its neighbour's depth instead
	uint x1 = p.x == w - 1 ? pw : min(p.x * 2 + 2, pw), y1 = p.y == h - 1 ? ph : min(p.y * 2 + 2, ph);
	float m = 1.0;
	for (uint y = p.y * 2; y < y1; y++)
		for (uint x = p.x * 2; x < x1; x++) m = min(m, prev.read(uint2(x, y)).x);
	out.write(float4(m), p);
}

// faces (soFace) of this frame: projected corners in occluder texels, depth, winding; facesHead: {count (= threadgroups), 1, 1}
struct SoFace {
	float2 q[4];
	float depth;
	float sgn;
	float pad0, pad1;
};

constant uint SO_MAX_FACES = 16384;

kernel void own_so_clear(device uint *occ [[buffer(13)]], constant SolidOcc &p [[buffer(14)]], device uint *head [[buffer(16)]],
	uint i [[thread_position_in_grid]]) {
	if (i < p.size.x * p.size.y) occ[i] = 0;
	if (i == 0) {
		head[0] = 0;
		head[1] = 1;
		head[2] = 1;
	}
}

// one face (4 camera-relative corners, a convex quad) into the face list, if it lies wholly in front of the camera
static void soFace(constant SolidOcc &p, device atomic_uint *head, device SoFace *faces, float3 c0, float3 c1, float3 c2, float3 c3) {
	float3 cs[4] = {c0, c1, c2, c3};
	SoFace f;
	float depth = 1.0;
	float2 sz = float2(p.size);
	for (int i = 0; i < 4; i++) {
		float4 h = p.clip * float4(cs[i], 1.0);
		if (h.w <= 0.05) return;
		float3 n = h.xyz / h.w;
		f.q[i] = (n.xy * 0.5 + 0.5) * sz;
		depth = min(depth, n.z);
	}
	if (depth <= 0) return;
	float area = 0;
	for (int i = 0; i < 4; i++) area += f.q[i].x * f.q[(i + 1) & 3].y - f.q[(i + 1) & 3].x * f.q[i].y;
	if (fabs(area) < 2.0) return;  // under a couple of texels: nothing it could wholly cover
	float2 mn = min(min(f.q[0], f.q[1]), min(f.q[2], f.q[3])), mx = max(max(f.q[0], f.q[1]), max(f.q[2], f.q[3]));
	if (mx.x < 0 || mx.y < 0 || mn.x > sz.x || mn.y > sz.y) return;
	f.depth = depth;
	f.sgn = area > 0 ? 1.0 : -1.0;
	f.pad0 = 0;
	f.pad1 = 0;
	uint at = atomic_fetch_add_explicit(&head[0], 1, memory_order_relaxed);
	if (at < SO_MAX_FACES) faces[at] = f;
}

// a thread per chunk column within the render distance: every run of opaque sections as one box, its faces toward the camera
kernel void own_so_raster(constant SolidOcc &p [[buffer(14)]], device const SolidCol *cols [[buffer(15)]], device atomic_uint *head [[buffer(16)]],
	device SoFace *faces [[buffer(17)]], uint2 tid [[thread_position_in_grid]]) {
	int span = p.rd * 2 + 1;
	if (int(tid.x) >= span || int(tid.y) >= span) return;
	int cx = p.camSection.x - p.rd + int(tid.x), cz = p.camSection.z - p.rd + int(tid.y);
	SolidCol col = cols[(cx & (p.grid - 1)) + (cz & (p.grid - 1)) * p.grid];
	if (col.x != cx || col.z != cz) return;
	ulong mask = ulong(col.lo) | ulong(col.hi) << 32;
	if (mask == 0) return;
	int ylo = max(0, p.camSection.y - p.rd - p.minY), yhi = min(p.levels - 1, p.camSection.y + p.rd - p.minY);
	int i = ylo;
	while (i <= yhi) {
		if (((mask >> i) & 1) == 0) {
			i++;
			continue;
		}
		int j = i;
		while (j + 1 <= yhi && ((mask >> (j + 1)) & 1) != 0) j++;
		float3 lo = float3(int3(cx * 16, (p.minY + i) * 16, cz * 16) - p.camBlock.xyz) + p.camOffset.xyz;
		float3 hi = lo + float3(16, (j - i + 1) * 16, 16);
		// the faces the camera (at the origin) can see
		if (0 < lo.x) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(lo.x, hi.y, lo.z), float3(lo.x, hi.y, hi.z), float3(lo.x, lo.y, hi.z));
		if (0 > hi.x) soFace(p, head, faces, float3(hi.x, lo.y, lo.z), float3(hi.x, hi.y, lo.z), float3(hi.x, hi.y, hi.z), float3(hi.x, lo.y, hi.z));
		if (0 < lo.y) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(hi.x, lo.y, lo.z), float3(hi.x, lo.y, hi.z), float3(lo.x, lo.y, hi.z));
		if (0 > hi.y) soFace(p, head, faces, float3(lo.x, hi.y, lo.z), float3(hi.x, hi.y, lo.z), float3(hi.x, hi.y, hi.z), float3(lo.x, hi.y, hi.z));
		if (0 < lo.z) soFace(p, head, faces, float3(lo.x, lo.y, lo.z), float3(hi.x, lo.y, lo.z), float3(hi.x, hi.y, lo.z), float3(lo.x, hi.y, lo.z));
		if (0 > hi.z) soFace(p, head, faces, float3(lo.x, lo.y, hi.z), float3(hi.x, lo.y, hi.z), float3(hi.x, hi.y, hi.z), float3(lo.x, hi.y, hi.z));
		i = j + 1;
	}
}

// a threadgroup per face, its rows spread over the threads: on each row, the texels whose four corners lie inside every edge
// (an interval per edge, intersected) take the face's depth by atomic max
kernel void own_so_fill(constant SolidOcc &p [[buffer(14)]], device const uint *head [[buffer(16)]], device const SoFace *faces [[buffer(17)]],
	device atomic_uint *occ [[buffer(13)]], uint fi [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]],
	uint lanes [[threads_per_threadgroup]]) {
	if (fi >= min(head[0], SO_MAX_FACES)) return;
	SoFace f = faces[fi];
	float2 mn = min(min(f.q[0], f.q[1]), min(f.q[2], f.q[3])), mx = max(max(f.q[0], f.q[1]), max(f.q[2], f.q[3]));
	int y0 = max(0, int(floor(mn.y))), y1 = min(int(p.size.y) - 1, int(ceil(mx.y)) - 1);
	uint bits = as_type<uint>(f.depth);
	for (int y = y0 + int(lid); y <= y1; y += int(lanes)) {
		float L = -1e30, R = 1e30;
		bool empty = false;
		for (int e = 0; e < 4 && !empty; e++) {
			float2 a = f.q[e], d = f.q[(e + 1) & 3] - a;
			float k = f.sgn * d.y;
			for (int r = 0; r < 2; r++) {
				float py = float(y + r);
				float c = f.sgn * d.x * (py - a.y);  // inside: c - k (px - a.x) >= 0
				if (fabs(k) < 1e-6) {
					if (c < 0) empty = true;
				} else if (k > 0) {
					R = min(R, a.x + c / k);
				} else {
					L = max(L, a.x + c / k);
				}
			}
		}
		if (empty) continue;
		int x0 = max(0, int(ceil(L))), x1 = min(int(p.size.x) - 1, int(floor(R)) - 1);
		for (int x = x0; x <= x1; x++) atomic_fetch_max_explicit(&occ[y * int(p.size.x) + x], bits, memory_order_relaxed);
	}
}

// the occluder depth into the pyramid's mip 0 (as floats)
kernel void own_so_mip0(device const uint *occ [[buffer(13)]], constant SolidOcc &p [[buffer(14)]], texture2d<float, access::write> out [[texture(1)]],
	uint2 q [[thread_position_in_grid]]) {
	if (q.x >= p.size.x || q.y >= p.size.y) return;
	out.write(float4(as_type<float>(occ[q.y * p.size.x + q.x])), q);
}

// ---- the drawn-quad tally (-Dmcopt.own.qstats): why each quad of this frame's opaque lists was drawn ----
// stats (uints): [0] units, [1] quads, [2] back-facing, [3] outside the frustum, [4] covering no pixel centre, [5] hidden behind
// the frame's final opaque depth (conservative pyramid test), [6] visible; [7] vertex invocations (units x run x 4).
// Categories are exclusive, tested in that order.
kernel void own_qstat(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *lists [[buffer(4)]], device const void *arena [[buffer(15)]],
	device atomic_uint *stats [[buffer(18)]], texture2d<float, access::read> hiz [[texture(0)]], uint tid [[thread_position_in_grid]]) {
	uint entry = tid / f.run, q = tid % f.run;
	uint nSolid = min(args[1], f.listCap), nCut = min(args[6], f.listCap);
	uint li;
	if (entry < nSolid) li = entry;
	else if (entry < nSolid + nCut) li = f.listCap + (entry - nSolid);
	else return;
	Rec rec = recs[lists[li]];
	if (q == 0) {
		atomic_fetch_add_explicit(&stats[0], 1, memory_order_relaxed);
		atomic_fetch_add_explicit(&stats[7], f.run * 4, memory_order_relaxed);
	}
	if (q > (rec.meta & 63)) return;
	atomic_fetch_add_explicit(&stats[1], 1, memory_order_relaxed);
	uint quad = rec.quadStart + q;
	Section s = sections[rec.slot];
	float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
	float3 c[4];
	for (uint i = 0; i < 4; i++) c[i] = o + quadCorner(arena, f.compact, quad * 4 + i);
	// back-facing: the first triangle's winding normal against the eye (at the origin)
	float3 n = cross(c[1] - c[0], c[2] - c[0]);
	if (dot(n, -c[0]) <= 0) {
		atomic_fetch_add_explicit(&stats[2], 1, memory_order_relaxed);
		return;
	}
	float4 h[4];
	for (uint i = 0; i < 4; i++) h[i] = f.clip * float4(c[i], 1.0);
	// outside one clip plane with all four corners (x, y; z near: w)
	bool outside = false;
	for (int pl = 0; pl < 5 && !outside; pl++) {
		bool all = true;
		for (uint i = 0; i < 4; i++) {
			float v = pl == 0 ? h[i].w + h[i].x : pl == 1 ? h[i].w - h[i].x : pl == 2 ? h[i].w + h[i].y : pl == 3 ? h[i].w - h[i].y : h[i].w - 0.05;
			if (v >= 0) all = false;
		}
		outside = all;
	}
	if (outside) {
		atomic_fetch_add_explicit(&stats[3], 1, memory_order_relaxed);
		return;
	}
	bool behind = false;
	float2 mn = float2(1e30), mx = float2(-1e30);
	float zmax = 0;
	for (uint i = 0; i < 4; i++) {
		if (h[i].w <= 0.05) behind = true;
		float3 d = h[i].xyz / max(h[i].w, 1e-6);
		mn = min(mn, d.xy);
		mx = max(mx, d.xy);
		zmax = max(zmax, d.z);
	}
	float2 sz = float2(f.screen);
	if (!behind) {
		// no pixel centre inside its screen box (then it can make no fragment)
		float2 pa = (mn * 0.5 + 0.5) * sz, pb = (mx * 0.5 + 0.5) * sz;
		if (floor(pb.x - 0.5) < ceil(pa.x - 0.5) || floor(pb.y - 0.5) < ceil(pa.y - 0.5)) {
			atomic_fetch_add_explicit(&stats[4], 1, memory_order_relaxed);
			return;
		}
		uint2 a = uint2(clamp(pa, float2(0), sz - 1)) / f.hizScale, b = uint2(clamp(pb, float2(0), sz - 1)) / f.hizScale;
		uint ext = max(b.x - a.x, b.y - a.y);
		uint level = ext == 0 ? 0 : min(f.mips - 1, 32 - clz(ext));
		uint2 lsize = uint2(max(1u, f.hiz0.x >> level), max(1u, f.hiz0.y >> level));
		uint2 la = min(a >> level, lsize - 1), lb = min(b >> level, lsize - 1);
		float far = 1.0;
		for (uint y = la.y; y <= lb.y; y++)
			for (uint x = la.x; x <= lb.x; x++) far = min(far, hiz.read(uint2(x, y), level).x);
		if (zmax < far - 1e-6) {
			atomic_fetch_add_explicit(&stats[5], 1, memory_order_relaxed);
			return;
		}
	}
	atomic_fetch_add_explicit(&stats[6], 1, memory_order_relaxed);
}

// ---- the exact per-quad prefilter (-Dmcopt.own.prefilter): no depth needed ----
// every quad of every unit the cull passed (T) is dropped when it faces away from the eye (its winding), lies wholly outside a
// clip plane, or its screen box holds no pixel centre (then it can make no fragment); the rest go to region 0's index lists.
kernel void own_q_pre(constant QuadFrame &f [[buffer(0)]], device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]],
	device const uint *args [[buffer(3)]], device const uint *tested [[buffer(9)]], device uint *idx [[buffer(13)]], device atomic_uint *qa [[buffer(11)]],
	device const void *arena [[buffer(15)]], uint tid [[thread_position_in_grid]]) {
	uint t = tid / f.run, q = tid % f.run;
	bool want = false;
	uint quad = 0, layer = 0;
	if (t < min(args[ARGS_T], f.listCap)) {
		Rec rec = recs[tested[t]];
		quad = rec.quadStart + q;
		layer = (rec.meta >> 9) & 1;
		if (q <= (rec.meta & 63)) {
			Section s = sections[rec.slot];
			float3 o = float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz;
			float3 c[4];
			for (uint i = 0; i < 4; i++) c[i] = o + quadCorner(arena, f.compact, quad * 4 + i);
			float3 n1 = cross(c[1] - c[0], c[2] - c[0]), n2 = cross(c[3] - c[2], c[0] - c[2]);
			// back-facing: both triangles turned away (the rasterizer would drop both)
			bool front = dot(n1, -c[0]) > 0 || dot(n2, -c[2]) > 0;
			if (front) {
				float4 h[4];
				for (uint i = 0; i < 4; i++) h[i] = f.clip * float4(c[i], 1.0);
				bool outside = false;
				for (int pl = 0; pl < 4 && !outside; pl++) {
					bool all = true;
					for (uint i = 0; i < 4; i++) {
						float v = pl == 0 ? h[i].w + h[i].x : pl == 1 ? h[i].w - h[i].x : pl == 2 ? h[i].w + h[i].y : h[i].w - h[i].y;
						if (v >= 0) all = false;
					}
					outside = all;
				}
				want = !outside;
				if (want) {
					bool behind = false;
					float2 mn = float2(1e30), mx = float2(-1e30);
					for (uint i = 0; i < 4; i++) {
						if (h[i].w <= 1e-4) behind = true;
						float2 d = h[i].xy / max(h[i].w, 1e-6);
						mn = min(mn, d);
						mx = max(mx, d);
					}
					if (!behind) {
						// (a margin of a hundredth of a pixel against rounding)
						float2 sz = float2(f.screen);
						float2 pa = (mn * 0.5 + 0.5) * sz - 0.01, pb = (mx * 0.5 + 0.5) * sz + 0.01;
						if (floor(pb.x - 0.5) < ceil(pa.x - 0.5) || floor(pb.y - 0.5) < ceil(pa.y - 0.5)) want = false;
					}
				}
			}
		}
	}
	putQuads(idx, qa, 0, want, layer, quad, f.qcap, f.qlist);
}

// ---- stats only (-Dmcopt.own.frag.facestats): the facing tally of what the frag lists draw ----
// One threadgroup per listed unit of list `id` (its mesh dispatch {units, 1, 1}), one thread per quad: the quad's own winding seen from
// the camera (vanilla's front faces are counter-clockwise, so n = (v1 - v0) x (v2 - v0) points at the eye for a front face). A back-facing
// quad is paid for in the vertex stage and dropped by the rasterizer. Counters at args + lists * 8: [22] quads, [23] back-facing in the
// facing buckets, [24] back-facing in the any bucket, [25] any-bucket quads, [26] degenerate (no area).
kernel void frag_face_stats(constant CullFrame &f [[buffer(0)]], constant FragFrame &g [[buffer(11)]], device const Section *sections [[buffer(1)]],
	device const Rec *recs [[buffer(2)]], device atomic_uint *args [[buffer(3)]], device const uint *lists [[buffer(4)]],
	constant uint &id [[buffer(7)]], constant uint &compact [[buffer(8)]], device const void *arena [[buffer(15)]],
	uint tg [[threadgroup_position_in_grid]], uint q [[thread_index_in_threadgroup]]) {
	uint r = lists[id * f.listCap + tg];
	Rec rec = recs[r];
	bool live = q <= (rec.meta & 63);
	bool any = ((rec.meta >> 6) & 7) == 6;
	uint back = 0, degenerate = 0;
	if (live) {
		Section s = sections[rec.slot];
		float3 cam = -(float3(int3(s.x, s.y, s.z) - f.camBlock.xyz) + f.camOffset.xyz);  // the eye, section-relative
		uint v = (rec.quadStart + q) * 4;
		float3 v0 = quadCorner(arena, compact, v), v1 = quadCorner(arena, compact, v + 1), v2 = quadCorner(arena, compact, v + 2);
		float3 n = cross(v1 - v0, v2 - v0);
		if (length_squared(n) < 1e-12) degenerate = 1;
		else if (dot(n, cam - v0) <= 0) back = 1;
	}
	uint quads = simd_sum(live ? 1u : 0u), backFacing = simd_sum(back != 0 && !any ? 1u : 0u), backAny = simd_sum(back != 0 && any ? 1u : 0u);
	uint anyQuads = simd_sum(live && any ? 1u : 0u), degen = simd_sum(degenerate);
	if (simd_is_first()) {
		uint base = g.lists * 8;
		atomic_fetch_add_explicit(&args[base + 22], quads, memory_order_relaxed);
		if (backFacing) atomic_fetch_add_explicit(&args[base + 23], backFacing, memory_order_relaxed);
		if (backAny) atomic_fetch_add_explicit(&args[base + 24], backAny, memory_order_relaxed);
		if (anyQuads) atomic_fetch_add_explicit(&args[base + 25], anyQuads, memory_order_relaxed);
		if (degen) atomic_fetch_add_explicit(&args[base + 26], degen, memory_order_relaxed);
	}
}

// the visibility oracle in motion (-Dmcopt.own.oracle with uocc): on a sample frame, the unit test's verdicts (occVis: the
// next frame's number for every unit that passed this frame's test) copied right after the test, before later frames overwrite them
kernel void own_oracle_copy(device const uint *src [[buffer(0)]], device uint *dst [[buffer(1)]], constant uint &n [[buffer(2)]],
	uint i [[thread_position_in_grid]]) {
	if (i < n) dst[i] = src[i];
}

// -Dmcopt.own.mesh.qrecVerify (OwnQrec.VERIFY: every layer keeps its 64-byte copy, the unit's +24 its first quad there): a thread
// per section slot compares every corner of its solid / cutout units as quadCorner (the per-quad readers) and ownQrec (ownVertex)
// decode it with the copy. stats: [0] corners, [1] corners differing, [2] units checked, [3] units without a copy
kernel void own_qrec_verify(device const Section *sections [[buffer(1)]], device const Rec *recs [[buffer(2)]], device const void *arena [[buffer(15)]],
	device atomic_uint *stats [[buffer(18)]], constant uint &slots [[buffer(7)]], uint tid [[thread_position_in_grid]]) {
	if (!OWN_REC || tid >= slots) return;
	Section s = sections[tid];
	uint corners = 0, bad = 0, units = 0, bare = 0;
	for (uint ri = s.recStart; ri < s.recStart + s.recCount; ri++) {
		Rec rec = recs[ri];
		if (((rec.meta >> 9) & 3) == 2) continue;
		if (rec.pad0 == 0xffffffffu) {
			bare++;
			continue;
		}
		units++;
		for (uint q = 0; q <= (rec.meta & 63); q++) {
			for (uint c = 0; c < 4; c++) {
				CVtx k = ((device const CVtx *) arena)[(rec.pad0 + q) * 4 + c];
				float3 pk = OWN_POS3(arena, k.px, k.py, k.pz);
				float3 pc = quadCorner(arena, 1, (rec.quadStart + q) * 4 + c);
				Vtx v = ownQrec(arena, rec.quadStart + q, c);
				bool same = all(pc == pk) && all(float3(v.pos) == pk) && all(v.color == k.color) && all(float2(v.uv0) == float2(k.u, k.v) / 65536.0)
					&& v.uv2.x == short(k.lu) && v.uv2.y == short(k.lv);
				corners++;
				if (!same) bad++;
			}
		}
	}
	if (corners) atomic_fetch_add_explicit(&stats[0], corners, memory_order_relaxed);
	if (bad) atomic_fetch_add_explicit(&stats[1], bad, memory_order_relaxed);
	if (units) atomic_fetch_add_explicit(&stats[2], units, memory_order_relaxed);
	if (bare) atomic_fetch_add_explicit(&stats[3], bare, memory_order_relaxed);
}

// -Dmcopt.own.int.animCopy with -Dmcopt.own.int.atlasWrite: every drawn animation sprite's rectangle copied from the scratch atlas's mip into
// the atlas's same mip (single-level views), one thread per texel; rects: srcX, srcY, dstX, dstY, w, h per sprite (grid z). A unorm8 texel
// read as float and written back is the same texel.
kernel void anim_copy(texture2d<float, access::read> src [[texture(0)]], texture2d<float, access::write> dst [[texture(1)]],
	constant uint *rects [[buffer(0)]], uint3 g [[thread_position_in_grid]]) {
	uint k = g.z * 6;
	if (g.x >= rects[k + 4] || g.y >= rects[k + 5]) return;
	dst.write(src.read(uint2(rects[k] + g.x, rects[k + 1] + g.y)), uint2(rects[k + 2] + g.x, rects[k + 3] + g.y));
}

// -Dmcopt.own.tl.a1Split: one list's indexed draw arguments (args + list * 5: indexCount, instanceCount, indexStart, baseVertex,
// baseInstance) as two draws, its first k = instanceCount * num / den instances, then the rest from baseInstance + k (instance_id counts
// from the base instance), into out[0..4] and out[5..9]: the same units in the same order, drawn as two draws so a render encoder can
// end between them. Appended to the cull's encoder after frag_finish.
kernel void own_tl_a1split(device const uint *args [[buffer(3)]], device uint *out [[buffer(29)]], constant uint4 &p [[buffer(28)]],
	uint i [[thread_position_in_grid]]) {
	if (i != 0) return;
	device const uint *a = args + p.x * 5;
	uint n = a[1], k = uint((ulong(n) * p.y) / max(p.z, 1u));
	out[0] = a[0]; out[1] = k; out[2] = a[2]; out[3] = a[3]; out[4] = a[4];
	out[5] = a[0]; out[6] = n - k; out[7] = a[2]; out[8] = a[3]; out[9] = a[4] + k;
}
