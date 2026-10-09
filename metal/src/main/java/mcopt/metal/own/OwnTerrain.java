package mcopt.metal.own;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Our own near terrain (-Dmcopt.own=true, without Sodium; see metal/OWN-RENDERER.md). Vanilla's section compiler still meshes;
 * its solid and cutout layers are written straight into one shared arena by the worker that meshed them (sorted into facing
 * buckets on the way), instead of vanilla's staging copy and per-layer uber buffers. Each frame a compute pass culls every
 * compiled section on the GPU (vanilla's distance rule, the view frustum, back-facing buckets) into two instance lists, and
 * one instanced indirect draw per layer pulls the quads from the arena and shades them as vanilla's core/terrain does.
 * Translucent stays on vanilla's path for now.
 */
public final class OwnTerrain {
	/** The switch; the mixins (mcopt-metal-own.mixins.json) apply only with it and without Sodium. */
	public static final boolean FLAG = Boolean.getBoolean("mcopt.own");
	public static final boolean STATS = Boolean.getBoolean("mcopt.own.stats");
	/** -Dmcopt.own.int.noFade (measurement only, NOT exact): sections drawn without vanilla's fade-in. */
	private static final boolean NO_FADE = Boolean.getBoolean("mcopt.own.int.noFade");
	/** Stats: section compiles (vanilla's mesher) and our store pass (bucket sort + arena write), count and nanoseconds. */
	private static final java.util.concurrent.atomic.LongAdder COMPILES = new java.util.concurrent.atomic.LongAdder(), COMPILE_NS = new java.util.concurrent.atomic.LongAdder(),
		STORES = new java.util.concurrent.atomic.LongAdder(), STORE_NS = new java.util.concurrent.atomic.LongAdder();

	public static void compiled(long ns) {
		COMPILES.increment();
		COMPILE_NS.add(ns);
	}
	/** -Dmcopt.own.graph=true: draw only the sections vanilla's occlusion graph found visible this frame (its cave culling). */
	private static final boolean GRAPH = Boolean.getBoolean("mcopt.own.graph");
	/**
	 * -Dmcopt.own.occ=true: two-phase occlusion. Phase A draws the units visible last frame; the pass is split once, a depth
	 * pyramid is built from phase A's depth, every unit the cull passed is tested against it and the newly visible are drawn
	 * (phase B). Exact: whatever shows in the frame is drawn in one phase or the other.
	 */
	private static final boolean OCC = Boolean.getBoolean("mcopt.own.occ");
	private static final int OCC_FRAME_BYTES = 128;
	/**
	 * -Dmcopt.own.probe=MS: in-run A/B probe. Every MS milliseconds the next of -Dmcopt.own.probeModes (default draw,skip,graph)
	 * takes over (draw: as configured; skip: no terrain drawn, cull still runs; graph: vanilla's visible sections only; nograph:
	 * every section the GPU cull passes), and every 5 s the mean frame interval of each mode is logged.
	 */
	private static long PROBE_MS = Long.getLong("mcopt.own.probe", 0);
	private static String[] PROBE_MODES = System.getProperty("mcopt.own.probeModes", "draw/skip/graph").split("[,/]");
	private int probeMode;
	private long probeSwitch, probeLastFrame;
	private double[] probeSum = new double[PROBE_MODES.length];
	private long[] probeCount = new long[PROBE_MODES.length];
	private long probeLog;
	private long argsAddress;
	/** The frag variants (-Dmcopt.own.frag.*, probe modes "f-..."): made on first use. */
	private @Nullable OwnFrag frag;
	/** This frame's per-quad draws go cutout first (-Dmcopt.own.frag.order=cutfirst, probe mode f-cutfirst). */
	private boolean quadsCutFirst;
	/** This frame's per-quad lists drawn by mesh shading (OwnFrag.QMESH or probe mode "qmesh"). */
	private boolean quadsMesh;
	/** Extra draw kind bits for this frame's per-quad draws (-Dmcopt.own.frag.half, probe mode f-half). */
	private int quadsKind;

	static final int VERTEX_BYTES = 28, QUAD_BYTES = 4 * VERTEX_BYTES, SECTION_BYTES = 32, REC_BYTES = 32,
		// the CPU-written per-frame copies (section tables, masks, dirty lists) and deferred releases: one more than the frames in
		// flight (-Dmcopt.metal.inFlight, default 2, so 3 as before), so the CPU never rewrites a copy a frame still on the GPU reads
		FRAMES = Math.max(3, Integer.getInteger("mcopt.metal.inFlight", 2) + 1);
	/** -Dmcopt.own.run=N: quads per draw unit (instance), 1-64. */
	static final int RUN = Math.max(1, Math.min(64, Integer.getInteger("mcopt.own.run", 64)));
	/** -Dmcopt.own.compact=true: 16-byte vertices in the arena (see terrain.metal CVtx) instead of vanilla's 28. */
	static final boolean COMPACT = Boolean.getBoolean("mcopt.own.compact");
	/** -Dmcopt.own.cpuClip=true: the vertex stage gets projection x view multiplied once a frame instead of per vertex. */
	private static final boolean CPU_CLIP = Boolean.getBoolean("mcopt.own.cpuClip");
	/** -Dmcopt.own.fat=true: the cull writes whole instances (unit + section origin, 32 B) so a vertex makes one load before its fetch. */
	private static final boolean FAT = Boolean.getBoolean("mcopt.own.fat");
	/**
	 * -Dmcopt.own.translucent=true: translucent terrain too (classic transparency only; with improved transparency it stays
	 * vanilla's): vertices in the arena in compile order, vanilla's sorted quad order (compile and every resort) beside them, and
	 * one draw a frame whose instance order is vanilla's visible sections far to near.
	 */
	public static final boolean TRANSLUCENT = Boolean.getBoolean("mcopt.own.translucent");
	/**
	 * -Dmcopt.own.rgssEarly (default true): with RGSS texture filtering, a fragment whose blend weight is 0 (a texel at least a pixel
	 * wide) returns the nearest sample without taking the eight RGSS samples (terrain.metal sampleRGSS; the same picture, bit for bit).
	 */
	private static final boolean RGSS_EARLY = Boolean.parseBoolean(System.getProperty("mcopt.own.rgssEarly", "true"));
	/**
	 * -Dmcopt.own.rgssTri: RGSS's two mip levels blended by the atlas sampler's linear mip filter (4 samples at the exact level instead
	 * of 4 at each of the two levels around it), and no nearest sample where RGSS's blend weight is 1 (terrain.metal sampleRGSS).
	 */
	private static final boolean RGSS_TRI = Boolean.getBoolean("mcopt.own.rgssTri");
	/** -Dmcopt.own.level=true: opaque terrain samples the atlas at a computed level instead of with gradients (see terrain.metal). */
	private static final boolean LEVEL = Boolean.getBoolean("mcopt.own.level");
	/**
	 * -Dmcopt.own.prepass=true: cutout's depth first (alpha-tested, no colour), then solid, then cutout's colour with depth EQUAL.
	 * Same picture as vanilla's solid-then-cutout (cutout still wins depth ties), but solid fragments behind leaves are rejected
	 * before shading, and no alpha-tested draw lands on a tile's pending opaque work.
	 */
	private static final boolean PREPASS = Boolean.getBoolean("mcopt.own.prepass");
	/**
	 * -Dmcopt.own.quads=true: occlusion per quad. The cull lists every unit it passes; phase A (pre command buffer) lists the
	 * quads visible last frame as an index list built on the GPU; the pass splits once for a depth pyramid; every listed quad is
	 * tested with its own corners and the newly visible are drawn (phase B). Exact as the unit version, finer grained.
	 */
	private static final boolean QUADS = Boolean.getBoolean("mcopt.own.quads");
	/** -Dmcopt.own.quads.coarse=N: units spanning at most N mip-0 texels skip per-quad tests in phase B (their verdict stands). */
	private static final int QUADS_COARSE = Integer.getInteger("mcopt.own.quads.coarse", 0);
	private static final boolean QUADS_UNIT_STAGGER = Boolean.getBoolean("mcopt.own.quads.unitStagger");
	private static final boolean QUADS_UNITS = Boolean.getBoolean("mcopt.own.quads.units");
	private static final boolean QUADS_LIST = Boolean.getBoolean("mcopt.own.quads.list");
	/**
	 * A copy per frame in flight of what the GPU writes each frame (the cull's arguments and lists, the per-quad lists and
	 * arguments), and per-quad phase A from the visible bits of two frames back (4 copies): frame N+1's cull and phase A then
	 * don't wait for frame N's split to finish reading or writing them (hazard-tracked buffers serialize across command buffers).
	 */
	private static final boolean RING = Boolean.getBoolean("mcopt.own.ring");
	private static final int RING_N = RING ? FRAMES : 1;
	private final long[] rArgs = new long[FRAMES], rArgsAddress = new long[FRAMES], rLists = new long[FRAMES], rTested = new long[FRAMES],
		rFat = new long[FRAMES], rQIdx = new long[FRAMES], rQArgs = new long[FRAMES], rQArgsAddress = new long[FRAMES], rVis = new long[4];
	/** Phase B tests each quad's bounds (8 bytes a quad, kept at publish) instead of reading its four arena vertices (64 bytes). */
	private static final boolean QUADS_BOX = COMPACT && Boolean.getBoolean("mcopt.own.quads.box");
	/** The per-quad path's phase A and flat test read the cull's TUnit entries (record fields + section origin) in place of index -> record -> section. */
	private static final boolean QUADS_FATT = !FAT && Boolean.getBoolean("mcopt.own.quads.fatT");
	private static final int QUAD_FRAME_BYTES = 192;
	/** Solid-section occlusion: the parameters' size (terrain.metal SolidOcc), the occluder depth's width (texels; height by aspect). */
	private static final int SOLID_BYTES = 144, SOLID_WIDTH = Integer.getInteger("mcopt.own.solidOcc.width", 320);
	private @Nullable OwnSolid solid;
	/** -Dmcopt.own.qstats=true: the drawn-quad tally (own_qstat; measurement only, splits the pass once a frame). */
	private static final boolean QSTATS = Boolean.getBoolean("mcopt.own.qstats");
	/**
	 * -Dmcopt.own.mesh.tcull=true: translucent quads facing away from the camera are left out of the drawn order on the CPU, no GPU
	 * vertex reads (OwnTerrain.tcull). Off by default.
	 */
	private static final boolean TCULL = Boolean.getBoolean("mcopt.own.mesh.tcull");
	/**
	 * -Dmcopt.own.mesh.tflat=true: translucent is one indexed, non-instanced draw over a per-quad list (entry = unit record << 6 | quad
	 * in it, a static u32 index buffer of 6 indices a quad), so no padding slots are drawn; with tcull only the live quads facing the
	 * camera. Each mesh keeps its entries per order version and the frame's list is a copy per mesh.
	 */
	/**
	 * -Dmcopt.own.tstat=true (measurement): translucent resorts (count, quads, ns in resorted()) and stores per 5 s, and the listed
	 * translucent meshes / units per frame (logged from drawTranslucent).
	 */
	private static final boolean TSTAT = Boolean.getBoolean("mcopt.own.tstat");
	/**
	 * -Dmcopt.own.tprobe=N (measurement only, picture not kept): the translucent draw with 1 position-only work and a constant fragment,
	 * 2 no order read (quads in mesh order), 3 a constant fragment, 4 constant colour / no lightmap / no fog distance, 5 no rich varyings.
	 */
	private static final int TPROBE = Integer.getInteger("mcopt.own.tprobe", 0);
	/**
	 * -Dmcopt.own.mesh.tsorted=true: every translucent order version (vanilla's sort, a resort, a tcull filter) also gets its quads
	 * copied in that order, with units of their own (meta bit 18) that the vertex stage reads directly: no order entry, no dependent
	 * read. -Dmcopt.own.mesh.thalf=true: the translucent draw with the half colour varying (as frag.half), and the far-to-near list's
	 * nearest run of sections that are faded in and wholly inside the render-distance fog start drawn lean (as frag.lean's class).
	 * Probe modes x-base / x-sorted / x-half / x-both switch them per frame for in-run A/B (the copies are kept while a mode needs them).
	 */
	private static final boolean TSORTED = Boolean.getBoolean("mcopt.own.mesh.tsorted"), THALF = Boolean.getBoolean("mcopt.own.mesh.thalf");
	private static final boolean TS_BUILD = TSORTED || PROBE_MS > 0 && java.util.Arrays.stream(PROBE_MODES).anyMatch(m -> m.equals("x-sorted") || m.equals("x-both"));
	/** This frame's translucent choices (the flags, or the probe mode). */
	private boolean tSortedNow = TSORTED, tHalfNow = THALF, tLeanWarm;
	/**
	 * -Dmcopt.own.mesh.tfrustum=true: a translucent section is left out of the frame's list when the box of its translucent vertices
	 * (vanilla's own floats, taken at store, grown TFR_EPS) lies wholly outside one of the four side planes of the frustum the
	 * translucent draw itself projects with (the same projection buffer and view rotation, so FOV changes, view bobbing and hurt tilt
	 * are in it). Such a section's triangles are all clipped: no fragment, so neither the picture, the blend order of the rest nor
	 * depth changes. Vanilla lists sections by a frustum moved back to hold the camera's 8-block cube (wider than the drawn one).
	 * Off by default; probe modes x-base / x-tfr switch it per frame. Not with native shading (its translucent passes aren't
	 * checked here).
	 */
	private static final boolean TFRUSTUM = Boolean.getBoolean("mcopt.own.mesh.tfrustum") && !OwnMaterials.ON;
	private static final boolean TFR_PROBE = PROBE_MS > 0 && java.util.Arrays.asList(PROBE_MODES).contains("x-tfr") && !OwnMaterials.ON;
	/** TFRUSTUM: the box's growth (blocks): far above the compact / exactPos position error and float rounding of the clip test. */
	private static final float TFR_EPS = 1.0f / 16;
	private boolean tFrNow = TFRUSTUM;
	private final float[] tFrPlanes = new float[16];
	private long tFrFrames, tFrSkipped, tFrSkippedQ, tFrListed, tFrLogAt;
	private static final java.util.concurrent.atomic.AtomicLong TS_SORTED_BYTES = new java.util.concurrent.atomic.AtomicLong(), TS_COPY_BYTES = new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.LongAdder TS_RESORTS = new java.util.concurrent.atomic.LongAdder(), TS_RESORT_QUADS = new java.util.concurrent.atomic.LongAdder(),
		TS_RESORT_NS = new java.util.concurrent.atomic.LongAdder(), TS_STORES = new java.util.concurrent.atomic.LongAdder(), TS_STORE_QUADS = new java.util.concurrent.atomic.LongAdder();
	private long tsAt, tsFrames, tsMeshes, tsUnits, tsLeanUnits, tsAllUnits;
	private static final boolean TFLAT = Boolean.getBoolean("mcopt.own.mesh.tflat") && !Boolean.getBoolean("mcopt.own.fat");
	/** TCULL: a quad is left out only when the camera is this far behind its plane (blocks); nearer, it stays. */
	private static final double TCULL_EPS = 1.0 / 64;
	private static final java.util.concurrent.atomic.AtomicLong TC_REFILTERS = new java.util.concurrent.atomic.AtomicLong(), TC_QUADS = new java.util.concurrent.atomic.AtomicLong(),
		TC_DROPPED = new java.util.concurrent.atomic.AtomicLong();
	private long tcLogAt, tcListed, tcFull, tcFrames;
	/** -Dmcopt.own.vcount=true (measurement): own_vs counts its invocations per path (terrain.metal OWN_VCOUNT), logged per 600 frames. */
	private static final boolean VCOUNT = Boolean.getBoolean("mcopt.own.vcount");
	/** -Dmcopt.own.cullR=true: the cull a thread per record with SIMD-aggregated appends (probe modes cullR / cullS). */
	private static final boolean CULL_R = Boolean.getBoolean("mcopt.own.cullR");
	/**
	 * -Dmcopt.own.prefilter=true: the exact per-quad prefilter. Every quad of every unit the cull passes is tested on the GPU
	 * before the pass (back-facing by its winding, outside the frustum, no pixel centre in its screen box) and only the rest is
	 * drawn, from GPU-built index lists. No depth, no pass split. Probe modes prefilter / noprefilter.
	 */
	private static final boolean PREFILTER = Boolean.getBoolean("mcopt.own.prefilter");
	/** The slot of every arena quad is kept (quadOwner) whenever a quad-mode draw can run: the flags, or their probe modes. */
	private static final boolean QUAD_OWNERS = QUADS || PREFILTER || System.getProperty("mcopt.own.probeModes", "").matches(".*\\b(quads|prefilter)\\b.*");
	private long qsBuffer, qsAddress, qsFrames;
	/** Bytes of one quad in the arena. */
	static final int ARENA_QUAD = COMPACT ? 64 : QUAD_BYTES;
	private static final int CULL_FRAME_BYTES = 144, BUCKETS = 7;
	static final int DRAW_FRAME_BYTES = 80;

	private static @Nullable OwnTerrain instance;
	private static boolean failed;

	private final Object encoder;
	private final long ctx, enc, own;

	// ---- the arena: quads, written by the meshing workers ----
	private final ReentrantReadWriteLock arenaLock = new ReentrantReadWriteLock();
	private volatile long arenaBuffer, arenaAddress;
	/** Physical units reserved; logical OwnAlloc capacity still grows on its original schedule. Guarded by arenaLock. */
	private int arenaPhysicalUnits;
	private final OwnAlloc quadAlloc;

	// ---- render thread only ----
	private long recBuffer, recAddress;
	private final OwnAlloc recAlloc;
	private int slotCap;
	private long master;
	private final long[] tables = new long[FRAMES], tableAddresses = new long[FRAMES];
	/** Per frame slot: a bit per section slot, vanilla's visible sections (GRAPH). */
	private final long[] masks = new long[FRAMES], maskAddresses = new long[FRAMES];
	private final int[][] dirty = new int[FRAMES][];
	private final int[] dirtyCount = new int[FRAMES];
	private OwnMesh[] slotMesh;
	/** Per slot: 1 once a compile result (even an empty one) is in the slot, 0 after a reset (OwnSeam's hand-off). */
	private byte[] compiled = new byte[0];
	/** Bumped whenever a slot's compiled state changes (OwnSeam recomputes its chunk mask only then). */
	private int seamSerial;
	private int maxSlot = -1;
	private long lists, args, tested, fat;
	/** Visibility bit per record: last frame's (read by phase A) and this frame's (written by phase B); swapped each frame. */
	private long prevVis, curVis;
	private int visWords;
	/** The per-quad path: slot of every arena quad (shared), visibility bit per quad (two, swapped), index regions, draw arguments. */
	private long qDrawnA, qDrawnB, qDrawnFrames;  // STATS: drawn quads summed over the frames since the last print (per-quad path)
	private long quadOwner, quadOwnerAddress, qPrev, qCur, qIdx, qArgs, qArgsAddress, quadBox, quadBoxAddress;
	/**
	 * -Dmcopt.own.mesh.tieGroups: the tie-group buffer (uint words; OwnTieGroups' block layout; word 0 reserved so a Section's 0 means
	 * none) and its allocator; the per-ID S bitset (a bit per quad id, words over quadIds(the arena's capacity)). Shared, CPU-written at
	 * publish; a block or an id range is only reused after later(), so no frame in flight sees it change.
	 */
	private long grpBuffer, grpAddress, sBits, sBitsAddress;
	private @Nullable OwnAlloc grpAlloc;
	private int sBitsWords;
	private long grpChecked, grpBad, grpDropped, grpMeshes;
	/** -Dmcopt.own.mesh.tieGroups: the cross-section directory, and the installed sections whose groups aren't known. */
	private final OwnTieGroups.Dir tieDir = new OwnTieGroups.Dir(new TieLookup());
	/** The installed slot of each section position (OwnTieGroups.Dir.pack of its block origin), for the directory's overhang lookups. */
	private final java.util.HashMap<Long, Integer> tieSlots = new java.util.HashMap<>();

	/** The directory's view of the installed sections: a slot's opaque quads near world corners, decoded from the arena as the shader does. */
	private final class TieLookup implements OwnTieGroups.Lookup {
		@Override
		public int slotAt(int x, int y, int z) {
			Integer s = OwnTerrain.this.tieSlots.get(OwnTieGroups.Dir.pack(x, y, z));
			return s == null ? -1 : s;
		}

		private final double[] lo = new double[3], hi = new double[3], q = new double[12];
		private final int[] org = new int[3], raw = new int[12];

		@Override
		public int matches(int slot, double[] w, int[] e, int o, int layer) {
			OwnMesh m = slot < OwnTerrain.this.slotCap ? OwnTerrain.this.slotMesh[slot] : null;
			if (m == null || m.recStart < 0) return 0;
			long sa = OwnTerrain.this.master + (long) slot * SECTION_BYTES;
			int[] org = this.org;
			org[0] = MemoryUtil.memGetInt(sa);
			org[1] = MemoryUtil.memGetInt(sa + 4);
			org[2] = MemoryUtil.memGetInt(sa + 8);
			double[] lo = this.lo, hi = this.hi, q = this.q;
			for (int k = 0; k < 3; k++) {
				lo[k] = Math.min(Math.min(w[k], w[3 + k]), Math.min(w[6 + k], w[9 + k])) - OwnTieGroups.TOL;
				hi[k] = Math.max(Math.max(w[k], w[3 + k]), Math.max(w[6 + k], w[9 + k])) + OwnTieGroups.TOL;
			}
			int k = 0, rb = OwnTieGroups.Dir.bucket(e, o);
			for (int r = m.recStart; r < m.recStart + m.recCount; r++) {
				long a = OwnTerrain.this.recAddress + (long) r * REC_BYTES;
				int meta = MemoryUtil.memGetInt(a + 8);
				if ((meta >>> 9 & 3) > 1) continue;
				if (OwnTieGroups.X_ONLY && (meta >>> 9 & 3) == layer) continue;  // (tieClose: solid / cutout pairs only)
				int ub = meta >>> 6 & 7;
				if (rb < 6 && ub != rb && ub != 6) continue;  // (Dir.bucket: only the same facing or bucket 6 can be drawn with it)
				// (the unit's whole-block box, section-relative + 16, against the quad's box give or take TOL)
				int ulo = MemoryUtil.memGetInt(a + 16), uhi = MemoryUtil.memGetInt(a + 20);
				boolean overlap = true;
				for (int d = 0; d < 3 && overlap; d++) {
					int l = (ulo >>> (8 * d) & 255) - 16, h = (uhi >>> (8 * d) & 255) - 16;
					overlap = org[d] + l <= hi[d] && lo[d] <= org[d] + h;
				}
				if (!overlap) continue;
				int start = MemoryUtil.memGetInt(a), count = (meta & 63) + 1;
				boolean qrec = (meta & OwnQrec.META) != 0;
				quads:
				for (int i = 0; i < count; i++) {
					int[] raw = OwnTieGroups.arenaCodesInto(this.raw, OwnTerrain.this.arenaAddress, start + i, qrec);
					// (every corner of a near quad is within TOL of one of w's: inside w's box +- TOL; most quads leave at a corner)
					for (int c = 0; c < 12; c++) {
						double v = org[c % 3] + (double) OwnPosTable.decode(raw[c]);
						if (v < lo[c % 3] || v > hi[c % 3]) continue quads;
						q[c] = v;
					}
					// (Dir.tie, certify only for identical corners)
					if (!OwnTieGroups.near(w, q)) continue;
					if (OwnTieGroups.identical(w, q) && !OwnTieGroups.Dir.candidate(OwnTieGroups.Dir.cert(e, o), OwnTieGroups.certify(raw, 0))) continue;
					k++;
				}
			}
			return k;
		}
	}
	private int tieUnknown;
	/**
	 * -Dmcopt.own.frag.tieClose: the tie components' record rings (a uint per record, shared; terrain.metal fragTiePromote; values
	 * OwnTieGroups.rings). Written at publish with
	 * the mesh's records (a released mesh's range is reused only after later(), so no frame in flight reads it).
	 */
	private long tieRing, tieRingAddress;
	/** (control, -Dmcopt.own.frag.tieCloseForce=1..5) every tieClose frame falls back for that reason: the fallback path's picture and cost. */
	private static final int TIE_FORCE = Math.min(5, Integer.getInteger("mcopt.own.frag.tieCloseForce", 0));
	private int tieRingCap;
	private long tieRingMeshes, tieRingUnits, tieRingAlways;
	private final long[] grpLive = new long[5];
	private int quadOwnerCap, qWords, qcap;
	private int listCap;
	private long frame;
	private final long baseMs = Util.getMillis();
	/** Releases waiting for the GPU: frame stamp and action. */
	private final ArrayDeque<Object[]> deferred = new ArrayDeque<>();
	/** Native buffers to release, queued from any thread (the arena grows on workers). */
	private final ConcurrentLinkedQueue<Long> releaseQueue = new ConcurrentLinkedQueue<>();

	private final ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();
	private final ConcurrentHashMap<Object, OwnMesh> meshes = new ConcurrentHashMap<>();
	private long statFrames, statLast = System.nanoTime();

	private record Publish(int slot, Object mesh, int x, int y, int z, long uploadedMs) {
	}

	private record Clear(int slot) {
	}

	private record Release(Object mesh) {
	}

	private record Resort(Object mesh, int permStart, int permUnits, int sortStart, int sortUnits) {
	}

	/** One compiled section's geometry in the arena: per layer (solid, cutout) its quads and draw units. */
	static final class OwnMesh {
		final @Nullable Layer[] layers = new Layer[3];
		int slot = -1, recStart = -1, recCount;
		/** Translucent: the sorted quad order (arena units), and where its units' records are. */
		int permStart = -1, permUnits, tRecStart, tRecCount;
		/** TCULL: per translucent quad (compile order) its facing bucket (OwnTerrain.facing: 0-5, 6 any) and plane coordinate. */
		byte @Nullable [] tFace;
		float @Nullable [] tPlane;
		/**
		 * TCULL's filtered version of the order: the quads not back-facing from the camera (kept in order) at arena units fPerm, its
		 * own units' records at fRec (fRecCount of them; fRec -1: list the full order; -2: every quad left out), built from order permStart fFor for slot
		 * fSlot; valid while the camera (section-relative) stays inside fBox (lo x, y, z, hi x, y, z): no quad's plane crossed.
		 */
		int fPerm = -1, fPermUnits, fRec = -1, fRecCount, fFor = -1, fSlot = -1;
		final double[] fBox = new double[6];
		/** This frame: list the filtered version (tcull's answer). */
		boolean tUse;
		/** TFRUSTUM: the translucent vertices' box, section-relative (lo x, y, z, hi x, y, z), from vanilla's floats; this frame: left out. */
		float @Nullable [] tBox;
		boolean tOut;
		/** TS_BUILD: the full order's sorted quad copy (arena units, 64 bytes a quad) and its units' records (after the order ones); the filtered version's copy and records. */
		int sortStart = -1, sortUnits, sRecStart = -1, fSort = -1, fSortUnits, fsRec = -1;
		/** When it was stored (System.nanoTime) and whether it was ever published: an unpublished mesh 10 s old is an orphan (sweepOrphans). */
		final long storedNs = System.nanoTime();
		boolean published;
		/** TFLAT: the version's kept quad count; the full and filtered versions' list entries (native ints, malloc'd) and counts. */
		int fKept;
		long eFull, eFilt;
		int eFullN, eFiltN = -1;
		/**
		 * -Dmcopt.own.mesh.tieGroups: the worker's mesh-relative identical-corner groups (OwnTieGroups.build), and where publish wrote
		 * them in the tie-group buffer (grpStart words, 0 = not written; grpWords long).
		 */
		int @Nullable [] groups;
		/** grpStart: the block (0 = no groups, OwnTieGroups.UNKNOWN = not known); grpDone: publishGroups ran for this mesh. */
		int grpStart, grpWords;
		boolean grpDone;
		/** Its cross-section edge records (OwnTieGroups.build), and its entries in the directory while installed in slot dirSlot. */
		int @Nullable [] edges, dirIds;
		int dirSlot = -1;
		/** Its share of the live tie-group counts: groups, X groups, S groups, S members, members. */
		final int[] grpStat = new int[5];
	}

	static final int RUN_INTS = 6;
	/** exactPos: table entries already in the arena at posTableArena. */
	private int posTableSynced;
	private long posTableArena;
	// (qpos=true|all: every coordinate; =small: only coordinates within 0.001 of a 1/16 step (snow's 0.002 insets, lichen);
	// =large: only the others (random block offsets, fluid heights))
	private static final String Q_POS_MODE = System.getProperty("mcopt.own.mesh.qpos", "false");
	private static final boolean Q_POS = !Q_POS_MODE.equals("false"), Q_UV = Boolean.getBoolean("mcopt.own.mesh.quv");

	private static boolean qposTakes(float v) {
		if (Q_POS_MODE.equals("true") || Q_POS_MODE.equals("all")) return true;
		double s = v * 16.0, d = Math.abs(s - Math.rint(s)) / 16.0;
		boolean small = d > 0 && d < 0.001;
		return Q_POS_MODE.equals("small") ? small : !small;
	}

	/**
	 * runs: per draw unit {first quad (from quadStart), quads, bucket, plane bound in 1/256 blocks, bounds lo, hi (whole blocks,
	 * section-relative + 16, x | y << 8 | z << 16)}.
	 */
	/** subs: -Dmcopt.own.mesh.subbox's two sub-box words per unit (OwnQuads.subboxes), or null. */
	/**
	 * qrecUnit, qrecUnits: -Dmcopt.own.mesh.qrec's arena units holding the layer as 40-byte records (quadStart is then the first
	 * record's index, 40 bytes each) and, after them, its stubs' 64-byte quads; or -1, 0 (64 bytes a quad at quadStart).
	 * raw: the layer's 64-byte quads (copyTo's order) at this quad index (qrecVerify's copy, or the vanilla-MeshData path's quads
	 * its stubs point into), else -1; rawUnits: arena units of their own to free with the layer (0: inside qrecUnits or none).
	 */
	record Layer(int quadStart, int quadCount, int[] runs, int matStart, int @Nullable [] subs, int qrecUnit, int qrecUnits, int raw, int rawUnits) {
		Layer(int quadStart, int quadCount, int[] runs, int matStart, int @Nullable [] subs) {
			this(quadStart, quadCount, runs, matStart, subs, -1, 0, -1, 0);
		}

		Layer(int quadStart, int quadCount, int[] runs, int matStart) {
			this(quadStart, quadCount, runs, matStart, null);
		}
	}

	private OwnTerrain(Object encoder) {
		this.encoder = encoder;
		this.ctx = MetalBridge.ctx(encoder);
		this.enc = MetalBridge.enc(encoder);
		this.own = OwnNative.create(this.ctx, (OwnPosTable.ON ? "#define OWN_EXACT_POS 1\n" : "") + (OwnQrec.ON ? "#define OWN_QREC 1\n" : "") + (VCOUNT ? "#define OWN_VCOUNT 1\n" : "") + (TFLAT ? "#define OWN_TFLAT 1\n" : "") + (RGSS_EARLY ? "" : "#define OWN_RGSS_VANILLA 1\n") + (RGSS_TRI ? "#define OWN_RGSS_TRI 1\n" : "") + (TPROBE > 0 ? "#define OWN_TPROBE " + TPROBE + "\n" : "") + (OwnFrag.EXACT_SHIFT != 2 ? "#define A1_SH " + OwnFrag.EXACT_SHIFT + "\n" : "") + resource("/mcopt/own/terrain.metal"), COMPACT, FAT, CPU_CLIP);
		// -Dmcopt.own.hizTile=true: the per-quad path's pyramid in one tiled pass for its first five levels (padded to 32 texels)
		applyNativeFlags();
		// -Dmcopt.own.gputime=true: GPU timestamps of our compute encoders (cull, list build, pyramid + test), logged per 500 frames
		OwnNative.setGpuTime(Boolean.getBoolean("mcopt.own.gputime"));
		int quads = OwnArenaSizing.LOGICAL_INITIAL_UNITS;
		// The reservation uses the configured distance. The server-effective distance can still be transient during join.
		int rd = Minecraft.getInstance().options.renderDistance().get();
		boolean preSizeRequested = Boolean.getBoolean("mcopt.own.arena.preSize");
		int preSizeMaxCores = Integer.getInteger("mcopt.own.arena.preSizeMaxCores", 8);
		int gpuCores = mcopt.metal.Profile.gpuCores();
		boolean preSize = OwnArenaSizing.preSizeEnabled(preSizeRequested, gpuCores, preSizeMaxCores);
		this.arenaPhysicalUnits = OwnArenaSizing.reserveUnits(preSize, rd, COMPACT);
		this.arenaBuffer = OwnNative.buffer(this.ctx, (long) this.arenaPhysicalUnits * ARENA_QUAD, true);
		if (preSizeRequested) System.out.println("mcopt-own arena preSize: " + (preSize ? "on" : "off") + " gpuCores=" + gpuCores + " maxGpuCores=" + preSizeMaxCores
			+ " rd=" + rd + " effectiveRd=" + Minecraft.getInstance().options.getEffectiveRenderDistance()
			+ " physicalMiB=" + (long) this.arenaPhysicalUnits * ARENA_QUAD / (1 << 20) + " logicalMiB=" + (long) quads * ARENA_QUAD / (1 << 20));
		this.arenaAddress = OwnNative.contents(this.arenaBuffer);
		this.quadAlloc = new OwnAlloc(quads);
		if (OwnPosTable.ON && this.quadAlloc.alloc(OwnPosTable.UNITS) != 0) throw new IllegalStateException("exactPos: the position table must be the arena's first units");
		int recs = 1 << 16;
		this.recBuffer = OwnNative.buffer(this.ctx, (long) recs * REC_BYTES, true);
		this.recAddress = OwnNative.contents(this.recBuffer);
		this.recAlloc = new OwnAlloc(recs);
		this.slotCap = 0;
		this.slotMesh = new OwnMesh[0];
		this.growSlots(1 << 14);
		this.listCap = 0;
		this.growLists(1 << 15);
		for (int i = 0; i < RING_N; i++) {
			this.rArgs[i] = OwnNative.buffer(this.ctx, 128, 2);
			this.rArgsAddress[i] = OwnNative.contents(this.rArgs[i]);
		}
		this.growVis(recs);
		this.pickRing();
		for (int i = 0; i < FRAMES; i++) this.dirty[i] = new int[256];
		System.out.println("mcopt-own: near terrain on (arena " + (long) this.arenaPhysicalUnits * ARENA_QUAD / (1 << 20) + " MB" + (COMPACT ? ", compact vertices" : "") + ")");
	}

	private static String resource(String path) {
		try (InputStream in = OwnTerrain.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Render thread, at the top of the level's frame: creates the renderer the first time (null when not on Metal). */
	public static @Nullable OwnTerrain init() {
		if (instance != null || failed) return instance;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) {
				failed = true;
				System.out.println("mcopt-own: not on the Metal backend, off");
				return null;
			}
			instance = new OwnTerrain(encoder);
		} catch (RuntimeException e) {
			failed = true;
			System.out.println("mcopt-own: off: " + e);
			e.printStackTrace();
		}
		return instance;
	}

	public static @Nullable OwnTerrain get() {
		return instance;
	}

	/** AnimCopy's compute copy: the native state (its library and pipelines) and the backend encoder. */
	long ownHandle() {
		return this.own;
	}

	long encHandle() {
		return this.enc;
	}

	public static boolean takes(ChunkSectionLayer layer) {
		if (instance == null) return false;
		if (layer != ChunkSectionLayer.TRANSLUCENT) return true;
		// The option itself, not the frame's render state : the option is set before its allChanged() rebuild is
		// scheduled, the render state follows a frame later, so a section compiled in between would land in a store not drawn.
		// With improved transparency vanilla keeps translucent terrain (its OIT draws it).
		return TRANSLUCENT && !Minecraft.getInstance().options.improvedTransparency().get();
	}

	// ---- any thread: the section compiler's results and the sections' lifecycle ----

	/** A meshing worker's solid or cutout layer: into the arena, sorted into facing buckets. vertices: vanilla's BLOCK vertices. */
	public void store(Object mesh, ChunkSectionLayer layer, ByteBuffer vertices) {
		long t0 = STATS ? System.nanoTime() : 0;
		int bytes = vertices.remaining(), n = bytes / QUAD_BYTES;
		long src = MemoryUtil.memAddress(vertices);
		byte[] bucket = new byte[n];
		float[] plane = new float[n];
		int[] counts = new int[BUCKETS];
		for (int q = 0; q < n; q++) {
			long a = src + (long) q * QUAD_BYTES;
			int b = facing(a, plane, q);
			bucket[q] = (byte) b;
			counts[b]++;
		}
		int[] order = new int[n], at = new int[BUCKETS];
		for (int b = 1; b < BUCKETS; b++) at[b] = at[b - 1] + counts[b - 1];
		int[] firsts = at.clone();
		for (int q = 0; q < n; q++) order[at[bucket[q]]++] = q;
		int start = this.allocLocked(n);
		try {
			long dst = this.arenaAddress + (long) start * ARENA_QUAD;
			if (COMPACT) {
				for (int i = 0; i < n; i++) {
					long qs = src + (long) order[i] * QUAD_BYTES, qd = dst + (long) i * ARENA_QUAD;
					for (int v = 0; v < 4; v++) compact(qs + (long) v * VERTEX_BYTES, qd + v * 16L);
				}
			} else {
				for (int i = 0; i < n; i++) MemoryUtil.memCopy(src + (long) order[i] * QUAD_BYTES, dst + (long) i * QUAD_BYTES, QUAD_BYTES);
				if (Q_POS || Q_UV) {
					// measurement (-Dmcopt.own.mesh.qpos / .quv): the 28-byte vertex with only its position, or only its uv, snapped to the
					// compact vertex's grid (1/2048 block; unorm16), to tell which field the compact format's picture differences come from
					for (long v = 0; v < 4L * n; v++) {
						long va = dst + v * VERTEX_BYTES;
						if (Q_POS) for (int k = 0; k < 3; k++) {
							float c = MemoryUtil.memGetFloat(va + 4L * k);
							if (qposTakes(c)) MemoryUtil.memPutFloat(va + 4L * k, fixed(c) / 2048f - 8f);
						}
						if (Q_UV) for (int k = 0; k < 2; k++) {
							float u = MemoryUtil.memGetFloat(va + 16 + 4L * k);
							MemoryUtil.memPutFloat(va + 16 + 4L * k, Math.max(0, Math.min(65535, Math.round(u * 65536f))) / 65536f);
						}
					}
				}
			}
		} finally {
			this.arenaLock.readLock().unlock();
		}
		int runCount = 0;
		for (int b = 0; b < BUCKETS; b++) runCount += (counts[b] + RUN - 1) / RUN;
		int[] runs = new int[runCount * RUN_INTS];
		int r = 0;
		for (int b = 0; b < BUCKETS; b++) {
			for (int first = firsts[b], end = firsts[b] + counts[b]; first < end; first += RUN) {
				int c = Math.min(RUN, end - first);
				float bound = (b & 1) == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
				for (int i = first; i < first + c; i++) bound = (b & 1) == 0 ? Math.min(bound, plane[order[i]]) : Math.max(bound, plane[order[i]]);
				int fixed = b == 6 ? 0 : (b & 1) == 0 ? (int) Math.floor(bound * 256) : (int) Math.ceil(bound * 256);
				float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
				for (int i = first; i < first + c; i++) {
					long qa = src + (long) order[i] * QUAD_BYTES;
					for (int v = 0; v < 4; v++) {
						long va = qa + (long) v * VERTEX_BYTES;
						float x = MemoryUtil.memGetFloat(va), y = MemoryUtil.memGetFloat(va + 4), z = MemoryUtil.memGetFloat(va + 8);
						minX = Math.min(minX, x);
						minY = Math.min(minY, y);
						minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x);
						maxY = Math.max(maxY, y);
						maxZ = Math.max(maxZ, z);
					}
				}
				runs[r++] = first;
				runs[r++] = c;
				runs[r++] = b;
				runs[r++] = fixed;
				runs[r++] = box(minX, true) | box(minY, true) << 8 | box(minZ, true) << 16;
				runs[r++] = box(maxX, false) | box(maxY, false) << 8 | box(maxZ, false) << 16;
			}
		}
		int mat = OwnMaterials.ON ? this.storeMaterials(src, n, order, bucket) : -1;
		Layer stored = new Layer(start, n, runs, mat);
		if (OwnQrec.ON && COMPACT && n > 0) {
			// -Dmcopt.own.mesh.qrec: solid / cutout are records everywhere (one quad id space); this layer's 64-byte quads stay as its
			// stubs' copies (no renderer hint here: uniform / grey colours become records, the rest stubs)
			int recUnits = (int) (((long) n * OwnQrec.BYTES + ARENA_QUAD - 1) / ARENA_QUAD) + 4;
			int unit = this.allocLocked(recUnits);
			int aligned = (unit + 4) / 5 * 5;
			try {
				long r0 = this.arenaAddress + (long) aligned * ARENA_QUAD, q0 = this.arenaAddress + (long) start * ARENA_QUAD;
				for (int i = 0; i < n; i++) {
					long rec = r0 + (long) i * OwnQrec.BYTES;
					if (!OwnQrec.write(rec, q0 + (long) i * ARENA_QUAD, false, 0, 0)) OwnQrec.stub(rec, start + i);
				}
				if (OwnQrec.CHECK) OwnQrec.check(r0, q0, n, this.arenaAddress);
			} finally {
				this.arenaLock.readLock().unlock();
			}
			OwnQrec.layer(true);
			stored = new Layer(aligned / 5 * 8, n, runs, mat, null, unit, recUnits, start, n);
		}
		this.meshes.computeIfAbsent(mesh, k -> new OwnMesh()).layers[layer == ChunkSectionLayer.SOLID ? 0 : 1] = stored;
		if (STATS) {
			STORES.increment();
			STORE_NS.add(System.nanoTime() - t0);
		}
	}

	/**
	 * Native shading (OwnMaterials): a byte per quad in sorted order (the class of the block the quad belongs to, and bits 4-7: corner
	 * v in the upper half of the quad) into arena units of their own; returns the first unit. The block: the quad's centre moved
	 * 1/32 block against its facing (a face on a block boundary belongs to the block behind it), clamped into the section.
	 */
	private int storeMaterials(long src, int n, int[] order, byte[] bucket) {
		byte[] grid = OwnMaterials.grid();
		int units = Math.max(1, (n + ARENA_QUAD - 1) / ARENA_QUAD), start = this.allocLocked(units);
		try {
			long dst = this.arenaAddress + (long) start * ARENA_QUAD;
			for (int i = 0; i < n; i++) {
				int q = order[i];
				long a = src + (long) q * QUAD_BYTES;
				float cx = 0, cy = 0, cz = 0, ylo = Float.POSITIVE_INFINITY, yhi = Float.NEGATIVE_INFINITY;
				for (int v = 0; v < 4; v++) {
					long va = a + (long) v * VERTEX_BYTES;
					float y = MemoryUtil.memGetFloat(va + 4);
					cx += MemoryUtil.memGetFloat(va);
					cy += y;
					cz += MemoryUtil.memGetFloat(va + 8);
					ylo = Math.min(ylo, y);
					yhi = Math.max(yhi, y);
				}
				cx *= 0.25F;
				cy *= 0.25F;
				cz *= 0.25F;
				final float e = 1.0F / 32.0F;
				switch (bucket[q]) {
					case 0 -> cx -= e;
					case 1 -> cx += e;
					case 2 -> cy -= e;
					case 3 -> cy += e;
					case 4 -> cz -= e;
					case 5 -> cz += e;
					default -> {
					}
				}
				int bx = Math.clamp((int) Math.floor(cx), 0, 15), by = Math.clamp((int) Math.floor(cy), 0, 15), bz = Math.clamp((int) Math.floor(cz), 0, 15);
				int m = grid[by << 8 | bz << 4 | bx] & 15;
				float mid = (ylo + yhi) * 0.5F + 1e-4F;
				for (int v = 0; v < 4; v++) if (MemoryUtil.memGetFloat(a + (long) v * VERTEX_BYTES + 4) > mid) m |= 16 << v;
				MemoryUtil.memPutByte(dst + i, (byte) m);
			}
		} finally {
			this.arenaLock.readLock().unlock();
		}
		return start;
	}

	/**
	 * -Dmcopt.own.mesh: a worker's solid or cutout layer from our own mesher (OwnQuads), already compact and bucketed: one copy
	 * into the arena. Same bytes and draw units as store() makes of vanilla's vertices for the same quads.
	 */
	public void storeQuads(Object mesh, ChunkSectionLayer layer, OwnQuads.Layer quads) {
		long t0 = STATS ? System.nanoTime() : 0;
		int n = quads.total();
		int start, qrecUnit = -1, qrecUnits = 0, raw = -1;
		if (OwnQrec.ON && n > 0) {
			// -Dmcopt.own.mesh.qrec: 40-byte records from a unit that is a multiple of 5 (320 bytes = 8 records), so the first
			// record's index is whole (4 units of slack for that); then the stubs' 64-byte quads (or with qrecVerify, all n)
			int recUnits = (int) (((long) n * OwnQrec.BYTES + ARENA_QUAD - 1) / ARENA_QUAD);
			int rawCount = OwnQrec.VERIFY ? n : quads.stubs();
			qrecUnits = recUnits + 4 + rawCount;
			qrecUnit = this.allocLocked(qrecUnits);
			int aligned = (qrecUnit + 4) / 5 * 5;
			start = aligned / 5 * 8;
			int rawAt = aligned + recUnits;
			try {
				if (OwnQrec.VERIFY) {
					quads.copyTo(this.arenaAddress + (long) rawAt * ARENA_QUAD);
					raw = rawAt;
				}
				quads.copyRecsTo(this.arenaAddress + (long) aligned * ARENA_QUAD, raw, this.arenaAddress + (long) rawAt * ARENA_QUAD, rawAt, this.arenaAddress);
			} finally {
				this.arenaLock.readLock().unlock();
			}
			OwnQrec.layer(false);
		} else {
			start = this.allocLocked(n);
			try {
				quads.copyTo(this.arenaAddress + (long) start * ARENA_QUAD);
			} finally {
				this.arenaLock.readLock().unlock();
			}
		}
		int mat = -1;
		if (OwnMaterials.ON) {
			// as storeMaterials: a byte per quad in arena order, in units of their own (-Dmcopt.own.mesh.shade)
			int units = Math.max(1, (n + ARENA_QUAD - 1) / ARENA_QUAD);
			mat = this.allocLocked(units);
			try {
				quads.writeMaterials(this.arenaAddress + (long) mat * ARENA_QUAD, OwnMaterials.grid());
			} finally {
				this.arenaLock.readLock().unlock();
			}
		}
		int[] runs = quads.runs(RUN), subs = null;
		if (OwnQuads.SUBBOX) {
			int[][] boxes = quads.arenaBoxes();
			subs = OwnQuads.subboxes(runs, boxes[0], boxes[1], null);
		}
		this.meshes.computeIfAbsent(mesh, k -> new OwnMesh()).layers[layer == ChunkSectionLayer.SOLID ? 0 : 1] = new Layer(start, n, runs, mat, subs, qrecUnit, qrecUnits, raw, 0);
		if (STATS) {
			STORES.increment();
			STORE_NS.add(System.nanoTime() - t0);
		}
	}

	/** -Dmcopt.own.mesh.tieGroups: a worker's groups for mesh (OwnTieGroups.build; null: none), published with it. */
	public void storeTieGroups(Object mesh, OwnTieGroups.@Nullable Result r) {
		if (!OwnTieGroups.ON || r == null) return;
		OwnMesh m = this.meshes.computeIfAbsent(mesh, k -> new OwnMesh());
		m.groups = r.groups();
		m.edges = r.edges();
	}

	/** One of vanilla's BLOCK vertices at s as the compact vertex at d (terrain.metal CVtx). */
	static void compact(long s, long d) {
		MemoryUtil.memPutShort(d, (short) fixed(MemoryUtil.memGetFloat(s)));
		MemoryUtil.memPutShort(d + 2, (short) fixed(MemoryUtil.memGetFloat(s + 4)));
		MemoryUtil.memPutShort(d + 4, (short) fixed(MemoryUtil.memGetFloat(s + 8)));
		MemoryUtil.memPutByte(d + 6, (byte) Math.max(0, Math.min(255, MemoryUtil.memGetShort(s + 24))));
		MemoryUtil.memPutByte(d + 7, (byte) Math.max(0, Math.min(255, MemoryUtil.memGetShort(s + 26))));
		MemoryUtil.memPutInt(d + 8, MemoryUtil.memGetInt(s + 12));
		MemoryUtil.memPutShort(d + 12, (short) Math.max(0, Math.min(65535, Math.round(MemoryUtil.memGetFloat(s + 16) * 65536f))));
		MemoryUtil.memPutShort(d + 14, (short) Math.max(0, Math.min(65535, Math.round(MemoryUtil.memGetFloat(s + 20) * 65536f))));
	}

	private static int fixed(float p) {
		if (OwnPosTable.ON) return OwnPosTable.encode(p);  // (-Dmcopt.own.mesh.exactPos: off-grid coordinates by table code)
		return Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
	}

	/** A bound coordinate in whole blocks, section-relative + 16, rounded outwards, clamped to a byte. */
	static int box(float v, boolean low) {
		int b = (low ? (int) Math.floor(v) : (int) Math.ceil(v)) + 16;
		return Math.max(0, Math.min(255, b));
	}

	/**
	 * The facing bucket of the quad at a (0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z: all four corners on one axis plane, wound to face
	 * that way; else 6) and its plane coordinate (section-relative) in plane[q]. The winding decides it, as for the rasterizer's
	 * back-face cull: a quad in a + bucket can only be front-facing from the + side of its plane.
	 */
	static int facing(long a, float[] plane, int q) {
		float x0 = MemoryUtil.memGetFloat(a), y0 = MemoryUtil.memGetFloat(a + 4), z0 = MemoryUtil.memGetFloat(a + 8);
		float x1 = MemoryUtil.memGetFloat(a + VERTEX_BYTES), y1 = MemoryUtil.memGetFloat(a + VERTEX_BYTES + 4), z1 = MemoryUtil.memGetFloat(a + VERTEX_BYTES + 8);
		float x2 = MemoryUtil.memGetFloat(a + 2 * VERTEX_BYTES), y2 = MemoryUtil.memGetFloat(a + 2 * VERTEX_BYTES + 4), z2 = MemoryUtil.memGetFloat(a + 2 * VERTEX_BYTES + 8);
		float x3 = MemoryUtil.memGetFloat(a + 3 * VERTEX_BYTES), y3 = MemoryUtil.memGetFloat(a + 3 * VERTEX_BYTES + 4), z3 = MemoryUtil.memGetFloat(a + 3 * VERTEX_BYTES + 8);
		float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0, vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		// the second triangle (2, 3, 0) must agree: a bent or folded quad goes to the any-facing bucket
		float wx = x3 - x2, wy = y3 - y2, wz = z3 - z2, tx = x0 - x2, ty = y0 - y2, tz = z0 - z2;
		float mx = wy * tz - wz * ty, my = wz * tx - wx * tz, mz = wx * ty - wy * tx;
		if (x0 == x1 && x1 == x2 && x2 == x3 && ny == 0 && nz == 0 && my == 0 && mz == 0 && nx != 0 && Math.signum(nx) == Math.signum(mx)) {
			plane[q] = x0;
			return nx > 0 ? 0 : 1;
		}
		if (y0 == y1 && y1 == y2 && y2 == y3 && nx == 0 && nz == 0 && mx == 0 && mz == 0 && ny != 0 && Math.signum(ny) == Math.signum(my)) {
			plane[q] = y0;
			return ny > 0 ? 2 : 3;
		}
		if (z0 == z1 && z1 == z2 && z2 == z3 && nx == 0 && ny == 0 && mx == 0 && my == 0 && nz != 0 && Math.signum(nz) == Math.signum(mz)) {
			plane[q] = z0;
			return nz > 0 ? 4 : 5;
		}
		plane[q] = 0;
		return 6;
	}

	/** n arena units; the arena's read lock is held on return (the caller writes, then unlocks). */
	private int allocLocked(int n) {
		while (true) {
			this.arenaLock.readLock().lock();
			int start;
			synchronized (this.quadAlloc) {
				start = this.quadAlloc.alloc(n);
			}
			if (start >= 0) return start;
			this.arenaLock.readLock().unlock();
			this.growArena(n);
		}
	}

	/** Units of the arena holding a quad order of n quads (u32 each). */
	private static int permUnits(int n) {
		return Math.max(1, (n * 4 + ARENA_QUAD - 1) / ARENA_QUAD);
	}

	/** Vanilla's sorted index buffer (6 indices a quad, the quad's first vertex first) as a quad order into the arena at unit start. */
	private void writeOrder(ByteBuffer indices, int n, int start) {
		int bytes = indices.remaining(), size = bytes / (n * 6);
		long src = MemoryUtil.memAddress(indices), dst = this.arenaAddress + (long) start * ARENA_QUAD;
		for (int q = 0; q < n; q++) {
			int first = size == 2 ? Short.toUnsignedInt(MemoryUtil.memGetShort(src + q * 12L)) : MemoryUtil.memGetInt(src + q * 24L);
			MemoryUtil.memPutInt(dst + q * 4L, first >> 2);
		}
	}

	/** A meshing worker's translucent layer: vertices in compile order, and the compile's sorted quad order. */
	public void storeTranslucent(Object mesh, ByteBuffer vertices, ByteBuffer indices) {
		int n = vertices.remaining() / QUAD_BYTES;
		if (TSTAT) {
			TS_STORES.increment();
			TS_STORE_QUADS.add(n);
		}
		long src = MemoryUtil.memAddress(vertices);
		int start = this.allocLocked(n);
		try {
			long dst = this.arenaAddress + (long) start * ARENA_QUAD;
			if (COMPACT) {
				for (int q = 0; q < n; q++) for (int v = 0; v < 4; v++) compact(src + (long) q * QUAD_BYTES + (long) v * VERTEX_BYTES, dst + (long) q * ARENA_QUAD + v * 16L);
			} else {
				MemoryUtil.memCopy(src, dst, (long) n * QUAD_BYTES);
			}
		} finally {
			this.arenaLock.readLock().unlock();
		}
		int units = permUnits(n), perm = this.allocLocked(units);
		try {
			this.writeOrder(indices, n, perm);
		} finally {
			this.arenaLock.readLock().unlock();
		}
		OwnMesh m = this.meshes.computeIfAbsent(mesh, k -> new OwnMesh());
		m.layers[2] = new Layer(start, n, new int[0], -1);
		if (TFRUSTUM || TFR_PROBE) {
			float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int v = 0, nv = n * 4; v < nv; v++) {
				long a = src + (long) v * VERTEX_BYTES;
				for (int c = 0; c < 3; c++) {
					float x = MemoryUtil.memGetFloat(a + 4L * c);
					if (x < box[c]) box[c] = x;
					if (x > box[3 + c]) box[3 + c] = x;
				}
			}
			m.tBox = box;
		}
		m.permStart = perm;
		m.permUnits = units;
		if (TS_BUILD) {
			m.sortStart = this.sortedCopy(start, perm, n);
			m.sortUnits = n;
		}
		if (TCULL) {
			// (the quads' facing and plane from vanilla's floats, as the opaque buckets: the winding of both triangles decides)
			byte[] face = new byte[n];
			float[] plane = new float[n];
			for (int q = 0; q < n; q++) face[q] = (byte) facing(src + (long) q * QUAD_BYTES, plane, q);
			m.tPlane = plane;
			m.tFace = face;
		}
	}

	/** A resort of a translucent layer (vanilla's ResortTransparencyTask): the new quad order replaces the old one. */
	public void resorted(Object mesh, ByteBuffer indices) {
		long t0 = TSTAT ? System.nanoTime() : 0;
		OwnMesh m = this.meshes.get(mesh);
		Layer l = m == null ? null : m.layers[2];
		if (l == null) return;
		if (TSTAT) {
			TS_RESORTS.increment();
			TS_RESORT_QUADS.add(l.quadCount);
		}
		int units = permUnits(l.quadCount), perm = this.allocLocked(units);
		try {
			this.writeOrder(indices, l.quadCount, perm);
		} finally {
			this.arenaLock.readLock().unlock();
		}
		int sort = TS_BUILD ? this.sortedCopy(l.quadStart, perm, l.quadCount) : -1;
		this.events.add(new Resort(mesh, perm, units, sort, sort < 0 ? 0 : l.quadCount));
		if (TSTAT) TS_RESORT_NS.add(System.nanoTime() - t0);
	}

	private void resort(Resort r) {
		OwnMesh m = this.meshes.get(r.mesh);
		if (m == null) {
			this.freeUnitsLater(r.permStart, r.permUnits);
			if (r.sortStart >= 0) this.freeUnitsLater(r.sortStart, r.sortUnits);
			return;
		}
		int oldStart = m.permStart, oldUnits = m.permUnits;
		m.permStart = r.permStart;
		m.permUnits = r.permUnits;
		if (r.sortStart >= 0) {
			int oldSort = m.sortStart, oldSortUnits = m.sortUnits;
			m.sortStart = r.sortStart;
			m.sortUnits = r.sortUnits;
			if (m.sRecStart >= 0) {
				long a = this.recAddress + (long) m.sRecStart * REC_BYTES;
				for (int i = 0; i < m.tRecCount; i++) MemoryUtil.memPutInt(a + (long) i * REC_BYTES, m.sortStart + i * RUN);
			}
			if (oldSort >= 0) {
				this.freeUnitsLater(oldSort, Math.max(1, oldSortUnits));
				TS_SORTED_BYTES.addAndGet(-(long) oldSortUnits * ARENA_QUAD);
			}
		}
		if (m.recStart >= 0) {
			long a = this.recAddress + (long) m.tRecStart * REC_BYTES;
			for (int i = 0; i < m.tRecCount; i++) MemoryUtil.memPutInt(a + (long) i * REC_BYTES, m.permStart * (ARENA_QUAD / 4) + i * RUN);
		}
		if (oldStart >= 0) this.freeUnitsLater(oldStart, oldUnits);
	}

	private void freeUnitsLater(int start, int units) {
		this.later(() -> {
			synchronized (this.quadAlloc) {
				this.quadAlloc.free(start, units);
			}
		});
	}

	private void growArena(int need) {
		long growthStart = OwnGrowth.begin();
		int oldUnits = 0, newUnits = 0;
		String growthKind = "arena-retry";
		this.arenaLock.writeLock().lock();
		try {
			int cap;
			synchronized (this.quadAlloc) {
				int s = this.quadAlloc.alloc(need);
				if (s >= 0) {
					this.quadAlloc.free(s, need);  // someone grew it already
					return;
				}
				cap = this.quadAlloc.capacity();
			}
			long newCap = Math.max((long) cap * 2, (long) cap + need * 2L);
			oldUnits = cap;
			newUnits = (int) newCap;
			if (newCap * ARENA_QUAD > (3L << 30)) throw new IllegalStateException("mcopt-own: arena over 3 GB");
			if (newCap <= this.arenaPhysicalUnits) {
				// Same logical free-tail extension and indices as ordinary growth, but the backing store already fits.
				synchronized (this.quadAlloc) {
					this.quadAlloc.grow((int) newCap);
				}
				growthKind = "arena-reserved";
				return;
			}
			growthKind = "arena-copy";
			long buffer = OwnNative.buffer(this.ctx, newCap * ARENA_QUAD, true);
			long address = OwnNative.contents(buffer);
			MemoryUtil.memCopy(this.arenaAddress, address, (long) cap * ARENA_QUAD);
			this.releaseQueue.add(this.arenaBuffer);
			this.arenaAddress = address;
			this.arenaBuffer = buffer;
			this.arenaPhysicalUnits = (int) newCap;
			synchronized (this.quadAlloc) {
				this.quadAlloc.grow((int) newCap);
			}
			System.out.println("mcopt-own: arena grown to " + newCap * ARENA_QUAD / (1 << 20) + " MB");
		} finally {
			this.arenaLock.writeLock().unlock();
			OwnGrowth.end(growthKind, growthStart, (long) oldUnits * ARENA_QUAD, (long) newUnits * ARENA_QUAD, (long) this.arenaPhysicalUnits * ARENA_QUAD);
		}
	}

	/** A section's mesh became m (RenderSection.setSectionMesh). */
	public void published(int slot, Object mesh, int x, int y, int z, long uploadedMs) {
		this.events.add(new Publish(slot, mesh, x, y, z, uploadedMs));
	}

	/** A section lost its mesh (RenderSection.reset). */
	public void cleared(int slot) {
		this.events.add(new Clear(slot));
	}

	/** A mesh is gone (RenderSection.releaseSectionMesh). */
	public void released(Object mesh) {
		if (this.meshes.containsKey(mesh)) this.events.add(new Release(mesh));
	}

	/**
	 * The section dispatcher is gone (new level, reload): forget every section, now. Its releases used to be queued until our next
	 * frame, and the title and loading screens draw none, so each new world stored its sections into an arena still holding the whole
	 * previous world (the arena only grows: with tsorted's copies the overlap took it a doubling further, kept across worlds). This
	 * runs between frames (the dispatcher is disposed outside the render): once the GPU has finished every submitted frame nothing can
	 * read the old units, so the pending events, every mesh's release and all deferred frees are applied here.
	 */
	public void disposeAll() {
		long usedBefore, capBefore;
		synchronized (this.quadAlloc) {
			usedBefore = this.quadAlloc.used();
			capBefore = this.quadAlloc.capacity();
		}
		int meshesBefore = this.meshes.size();
		MetalBridge.waitIdle(this.encoder);
		this.processEvents();
		for (Object m : new java.util.ArrayList<>(this.meshes.keySet())) this.release(m);
		this.runDeferredNow();
		// (the lists back to their first size: a level's high-water mark isn't kept for the next; they grow again as needed)
		if (this.listCap > 1 << 15) this.growLists(1 << 15);
		this.runDeferredNow();
		boolean renewed = this.renewArena();
		long used, cap, recUsed, recCap;
		synchronized (this.quadAlloc) {
			used = this.quadAlloc.used();
			cap = this.quadAlloc.capacity();
		}
		recUsed = this.recAlloc.used();
		recCap = this.recAlloc.capacity();
		System.out.printf("mcopt-own reset: %d meshes released; arena %.1f -> %.1f of %.0f MB used (was %.0f)" + (renewed ? ", pages renewed" : ", kept") + ", records %d of %d, lists %d, sorted copies %.1f MB, orphans swept so far %d; own Metal buffers %d, %.1f MB%n",
			meshesBefore, usedBefore * (double) ARENA_QUAD / 1048576, used * (double) ARENA_QUAD / 1048576, cap * (double) ARENA_QUAD / 1048576,
			capBefore * (double) ARENA_QUAD / 1048576, recUsed, recCap, this.listCap, TS_SORTED_BYTES.get() / 1048576.0, this.orphansSwept,
			OwnNative.liveBuffers(), OwnNative.liveBufferBytes() / 1048576.0);
	}

	/**
	 * disposeAll, once every unit is free (GPU idle): the arena as a fresh buffer of the same size. Freed units keep their pages
	 * resident (the allocator reuses offsets, the system never gets them back), so a level's high-water mark of touched arena pages
	 * stayed in the process across every later world: tsorted's sorted copies (and orders) spread a level over ~30-50 MB more of it.
	 * A new shared buffer's pages are only made resident as the next level's sections are written. False (kept) if any unit is live.
	 */
	private boolean renewArena() {
		this.arenaLock.writeLock().lock();
		try {
			synchronized (this.quadAlloc) {
				if (this.quadAlloc.used() > (OwnPosTable.ON ? OwnPosTable.UNITS : 0)) return false;
			}
			long buffer = OwnNative.buffer(this.ctx, (long) this.arenaPhysicalUnits * ARENA_QUAD, true);
			long old = this.arenaBuffer;
			this.arenaAddress = OwnNative.contents(buffer);  // (a new address: exactPos's table is written again, posTableArena)
			this.arenaBuffer = buffer;
			OwnNative.release(old);
			return true;
		} finally {
			this.arenaLock.writeLock().unlock();
		}
	}

	/** The queued events (publish, clear, release, resort), in order. Render thread. */
	private void processEvents() {
		for (Object e; (e = this.events.poll()) != null; ) {
			switch (e) {
				case Publish p -> {
					if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.event(mcopt.metal.FrameLog.PUBLISH);
					this.publish(p);
				}
				case Clear c -> {
					if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.event(mcopt.metal.FrameLog.CLEAR);
					this.setSlot(c.slot, null, 0, 0, 0, 0);
					this.compiled[c.slot] = 0;
					this.seamSerial++;
				}
				case Release r -> {
					if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.event(mcopt.metal.FrameLog.RELEASE);
					this.release(r.mesh);
				}
				case Resort r -> {
					if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.event(mcopt.metal.FrameLog.RESORT);
					this.resort(r);
				}
				default -> throw new IllegalStateException();
			}
		}
	}

	/** Every deferred action (and native buffer release) now: only when the GPU is idle (disposeAll). */
	private void runDeferredNow() {
		for (Long b; (b = this.releaseQueue.poll()) != null; ) OwnNative.release(b);
		while (!this.deferred.isEmpty()) ((Runnable) this.deferred.pollFirst()[1]).run();
	}

	private long orphansSwept, orphanSweepAt;

	/**
	 * Meshes stored but never published within 10 s (compile results of a dispatcher that is gone, or superseded before vanilla set
	 * them): vanilla never releases them, so they'd hold their arena units forever. Every 5 s, render thread.
	 */
	private void sweepOrphans() {
		long now = System.nanoTime();
		if (now < this.orphanSweepAt) return;
		this.orphanSweepAt = now + 5_000_000_000L;
		for (java.util.Map.Entry<Object, OwnMesh> e : this.meshes.entrySet()) {
			OwnMesh m = e.getValue();
			if (!m.published && m.slot < 0 && now - m.storedNs > 10_000_000_000L) {
				this.release(e.getKey());
				this.orphansSwept++;
			}
		}
	}

	// ---- render thread: the frame ----

	private void beginFrame() {
		if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.begin(mcopt.metal.FrameLog.OWN_BEGIN);
		this.beginFrame0();
		if (mcopt.metal.FrameLog.ON) mcopt.metal.FrameLog.end(mcopt.metal.FrameLog.OWN_BEGIN);
	}

	private void beginFrame0() {
		this.frame++;
		for (Long b; (b = this.releaseQueue.poll()) != null; ) {
			long handle = b;
			this.later(() -> OwnNative.release(handle));
		}
		while (!this.deferred.isEmpty() && (long) this.deferred.peekFirst()[0] <= this.frame - FRAMES) ((Runnable) this.deferred.pollFirst()[1]).run();
		int k = (int) (this.frame % FRAMES);
		this.dirtyCount[k] = 0;
		this.processEvents();
		this.sweepOrphans();
		if (OwnPosTable.ON) {
			// exactPos: table entries the published quads use, into the arena (after the events: each was queued after its entries)
			this.arenaLock.readLock().lock();
			try {
				if (this.posTableArena != this.arenaAddress) this.posTableSynced = 0;
				this.posTableSynced = OwnPosTable.sync(this.arenaAddress, this.posTableSynced);
				this.posTableArena = this.arenaAddress;
			} finally {
				this.arenaLock.readLock().unlock();
			}
		}
		// bring this frame's copy of the section table up to date: the slots changed in the frames since it was last used
		long table = this.tableAddresses[k];
		for (int f = 0; f < FRAMES; f++) {
			int[] d = this.dirty[f];
			for (int i = 0, n = this.dirtyCount[f]; i < n; i++) {
				int slot = d[i];
				MemoryUtil.memCopy(this.master + (long) slot * SECTION_BYTES, table + (long) slot * SECTION_BYTES, SECTION_BYTES);
			}
		}
		int live = (int) this.recAlloc.used();
		// (-Dmcopt.own.frag.listGrowFault, tieClose test: this growth skipped, so the cull meets a real shortfall, listCap below the
		// records in use; tieCloseFallback must grow the lists before encoding, counted as 'lists grown at the cull')
		if (live > this.listCap && !LIST_GROW_FAULT) this.growLists(Integer.highestOneBit(live) * 2);
		this.pickRing();
		if (OwnTieGroups.ON) this.tieGroupLog();
	}

	private long tgLogAt;

	/** -Dmcopt.own.mesh.tieGroups: every 5 s, the meshes grouped since the start (worker side) and what the buffers hold now. */
	private void tieGroupLog() {
		long now = System.nanoTime();
		if (now < this.tgLogAt) return;
		this.tgLogAt = now + 5_000_000_000L;
		long meshes = OwnTieGroups.MESHES.sum();
		if (meshes > 0) System.out.printf("mcopt-own mesh tieGroups stages: keys %.1f (runs %.1f), edges %.1f, groups %.1f (exact %.1f, near %.1f) us a mesh; reaching quads built: overhangs %d, axis %d, other %d (cutout %d)%n",
			OwnTieGroups.KEY_NS.sum() / 1e3 / meshes, OwnTieGroups.RUNS_NS.sum() / 1e3 / meshes, OwnTieGroups.EDGE_NS.sum() / 1e3 / meshes, OwnTieGroups.GROUP_NS.sum() / 1e3 / meshes,
			OwnTieGroups.EXACTX_NS.sum() / 1e3 / meshes, OwnTieGroups.NEARX_NS.sum() / 1e3 / meshes, OwnTieGroups.REACH_OVER.sum(),
			OwnTieGroups.REACH_AXIS.sum(), OwnTieGroups.REACH_ANY.sum(), OwnTieGroups.REACH_CUT.sum());
		System.out.printf("mcopt-own mesh tieGroups: %d meshes, %d opaque quads, %.1f us a mesh; groups %d (X solid/cutout %d, S same-layer %d), members %d, S members %d;"
				+ " table %d words in use, S bits %d words; dropped %d; verify %d members, %d mismatches%n",
			meshes, OwnTieGroups.QUADS.sum(), meshes == 0 ? 0.0 : OwnTieGroups.BUILD_NS.sum() / 1000.0 / meshes, OwnTieGroups.GROUPS.sum(),
			OwnTieGroups.X_GROUPS.sum(), OwnTieGroups.S_GROUPS.sum(), OwnTieGroups.MEMBERS.sum(), OwnTieGroups.S_MEMBERS.sum(),
			this.grpAlloc == null ? 0 : this.grpAlloc.used(), this.sBitsWords, this.grpDropped, this.grpChecked, this.grpBad);
		System.out.printf("mcopt-own mesh tieGroups live: %d meshes with groups; groups %d (X %d, S %d), members %d, S members %d%n", this.grpMeshes,
			this.grpLive[0], this.grpLive[1], this.grpLive[2], this.grpLive[4], this.grpLive[3]);
		long frames = this.frame - this.tgLogFrame, dirNs = this.tieDir.ns - this.tgLogDirNs;
		this.tgLogFrame = this.frame;
		this.tgLogDirNs = this.tieDir.ns;
		System.out.printf("mcopt-own mesh tieGroups fallback: %s; cross-section candidates %d, unknown sections %d; directory %d edge quads (%d recorded),"
				+ " %.2f us an entry (add/remove), render thread %.2f us a frame since the last line; exact certifications %d%n", this.tieFallback() ? "YES" : "no",
			this.tieDir.pairs, this.tieUnknown, this.tieDir.live(), OwnTieGroups.EDGES.sum(), this.tieDir.ops == 0 ? 0.0 : this.tieDir.ns / 1000.0 / this.tieDir.ops,
			frames <= 0 ? 0.0 : dirNs / 1000.0 / frames, OwnTieGroups.EXACT.sum());
	}

	private long tgLogFrame, tgLogDirNs;

	void later(Runnable r) {
		this.deferred.addLast(new Object[] {this.frame, r});
	}

	private void publish(Publish p) {
		OwnMesh m = this.meshes.get(p.mesh);
		if (m != null) m.published = true;
		if (m != null && m.recStart < 0) this.buildRecords(m, p.slot);
		if (OwnTieGroups.ON && m != null && !m.grpDone) this.publishGroups(m);
		if (QUAD_OWNERS && m != null) {
			for (int li = 0; li < 2; li++) {
				Layer l = m.layers[li];
				if (l == null) continue;
				this.ensureQuadOwner(l.quadStart + l.quadCount);
				for (int q = 0; q < l.quadCount; q++) MemoryUtil.memPutInt(this.quadOwnerAddress + (long) (l.quadStart + q) * 4, p.slot);
				if (QUADS_BOX) for (int q = 0; q < l.quadCount; q++) this.putBox(l.quadStart + q);
			}
		}
		this.setSlot(p.slot, m, p.x, p.y, p.z, (int) (p.uploadedMs - this.baseMs));
		this.compiled[p.slot] = 1;
		this.seamSerial++;
	}

	/** Render thread (after this frame's drawOpaque): whether slot holds a compile result of the section at block origin x, y, z. */
	public boolean compiledAt(int slot, int x, int y, int z) {
		if (slot < 0 || slot >= this.slotCap || this.compiled[slot] == 0) return false;
		long a = this.master + (long) slot * SECTION_BYTES;
		return MemoryUtil.memGetInt(a) == x && MemoryUtil.memGetInt(a + 4) == y && MemoryUtil.memGetInt(a + 8) == z;
	}

	public int seamSerial() {
		return this.seamSerial;
	}

	/** OwnSeam: this frame's number (bumped by drawOpaque) and the slots whose state changed in it (until the next drawOpaque). */
	public long frameNumber() {
		return this.frame;
	}

	public int changedCount() {
		return this.dirtyCount[(int) (this.frame % FRAMES)];
	}

	public int[] changed() {
		return this.dirty[(int) (this.frame % FRAMES)];
	}

	private void buildRecords(OwnMesh m, int slot) {
		int n = 0;
		for (int li = 0; li < 2; li++) if (m.layers[li] != null) n += m.layers[li].runs.length / RUN_INTS;
		Layer t = m.layers[2];
		m.tRecCount = t == null ? 0 : (t.quadCount + RUN - 1) / RUN;
		m.tRecStart = n;
		n += m.tRecCount;
		boolean sorted = TS_BUILD && m.tRecCount > 0 && m.sortStart >= 0;
		if (sorted) n += m.tRecCount;
		m.recCount = n;
		if (n == 0) {
			m.recStart = 0;
			return;
		}
		int start = this.recAlloc.alloc(n);
		if (start < 0) {
			int cap = this.recAlloc.capacity(), newCap = Math.max(cap * 2, cap + n * 2);
			long growthStart = OwnGrowth.begin();
			long buffer = OwnNative.buffer(this.ctx, (long) newCap * REC_BYTES, true);
			long address = OwnNative.contents(buffer);
			MemoryUtil.memCopy(this.recAddress, address, (long) cap * REC_BYTES);
			long old = this.recBuffer;
			this.later(() -> OwnNative.release(old));
			this.recBuffer = buffer;
			this.recAddress = address;
			this.recAlloc.grow(newCap);
			this.growVis(newCap);
			OwnGrowth.end("records-build", growthStart, (long) cap * REC_BYTES, (long) newCap * REC_BYTES, (long) newCap * REC_BYTES);
			start = this.recAlloc.alloc(n);
		}
		m.recStart = start;
		long a = this.recAddress + (long) start * REC_BYTES;
		for (int li = 0; li < 2; li++) {
			Layer l = m.layers[li];
			if (l == null) continue;
			int[] bucketMax = null;
			if (OwnQuads.BUCKET_CLASS) {
				// (mesher bucketClass: the largest unit of each facing bucket of this layer, for the cull's size class)
				bucketMax = new int[8];
				for (int r = 0; r < l.runs.length; r += RUN_INTS) bucketMax[l.runs[r + 2]] = Math.max(bucketMax[l.runs[r + 2]], l.runs[r + 1] - 1);
			}
			if (PADSTAT) padStat(l, bucketMax);
			int[] hint = null;
			if (OwnQuads.BUCKET_SUFFIX) {
				// (bucketClass=suffix: per unit, the largest count - 1 of it and the later units of its bucket)
				hint = new int[l.runs.length / RUN_INTS];
				int[] run = new int[8];
				for (int r = l.runs.length - RUN_INTS; r >= 0; r -= RUN_INTS) {
					int b = l.runs[r + 2];
					run[b] = Math.max(run[b], l.runs[r + 1] - 1);
					hint[r / RUN_INTS] = run[b];
				}
			}
			for (int r = 0; r < l.runs.length; r += RUN_INTS, a += REC_BYTES) {
				MemoryUtil.memPutInt(a, l.quadStart + l.runs[r]);
				MemoryUtil.memPutInt(a + 4, slot);
				MemoryUtil.memPutInt(a + 8, (l.runs[r + 1] - 1) | l.runs[r + 2] << 6 | li << 9 | (hint != null ? hint[r / RUN_INTS] << 11 : bucketMax == null ? 0 : bucketMax[l.runs[r + 2]] << 11)
					| (l.qrecUnit >= 0 ? OwnQrec.META : 0));
				MemoryUtil.memPutInt(a + 12, l.runs[r + 3]);
				// -Dmcopt.own.mesh.subbox: sub-box nibbles in lo / hi bits 24-31 and +28 (zero without: the union box only)
				int s0 = l.subs == null ? 0 : l.subs[2 * (r / RUN_INTS)], s1 = l.subs == null ? 0 : l.subs[2 * (r / RUN_INTS) + 1];
				MemoryUtil.memPutInt(a + 16, l.runs[r + 4] | (s0 & 255) << 24);
				MemoryUtil.memPutInt(a + 20, l.runs[r + 5] | (s0 >>> 8 & 255) << 24);
				MemoryUtil.memPutInt(a + 28, s1);
				// the unit's material bytes (OwnMaterials); with -Dmcopt.own.mesh.qrecVerify the 64-byte copy of its first quad
				MemoryUtil.memPutInt(a + 24, OwnQrec.VERIFY ? (l.raw < 0 ? -1 : l.raw + l.runs[r]) : l.matStart < 0 ? -1 : l.matStart * ARENA_QUAD + l.runs[r]);  // the unit's material bytes (OwnMaterials)
			}
		}
		m.tRecStart += start;
		for (int i = 0; i < m.tRecCount; i++, a += REC_BYTES) {
			int c = Math.min(RUN, t.quadCount - i * RUN);
			MemoryUtil.memPutInt(a, m.permStart * (ARENA_QUAD / 4) + i * RUN);
			MemoryUtil.memPutInt(a + 4, slot);
			MemoryUtil.memPutInt(a + 8, (c - 1) | 6 << 6 | 2 << 9);
			MemoryUtil.memPutInt(a + 12, t.quadStart);
			MemoryUtil.memPutInt(a + 16, 0);
			MemoryUtil.memPutInt(a + 20, 255 | 255 << 8 | 255 << 16);
			MemoryUtil.memPutInt(a + 24, -1);
			MemoryUtil.memPutInt(a + 28, TPROBE == 2 ? i * RUN : 0);  // (no sub-boxes; a reused slot may hold an opaque unit's; tprobe 2: the unit's offset in the mesh)
		}
		if (sorted) {
			m.sRecStart = m.tRecStart + m.tRecCount;
			writeSortedRecs(a, m.tRecCount, t.quadCount, m.sortStart, slot);
		}
	}

	/** TS_BUILD: units records of a sorted translucent copy at quad index first (64-byte quads), n quads, meta bit 18. */
	private static void writeSortedRecs(long a, int units, int n, int first, int slot) {
		for (int i = 0; i < units; i++, a += REC_BYTES) {
			int c = Math.min(RUN, n - i * RUN);
			MemoryUtil.memPutInt(a, first + i * RUN);
			MemoryUtil.memPutInt(a + 4, slot);
			MemoryUtil.memPutInt(a + 8, (c - 1) | 6 << 6 | 2 << 9 | 1 << 18);
			MemoryUtil.memPutInt(a + 12, 0);
			MemoryUtil.memPutInt(a + 16, 0);
			MemoryUtil.memPutInt(a + 20, 255 | 255 << 8 | 255 << 16);
			MemoryUtil.memPutInt(a + 24, -1);
			MemoryUtil.memPutInt(a + 28, 0);
		}
	}

	/**
	 * TS_BUILD: the n quads of the layer at quad index quadStart copied in the order at arena units perm (u32 entries) into new arena
	 * units; returns the first (also the copy's first quad index: 64-byte units).
	 */
	private int sortedCopy(int quadStart, int perm, int n) {
		return this.sortedCopy(quadStart, perm, n, n);
	}

	/** count entries of the order at perm, quad indices below layerQuads. */
	private int sortedCopy(int quadStart, int perm, int count, int layerQuads) {
		int n = count;
		int start = this.allocLocked(Math.max(1, n));
		try {
			long src = this.arenaAddress + (long) quadStart * ARENA_QUAD, ord = this.arenaAddress + (long) perm * ARENA_QUAD,
				dst = this.arenaAddress + (long) start * ARENA_QUAD;
			for (int i = 0; i < n; i++) {
				int q = MemoryUtil.memGetInt(ord + 4L * i);
				if (q >= 0 && q < layerQuads) MemoryUtil.memCopy(src + (long) q * ARENA_QUAD, dst + (long) i * ARENA_QUAD, ARENA_QUAD);
			}
		} finally {
			this.arenaLock.readLock().unlock();
		}
		TS_SORTED_BYTES.addAndGet((long) n * ARENA_QUAD);
		TS_COPY_BYTES.addAndGet((long) n * ARENA_QUAD);
		return start;
	}

	private void setSlot(int slot, @Nullable OwnMesh m, int x, int y, int z, int uploadMs) {
		if (slot >= this.slotCap) this.growSlots(Integer.highestOneBit(slot) * 2);
		OwnMesh old = this.slotMesh[slot];
		boolean tieChange = OwnTieGroups.ON && old != m;
		if (tieChange) {
			// the cross-section directory and the UNKNOWN count follow what each slot holds: the old mesh leaves while it is still the
			// slot's (others' overhangs into it uncounted against its quads, then its own records), the new one joins below
			if (old != null) {
				long a = this.master + (long) slot * SECTION_BYTES;
				int px = MemoryUtil.memGetInt(a), py = MemoryUtil.memGetInt(a + 4), pz = MemoryUtil.memGetInt(a + 8);
				if (old.dirSlot == slot) {
					this.tieDir.uninstalling(slot, px, py, pz);
					if (old.dirIds != null) this.tieDir.remove(old.dirIds);
					old.dirIds = null;
					old.dirSlot = -1;
				}
				if (this.tieSlots.remove(OwnTieGroups.Dir.pack(px, py, pz), slot)) this.tieOrigin(px, py, pz, -1);
				if (old.grpStart == OwnTieGroups.UNKNOWN) this.tieUnknown--;
			}
			if (m != null && m.dirSlot >= 0 && m.dirSlot != slot) {
				// (moved from another slot: it leaves that one first)
				long a = this.master + (long) m.dirSlot * SECTION_BYTES;
				this.tieDir.uninstalling(m.dirSlot, MemoryUtil.memGetInt(a), MemoryUtil.memGetInt(a + 4), MemoryUtil.memGetInt(a + 8));
				if (m.dirIds != null) this.tieDir.remove(m.dirIds);
				m.dirIds = null;
				m.dirSlot = -1;
			}
		}
		if (old != null && old != m) old.slot = -1;
		this.slotMesh[slot] = m;
		if (m != null) m.slot = slot;
		if (tieChange && m != null) {
			Integer prev = this.tieSlots.put(OwnTieGroups.Dir.pack(x, y, z), slot);
			if (prev == null) this.tieOrigin(x, y, z, 1);
			m.dirIds = m.edges == null ? null : this.tieDir.add(slot, m.edges, x, y, z);
			m.dirSlot = slot;
			this.tieDir.installed(slot, x, y, z);
			if (m.grpStart == OwnTieGroups.UNKNOWN) this.tieUnknown++;
		}
		boolean hasT = m != null && m.tRecCount > 0;
		if (this.tSlots.get(slot) != hasT) {
			this.tSlots.set(slot, hasT);
			this.tSlotsVersion++;
		}
		this.maxSlot = Math.max(this.maxSlot, slot);
		long a = this.master + (long) slot * SECTION_BYTES;
		MemoryUtil.memPutInt(a, x);
		MemoryUtil.memPutInt(a + 4, y);
		MemoryUtil.memPutInt(a + 8, z);
		MemoryUtil.memPutInt(a + 12, uploadMs);
		MemoryUtil.memPutInt(a + 16, m == null ? 0 : m.recStart);
		MemoryUtil.memPutInt(a + 20, m == null ? 0 : m.recCount);
		// pad0: where the section's cutout records are (buildRecords lays out solid runs, then cutout runs, then translucent): bit 31 set,
		// bits 16-30 the cutout units, bits 0-15 the solid units before them (terrain.metal fragRedraw walks just that run)
		int solidUnits = m == null || m.layers[0] == null ? 0 : m.layers[0].runs.length / RUN_INTS;
		int cutoutUnits = m == null || m.layers[1] == null ? 0 : m.layers[1].runs.length / RUN_INTS;
		MemoryUtil.memPutInt(a + 24, solidUnits <= 0xFFFF && cutoutUnits <= 0x7FFF ? 1 << 31 | cutoutUnits << 16 | solidUnits : 0);
		if (OwnTieGroups.ON && OwnTieGroups.BLOCKS) MemoryUtil.memPutInt(a + 28, m == null ? 0 : m.grpStart);  // (Section.pad1: the tie-group block, 0 = none)
		int k = (int) (this.frame % FRAMES);
		if (this.dirtyCount[k] == this.dirty[k].length) this.dirty[k] = Arrays.copyOf(this.dirty[k], this.dirty[k].length * 2);
		this.dirty[k][this.dirtyCount[k]++] = slot;
	}

	private void release(Object mesh) {
		OwnMesh m = this.meshes.remove(mesh);
		if (m == null) return;
		if (m.slot >= 0 && this.slotMesh[m.slot] == m) this.setSlot(m.slot, null, 0, 0, 0, 0);
		this.later(() -> {
			synchronized (this.quadAlloc) {
				for (Layer l : m.layers) {
					if (l == null) continue;
					if (l.qrecUnit >= 0) this.quadAlloc.free(l.qrecUnit, l.qrecUnits);
					else this.quadAlloc.free(l.quadStart, l.quadCount);
					if (l.rawUnits > 0) this.quadAlloc.free(l.raw, l.rawUnits);
					if (l.matStart >= 0) this.quadAlloc.free(l.matStart, Math.max(1, (l.quadCount + ARENA_QUAD - 1) / ARENA_QUAD));
				}
				if (m.permStart >= 0) this.quadAlloc.free(m.permStart, m.permUnits);
				if (m.fPerm >= 0) this.quadAlloc.free(m.fPerm, m.fPermUnits);
				if (m.sortStart >= 0) {
					this.quadAlloc.free(m.sortStart, Math.max(1, m.sortUnits));
					TS_SORTED_BYTES.addAndGet(-(long) m.sortUnits * ARENA_QUAD);
				}
				if (m.fSort >= 0) {
					this.quadAlloc.free(m.fSort, Math.max(1, m.fSortUnits));
					TS_SORTED_BYTES.addAndGet(-(long) m.fSortUnits * ARENA_QUAD);
				}
			}
			if (m.recStart >= 0 && m.recCount > 0) this.recAlloc.free(m.recStart, m.recCount);
			if (m.grpWords > 0 && m.grpStart != OwnTieGroups.UNKNOWN && this.grpAlloc != null) {
				this.grpAlloc.free(m.grpStart, m.grpWords);
				this.grpMeshes--;
				for (int k = 0; k < 5; k++) this.grpLive[k] -= m.grpStat[k];
			}
			if (m.fRec >= 0) this.recAlloc.free(m.fRec, m.fRecCount);
			if (m.fsRec >= 0) this.recAlloc.free(m.fsRec, m.fRecCount);
			if (m.eFull != 0) MemoryUtil.nmemFree(m.eFull);
			if (m.eFilt != 0) MemoryUtil.nmemFree(m.eFilt);
		});
	}

	private void growSlots(int cap) {
		long growthStart = OwnGrowth.begin();
		int oldCap = this.slotCap;
		long master = MemoryUtil.nmemCalloc(cap, SECTION_BYTES);
		if (this.master != 0) {
			MemoryUtil.memCopy(this.master, master, (long) this.slotCap * SECTION_BYTES);
			MemoryUtil.nmemFree(this.master);
		}
		this.master = master;
		for (int i = 0; i < FRAMES; i++) {
			long old = this.tables[i];
			if (old != 0) this.later(() -> OwnNative.release(old));
			this.tables[i] = OwnNative.buffer(this.ctx, (long) cap * SECTION_BYTES, true);
			this.tableAddresses[i] = OwnNative.contents(this.tables[i]);
			MemoryUtil.memCopy(master, this.tableAddresses[i], (long) cap * SECTION_BYTES);
			long oldMask = this.masks[i];
			if (oldMask != 0) this.later(() -> OwnNative.release(oldMask));
			this.masks[i] = OwnNative.buffer(this.ctx, cap / 8L, true);
			this.maskAddresses[i] = OwnNative.contents(this.masks[i]);
		}
		this.slotMesh = Arrays.copyOf(this.slotMesh, cap);
		this.compiled = Arrays.copyOf(this.compiled, cap);
		this.slotCap = cap;
		OwnGrowth.end("slots", growthStart, (long) oldCap * SECTION_BYTES, (long) cap * SECTION_BYTES, (long) cap * SECTION_BYTES * (FRAMES + 1));
	}

	/**
	 * Quad's bounds from its four compact vertices: section-relative, in quarter blocks from -8 (a compact coordinate c is
	 * c / 2048 - 8 blocks, so c >> 9 quarters), min rounded down and max up (never smaller than the quad); lo x, y, z then hi
	 * x, y, z as bytes.
	 */
	private static int gridOf(float p) {
		return Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
	}

	private void putBox(int quad) {
		long v = this.arenaAddress + (long) quad * ARENA_QUAD;
		long tmp = 0;
		if (OwnQrec.ON) {
			// (quad is a record id: its corners decoded into 64 bytes first)
			tmp = MemoryUtil.nmemAlloc(64);
			for (int c = 0; c < 4; c++) OwnQrec.decode(this.arenaAddress + (long) quad * OwnQrec.BYTES, c, tmp + 16L * c, this.arenaAddress);
			v = tmp;
		}
		int lx = 255, ly = 255, lz = 255, hx = 0, hy = 0, hz = 0;
		for (int c = 0; c < 4; c++, v += 16) {
			int x = MemoryUtil.memGetShort(v) & 0xFFFF, y = MemoryUtil.memGetShort(v + 2) & 0xFFFF, z = MemoryUtil.memGetShort(v + 4) & 0xFFFF;
			if (OwnPosTable.ON) {
				// (table codes: the exact coordinate, as a grid code rounded outwards per side below; conservative)
				if (x >= OwnPosTable.BASE) x = gridOf(OwnPosTable.decode(x));
				if (y >= OwnPosTable.BASE) y = gridOf(OwnPosTable.decode(y));
				if (z >= OwnPosTable.BASE) z = gridOf(OwnPosTable.decode(z));
			}
			lx = Math.min(lx, x >> 9);
			ly = Math.min(ly, y >> 9);
			lz = Math.min(lz, z >> 9);
			hx = Math.max(hx, Math.min(255, (x + 511) >> 9));
			hy = Math.max(hy, Math.min(255, (y + 511) >> 9));
			hz = Math.max(hz, Math.min(255, (z + 511) >> 9));
		}
		if (tmp != 0) MemoryUtil.nmemFree(tmp);
		long b = this.quadBoxAddress + (long) quad * 8;
		MemoryUtil.memPutInt(b, lx | ly << 8 | lz << 16);
		MemoryUtil.memPutInt(b + 4, hx | hy << 8 | hz << 16);
	}

	/**
	 * -Dmcopt.own.padstat=true (measurement): over every published solid / cutout layer, the quads its units issue when drawn in size
	 * classes of g quads (a unit draws its class's quads, the rest are padding), per class from the unit's own count or from its
	 * facing bucket's largest unit (bucketClass), for g = 8, 4, 2, 1; logged every 5 s, cumulative.
	 */
	private static final boolean PADSTAT = Boolean.getBoolean("mcopt.own.padstat");
	private static final long[] PAD = new long[1 + 8 + 2 + 4];
	private static long padLogAt;

	private static void padStat(Layer l, int @Nullable [] bucketMax) {
		int[] bm = bucketMax;
		if (bm == null) {
			bm = new int[8];
			for (int r = 0; r < l.runs.length; r += RUN_INTS) bm[l.runs[r + 2]] = Math.max(bm[l.runs[r + 2]], l.runs[r + 1] - 1);
		}
		int[] suf = new int[l.runs.length / RUN_INTS], run = new int[8];
		for (int r = l.runs.length - RUN_INTS; r >= 0; r -= RUN_INTS) {
			int b = l.runs[r + 2];
			run[b] = Math.max(run[b], l.runs[r + 1] - 1);
			suf[r / RUN_INTS] = run[b];
		}
		synchronized (PAD) {
			for (int r = 0; r < l.runs.length; r += RUN_INTS) {
				for (int k = 0; k < 4; k++) {
					int g = 8 >> k, sizes = Math.max(1, RUN / g);
					PAD[11 + k] += (long) (Math.min(suf[r / RUN_INTS] / g, sizes - 1) + 1) * g;
				}
				int c = l.runs[r + 1], cqOwn = c - 1, cqBucket = bm[l.runs[r + 2]];
				PAD[0] += c;
				PAD[9]++;
				for (int k = 0; k < 4; k++) {
					int g = 8 >> k, sizes = Math.max(1, RUN / g);
					PAD[1 + k] += (long) (Math.min(cqOwn / g, sizes - 1) + 1) * g;
					PAD[5 + k] += (long) (Math.min(cqBucket / g, sizes - 1) + 1) * g;
				}
			}
			long now = System.nanoTime();
			if (now > padLogAt) {
				padLogAt = now + 5_000_000_000L;
				double q = Math.max(1, PAD[0]);
				System.out.printf("mcopt-own padstat: units %d, quads %d (%.2f a unit); issued / real quads, own class g=8/4/2/1: %.3f %.3f %.3f %.3f; bucket class g=8/4/2/1: %.3f %.3f %.3f %.3f; suffix class g=8/4/2/1: %.3f %.3f %.3f %.3f%n",
					PAD[9], PAD[0], PAD[0] / (double) Math.max(1, PAD[9]), PAD[1] / q, PAD[2] / q, PAD[3] / q, PAD[4] / q, PAD[5] / q, PAD[6] / q, PAD[7] / q, PAD[8] / q, PAD[11] / q, PAD[12] / q, PAD[13] / q, PAD[14] / q);
			}
		}
	}

	private long vcBuffer, vcAddress, vcFrames;

	/** VCOUNT: binds the counters once; every 600 frames logs their per-frame means per path and clears them. */
	private void vcount() {
		if (this.vcBuffer == 0) {
			this.vcBuffer = OwnNative.buffer(this.ctx, 32 * 4, true);
			this.vcAddress = OwnNative.contents(this.vcBuffer);
			MemoryUtil.memSet(this.vcAddress, 0, 32 * 4);
			OwnNative.setVcount(this.own, this.vcBuffer);
		}
		if (++this.vcFrames % 600 != 0) return;
		StringBuilder b = new StringBuilder("mcopt-own vcount per frame:");
		String[] names = {"opaque", "translucent", "per-quad", "non-indexed"};
		for (int p = 0; p < 4; p++) {
			long a = this.vcAddress + 32L * p;
			double inv = (MemoryUtil.memGetInt(a) & 0xFFFFFFFFL) / 600.0, quads = (MemoryUtil.memGetInt(a + 4) & 0xFFFFFFFFL) / 600.0;
			double padInv = (MemoryUtil.memGetInt(a + 8) & 0xFFFFFFFFL) / 600.0, padQuads = (MemoryUtil.memGetInt(a + 12) & 0xFFFFFFFFL) / 600.0;
			double c0 = (MemoryUtil.memGetInt(a + 16) & 0xFFFFFFFFL) / 600.0, c2 = (MemoryUtil.memGetInt(a + 20) & 0xFFFFFFFFL) / 600.0;
			double c3 = (MemoryUtil.memGetInt(a + 24) & 0xFFFFFFFFL) / 600.0;
			if (inv == 0) continue;
			b.append(String.format(" %s: invocations %.0f, quads issued %.0f (padding %.0f, its invocations %.0f), %.3f invocations an issued quad, %.3f a real quad; corner 0/1/2/3 %.0f/%.0f/%.0f/%.0f;",
				names[p], inv, quads, padQuads, padInv, inv / Math.max(1, quads), inv / Math.max(1, quads - padQuads), c0, quads, c2, c3));
		}
		System.out.println(b);
		MemoryUtil.memSet(this.vcAddress, 0, 32 * 4);
	}

	private long qrecStats, qrecStatsAddress, qrecFrames, qrecPasses;
	private final long[] qrecTotals = new long[4];

	/**
	 * -Dmcopt.own.mesh.qrecVerify: every 600 frames the GPU compares every live solid / cutout record corner (as quadCorner and
	 * ownVertex decode it) with the layer's 64-byte copy (own_qrec_verify); 300 frames later the pass's counts are added to the
	 * running totals (logged) and the counters cleared.
	 */
	private void qrecVerify(int k) {
		if (this.qrecStats == 0) {
			this.qrecStats = OwnNative.buffer(this.ctx, 16, true);
			this.qrecStatsAddress = OwnNative.contents(this.qrecStats);
			MemoryUtil.memSet(this.qrecStatsAddress, 0, 16);
		}
		long n = this.qrecFrames++;
		if (n % 600 == 0) {
			OwnNative.qrecVerify(this.own, this.enc, this.tables[k], this.recBuffer, this.arenaBuffer, this.qrecStats, this.maxSlot + 1);
			this.qrecPasses++;
		} else if (n % 600 == 300) {
			long a = this.qrecStatsAddress;
			for (int i = 0; i < 4; i++) this.qrecTotals[i] += MemoryUtil.memGetInt(a + 4L * i) & 0xFFFFFFFFL;
			MemoryUtil.memSet(a, 0, 16);
			System.out.printf("mcopt-own mesh qrec verify: %d passes, corners %d, differing %d, units %d, units without a copy %d%n", this.qrecPasses,
				this.qrecTotals[0], this.qrecTotals[1], this.qrecTotals[2], this.qrecTotals[3]);
		}
	}

	/**
	 * -Dmcopt.own.mesh.tieGroups: m's groups with absolute records into the tie-group buffer (Section.pad1 = the block), and the per-ID
	 * S bits of its quads (its id ranges cleared first: they may have held a released mesh's bits). A mismatch between the worker's
	 * unit counts and the stored layers drops the mesh's groups (counted).
	 */
	private void publishGroups(OwnMesh m) {
		m.grpDone = true;
		int[] g = m.groups;
		int[] su = new int[2];
		for (int li = 0; li < 2; li++) su[li] = m.layers[li] == null ? 0 : m.layers[li].runs.length / RUN_INTS;
		if (su[0] + su[1] == 0) return;  // (no opaque units: nothing to group, 0)
		boolean known = g != null && g[0] == su[0] && g[1] == su[1];
		// tieClose: the mesh's opaque records get their rings (all TIE_NONE when unknown: the frame falls back while it is installed)
		if (OwnTieGroups.CLOSE) this.writeRing(m, su[0] + su[1], known ? g : null);
		if (OwnTieGroups.BLOCKS) {
			// the mesh's quad ids lose any S bits a released mesh left on them FIRST, whatever follows (no groups, unknown): a stale S
			// bit takes a quad out of phase B's main entries, and with no group in its section the closure never draws it (missing geometry)
			this.ensureSBits(quadIdCapacity());
			for (int li = 0; li < 2; li++) {
				Layer l = m.layers[li];
				if (l != null) for (int q = 0; q < l.quadCount; q++) this.sBit(l.quadStart + q, false);
			}
		}
		if (!known) {
			// fail-closed: not grouped (vanilla's store path), too large, or the worker's units don't match the stored layers
			m.grpStart = OwnTieGroups.UNKNOWN;
			this.grpDropped++;
			return;
		}
		if (g[2] == 0 || !OwnTieGroups.BLOCKS) return;  // (grouped, no groups: 0; or tieClose alone: no blocks)
		int groups = g[2], words = 2 + g.length - 3;
		if (this.grpAlloc == null) {
			int cap = 1 << 16;
			this.grpBuffer = OwnNative.buffer(this.ctx, cap * 4L, true);
			this.grpAddress = OwnNative.contents(this.grpBuffer);
			MemoryUtil.memSet(this.grpAddress, 0, cap * 4L);
			this.grpAlloc = new OwnAlloc(cap);
			this.grpAlloc.alloc(1);  // (word 0: a Section's 0 means no groups)
		}
		int start = this.grpAlloc.alloc(words);
		if (start < 0) {
			int cap = this.grpAlloc.capacity(), newCap = Math.max(cap * 2, cap + words * 2);
			long buffer = OwnNative.buffer(this.ctx, newCap * 4L, true), address = OwnNative.contents(buffer);
			MemoryUtil.memSet(address, 0, newCap * 4L);
			MemoryUtil.memCopy(this.grpAddress, address, cap * 4L);
			long old = this.grpBuffer;
			this.later(() -> OwnNative.release(old));
			this.grpBuffer = buffer;
			this.grpAddress = address;
			this.grpAlloc.grow(newCap);
			start = this.grpAlloc.alloc(words);
		}
		long a = this.grpAddress + start * 4L;
		MemoryUtil.memPutInt(a, words - 2);
		MemoryUtil.memPutInt(a + 4, groups);
		a += 8;
		for (int i = 3; i < g.length; ) {
			int h = g[i], n = h & 0xFFFF;
			MemoryUtil.memPutInt(a, h);
			a += 4;
			int[] nl = new int[2];
			for (int j = 0; j < n; j++) nl[g[i + 1 + j] >>> 6 < su[0] ? 0 : 1]++;
			m.grpStat[0]++;
			if ((h & OwnTieGroups.X) != 0) m.grpStat[1]++;
			if ((h & OwnTieGroups.S) != 0) m.grpStat[2]++;
			m.grpStat[3] += (nl[0] >= 2 ? nl[0] : 0) + (nl[1] >= 2 ? nl[1] : 0);
			m.grpStat[4] += n;
			long[] key0 = null;
			for (int j = 0; j < n; j++) {
				int e = g[i + 1 + j], u = e >>> 6, lq = e & 63, li = u < su[0] ? 0 : 1;
				int r = m.recStart + u;
				if (r >= 1 << 26) throw new IllegalStateException("tieGroups: record " + r + " >= 2^26");
				MemoryUtil.memPutInt(a, r << 6 | lq);
				a += 4;
				Layer l = m.layers[li];
				int id = l.quadStart + l.runs[(u - (li == 0 ? 0 : su[0])) * RUN_INTS] + lq;
				if (nl[li] >= 2) this.sBit(id, true);
				if (OwnTieGroups.VERIFY) {
					// (the member's corners from the arena equal the group's; if the group was split by winding, its winding too)
					int[] raw = OwnTieGroups.arenaCodes(this.arenaAddress, id, l.qrecUnit >= 0);
					long[] k = java.util.Arrays.copyOf(OwnTieGroups.sortedKey(raw), 5);
					k[4] = OwnTieGroups.certify(raw, 0);
					if (key0 == null) key0 = k;
					else if (!java.util.Arrays.equals(java.util.Arrays.copyOf(k, 4), java.util.Arrays.copyOf(key0, 4)) || k[4] != 0 && key0[4] != 0 && k[4] != key0[4]) this.grpBad++;
					this.grpChecked++;
				}
			}
			i += 1 + n;
		}
		m.grpStart = start;
		m.grpWords = words;
		this.grpMeshes++;
		for (int k = 0; k < 5; k++) this.grpLive[k] += m.grpStat[k];
	}

	/** tieClose: m's opaque records' rings (OwnTieGroups.rings) from its groups (g null: none known). */
	private void writeRing(OwnMesh m, int units, int @Nullable [] g) {
		int need = m.recStart + units;
		if (need > this.tieRingCap) {
			int cap = Math.max(need, Math.max(this.recAlloc.capacity(), this.tieRingCap * 2));
			long buffer = OwnNative.buffer(this.ctx, 4L * cap, true), address = OwnNative.contents(buffer);
			MemoryUtil.memSet(address, 0xFF, 4L * cap);
			if (this.tieRing != 0) {
				MemoryUtil.memCopy(this.tieRingAddress, address, 4L * this.tieRingCap);
				long old = this.tieRing;
				this.later(() -> OwnNative.release(old));
			}
			this.tieRing = buffer;
			this.tieRingAddress = address;
			this.tieRingCap = cap;
		}
		long base = this.tieRingAddress + 4L * m.recStart;
		int[] ring = OwnTieGroups.rings(g, units, m.recStart);
		for (int u = 0; u < units; u++) MemoryUtil.memPutInt(base + 4L * u, ring[u]);
		this.tieRingMeshes++;
		for (int u = 0; u < units; u++) {
			if (ring[u] == OwnTieGroups.TIE_ALWAYS) this.tieRingAlways++;
			else if (ring[u] != OwnTieGroups.TIE_NONE) this.tieRingUnits++;
		}
	}

	/** tieClose: the rings (0 until a mesh is published with tieClose). */
	long tieRing() {
		return this.tieRing;
	}

	/**
	 * tieClose: why this frame must draw without uocc (0 = it need not), checked before the cull is encoded: 2 a cross-section tie
	 * candidate installed, 3 a section whose components aren't known, 4 the installed sections' coordinates can reach TOL's float32
	 * scope from this camera (OwnTieGroups.inScope); 1 and 5 only forced (tieCloseForce). With -Dmcopt.metal.inFlight > 2 tieClose is
	 * off (OwnFrag.tieCloseOn), not a fallback. Capacity, by construction: a list (A, T or B) holds each record of this frame's cull
	 * at most once, and promotion only moves a record from T / B into A, so listCap >= the records in use suffices; if it is short
	 * here (beginFrame grows it every frame; -Dmcopt.own.frag.listGrowFault skips that to make a real shortfall) the lists grow now,
	 * before encoding, counted ('lists grown at the cull'). The GPU's capacity counters (OwnFrag's log) check every frame.
	 */
	int tieCloseFallback() {
		if (TIE_FORCE > 0) return TIE_FORCE;
		long used = this.recAlloc.used();
		if (this.listCap < used) {
			this.growLists(Integer.highestOneBit((int) used) * 2);
			this.tieListGrowths++;
		}
		if (!OwnTieGroups.ON) return 0;  // (tieClose off: OwnFrag.tieCloseOn)
		if (this.tieDir.pairs > 0) return 2;
		if (this.tieUnknown > 0) return 3;
		if (!this.tieInScope()) return 4;
		return 0;
	}

	private long tieListGrowths;
	private static final boolean LIST_GROW_FAULT = OwnTieGroups.CLOSE && Boolean.getBoolean("mcopt.own.frag.listGrowFault");

	/** The installed sections can't reach TOL's scope from the main pass's camera (none recorded yet: not in scope). */
	private boolean tieInScope() {
		if (this.tieCam == null) return false;
		if (this.tieOrigins[0].isEmpty()) return true;
		int[] lo = new int[3], hi = new int[3];
		for (int k = 0; k < 3; k++) {
			lo[k] = this.tieOrigins[k].firstKey();
			hi[k] = this.tieOrigins[k].lastKey();
		}
		return OwnTieGroups.inScope(lo, hi, OwnTieGroups.local(), this.tieCam, this.tieCamOff);
	}

	/** The installed sections' block origins per axis (counts), for the scope check. */
	@SuppressWarnings("unchecked")
	private final java.util.TreeMap<Integer, Integer>[] tieOrigins = new java.util.TreeMap[] {new java.util.TreeMap<>(), new java.util.TreeMap<>(), new java.util.TreeMap<>()};
	/** The main pass's camera block and CameraOffset this frame (null before the first frame). */
	private int @Nullable [] tieCam;
	private final float[] tieCamOff = new float[3];

	private void tieOrigin(int x, int y, int z, int d) {
		int[] o = {x, y, z};
		for (int k = 0; k < 3; k++) this.tieOrigins[k].merge(o[k], d, (a, b) -> a + b == 0 ? null : a + b);
	}

	String tieRingStats() {
		return String.format("rings (meshes published): meshes %d, units in rings %d, units always A (component > %d) %d; directory pairs %d, unknown sections %d; "
			+ "lists grown at the cull %d", this.tieRingMeshes, this.tieRingUnits, OwnTieGroups.TIE_RING_MAX, this.tieRingAlways, this.tieDir.pairs, this.tieUnknown,
			this.tieListGrowths);
	}

	private int quadIdCapacity() {
		synchronized (this.quadAlloc) {
			return quadIds(this.quadAlloc.capacity());
		}
	}

	/** The S bitset covers quad ids [0, ids) (grown by copy; the old one released later). */
	private void ensureSBits(int ids) {
		int words = (ids + 31) / 32;
		if (words <= this.sBitsWords) return;
		words = Math.max(words, this.sBitsWords * 2);
		long buffer = OwnNative.buffer(this.ctx, words * 4L, true), address = OwnNative.contents(buffer);
		MemoryUtil.memSet(address, 0, words * 4L);
		if (this.sBits != 0) {
			MemoryUtil.memCopy(this.sBitsAddress, address, this.sBitsWords * 4L);
			long old = this.sBits;
			this.later(() -> OwnNative.release(old));
		}
		this.sBits = buffer;
		this.sBitsAddress = address;
		this.sBitsWords = words;
	}

	private void sBit(int id, boolean on) {
		long w = this.sBitsAddress + (id >>> 5) * 4L;
		int v = MemoryUtil.memGetInt(w), b = 1 << (id & 31);
		MemoryUtil.memPutInt(w, on ? v | b : v & ~b);  // (one aligned word store: a GPU read sees the old or the new word)
	}

	/**
	 * -Dmcopt.own.mesh.tieGroups: this frame must not rely on the tie groups (a consumer uses its ordered fallback): a cross-section
	 * tie candidate is installed, or a section whose groups aren't known.
	 */
	boolean tieFallback() {
		return OwnTieGroups.fallback(OwnTieGroups.ON, this.tieDir.pairs, this.tieUnknown, this.tieInScope());
	}

	/** The tie-group buffer and the S bitset (0 until the first published group), for the per-quad path (P5). */
	long tieGroupBuffer() {
		return this.grpBuffer;
	}

	long tieSBits() {
		return this.sBits;
	}

	/** The quad ids an arena of units can hold: units (64 bytes a quad), or with qrec its 40-byte record ids. */
	private static int quadIds(int units) {
		return OwnQrec.ON ? (int) Math.min(Integer.MAX_VALUE, (long) units * 8 / 5 + 8) : units;
	}

	/** The per-quad path's per-quad buffers cover units [0, units). */
	private void ensureQuadOwner(int units) {
		if (units <= this.quadOwnerCap) return;
		int cap;
		synchronized (this.quadAlloc) {
			cap = Math.max(units, quadIds(this.quadAlloc.capacity()));
		}
		long owner = OwnNative.buffer(this.ctx, (long) cap * 4, true), address = OwnNative.contents(owner);
		if (this.quadOwner != 0) {
			MemoryUtil.memCopy(this.quadOwnerAddress, address, (long) this.quadOwnerCap * 4);
			long old = this.quadOwner, a = this.qPrev, b = this.qCur;
			long[] vis = this.rVis.clone();
			this.later(() -> {
				OwnNative.release(old);
				if (RING) {
					for (long v : vis) OwnNative.release(v);
				} else {
					OwnNative.release(a);
					OwnNative.release(b);
				}
			});
		}
		if (QUADS_BOX) {
			long box = OwnNative.buffer(this.ctx, (long) cap * 8, true), boxAddress = OwnNative.contents(box);
			if (this.quadBox != 0) {
				MemoryUtil.memCopy(this.quadBoxAddress, boxAddress, (long) this.quadOwnerCap * 8);
				long old = this.quadBox;
				this.later(() -> OwnNative.release(old));
			}
			this.quadBox = box;
			this.quadBoxAddress = boxAddress;
		}
		this.quadOwner = owner;
		this.quadOwnerAddress = address;
		this.quadOwnerCap = cap;
		this.qWords = (cap + 31) / 32;
		if (RING) {
			// (fresh copies are zero: phase A draws nothing from them, phase B tests every quad)
			for (int i = 0; i < 4; i++) this.rVis[i] = OwnNative.buffer(this.ctx, this.qWords * 4L, false);
			this.pickRing();
		} else {
			this.qPrev = OwnNative.buffer(this.ctx, this.qWords * 4L, false);
			this.qCur = OwnNative.buffer(this.ctx, this.qWords * 4L, false);
		}
	}

	/** The index regions hold every live quad once per phase (6 indices each). */
	private void ensureQuadIndices() {
		long used;
		synchronized (this.quadAlloc) {
			used = this.quadAlloc.used();
		}
		long need = 6 * Math.max(1L << 16, used);
		if (need <= this.qcap) return;
		int cap = (int) Math.min(Integer.MAX_VALUE / 8, Long.highestOneBit(need) * 2);
		for (int i = 0; i < RING_N; i++) {
			long old = this.rQIdx[i];
			if (old != 0) this.later(() -> OwnNative.release(old));
			this.rQIdx[i] = OwnNative.buffer(this.ctx, 2L * cap * 4, false);
			if (this.rQArgs[i] == 0) {
				this.rQArgs[i] = OwnNative.buffer(this.ctx, 256, 2);  // (bytes 128-175: the lists' mesh dispatches, OwnFrag.QMESH)
				this.rQArgsAddress[i] = OwnNative.contents(this.rQArgs[i]);
			}
		}
		this.qcap = cap / 6 * 6;  // (regions a whole number of quads: the quad-list draws read entry = vertex / 6)
		this.pickRing();
	}

	/** Unit records the record buffer holds (its index range; OwnFrag's occlusion masks follow it). */
	int recCapacity() {
		return this.recAlloc.capacity();
	}

	/** the visibility oracle (OwnFrag, -Dmcopt.own.oracle): the CPU-visible unit records (REC_BYTES each) and section table. */
	long recAddress() {
		return this.recAddress;
	}

	long masterAddress() {
		return this.master;
	}

	int slotCapacity() {
		return this.slotCap;
	}

	/** Whether this frame is the -Dmcopt.metal.trace submit (its operations are printed). */
	boolean tracing() {
		return MetalBridge.tracing(this.encoder);
	}

	/** The arena buffer of this frame (OwnFrag's facing tally reads quads from it). */
	long arenaBuffer() {
		return this.arenaBuffer;
	}

	private void growVis(int recCap) {
		long a = this.prevVis, b = this.curVis;
		if (a != 0) this.later(() -> {
			OwnNative.release(a);
			OwnNative.release(b);
		});
		this.visWords = (recCap + 31) / 32;
		this.prevVis = OwnNative.buffer(this.ctx, this.visWords * 4L, false);
		this.curVis = OwnNative.buffer(this.ctx, this.visWords * 4L, false);
	}

	private void growLists(int cap) {
		for (int i = 0; i < RING_N; i++) {
			long old = this.rLists[i], oldTested = this.rTested[i], oldFat = this.rFat[i];
			if (old != 0) this.later(() -> {
				OwnNative.release(old);
				OwnNative.release(oldTested);
				OwnNative.release(oldFat);
			});
			this.rLists[i] = OwnNative.buffer(this.ctx, 4L * cap * 4, false);
			this.rTested[i] = OwnNative.buffer(this.ctx, (long) cap * 4, false);
			this.rFat[i] = OwnNative.buffer(this.ctx, FAT ? 4L * cap * 32 : QUADS_FATT ? (long) cap * 32 : 16, false);
		}
		this.listCap = cap;
		this.pickRing();
	}

	/** This frame's copies (RING), or the only ones. */
	private void pickRing() {
		int i = RING ? (int) (this.frame % FRAMES) : 0;
		this.lists = this.rLists[i];
		this.tested = this.rTested[i];
		this.fat = this.rFat[i];
		if (this.rArgs[i] != 0) {
			this.args = this.rArgs[i];
			this.argsAddress = this.rArgsAddress[i];
		}
		if (this.rQIdx[i] != 0) {
			this.qIdx = this.rQIdx[i];
			this.qArgs = this.rQArgs[i];
			this.qArgsAddress = this.rQArgsAddress[i];
		}
		if (RING && this.rVis[0] != 0) {
			this.qPrev = this.rVis[(int) ((this.frame + 2) & 3)];  // frame - 2
			this.qCur = this.rVis[(int) (this.frame & 3)];
		}
	}

	/**
	 * Vanilla's opaque terrain group (solid, cutout) for this frame, into the level's open render pass. False when nothing
	 * could be drawn (no render encoder open): the caller then lets vanilla draw.
	 */
	public boolean drawOpaque(CameraRenderState camera, int renderDistance, long fadeMs, GpuSampler sampler, GpuTextureView atlas,
		List<SectionRenderDispatcher.RenderSection> visible) {
		if (!MetalBridge.inRenderPass(this.encoder)) return false;
		if (NO_FADE) fadeMs = 0;
		this.beginFrame();
		String mode = PROBE_MS > 0 ? this.probe() : "draw";
		if (mode.startsWith("x-")) {
			// (translucent probe modes: the opaque terrain as "draw")
			this.tSortedNow = TS_BUILD && (mode.equals("x-sorted") || mode.equals("x-both"));
			this.tHalfNow = mode.equals("x-half") || mode.equals("x-both");
			this.tFrNow = TFR_PROBE ? mode.equals("x-tfr") : TFRUSTUM;
			mode = "draw";
		} else {
			this.tFrNow = TFRUSTUM;
		}
		// probe "none": no cull and no draw at all (with "skip", which still culls: the cull's own cost)
		if (mode.equals("none")) {
			MetalBridge.reapplyPipeline(this.encoder);
			return true;
		}
		boolean graph = mode.equals("graph") || GRAPH && !mode.equals("nograph");
		boolean pyr = mode.equals("pyr");
		// (probe mode "qmesh": the per-quad path with its lists drawn by mesh shading, OwnFrag.QMESH; frag probe modes ending in
		// "+noquads" turn the per-quad path off for the frame, so a probe with -Dmcopt.own.quads=true can compare a frag set against it)
		boolean quads = QUADS && !mode.equals("noquads") && !mode.endsWith("+noquads") || mode.equals("quads") || mode.equals("qmesh");
		boolean prefilter = PREFILTER && !mode.equals("noprefilter") || mode.equals("prefilter");
		if (prefilter) quads = false;
		boolean occ = OCC && !mode.equals("noocc") || mode.equals("occ") || pyr;
		int k = (int) (this.frame % FRAMES);
		GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer(), fog = RenderSystem.getShaderFog();
		GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
		if (projection == null || fog == null || globals == null) return false;
		Matrix4f proj = new Matrix4f();
		long pa = MetalBridge.bufferAddress(projection.buffer());
		if (pa != 0) proj.setFromAddress(pa + projection.offset());
		else proj.set(camera.projectionMatrix);
		Matrix4fc view = camera.viewRotationMatrix;
		Matrix4f clip = new Matrix4f(proj).mul(view);
		GpuBufferSlice terrain = RenderSystem.getDynamicUniforms().writeTerrainTransform(new Matrix4f(view), atlas.getWidth(0), atlas.getHeight(0));
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long f = stack.ncalloc(16, 1, CULL_FRAME_BYTES);
			Vector4f pl = new Vector4f();
			int[] planes = {Matrix4fc.PLANE_NX, Matrix4fc.PLANE_PX, Matrix4fc.PLANE_NY, Matrix4fc.PLANE_PY};
			for (int i = 0; i < 4; i++) {
				clip.frustumPlane(planes[i], pl);
				MemoryUtil.memPutFloat(f + i * 16L, pl.x);
				MemoryUtil.memPutFloat(f + i * 16L + 4, pl.y);
				MemoryUtil.memPutFloat(f + i * 16L + 8, pl.z);
				MemoryUtil.memPutFloat(f + i * 16L + 12, pl.w);
			}
			int bx = camera.blockPos.getX(), by = camera.blockPos.getY(), bz = camera.blockPos.getZ();
			if (OwnTieGroups.ON) {
				// (tieCloseFallback's scope check: this pass's camera, as the CullFrame below gets it)
				this.tieCam = new int[] {bx, by, bz};
				this.tieCamOff[0] = (float) (bx - camera.pos.x);
				this.tieCamOff[1] = (float) (by - camera.pos.y);
				this.tieCamOff[2] = (float) (bz - camera.pos.z);
			}
			MemoryUtil.memPutInt(f + 64, bx);
			MemoryUtil.memPutInt(f + 68, by);
			MemoryUtil.memPutInt(f + 72, bz);
			MemoryUtil.memPutFloat(f + 80, (float) (bx - camera.pos.x));
			MemoryUtil.memPutFloat(f + 84, (float) (by - camera.pos.y));
			MemoryUtil.memPutFloat(f + 88, (float) (bz - camera.pos.z));
			MemoryUtil.memPutInt(f + 96, bx >> 4);
			MemoryUtil.memPutInt(f + 100, by >> 4);
			MemoryUtil.memPutInt(f + 104, bz >> 4);
			MemoryUtil.memPutInt(f + 112, renderDistance);
			MemoryUtil.memPutInt(f + 116, this.maxSlot + 1);
			MemoryUtil.memPutInt(f + 120, this.listCap);
			MemoryUtil.memPutInt(f + 128, RUN);
			MemoryUtil.memPutInt(f + 132, quads || prefilter ? 2 : occ && !pyr ? 1 : 0);  // (probe "pyr": every unit in list A, then the split + pyramid alone)
			MemoryUtil.memPutInt(f + 136, FAT ? 1 : 0);
			MemoryUtil.memPutInt(f + 140, QUADS_FATT ? 1 : 0);  // (CullFrame.fatT: read only with occ 2)
			if (graph) {
				long m = this.maskAddresses[k];
				MemoryUtil.memSet(m, 0, this.slotCap / 8L);
				for (int i = 0, n = visible.size(); i < n; i++) {
					int slot = visible.get(i).index;
					if (slot < this.slotCap) {
						long w = m + (slot >>> 5) * 4L;
						MemoryUtil.memPutInt(w, MemoryUtil.memGetInt(w) | 1 << (slot & 31));
					}
				}
				MemoryUtil.memPutInt(f + 124, 1);
			}
			int nowMs = (int) (Util.getMillis() - this.baseMs);
			// solid-section occlusion (OwnSolid): built before the cull, which tests units against it; probe modes solid / nosolid
			// (frag probe modes ending in "+nosolid" turn it off too, so a probe can compare a frag set with and without it)
			boolean solid = OwnSolid.ON && !mode.equals("nosolid") && !mode.endsWith("+nosolid") || mode.equals("solidocc");
			if (solid && this.solid == null) {
				this.solid = new OwnSolid(this.ctx);
			}
			if (this.solid != null) {
				this.solid.update();
				net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
				int fw = Minecraft.getInstance().gameRenderer.mainRenderTarget().width, fh = Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
				if (solid && level != null && fw > 0 && fh > 0) {
					long sp = stack.ncalloc(16, 1, SOLID_BYTES);
					clip.getToAddress(sp);
					MemoryUtil.memCopy(f + 64, sp + 64, 12);
					MemoryUtil.memCopy(f + 80, sp + 80, 12);
					MemoryUtil.memCopy(f + 96, sp + 96, 12);
					MemoryUtil.memPutInt(sp + 128, renderDistance);
					MemoryUtil.memPutInt(sp + 132, level.getMinSectionY());
					MemoryUtil.memPutInt(sp + 136, Math.min(64, level.getSectionsCount()));
					MemoryUtil.memPutInt(sp + 140, OwnSolid.GRID);
					int ow = SOLID_WIDTH, oh = Math.max(1, Math.round(SOLID_WIDTH * (float) fh / fw));
					OwnNative.solidOcc(this.own, this.enc, sp, SOLID_BYTES, this.solid.buffer, ow, oh, renderDistance * 2 + 1);
				} else {
					OwnNative.solidOcc(this.own, this.enc, 0, 0, 0, 0, 0, 0);
				}
			}
			OwnFrag.Config fc = OwnFrag.config(mode, occ || FAT || quads || prefilter, OwnTieGroups.CLOSE ? this.tieCloseFallback() : 0);
			if (fc != null) {
				if (this.frag == null) this.frag = new OwnFrag(this, this.ctx, this.enc, this.own);
				this.frag.cull(fc, f, CULL_FRAME_BYTES, this.tables[k], this.recBuffer, this.masks[k], this.maxSlot + 1, this.listCap, fog, nowMs, (int) fadeMs, renderDistance,
					clip);
			} else {
				// -Dmcopt.own.cullR: a thread per record (not with fat lists: their entries are built per section)
				int recThreads = CULL_R && !FAT && !mode.equals("cullS") || mode.equals("cullR") ? this.recAlloc.capacity() : 0;
				OwnNative.cull(this.own, this.enc, f, CULL_FRAME_BYTES, this.tables[k], this.recBuffer, this.args, this.lists, this.masks[k], this.maxSlot + 1,
					this.prevVis, this.curVis, this.tested, occ && !pyr ? this.visWords : 0, this.fat, recThreads);
				if (this.tracing()) System.out.println("mcopt-metal trace: compute encoder 'own cull' #" + OwnNative.lastComputeGroup() + " (pre command buffer, profiled like a pass)");
			}
			// (the camera's occluders apply to this cull only: any later cull this frame, e.g. the shadow pass's, sees none)
			if (this.solid != null) OwnNative.solidOcc(this.own, this.enc, 0, 0, 0, 0, 0, 0);
			if (OwnQrec.VERIFY) this.qrecVerify(k);
			if (VCOUNT) this.vcount();

			long u = stack.nmalloc(8, 8 * 8);
			MemoryUtil.memPutLong(u, MetalBridge.useBuffer(this.encoder, projection.buffer()));
			MemoryUtil.memPutLong(u + 8, projection.offset());
			MemoryUtil.memPutLong(u + 16, MetalBridge.useBuffer(this.encoder, globals));
			MemoryUtil.memPutLong(u + 24, 0);
			MemoryUtil.memPutLong(u + 32, MetalBridge.useBuffer(this.encoder, terrain.buffer()));
			MemoryUtil.memPutLong(u + 40, terrain.offset());
			MemoryUtil.memPutLong(u + 48, MetalBridge.useBuffer(this.encoder, fog.buffer()));
			MemoryUtil.memPutLong(u + 56, fog.offset());
			GpuTextureView light = Minecraft.getInstance().gameRenderer.lightmap();
			GpuSampler lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			long d = stack.ncalloc(16, 1, DRAW_FRAME_BYTES);
			MemoryUtil.memPutInt(d + 4, fc != null ? nowMs : (int) (Util.getMillis() - this.baseMs));
			MemoryUtil.memPutInt(d + 8, (int) fadeMs);
			if (CPU_CLIP) {
				MemoryUtil.memPutInt(d + 12, 1);
				clip.getToAddress(d + 16);
			}
			long arena = this.arenaBuffer;
			boolean draw = !mode.equals("skip") && !mode.equals("skipall"), fragSplit = false;
			if (prefilter) {
				this.drawPrefiltered(f, clip, d, arena, k, u, atlas, sampler, light, lightSampler, draw);
				if (STATS) this.stats();
				return true;
			}
			if (quads) {
				this.quadsCutFirst = OwnFrag.cutFirst(mode);
				this.quadsMesh = OwnFrag.QMESH && !mode.equals("quads") || mode.equals("qmesh");
				this.quadsKind = OwnFrag.quadsKind(mode);
				this.drawQuads(f, clip, camera, d, arena, k, u, atlas, sampler, light, lightSampler, draw);
				if (STATS) this.stats();
				return true;
			}
			if (fc != null) {
				if (OwnCapture.ON && OwnCapture.active()) {
					// (-Dmcopt.own.capture: the samplers, the pass before our opaque block; frag.draw binds everything it needs anyway)
					OwnCapture.sampler(sampler);
					OwnCapture.sampler(lightSampler);
					OwnCapture.image(this.enc, "preO", MetalBridge.viewHandle(atlas), MetalBridge.viewHandle(light));
				}
				fragSplit = this.frag.draw(fc, d, arena, this.tables[k], this.recBuffer, u, atlas, sampler, light, lightSampler);
				if (OwnCapture.ON && OwnCapture.active()) fragSplit |= OwnCapture.image(this.enc, "postO", 0, 0);
				draw = false;  // (the base path's draws below are skipped)
			}
			int flat = mode.equals("flat") || mode.equals("flatpos") ? 4 : LEVEL && !mode.equals("grad") || mode.equals("level") ? 8 : 0;
			// probe vertex variants: "nolight" (no lightmap fetch), "pos" / "flatpos" (position only)
			flat |= mode.equals("nolight") ? 1 << 4 : mode.equals("pos") || mode.equals("flatpos") ? 2 << 4 : 0;
			// probe "cutfirst": cutout before solid (measures what drawing alpha-tested after opaque costs; not the vanilla picture)
			boolean cutFirst = mode.equals("cutfirst");
			boolean prepass = (PREPASS && !mode.equals("noprepass") || mode.equals("prepass"));
			if (prepass && draw && !mode.equals("solid")) this.drawList(1 | 256, d, arena, k, u, atlas, sampler, light, lightSampler);
			for (int i = 0; i < 2 && draw; i++) {
				int list = cutFirst ? 1 - i : i;
				if (list == 0 && mode.equals("cutout") || list == 1 && mode.equals("solid")) continue;
				this.drawList(list | flat | (prepass && list == 1 ? 512 : 0), d, arena, k, u, atlas, sampler, light, lightSampler);
			}
			if (QSTATS && draw && !occ && fc == null) this.tally(clip, f, arena, k);
			boolean split = fragSplit;
			if (mode.equals("split")) split = OwnNative.split(this.own, this.enc) == 1;
			if (occ) {
				long o = stack.ncalloc(16, 1, OCC_FRAME_BYTES);
				clip.getToAddress(o);
				MemoryUtil.memPutInt(o + 64, camera.blockPos.getX());
				MemoryUtil.memPutInt(o + 68, camera.blockPos.getY());
				MemoryUtil.memPutInt(o + 72, camera.blockPos.getZ());
				MemoryUtil.memCopy(f + 80, o + 80, 12);
				MemoryUtil.memPutInt(o + 116, this.listCap);
				MemoryUtil.memPutInt(o + 120, this.visWords);
				MemoryUtil.memPutInt(o + 124, FAT ? 1 : 0);
				long hiz = stack.ncalloc(4, 8, 4);
				split = OwnNative.occ(this.own, this.enc, o, OCC_FRAME_BYTES, this.tables[k], this.recBuffer, this.args, this.lists, this.curVis, this.tested, hiz, this.fat, pyr ? 1 : 0) == 1;
				if (split) {
					MetalBridge.restorePass(this.encoder);
					for (int list = 2; list < 4 && draw && !pyr; list++) this.drawList(list | flat, d, arena, k, u, atlas, sampler, light, lightSampler);
				}
				if (!pyr) {
					long t = this.prevVis;
					this.prevVis = this.curVis;
					this.curVis = t;
				}
			}
			if (split) MetalBridge.restorePass(this.encoder);
			else MetalBridge.reapplyPipeline(this.encoder);
		}
		if (STATS) this.stats();
		return true;
	}

	// ---- native shading's sun shadows (mcopt.metal.shade.ShadeOwnShadows): our units culled from the light's view ----
	private long shArgs, shLists, shTested, shFat;
	private int shCap;
	/** The block atlas and its sampler as the last opaque draw bound them (the shadows' cutout test samples them). */
	public long lastAtlas, lastAtlasSampler;

	/**
	 * Culls our units for one shadow cascade into lists of their own, in the pre command buffer (it runs before this frame's
	 * passes). clip: camera-relative world -> the cascade's clip space, an orthographic box whose four sides are the cull's planes;
	 * (lx, ly, lz): the unit direction to the light, so the facing test keeps the faces turned toward it (the cull sees a camera
	 * 4096 blocks out along it; the planes are moved by the same amount, which leaves them where they were). The distance rule
	 * is the camera's, as for the view. Reads the section table of the last frame drawn. False when nothing is published yet.
	 */
	public boolean shadowCull(Matrix4fc clip, float lx, float ly, float lz) {
		if (this.maxSlot < 0 || this.frame == 0) return false;
		net.minecraft.client.renderer.ViewArea va = Minecraft.getInstance().levelRenderer.viewArea();
		if (va == null) return false;
		if (this.shCap != this.listCap) {
			for (long b : new long[] {this.shLists, this.shTested, this.shFat}) if (b != 0) this.later(() -> OwnNative.release(b));
			this.shLists = OwnNative.buffer(this.ctx, 4L * this.listCap * 4, false);
			this.shTested = OwnNative.buffer(this.ctx, (long) this.listCap * 4, false);
			this.shFat = OwnNative.buffer(this.ctx, FAT ? 4L * this.listCap * 32 : 16, false);
			if (this.shArgs == 0) this.shArgs = OwnNative.buffer(this.ctx, 128, 2);
			this.shCap = this.listCap;
		}
		net.minecraft.world.phys.Vec3 pos = Minecraft.getInstance().gameRenderer.mainCamera().position();
		final float shift = 4096.0F;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long f = stack.ncalloc(16, 1, CULL_FRAME_BYTES);
			Vector4f pl = new Vector4f();
			int[] planes = {Matrix4fc.PLANE_NX, Matrix4fc.PLANE_PX, Matrix4fc.PLANE_NY, Matrix4fc.PLANE_PY};
			for (int i = 0; i < 4; i++) {
				clip.frustumPlane(planes[i], pl);
				MemoryUtil.memPutFloat(f + i * 16L, pl.x);
				MemoryUtil.memPutFloat(f + i * 16L + 4, pl.y);
				MemoryUtil.memPutFloat(f + i * 16L + 8, pl.z);
				MemoryUtil.memPutFloat(f + i * 16L + 12, pl.w + shift * (pl.x * lx + pl.y * ly + pl.z * lz));
			}
			int bx = (int) Math.floor(pos.x), by = (int) Math.floor(pos.y), bz = (int) Math.floor(pos.z);
			MemoryUtil.memPutInt(f + 64, bx);
			MemoryUtil.memPutInt(f + 68, by);
			MemoryUtil.memPutInt(f + 72, bz);
			MemoryUtil.memPutFloat(f + 80, (float) (bx - pos.x) - lx * shift);
			MemoryUtil.memPutFloat(f + 84, (float) (by - pos.y) - ly * shift);
			MemoryUtil.memPutFloat(f + 88, (float) (bz - pos.z) - lz * shift);
			MemoryUtil.memPutInt(f + 96, bx >> 4);
			MemoryUtil.memPutInt(f + 100, by >> 4);
			MemoryUtil.memPutInt(f + 104, bz >> 4);
			MemoryUtil.memPutInt(f + 112, va.getViewDistance());
			MemoryUtil.memPutInt(f + 116, this.maxSlot + 1);
			MemoryUtil.memPutInt(f + 120, this.shCap);
			MemoryUtil.memPutInt(f + 128, RUN);
			MemoryUtil.memPutInt(f + 136, FAT ? 1 : 0);
			int k = (int) (this.frame % FRAMES);
			OwnNative.cull(this.own, this.enc, f, CULL_FRAME_BYTES, this.tables[k], this.recBuffer, this.shArgs, this.shLists, this.masks[k], this.maxSlot + 1,
				this.prevVis, this.curVis, this.shTested, 0, this.shFat);
		}
		return true;
	}

	/** What a shadow draw after shadowCull reads: arena, section table, records, lists, indirect arguments (solid at 0, cutout at 20), list capacity. */
	public long[] shadowBuffers() {
		return new long[] {this.arenaBuffer, this.tables[(int) (this.frame % FRAMES)], this.recBuffer, FAT ? this.shFat : this.shLists, this.shArgs, this.shCap};
	}

	public static boolean compact() {
		return COMPACT;
	}

	public static boolean fat() {
		return FAT;
	}

	/** -Dmcopt.own.int.visible: slots whose mesh has translucent units (kept by setSlot), and a scratch count. */
	private final java.util.BitSet tSlots = new java.util.BitSet();
	private int tSlotsVersion;
	private final int[] orderCount = new int[1];
	private long tVerifyFrames, tVerifyBad, tVerifyLog;

	/** =verify: the visible walk's slots with translucent units, far to near, must be exactly OwnVisible's order. */
	private void verifyTranslucentOrder(List<SectionRenderDispatcher.RenderSection> visible, int[] order, int count) {
		int j = 0;
		boolean ok = true;
		for (int i = visible.size() - 1; i >= 0 && ok; i--) {
			int slot = visible.get(i).index;
			OwnMesh m = slot < this.slotCap ? this.slotMesh[slot] : null;
			if (m == null || m.tRecCount == 0) continue;
			ok = j < count && order[j++] == slot;
		}
		ok &= j == count;
		this.tVerifyFrames++;
		if (!ok) this.tVerifyBad++;
		long now = System.nanoTime();
		if (now - this.tVerifyLog > 5_000_000_000L) {
			System.out.println("mcopt-own visible verify (translucent): " + this.tVerifyFrames + " frames, order mismatches " + this.tVerifyBad + " (" + count + " slots)");
			this.tVerifyLog = now;
		}
	}

	/**
	 * TCULL: how many units mesh m lists this frame, and whether they are its filtered version (m.tUse). The filtered version is the
	 * current order without the axis-aligned quads whose plane the camera is more than TCULL_EPS behind (the rasterizer's back-face
	 * cull drops those anyway; the kept quads keep vanilla's order, so blending is unchanged). It is valid while the camera stays in
	 * fBox: between the nearest axis-aligned planes on either side of where it was built (shrunk by TCULL_EPS), so no quad's facing
	 * can have changed. Otherwise (a resort, a plane crossed, a new slot) it is rebuilt here, from the camera of this frame. Old
	 * versions (order units and records) are freed after the frames in flight, so a frame's list always reads its own version.
	 */
	/** This frame's list entries of mesh m: units (instanced), or with TFLAT quads; TCULL picks the version. */
	/** TFRUSTUM: whether mesh m's translucent box (grown TFR_EPS), at slot's origin, is wholly outside a side plane of this frame's frustum. */
	private boolean tOutside(OwnMesh m, int slot, double cx, double cy, double cz) {
		float[] b = m.tBox;
		if (b == null || slot >= this.slotCap) return false;
		long sa = this.master + (long) slot * SECTION_BYTES;
		double ox = MemoryUtil.memGetInt(sa) - cx, oy = MemoryUtil.memGetInt(sa + 4) - cy, oz = MemoryUtil.memGetInt(sa + 8) - cz;
		double x0 = ox + b[0] - TFR_EPS, y0 = oy + b[1] - TFR_EPS, z0 = oz + b[2] - TFR_EPS, x1 = ox + b[3] + TFR_EPS, y1 = oy + b[4] + TFR_EPS, z1 = oz + b[5] + TFR_EPS;
		float[] p = this.tFrPlanes;
		for (int j = 0; j < 16; j += 4) {
			double a = p[j], bb = p[j + 1], c = p[j + 2];
			if (a * (a > 0 ? x1 : x0) + bb * (bb > 0 ? y1 : y0) + c * (c > 0 ? z1 : z0) + p[j + 3] < 0) return true;
		}
		return false;
	}

	private int tCount(OwnMesh m, int slot, double cx, double cy, double cz) {
		int units = TCULL ? this.tcull(m, slot, cx, cy, cz) : m.tRecCount;
		if (!TFLAT) return units;
		Layer t = m.layers[2];
		if (m.tRecCount == 0 || t == null) return 0;
		return TCULL && m.tUse ? m.fKept : t.quadCount;
	}

	/** TFLAT: mesh m's entries for the version tCount picked (built once per version): unit record << 6 | quad, in order. */
	private long tEntries(OwnMesh m) {
		boolean filtered = TCULL && m.tUse;
		if (filtered ? m.eFiltN >= 0 : m.eFull != 0) return filtered ? m.eFilt : m.eFull;
		int n = filtered ? m.fKept : m.layers[2].quadCount, first = filtered ? m.fRec : m.tRecStart;
		long e = MemoryUtil.nmemAlloc(Math.max(4L, 4L * n));
		for (int q = 0; q < n; q++) MemoryUtil.memPutInt(e + 4L * q, (first + q / RUN) << 6 | q % RUN);
		if (filtered) {
			m.eFilt = e;
			m.eFiltN = n;
		} else {
			m.eFull = e;
			m.eFullN = n;
		}
		return e;
	}

	private long tIdx;
	private int tIdxQuads;

	/** TFLAT: the static u32 index buffer for at least quads quads (4q, 4q+1, 4q+2, 4q+2, 4q+3, 4q: vanilla's quad triangles). */
	private long tIndices(int quads) {
		if (quads > this.tIdxQuads || this.tIdx == 0) {
			int cap = Math.max(1 << 16, Integer.highestOneBit(Math.max(1, quads)) * 2);
			long old = this.tIdx;
			if (old != 0) this.later(() -> OwnNative.release(old));
			this.tIdx = OwnNative.buffer(this.ctx, 24L * cap, true);
			long a = OwnNative.contents(this.tIdx);
			for (int q = 0; q < cap; q++, a += 24) {
				int b = 4 * q;
				MemoryUtil.memPutInt(a, b);
				MemoryUtil.memPutInt(a + 4, b + 1);
				MemoryUtil.memPutInt(a + 8, b + 2);
				MemoryUtil.memPutInt(a + 12, b + 2);
				MemoryUtil.memPutInt(a + 16, b + 3);
				MemoryUtil.memPutInt(a + 20, b);
			}
			this.tIdxQuads = cap;
		}
		return this.tIdx;
	}

	private int tcull(OwnMesh m, int slot, double cx, double cy, double cz) {
		m.tUse = false;
		Layer t = m.layers[2];
		if (m.tRecCount == 0 || t == null || m.tFace == null || m.permStart < 0) return m.tRecCount;
		long sa = this.master + (long) slot * SECTION_BYTES;
		double x = cx - MemoryUtil.memGetInt(sa), y = cy - MemoryUtil.memGetInt(sa + 4), z = cz - MemoryUtil.memGetInt(sa + 8);
		double[] box = m.fBox;
		boolean inside = m.fFor == m.permStart && m.fSlot == slot && x > box[0] && y > box[1] && z > box[2] && x < box[3] && y < box[4] && z < box[5];
		if (!inside) this.refilter(m, t, slot, x, y, z);
		this.tcListed++;
		if (m.fRec == -1) {
			this.tcFull++;
			return m.tRecCount;
		}
		m.tUse = true;
		return m.fRecCount;
	}

	private void refilter(OwnMesh m, Layer t, int slot, double x, double y, double z) {
		TC_REFILTERS.incrementAndGet();
		// the old version goes after the frames in flight
		if (m.fPerm >= 0) this.freeUnitsLater(m.fPerm, m.fPermUnits);
		if (m.fRec >= 0) {
			int r0 = m.fRec, rn = m.fRecCount;
			this.later(() -> this.recAlloc.free(r0, rn));
		}
		if (m.fSort >= 0) {
			this.freeUnitsLater(m.fSort, Math.max(1, m.fSortUnits));
			TS_SORTED_BYTES.addAndGet(-(long) m.fSortUnits * ARENA_QUAD);
			m.fSort = -1;
		}
		if (m.fsRec >= 0) {
			int r0 = m.fsRec, rn = m.fRecCount;
			this.later(() -> this.recAlloc.free(r0, rn));
			m.fsRec = -1;
		}
		m.fPerm = -1;
		m.fRec = -1;
		m.fRecCount = 0;
		m.fKept = 0;
		if (m.eFilt != 0) {
			MemoryUtil.nmemFree(m.eFilt);
			m.eFilt = 0;
		}
		m.eFiltN = -1;
		m.fFor = m.permStart;
		m.fSlot = slot;
		int n = t.quadCount;
		byte[] face = m.tFace;
		float[] plane = m.tPlane;
		double[] cam = {x, y, z};
		double[] box = m.fBox;
		box[0] = box[1] = box[2] = Double.NEGATIVE_INFINITY;
		box[3] = box[4] = box[5] = Double.POSITIVE_INFINITY;
		boolean[] drop = new boolean[n];
		int kept = n;
		for (int q = 0; q < n; q++) {
			int b = face[q];
			if (b >= 6) continue;
			int a = b >> 1;
			double p = plane[q], d = cam[a] - p;
			// the box: between the nearest planes on either side (any facing: crossing one changes some quad's facing)
			if (d > 0) box[a] = Math.max(box[a], p + TCULL_EPS);
			else box[3 + a] = Math.min(box[3 + a], p - TCULL_EPS);
			// a + face is seen from cam > plane; a - face from cam < plane
			if ((b & 1) == 0 ? d < -TCULL_EPS : d > TCULL_EPS) {
				drop[q] = true;
				kept--;
			}
		}
		TC_QUADS.addAndGet(n);
		TC_DROPPED.addAndGet(n - kept);
		if (kept == n) return;  // (nothing to leave out: the full order's units, until the camera leaves the box)
		// the kept quads in the current order, then their units' records (fresh: a frame in flight keeps reading the old ones)
		int units = permUnits(Math.max(1, kept)), perm = this.allocLocked(units);
		try {
			long src = this.arenaAddress + (long) m.permStart * ARENA_QUAD, dst = this.arenaAddress + (long) perm * ARENA_QUAD;
			int k = 0;
			for (int i = 0; i < n; i++) {
				int q = MemoryUtil.memGetInt(src + 4L * i);
				if (q >= 0 && q < n && drop[q]) continue;
				MemoryUtil.memPutInt(dst + 4L * k++, q);
			}
		} finally {
			this.arenaLock.readLock().unlock();
		}
		m.fPerm = perm;
		m.fPermUnits = units;
		m.fKept = kept;
		int rc = (kept + RUN - 1) / RUN;
		if (rc == 0) {
			// (every quad faces away: no units at all; the order units stay allocated as the version's)
			m.fRec = -2;
			m.fRecCount = 0;
			return;
		}
		int start = this.allocRecs(rc);
		if (TS_BUILD) {
			// (tsorted: the kept quads copied in order, their own units)
			m.fSort = this.sortedCopy(t.quadStart, perm, kept, n);
			m.fSortUnits = kept;
			m.fsRec = this.allocRecs(rc);
			writeSortedRecs(this.recAddress + (long) m.fsRec * REC_BYTES, rc, kept, m.fSort, slot);
		}
		long a = this.recAddress + (long) start * REC_BYTES;
		for (int i = 0; i < rc; i++, a += REC_BYTES) {
			int c = Math.min(RUN, kept - i * RUN);
			MemoryUtil.memPutInt(a, perm * (ARENA_QUAD / 4) + i * RUN);
			MemoryUtil.memPutInt(a + 4, slot);
			MemoryUtil.memPutInt(a + 8, (c - 1) | 6 << 6 | 2 << 9);
			MemoryUtil.memPutInt(a + 12, t.quadStart);
			MemoryUtil.memPutInt(a + 16, 0);
			MemoryUtil.memPutInt(a + 20, 255 | 255 << 8 | 255 << 16);
			MemoryUtil.memPutInt(a + 24, -1);
			MemoryUtil.memPutInt(a + 28, TPROBE == 2 ? i * RUN : 0);
		}
		m.fRec = start;
		m.fRecCount = rc;
	}

	/** n records (the record buffer grown as buildRecords does). */
	private int allocRecs(int n) {
		int start = this.recAlloc.alloc(n);
		if (start < 0) {
			int cap = this.recAlloc.capacity(), newCap = Math.max(cap * 2, cap + n * 2);
			long growthStart = OwnGrowth.begin();
			long buffer = OwnNative.buffer(this.ctx, (long) newCap * REC_BYTES, true);
			long address = OwnNative.contents(buffer);
			MemoryUtil.memCopy(this.recAddress, address, (long) cap * REC_BYTES);
			long old = this.recBuffer;
			this.later(() -> OwnNative.release(old));
			this.recBuffer = buffer;
			this.recAddress = address;
			this.recAlloc.grow(newCap);
			this.growVis(newCap);
			OwnGrowth.end("records-alloc", growthStart, (long) cap * REC_BYTES, (long) newCap * REC_BYTES, (long) newCap * REC_BYTES);
			start = this.recAlloc.alloc(n);
		}
		return start;
	}

	/** Translucent instance lists (one per frame slot, CPU-written) and their draws' arguments (20 bytes per slot). */
	private final long[] tLists = new long[FRAMES], tListAddresses = new long[FRAMES];
	private int tListCap;
	private long tArgs, tArgsAddress;

	/**
	 * Vanilla's translucent terrain group for this frame (classic transparency), into the level's open render pass: one draw whose
	 * instances are the visible sections' translucent units far to near (vanilla's visible list reversed, as its draws go), each
	 * section's quads in vanilla's sorted order. False when nothing could be drawn: the caller lets vanilla draw.
	 */
	private long tStatRecs, tStatIn, tStatB, tStatC, tStatFrames, tStatLast;

	/**
	 * Measurement (-Dmcopt.own.frag.fogLog): the share of our translucent records in sections whose box lies wholly inside the
	 * render-distance fog start (the largest cylindrical distance + 1 below it, as the cull's lean test): there the render-distance fog is 0
	 * and the environmental fog linear, so a per-vertex fog would be exact. Logged every 5 s.
	 */
	private void tFogStat(CameraRenderState camera, GpuBufferSlice fog, int @Nullable [] order, int orderCount, List<SectionRenderDispatcher.RenderSection> visible) {
		long fa = MetalBridge.bufferAddress(fog.buffer());
		if (fa == 0) return;
		float rdStart = MemoryUtil.memGetFloat(fa + fog.offset() + 24), rdEnd = MemoryUtil.memGetFloat(fa + fog.offset() + 28),
			envEnd = MemoryUtil.memGetFloat(fa + fog.offset() + 20);
		int count = order != null ? orderCount : visible.size();
		for (int i = 0; i < count; i++) {
			int slot = order != null ? order[i] : visible.get(i).index;
			OwnMesh m = slot < this.slotCap ? this.slotMesh[slot] : null;
			if (m == null || m.tRecCount == 0) continue;
			long a = this.master + (long) slot * SECTION_BYTES;
			double x0 = MemoryUtil.memGetInt(a) - camera.pos.x, y0 = MemoryUtil.memGetInt(a + 4) - camera.pos.y, z0 = MemoryUtil.memGetInt(a + 8) - camera.pos.z;
			double mx = Math.max(Math.abs(x0), Math.abs(x0 + 16)), my = Math.max(Math.abs(y0), Math.abs(y0 + 16)), mz = Math.max(Math.abs(z0), Math.abs(z0 + 16));
			double cyl = Math.max(Math.sqrt(mx * mx + mz * mz), my);
			double nx = x0 < 0 && x0 + 16 > 0 ? 0 : Math.min(Math.abs(x0), Math.abs(x0 + 16)), ny = y0 < 0 && y0 + 16 > 0 ? 0 : Math.min(Math.abs(y0), Math.abs(y0 + 16)),
				nz = z0 < 0 && z0 + 16 > 0 ? 0 : Math.min(Math.abs(z0), Math.abs(z0 + 16));
			double cylMin = Math.max(Math.sqrt(nx * nx + nz * nz), ny), sph = Math.sqrt(mx * mx + my * my + mz * mz);
			this.tStatRecs += m.tRecCount;
			if (cyl + 1.0 < rdStart) this.tStatIn += m.tRecCount;
			else if (cylMin - 1.0 >= rdEnd) this.tStatC += m.tRecCount;
			else if (cylMin - 1.0 >= rdStart && cyl + 1.0 <= rdEnd && sph < envEnd && (cylMin - 1.0 - rdStart) / (rdEnd - rdStart) >= sph / envEnd) this.tStatB += m.tRecCount;
		}
		this.tStatFrames++;
		long now = System.nanoTime();
		if (now - this.tStatLast >= 5_000_000_000L) {
			if (this.tStatFrames > 0 && this.tStatRecs > 0)
				System.out.printf("mcopt-own-frag: translucent fog class: %.0f records a frame, %.1f%% in sections inside the render-distance fog start (%.1f), %.1f%% in the band with that term winning, %.1f%% beyond its end (%.1f)%n",
					(double) this.tStatRecs / this.tStatFrames, 100.0 * this.tStatIn / this.tStatRecs, rdStart, 100.0 * this.tStatB / this.tStatRecs,
					100.0 * this.tStatC / this.tStatRecs, rdEnd);
			this.tStatRecs = this.tStatIn = this.tStatB = this.tStatC = this.tStatFrames = 0;
			this.tStatLast = now;
		}
	}

	public boolean drawTranslucent(CameraRenderState camera, long fadeMs, GpuSampler sampler, GpuTextureView atlas, List<SectionRenderDispatcher.RenderSection> visible) {
		if (!TRANSLUCENT || !MetalBridge.inRenderPass(this.encoder)) return false;
		// (probe modes "notrans" / "skipall": our translucent terrain not drawn, the frame's translucent cost measured apart)
		if (PROBE_MS > 0 && (PROBE_MODES[this.probeMode].equals("notrans") || PROBE_MODES[this.probeMode].equals("skipall"))) return true;
		if (NO_FADE) fadeMs = 0;
		int k = (int) (this.frame % FRAMES);
		GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer(), fog = RenderSystem.getShaderFog();
		GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
		if (projection == null || fog == null || globals == null) return false;
		int n = 0;
		// -Dmcopt.own.int.visible: only the visible slots with translucent units, in the same far-to-near order (OwnVisible)
		int[] order = null;
		int orderCount = 0;
		if (OwnVisible.ON || OwnVisible.VERIFY) {
			order = OwnVisible.visibleSlotsReversed(this.tSlots, this.tSlotsVersion, this.orderCount);
			orderCount = this.orderCount[0];
			if (OwnVisible.VERIFY) {
				this.verifyTranslucentOrder(visible, order, orderCount);
				order = null;
			}
		}
		double cx = camera.pos.x, cy = camera.pos.y, cz = camera.pos.z;
		if (TSTAT) {
			long now = System.nanoTime();
			if (this.tsAt == 0) this.tsAt = now;
			if (now - this.tsAt > 5_000_000_000L) {
				double sec = (now - this.tsAt) / 1e9, fr = Math.max(1, this.tsFrames);
				long rs = TS_RESORTS.sumThenReset(), rq = TS_RESORT_QUADS.sumThenReset(), rns = TS_RESORT_NS.sumThenReset();
				System.out.printf("mcopt-own tstat: %.1f s, %d frames: resorts %.0f/s (%.0f quads/s, %.1f us each in resorted()), stores %.0f/s (%.0f quads/s); listed per frame: meshes %.0f, units %.0f; sorted copies %.1f MB held, %.1f MB/s written; lean units %.1f%%%n",
					sec, this.tsFrames, rs / sec, rq / sec, rns / 1e3 / Math.max(1, rs), TS_STORES.sumThenReset() / sec, TS_STORE_QUADS.sumThenReset() / sec,
					this.tsMeshes / fr, this.tsUnits / fr, TS_SORTED_BYTES.get() / 1048576.0, TS_COPY_BYTES.getAndSet(0) / 1048576.0 / sec,
					100.0 * this.tsLeanUnits / Math.max(1, this.tsAllUnits));
				this.tsLeanUnits = this.tsAllUnits = 0;
				this.tsAt = now;
				this.tsFrames = this.tsMeshes = this.tsUnits = 0;
			}
			this.tsFrames++;
		}
		if (TCULL && ++this.tcFrames % 600 == 0) {
			long now = System.nanoTime();
			if (now > this.tcLogAt) {
				this.tcLogAt = now + 5_000_000_000L;
				System.out.printf("mcopt-own tcull: %d frames, meshes listed %d (full order %d), refilters %d over %d quads, %d left out (%.1f%%)%n", this.tcFrames,
					this.tcListed, this.tcFull, TC_REFILTERS.get(), TC_QUADS.get(), TC_DROPPED.get(), 100.0 * TC_DROPPED.get() / Math.max(1, TC_QUADS.get()));
			}
		}
		long fpa = this.tFrNow ? MetalBridge.bufferAddress(projection.buffer()) : 0;
		// (TFRUSTUM needs the projection buffer the draw binds: vanilla's level projection carries view bobbing, hurt tilt and the
		// nausea skew, which camera.projectionMatrix doesn't; unreadable, the frame lists every section)
		if (fpa == 0) this.tFrNow = false;
		if (this.tFrNow) {
			// (TFRUSTUM: the side planes of the clip matrix the draw below uses, normalized: inside when x . p + w >= 0)
			Matrix4f fp = new Matrix4f().setFromAddress(fpa + projection.offset());
			Matrix4f fc = fp.mul(camera.viewRotationMatrix);
			Vector4f fv = new Vector4f();
			int[] fps = {Matrix4fc.PLANE_NX, Matrix4fc.PLANE_PX, Matrix4fc.PLANE_NY, Matrix4fc.PLANE_PY};
			for (int j = 0; j < 4; j++) {
				fc.frustumPlane(fps[j], fv);
				this.tFrPlanes[j * 4] = fv.x;
				this.tFrPlanes[j * 4 + 1] = fv.y;
				this.tFrPlanes[j * 4 + 2] = fv.z;
				this.tFrPlanes[j * 4 + 3] = fv.w;
			}
		}
		if (order != null) {
			for (int j = 0; j < orderCount; j++) {
				OwnMesh m = this.slotMesh[order[j]];
				if (m != null && !(m.tOut = this.tFrNow && this.tOutside(m, order[j], cx, cy, cz))) n += this.tCount(m, order[j], cx, cy, cz);
			}
		} else {
			for (int i = visible.size() - 1; i >= 0; i--) {
				int slot = visible.get(i).index;
				OwnMesh m = slot < this.slotCap ? this.slotMesh[slot] : null;
				if (m != null && !(m.tOut = this.tFrNow && this.tOutside(m, slot, cx, cy, cz))) n += this.tCount(m, slot, cx, cy, cz);
			}
		}
		if (this.tFrNow && ++this.tFrFrames % 600 == 0) {
			long now = System.nanoTime();
			if (now > this.tFrLogAt) {
				this.tFrLogAt = now + 5_000_000_000L;
				System.out.printf("mcopt-own tfrustum: %d frames, per frame: translucent sections left out %.1f of %.1f listed by vanilla, their stored quads %.0f%n",
					this.tFrFrames, this.tFrSkipped / 600.0, this.tFrListed / 600.0, this.tFrSkippedQ / 600.0);
			}
			this.tFrSkipped = this.tFrSkippedQ = this.tFrListed = 0;
		}
		int entry = FAT ? 32 : 4;
		if (n > this.tListCap || this.tArgs == 0) {
			int cap = Math.max(1024, Integer.highestOneBit(Math.max(1, n)) * 2);
			for (int i = 0; i < FRAMES; i++) {
				long old = this.tLists[i];
				if (old != 0) this.later(() -> OwnNative.release(old));
				this.tLists[i] = OwnNative.buffer(this.ctx, (long) cap * entry, true);
				this.tListAddresses[i] = OwnNative.contents(this.tLists[i]);
			}
			this.tListCap = cap;
			if (this.tArgs == 0) {
				this.tArgs = OwnNative.buffer(this.ctx, FRAMES * 64L, true);
				this.tArgsAddress = OwnNative.contents(this.tArgs);
			}
		}
		long out = this.tListAddresses[k];
		int at = 0;
		// (thalf: the split after the last section not lean-eligible; the fog start and now as frag_cull reads them)
		int tSplit = 0, tNowMs = (int) (Util.getMillis() - this.baseMs);
		float tRdStart = 0;
		if (this.tHalfNow) {
			long fa = MetalBridge.bufferAddress(fog.buffer());
			if (fa != 0) tRdStart = MemoryUtil.memGetFloat(fa + fog.offset() + 24);
		}
		if (OwnFrag.FOG_LOG) this.tFogStat(camera, fog, order, orderCount, visible);
		for (int i = order != null ? orderCount - 1 : visible.size() - 1; i >= 0; i--) {
			int slot = order != null ? order[orderCount - 1 - i] : visible.get(i).index;
			OwnMesh m = slot < this.slotCap ? this.slotMesh[slot] : null;
			if (m == null || m.tRecCount == 0) continue;
			if (this.tFrNow) {
				this.tFrListed++;
				if (m.tOut) {
					this.tFrSkipped++;
					Layer ol = m.layers[2];
					if (ol != null) this.tFrSkippedQ += ol.quadCount;
					continue;
				}
			}
			if (TSTAT) {
				this.tsMeshes++;
				this.tsUnits += TCULL && m.tUse ? m.fRecCount : m.tRecCount;
			}
			if (TFLAT) {
				// (a quad entry each, copied from the mesh's entries of the version tCount picked)
				long e = this.tEntries(m);
				int c = TCULL && m.tUse ? m.eFiltN : m.eFullN;
				if (c > 0) MemoryUtil.memCopy(e, out + (long) at * 4, (long) c * 4);
				at += c;
				continue;
			}
			// (TCULL: the filtered version's units when the camera is inside its box, as tcull decided in the counting pass; tsorted: the
			// version's sorted copy's units)
			boolean filtered = TCULL && m.tUse;
			int units = filtered ? m.fRecCount : m.tRecCount, first = filtered ? m.fRec : m.tRecStart;
			if (this.tSortedNow) first = filtered ? (m.fsRec >= 0 ? m.fsRec : first) : (m.sRecStart >= 0 ? m.sRecStart : first);
			if (this.tHalfNow && units > 0) {
				// lean: faded in and the section box wholly inside the render-distance fog start (frag_cull's rule, on the CPU); the list's
				// nearest run of such sections is drawn lean, everything before it rich (one split: the order is kept)
				long sa = this.master + (long) slot * SECTION_BYTES;
				boolean lean = tNowMs - MemoryUtil.memGetInt(sa + 12) >= fadeMs;
				if (lean) {
					double ox = MemoryUtil.memGetInt(sa) - cx, oy = MemoryUtil.memGetInt(sa + 4) - cy, oz = MemoryUtil.memGetInt(sa + 8) - cz;
					double mx = Math.max(Math.abs(ox), Math.abs(ox + 16)), my = Math.max(Math.abs(oy), Math.abs(oy + 16)), mz = Math.max(Math.abs(oz), Math.abs(oz + 16));
					lean = Math.max(Math.sqrt(mx * mx + mz * mz), my) + 1.0 < tRdStart;
				}
				if (!lean) tSplit = at + units;
			}
			for (int r = 0; r < units; r++, at++) {
				int rec = first + r;
				if (FAT) {
					long e = out + (long) at * 32, ra = this.recAddress + (long) rec * REC_BYTES, sa = this.master + (long) slot * SECTION_BYTES;
					MemoryUtil.memPutInt(e, MemoryUtil.memGetInt(ra));
					MemoryUtil.memPutInt(e + 4, MemoryUtil.memGetInt(ra + 8));
					MemoryUtil.memPutInt(e + 8, MemoryUtil.memGetInt(sa));
					MemoryUtil.memPutInt(e + 12, MemoryUtil.memGetInt(sa + 4));
					MemoryUtil.memPutInt(e + 16, MemoryUtil.memGetInt(sa + 8));
					MemoryUtil.memPutInt(e + 20, MemoryUtil.memGetInt(sa + 12));
					MemoryUtil.memPutInt(e + 24, MemoryUtil.memGetInt(ra + 12));
					MemoryUtil.memPutInt(e + 28, 0);
				} else {
					MemoryUtil.memPutInt(out + (long) at * 4, rec);
				}
			}
		}
		long args = this.tArgsAddress + k * 64L;
		// (thalf: the rich part [0, split) here, the lean part [split, at) at +32)
		boolean leanPart = this.tHalfNow && !TFLAT && tSplit < at;
		int richN = leanPart ? tSplit : at;
		MemoryUtil.memPutInt(args, TFLAT ? at * 6 : RUN * 6);
		MemoryUtil.memPutInt(args + 4, TFLAT ? (at > 0 ? 1 : 0) : richN);
		MemoryUtil.memPutInt(args + 32, RUN * 6);
		MemoryUtil.memPutInt(args + 36, at - richN);
		MemoryUtil.memPutInt(args + 40, 0);
		MemoryUtil.memPutInt(args + 44, 0);
		MemoryUtil.memPutInt(args + 48, 0);
		MemoryUtil.memPutInt(args + 8, 0);
		MemoryUtil.memPutInt(args + 12, 0);
		MemoryUtil.memPutInt(args + 16, 0);
		if (at == 0) return true;
		Matrix4f proj = new Matrix4f();
		long pa = MetalBridge.bufferAddress(projection.buffer());
		if (pa != 0) proj.setFromAddress(pa + projection.offset());
		else proj.set(camera.projectionMatrix);
		Matrix4fc view = camera.viewRotationMatrix;
		Matrix4f clip = new Matrix4f(proj).mul(view);
		GpuBufferSlice terrain = RenderSystem.getDynamicUniforms().writeTerrainTransform(new Matrix4f(view), atlas.getWidth(0), atlas.getHeight(0));
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long u = stack.nmalloc(8, 8 * 8);
			MemoryUtil.memPutLong(u, MetalBridge.useBuffer(this.encoder, projection.buffer()));
			MemoryUtil.memPutLong(u + 8, projection.offset());
			MemoryUtil.memPutLong(u + 16, MetalBridge.useBuffer(this.encoder, globals));
			MemoryUtil.memPutLong(u + 24, 0);
			MemoryUtil.memPutLong(u + 32, MetalBridge.useBuffer(this.encoder, terrain.buffer()));
			MemoryUtil.memPutLong(u + 40, terrain.offset());
			MemoryUtil.memPutLong(u + 48, MetalBridge.useBuffer(this.encoder, fog.buffer()));
			MemoryUtil.memPutLong(u + 56, fog.offset());
			GpuTextureView light = Minecraft.getInstance().gameRenderer.lightmap();
			GpuSampler lightSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			long d = stack.ncalloc(16, 1, DRAW_FRAME_BYTES);
			MemoryUtil.memPutInt(d + 4, (int) (Util.getMillis() - this.baseMs));
			MemoryUtil.memPutInt(d + 8, (int) fadeMs);
			if (CPU_CLIP) {
				MemoryUtil.memPutInt(d + 12, 1);
				clip.getToAddress(d + 16);
			}
			// (tprobe 8: the translucent draw with the frag variants' half colour and lean varyings: K_HALF 1 << 18, K_LEAN 1 << 19; thalf:
			// half on both parts, lean on the second)
			int half = TPROBE == 8 ? 1 << 18 | 1 << 19 : this.tHalfNow ? 1 << 18 : 0;
			if (richN > 0 || !leanPart)
				OwnNative.draw(this.own, this.enc, 3 | half | OwnFrag.tKind(), d, DRAW_FRAME_BYTES, this.arenaBuffer, this.tables[k], this.recBuffer, this.tLists[k], this.tArgs, k * 64L, u,
					MetalBridge.viewHandle(atlas), MetalBridge.samplerHandle(sampler), MetalBridge.viewHandle(light), MetalBridge.samplerHandle(lightSampler),
					TFLAT ? this.tIndices(at) : 0);
			if (leanPart || this.tHalfNow && !this.tLeanWarm) {
				// (the lean pipeline is made on its first draw: the first half frame draws it, with no instances if there is no lean part,
				// so its compile can't land mid-flight)
				this.tLeanWarm = true;
				MemoryUtil.memPutInt(d, richN);  // (DrawFrame.listBase: the lean part's first entry)
				OwnNative.draw(this.own, this.enc, 3 | half | 1 << 19 | OwnFrag.tKind(), d, DRAW_FRAME_BYTES, this.arenaBuffer, this.tables[k], this.recBuffer, this.tLists[k], this.tArgs,
					k * 64L + 32, u, MetalBridge.viewHandle(atlas), MetalBridge.samplerHandle(sampler), MetalBridge.viewHandle(light), MetalBridge.samplerHandle(lightSampler), 0);
				this.tsLeanUnits += at - richN;
			}
			this.tsAllUnits += at;
		}
		MetalBridge.reapplyPipeline(this.encoder);
		return true;
	}

	/** The prefiltered frame (PREFILTER): the quads that can make a fragment, drawn from region 0's index lists. */
	private void drawPrefiltered(long f, Matrix4f clip, long d, long arena, int k, long u, GpuTextureView atlas, GpuSampler sampler, GpuTextureView light,
		GpuSampler lightSampler, boolean draw) {
		synchronized (this.quadAlloc) {
			this.ensureQuadOwner(Math.max(1, quadIds(this.quadAlloc.capacity())));
		}
		this.ensureQuadIndices();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long q = stack.ncalloc(16, 1, QUAD_FRAME_BYTES);
			clip.getToAddress(q);
			MemoryUtil.memCopy(f + 64, q + 64, 12);
			MemoryUtil.memCopy(f + 80, q + 80, 12);
			MemoryUtil.memPutInt(q + 96, Minecraft.getInstance().gameRenderer.mainRenderTarget().width);
			MemoryUtil.memPutInt(q + 100, Minecraft.getInstance().gameRenderer.mainRenderTarget().height);
			MemoryUtil.memPutInt(q + 116, this.qcap);
			MemoryUtil.memPutInt(q + 120, RUN);
			MemoryUtil.memPutInt(q + 124, COMPACT ? 1 : 0);
			MemoryUtil.memPutInt(q + 128, this.listCap);
			MemoryUtil.memPutInt(q + 152, QUADS_LIST ? 1 : 0);
			OwnNative.quadsPre(this.own, this.enc, q, QUAD_FRAME_BYTES, this.tables[k], this.recBuffer, this.args, this.tested, this.qIdx, this.qArgs, arena);
			for (int list = 0; list < 2 && draw; list++) this.drawQuadList(list, d, arena, k, u, atlas, sampler, light, lightSampler);
		}
		MetalBridge.reapplyPipeline(this.encoder);
	}

	/** The per-quad path's frame (QUADS): phase A, the split, phase B. */
	private void drawQuads(long f, Matrix4f clip, CameraRenderState camera, long d, long arena, int k, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, boolean draw) {
		this.ensureQuadOwner(1);
		synchronized (this.quadAlloc) {
			this.ensureQuadOwner(quadIds(this.quadAlloc.capacity()));
		}
		this.ensureQuadIndices();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long q = stack.ncalloc(16, 1, QUAD_FRAME_BYTES);
			clip.getToAddress(q);
			MemoryUtil.memCopy(f + 64, q + 64, 12);
			MemoryUtil.memCopy(f + 80, q + 80, 12);
			MemoryUtil.memPutInt(q + 116, this.qcap);
			MemoryUtil.memPutInt(q + 120, RUN);
			MemoryUtil.memPutInt(q + 124, COMPACT ? 1 : 0);
			MemoryUtil.memPutInt(q + 128, this.listCap);
			// (RING: each frame continues the bits of two frames back, two interleaved chains; frame / 2 keeps every chain's
			// 1-in-4 re-test going through all four phases)
			MemoryUtil.memPutInt(q + 132, (int) (RING ? this.frame >> 1 : this.frame));
			MemoryUtil.memPutInt(q + 140, QUADS_COARSE);
			MemoryUtil.memPutInt(q + 144, QUADS_UNIT_STAGGER ? 1 : 0);
			MemoryUtil.memPutInt(q + 148, QUADS_UNITS ? 1 : 0);
			MemoryUtil.memPutInt(q + 152, QUADS_LIST ? 1 : 0);
			MemoryUtil.memPutInt(q + 156, QUADS_BOX ? 1 : 0);
			if (STATS && this.qArgsAddress != 0) {
				// (the counts this copy last held: the previous frame's, or with RING three frames back's; read before this frame resets them)
				long a = this.qArgsAddress;
				this.qDrawnA += (MemoryUtil.memGetInt(a) + MemoryUtil.memGetInt(a + 20)) / 6;
				this.qDrawnB += (MemoryUtil.memGetInt(a + 40) + MemoryUtil.memGetInt(a + 60)) / 6;
				this.qDrawnFrames++;
			}
			MemoryUtil.memPutInt(q + 160, Integer.getInteger("mcopt.own.quads.debug", 0));
			MemoryUtil.memPutInt(q + 164, Boolean.getBoolean("mcopt.own.quads.flat") ? 1 : 0);
			MemoryUtil.memPutInt(q + 168, Boolean.getBoolean("mcopt.own.quads.centre") ? 1 : 0);
			MemoryUtil.memPutInt(q + 176, Boolean.getBoolean("mcopt.own.quads.gate") ? 1 : 0);
			MemoryUtil.memPutInt(q + 180, Boolean.getBoolean("mcopt.own.quads.alu") ? 1 : 0);
			MemoryUtil.memPutInt(q + 184, QUADS_FATT ? 1 : 0);
			MemoryUtil.memPutInt(q + 188, Boolean.getBoolean("mcopt.own.quads.lazy") ? 1 : 0);
			MemoryUtil.memPutInt(q + 172, this.quadsMesh ? 1 : 0);  // (OwnFrag.QMESH)
			OwnNative.quadsA(this.own, this.enc, q, QUAD_FRAME_BYTES, this.recBuffer, this.args, this.tested, this.qPrev, this.qCur, this.qWords, this.qIdx,
				this.qArgs, this.fat);
			for (int i = 0; i < 2 && draw; i++) this.drawQuadList(this.quadsCutFirst ? 1 - i : i, d, arena, k, u, atlas, sampler, light, lightSampler);
			boolean split = OwnNative.quadsB(this.own, this.enc, q, QUAD_FRAME_BYTES, this.tables[k], this.recBuffer, this.args, this.tested, this.qPrev, this.qCur,
				this.qIdx, this.qArgs, arena, QUADS_BOX ? this.quadBox : 0, this.fat) == 1;
			if (split) {
				MetalBridge.restorePass(this.encoder);
				for (int i = 2; i < 4 && draw; i++) this.drawQuadList(this.quadsCutFirst ? 5 - i : i, d, arena, k, u, atlas, sampler, light, lightSampler);
				if (!RING) {
					long t = this.qPrev;
					this.qPrev = this.qCur;
					this.qCur = t;
				}
				MetalBridge.restorePass(this.encoder);
			} else {
				MetalBridge.reapplyPipeline(this.encoder);
			}
		}
	}

	private void drawQuadList(int list, long d, long arena, int k, long u, GpuTextureView atlas, GpuSampler sampler, GpuTextureView light, GpuSampler lightSampler) {
		// (cutfirst: cutout before solid, solid with a strict depth test so cutout keeps winning its ties with it)
		int greater = this.quadsCutFirst && (list & 1) == 0 ? OwnFrag.K_GREATER : 0;
		// (OwnFrag.QMESH: the list by mesh shading; its count and start are read from qArgs by list number, its dispatch at qArgs 128 + list * 12)
		if (this.quadsMesh) MemoryUtil.memPutInt(d, list);
		OwnNative.draw(this.own, this.enc, (list & 1) | 128 | greater | (QUADS_LIST ? 1 << 20 : 0) | this.quadsKind | OwnFrag.qmeshKind(this.quadsMesh), d, DRAW_FRAME_BYTES, arena,
			this.tables[k], this.recBuffer, this.quadOwner, this.qArgs, this.quadsMesh ? 128 + list * 12L : list * 20L, u,
			MetalBridge.viewHandle(atlas), MetalBridge.samplerHandle(sampler), MetalBridge.viewHandle(light), MetalBridge.samplerHandle(lightSampler), this.qIdx);
	}

	/** The drawn-quad tally for this frame's opaque lists (QSTATS); rebinds the pass afterwards. */
	private void tally(Matrix4f clip, long f, long arena, int k) {
		if (this.qsBuffer == 0) {
			this.qsBuffer = OwnNative.buffer(this.ctx, 64, 2);
			this.qsAddress = OwnNative.contents(this.qsBuffer);
			MemoryUtil.memSet(this.qsAddress, 0, 64);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long q = stack.ncalloc(16, 1, QUAD_FRAME_BYTES);
			clip.getToAddress(q);
			MemoryUtil.memCopy(f + 64, q + 64, 12);
			MemoryUtil.memCopy(f + 80, q + 80, 12);
			MemoryUtil.memPutInt(q + 120, RUN);
			MemoryUtil.memPutInt(q + 124, COMPACT ? 1 : 0);
			MemoryUtil.memPutInt(q + 128, this.listCap);
			if (OwnNative.qstats(this.own, this.enc, q, QUAD_FRAME_BYTES, this.tables[k], this.recBuffer, this.args, this.lists, arena, this.qsBuffer,
				2 * this.listCap * RUN) == 1) {
				MetalBridge.restorePass(this.encoder);
				this.qsFrames++;
			}
		}
	}

	private void drawList(int list, long d, long arena, int k, long u, GpuTextureView atlas, GpuSampler sampler, GpuTextureView light, GpuSampler lightSampler) {
		MemoryUtil.memPutInt(d, (list & 3) * this.listCap);
		int kind = ((list & 4) != 0 ? 2 : (list & 8) != 0 ? 4 + (list & 1) : list & 1) | (list >> 4 & 3) << 4;
		if ((list & 256) != 0) kind = 6;          // the cutout depth pre-pass
		if ((list & 512) != 0) kind |= 64;        // cutout colour after it: depth EQUAL
		OwnNative.draw(this.own, this.enc, kind, d, DRAW_FRAME_BYTES, arena, this.tables[k], this.recBuffer, FAT ? this.fat : this.lists, this.args, (list & 3) * 20L, u,
			MetalBridge.viewHandle(atlas), MetalBridge.samplerHandle(sampler), MetalBridge.viewHandle(light), MetalBridge.samplerHandle(lightSampler));
	}

	private static int warmGeneration;
	private int seenWarmGeneration;
	/** Render-thread only, invoked by OwnProbe after validating the entire request. */
	static void configureWarm(String[] names, long milliseconds) {
		PROBE_MODES = names;
		PROBE_MS = milliseconds;
		warmGeneration++;
	}

	private String probe() {
		if (seenWarmGeneration != warmGeneration) {
			seenWarmGeneration = warmGeneration;
			probeSum = new double[PROBE_MODES.length];
			probeCount = new long[PROBE_MODES.length];
			probeMode = probePos = probeWindow = 0;
			probeSwitch = probeLastFrame = probeLog = 0;
			probeRng.setSeed(Long.getLong("mcopt.own.probe.seed", 1));
		}
		long now = System.nanoTime();
		if (this.probeLastFrame != 0) {
			this.probeSum[this.probeMode] += (now - this.probeLastFrame) / 1e6;
			this.probeCount[this.probeMode]++;
		}
		this.probeLastFrame = now;
		if (this.probeSwitch == 0) {
			// a window = one pass over the modes, in an order shuffled per window (-Dmcopt.own.probe.seed)
			this.probeSwitch = now;
			this.probeOrder = new int[PROBE_MODES.length];
			for (int i = 0; i < this.probeOrder.length; i++) this.probeOrder[i] = i;
			this.shuffleProbe();
			this.probeMode = this.probeOrder[0];
			this.probeSelect();
		}
		if (now - this.probeSwitch >= PROBE_MS * 1_000_000L) {
			this.probeSwitch = now;
			if (++this.probePos == this.probeOrder.length) {
				this.probePos = 0;
				this.probeWindow++;
				this.shuffleProbe();
			}
			this.probeMode = this.probeOrder[this.probePos];
			this.probeLastFrame = 0;  // the switching frame itself is no one's
			this.probeSelect();
		}
		if (this.probeLog == 0) this.probeLog = now;
		if (now - this.probeLog >= 5_000_000_000L) {
			StringBuilder b = new StringBuilder("mcopt-own probe:");
			for (int i = 0; i < PROBE_MODES.length; i++) {
				b.append(String.format(" %s %.4f ms (%d)", PROBE_MODES[i], this.probeCount[i] == 0 ? 0 : this.probeSum[i] / this.probeCount[i], this.probeCount[i]));
				this.probeSum[i] = 0;
				this.probeCount[i] = 0;
			}
			System.out.println(b);
			this.probeLog = now;
		}
		return this.probeOverride ? "draw" : PROBE_MODES[this.probeMode];
	}

	private int[] probeOrder;
	private int probePos, probeWindow;
	private boolean probeOverride;
	private final java.util.Random probeRng = new java.util.Random(Long.getLong("mcopt.own.probe.seed", 1));

	private void shuffleProbe() {
		for (int i = this.probeOrder.length - 1; i > 0; i--) {
			int j = this.probeRng.nextInt(i + 1), t = this.probeOrder[i];
			this.probeOrder[i] = this.probeOrder[j];
			this.probeOrder[j] = t;
		}
	}

	/** The current mode's flag overrides (OwnProbe), the native flags they reach, and the mode / window tag on this frame's GPU record. */
	private void probeSelect() {
		this.probeOverride = OwnProbe.select(PROBE_MODES[this.probeMode]);
		applyNativeFlags();
		mcopt.metal.GpuTimes.probeTag(this.probeMode, this.probeWindow, PROBE_MODES);
	}

	/** The pyramid / unit-test flags in the native globals (OwnNative.setHizTiled), through OwnProbe. */
	static void applyNativeFlags() {
		OwnNative.setHizTiled(Boolean.getBoolean("mcopt.own.hizTile"), OwnProbe.bool("hizTop", Boolean.getBoolean("mcopt.own.hizTop")),
			OwnProbe.bool("hizGather", Boolean.getBoolean("mcopt.own.hizGather")));
	}

	private void stats() {
		this.statFrames++;
		long now = System.nanoTime();
		if (now - this.statLast < 5_000_000_000L) return;
		long quadsUsed;
		int quadCap;
		synchronized (this.quadAlloc) {
			quadsUsed = this.quadAlloc.used();
			quadCap = this.quadAlloc.capacity();
		}
		System.out.printf("mcopt-own stats: %d frames, slots %d (cap %d), meshes %d, records %d, quads %d (%.1f of %.1f MB), lists %d, drawn records solid %d cutout %d%n",
			this.statFrames, this.maxSlot + 1, this.slotCap, this.meshes.size(), this.recAlloc.used(), quadsUsed, quadsUsed * ARENA_QUAD / 1048576.0,
			(double) quadCap * ARENA_QUAD / 1048576.0, this.listCap, MemoryUtil.memGetInt(this.argsAddress + 4) + MemoryUtil.memGetInt(this.argsAddress + 44),
			MemoryUtil.memGetInt(this.argsAddress + 24) + MemoryUtil.memGetInt(this.argsAddress + 64));
		if (QUADS && this.qArgsAddress != 0) {
			System.out.printf("mcopt-own quads: units tested %d, quads drawn A solid %d cutout %d, B solid %d cutout %d%n", MemoryUtil.memGetInt(this.argsAddress + 80),
				MemoryUtil.memGetInt(this.qArgsAddress) / 6, MemoryUtil.memGetInt(this.qArgsAddress + 20) / 6, MemoryUtil.memGetInt(this.qArgsAddress + 40) / 6,
				MemoryUtil.memGetInt(this.qArgsAddress + 60) / 6);
			if (this.qDrawnFrames > 0) {
				System.out.printf("mcopt-own quads mean: drawn %.0f a frame (A %.0f, B %.0f) over %d frames%n", (double) (this.qDrawnA + this.qDrawnB) / this.qDrawnFrames,
					(double) this.qDrawnA / this.qDrawnFrames, (double) this.qDrawnB / this.qDrawnFrames, this.qDrawnFrames);
				this.qDrawnA = this.qDrawnB = this.qDrawnFrames = 0;
			}
		}
		if (this.solid != null) System.out.println("mcopt-own solid: " + this.solid.stats());
		if (this.qsBuffer != 0 && this.qsFrames > 0) {
			long[] v = new long[8];
			for (int i = 0; i < 8; i++) v[i] = Integer.toUnsignedLong(MemoryUtil.memGetInt(this.qsAddress + i * 4L));
			double fr = this.qsFrames;
			System.out.printf("mcopt-own tally per frame: units %.0f, quads %.0f, back-facing %.0f, outside frustum %.0f, no pixel centre %.0f, hidden %.0f, visible %.0f, vertex invocations %.0f%n",
				v[0] / fr, v[1] / fr, v[2] / fr, v[3] / fr, v[4] / fr, v[5] / fr, v[6] / fr, v[7] / fr);
			MemoryUtil.memSet(this.qsAddress, 0, 64);
			this.qsFrames = 0;
		}
		long c = COMPILES.sumThenReset(), cns = COMPILE_NS.sumThenReset(), st = STORES.sumThenReset(), sns = STORE_NS.sumThenReset();
		System.out.printf("mcopt-own mesh: %d compiles %.3f ms each (%.0f ms), %d layer stores %.3f ms each (%.0f ms)%n", c, c == 0 ? 0 : cns / 1e6 / c, cns / 1e6, st,
			st == 0 ? 0 : sns / 1e6 / st, sns / 1e6);
		this.statFrames = 0;
		this.statLast = now;
	}
}
