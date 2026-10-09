package mcopt.metal.own;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import mcopt.metal.MetalBridge;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The GPU cost of our solid and cutout terrain (metal/OWN-RENDERER.md, the frag section). Off unless a
 * -Dmcopt.own.frag.* flag is set or an in-run probe mode "f-..." runs; then the cull (terrain.metal frag_cull) sorts the visible
 * units into classed lists and the layers are drawn in the configured order with pipelines specialized per class.
 * <ul>
 * <li>order: vanilla (solid, then cutout), cutfirst (cutout, then solid with a strict depth test, so cutout still wins vanilla's
 * depth ties: no alpha-tested draw lands on a tile's pending opaque work) or bpre (cutout depth with its alpha test and a one-unit
 * depth bias, then solid, then cutout's colour with the same bias, depth EQUAL and no alpha test: the only alpha-tested draw comes
 * first and writes no colour) or split (solid, then the pass split and reopened, then cutout: the alpha-tested draw starts on tiles
 * with no pending opaque work)</li>
 * <li>sizes: units in size classes of 8 quads, each class one draw of its own size (fewer padding vertices)</li>
 * <li>lean: sections faded in and wholly inside the render-distance fog start drawn without the varyings that are constant there</li>
 * <li>half: the vertex colour as half4</li>
 * <li>box: each unit's own box against the frustum</li>
 * <li>sort: cutout units in BINS distance bins of their section, drawn near to far (alpha-tested fragments drawn first reject more
 * behind them; no size classes for cutout then)</li>
 * <li>nonidx: probe only, 6 vertex invocations a quad (the index reuse's worth)</li>
 * <li>posonly: probe only, both layers with position-only vertex output and a constant fragment stage (the geometry's floor)</li>
 * <li>tight: the unit's record and the compact vertex each in one 16-byte load, no default outputs for padding lanes (bit-identical;
 * offline M1-ISA count: vertex program 132 -> 114 instructions, 8 -> 4 loads)</li>
 * <li>mesh: mesh shading (terrain.metal own_mesh): a threadgroup per unit, its quads culled one by one (clip volume, facing) and packed,
 * no padding (units of up to 32 quads); mesh4: the same with a thread per vertex</li>
 * <li>occ: two-phase occlusion inside the mesh stage (implies mesh, vanilla order): phase A emits the quads each unit record's mask says
 * were visible, the pass is split once for the depth pyramid (tiled with -Dmcopt.own.hizTile), phase B tests every quad of the same
 * units against it, emits the visible ones phase A did not draw and rewrites the mask. No list build, no index lists, no clears.</li>
 * <li>uocc: two-phase occlusion of whole units (instanced draws, size classes): frame N's test writes each visible unit's next frame
 * number, frame N + 1's cull (it runs anyway) lists exactly those for phase A; one split for the tiled pyramid; one thread per tested
 * unit tests its box (no vertex fetch) and lists the visible ones phase A did not draw for phase B. No list build of its own, no clears.</li>
 * </ul>
 */
final class OwnFrag {
	static final int VANILLA = 0, CUTFIRST = 1, BPRE = 2, SPLIT = 3;
	/** Quads per size class step. */
	/** -Dmcopt.own.frag.sizeQuads=N (probe, power of two, default 8): quads per size class step (padding vs draws). */
	private static final int SIZE_QUADS = Math.max(1, Integer.highestOneBit(Math.max(1, Integer.getInteger("mcopt.own.frag.sizeQuads", 8)))), FRAG_FRAME_BYTES = 64, BINS = 8;
	/**
	 * Draw kind bits (mcown.m mco_draw; bits 0-7 are the base path's): depth EQUAL (the base path's), depth GREATER, depth bias, F_HALF, F_LEAN, F_NONIDX,
	 * mesh, F_REVERSE.
	 */
	static final int K_GREATER = 1 << 16, K_HALF = 1 << 18;
	private static final int K_EQUAL = 64, K_BIAS = 1 << 17, K_LEAN = 1 << 19, K_NONIDX = 1 << 20,
		K_MESH = 1 << 21, K_REVERSE = 1 << 22, K_MESH4 = 1 << 23, K_BOUND = 1 << 24, K_TIGHT = 1 << 25, K_POSONLY = 1 << 26, K_OCCA = 1 << 27,
		K_OCCB = 1 << 28, K_QMESH = 1 << 29, K_FOGVERT = 1 << 14, K_FOGVERT_ALL = 1 << 15, K_FOGVERT_T = 1 << 13;
	/**
	 * -Dmcopt.own.frag.qmesh (with -Dmcopt.own.quads): the per-quad path's GPU-built lists drawn by mesh shading (terrain.metal
	 * own_mesh_q: 32 listed quads a threadgroup, the owner section once per quad) instead of indexed draws of their u32 index lists.
	 */
	static final boolean QMESH = Boolean.getBoolean("mcopt.own.frag.qmesh");
	/**
	 * -Dmcopt.own.frag.facestats (with -Dmcopt.own.stats): after the frag cull, every listed quad classified by its own winding seen from
	 * the camera (terrain.metal frag_face_stats): how many of the quads we draw face away (vertex work the rasterizer then drops).
	 */
	static final boolean FACESTATS = Boolean.getBoolean("mcopt.own.frag.facestats");
	/**
	 * -Dmcopt.own.frag.msub (with uocc and the mesher's -Dmcopt.own.mesh.subbox): uocc's test reads the mesher's two sub-boxes from the
	 * unit record instead (the mesher's layout, a surface-area split); a unit without them is tested by its box. Probe option nosub too.
	 */
	static final boolean MSUB = Boolean.getBoolean("mcopt.own.frag.msub");
	/**
	 * -Dmcopt.own.frag.pipe (with uocc): frames alternate between two sets of the frag path's per-frame buffers (lists and arguments, the
	 * unit test's list, phase B's lists and arguments, the visibility marks), and the unit test marks a visible unit for the frame after
	 * next: frame N + 1's cull then reads nothing frame N's mid-frame test writes and nothing frame N still reads, so it need not wait for
	 * frame N's split (the cross-frame overlap uocc lost). Still exact: phase B tests every unit against this frame's pyramid.
	 */
	static final boolean PIPE = Boolean.getBoolean("mcopt.own.frag.pipe");
	/**
	 * -Dmcopt.own.frag.lists2 (without uocc): frames alternate between two sets of the cull's lists and draw arguments, so frame N + 1's
	 * cull writes one set while frame N's level pass still reads the other. With one set, the cull waits until the frame before it has
	 * finished its vertex work (it rewrites what that vertex work reads), which puts the cull on the frame's critical path. Same lists,
	 * same order, same draws: exact. (With uocc, pipe does this and more.)
	 */
	static final boolean LISTS2 = Boolean.getBoolean("mcopt.own.frag.lists2");
	/**
	 * -Dmcopt.own.frag.splitA (with uocc): phase A in two parts around the test, so the GPU has vertex work while the test waits on
	 * phase A's depth. Vertex work runs one render encoder at a time in submission order, and phase B's vertex work waits for the
	 * test, so without this the vertex stream idles through phase A's fragment work and the test. Here phase A's solid units go
	 * first (the pyramid and the test read their depth), then phase A's cutout units in an encoder of their own (it reads nothing the
	 * test writes), then phase B in another. Same draws in the same order: exact. The pyramid lacks phase A's cutout depth, so
	 * the test culls a little less. (The oracle's phase-A redraw assumes both layers before the test: no oracle with this.)
	 */
	static final boolean SPLIT_A = Boolean.getBoolean("mcopt.own.frag.splitA");
	/**
	 * -Dmcopt.own.frag.splitNear=B (with uocc; blocks, 0 = off): phase A split by section distance around the test. Phase A's solid units
	 * of sections nearer than B blocks draw first (the pyramid and the test read their depth); after the test come the farther
	 * sections' solid units, then all of phase A's cutout units (still every solid unit before every cutout one), each in an encoder
	 * phase B's arguments don't gate, then phase B. A section's units stay together and in order (the split is per section). The
	 * cull lists phase A into two banks of the same lists (FragFrame flag 1024): the list count doubles. Exact; the pyramid lacks
	 * the farther sections' depth, so the test culls a little less.
	 */
	static final int SPLIT_NEAR = Integer.getInteger("mcopt.own.frag.splitNear", 0);
	/**
	 * -Dmcopt.own.frag.nearOnly (with splitNear=B): phase A draws only its nearer sections' units. A farther section's unit visible at
	 * the last test is not drawn before or after the test unconditionally: like every other candidate it is tested against the pyramid
	 * of the near depth and drawn in phase B if it passes. Exact (a box behind depth that phase A drew is behind the final depth too).
	 * The interval's serial chain V(A) + F(A) + test + V(B) loses the farther units' fragment work, which moves to F(B), off the chain.
	 */
	static final boolean NEAR_ONLY = Boolean.getBoolean("mcopt.own.frag.nearOnly");
	/**
	 * -Dmcopt.own.frag.testLate (with splitNear or splitA): the split's compute in two encoders around phase A's later part: the
	 * pyramid of phase A's first part, then that later part (its own render encoder), then the unit test, then phase B. Only the
	 * pyramid reads the depth, so the later part's fragment work waits for the pyramid alone (with one compute encoder it waited for
	 * the whole test: a write-after-read on the depth), and the test runs under that fragment work. Same pyramid, draws and order: exact.
	 */
	static final boolean TEST_LATE = Boolean.getBoolean("mcopt.own.frag.testLate");
	/**
	 * -Dmcopt.own.tl.a1Split=P (probe key tl.a1Split; 0 = off, the default; 1-99): phase A's first part (A1) as two render
	 * passes. A pass's fragment work starts only after all of its vertex work (TBDR), and A1's vertex and fragment work are both on the
	 * frame's serial chain (the next frame's A1 waits for this frame's last vertex work; the pyramid waits for A1's fragments). Split in
	 * two, the first pass's fragments run while the second's vertex work does. The split is inside A1's largest list (its biggest class,
	 * lean when lean is on, drawn first or second of A1's eight): the cull's encoder also writes that list's draw as two (terrain.metal
	 * own_tl_a1split: its first P% of instances, then the rest from that base instance), A1 draws the first, the render encoder is ended
	 * and reopened (colour and depth stored and loaded: mcmetal.m mc_tl_restart), then the second and A1's other lists. The same units
	 * in the same order: the same pixels. Not with a1Exact (A1's table draws).
	 */
	static final int TL_A1_SPLIT = Integer.getInteger("mcopt.own.tl.a1Split", 0);
	/**
	 * -Dmcopt.own.tl.a1SplitMaxCores=N / a1SplitMinCores=M (rig gates, as a2ExactMaxCores / uoccMaxMpCores): tl.a1Split only on GPUs of at
	 * most N or at least M cores (either matching; neither set: every GPU; an unknown core count: off when either is set). The split
	 * adds fragment work (a pass can't hide the first part's fragments the second covers) and a store and reload: it pays where A1's
	 * vertex work is long against its fragment work (A18 Pro: hold -63 us) or fragment work is fast (M6), and loses where the
	 * fragment stream binds the hold (M4, 10 cores: +39 us).
	 */
	static final int TL_A1_MAX_CORES = Integer.getInteger("mcopt.own.tl.a1SplitMaxCores", 0), TL_A1_MIN_CORES = Integer.getInteger("mcopt.own.tl.a1SplitMinCores", 0);
	private static final boolean TL_A1_GATED;
	static {
		int cores = mcopt.metal.Profile.gpuCores();
		boolean any = TL_A1_MAX_CORES > 0 || TL_A1_MIN_CORES > 0;
		boolean on = !any || cores > 0 && (TL_A1_MAX_CORES > 0 && cores <= TL_A1_MAX_CORES || TL_A1_MIN_CORES > 0 && cores >= TL_A1_MIN_CORES);
		TL_A1_GATED = !on;
		if (TL_A1_SPLIT > 0) System.out.println("mcopt-own-frag tl.a1Split: " + TL_A1_SPLIT + "% " + (on ? "on" : "off") + " (gpu cores " + cores + ", max " + TL_A1_MAX_CORES + ", min " + TL_A1_MIN_CORES + ")");
	}
	/** tl.a1Split: this frame's percent (0 none), the list split, its two draws' arguments (a buffer a frame slot), whether A1 restarted. */
	private int a1sNow, a1sId;
	private long a1sCur;
	private final long[] a1sBuf = new long[3];
	private int a1sFrame;
	private boolean a1sDidSplit;
	/**
	 * tl.a1Split fails closed: the halves are drawn only when the native request was taken (the kernel exists), the cull wrote this frame's
	 * split arguments and the open render pass is one mc_tl_restart supports; otherwise the list is drawn unsplit (one draw from the cull's
	 * own arguments), each reason logged once and counted. -Dmcopt.own.tl.a1SplitFailTest=true (test only): the kernel's creation stubbed
	 * to fail, so every frame takes the unsplit path.
	 */
	private static final boolean A1S_FAIL_TEST = Boolean.getBoolean("mcopt.own.tl.a1SplitFailTest");
	private boolean a1sTestSet;
	private long a1sSplit, a1sNoKernel, a1sNotWritten, a1sPassRefused, a1sLogAt;
	private final java.util.Set<String> a1sLogged = new java.util.HashSet<>();

	private void a1sOnce(String why) {
		if (this.a1sLogged.add(why)) System.out.println("mcopt-own-frag tl.a1Split: drawn unsplit: " + why + " (logged once)");
	}

	/** tl.a1Split: the open render pass is one mc_tl_restart supports (memory-backed colour + Depth32Float, no MSAA, no pending discard). */
	private boolean a1sPassOk() {
		int why = OwnNative.tlRestartOk(this.enc);
		if (why == 0) return true;
		this.a1sNow = 0;
		this.a1sPassRefused++;
		this.a1sOnce("the render pass isn't one a restart keeps exact (mcmetal.m mc_tl_restart_ok reason " + why + ")");
		return false;
	}

	/** tl.a1Split's counts (every 10 s while it is requested): frames split, and frames drawn unsplit by reason. */
	private void a1sLog() {
		long now = System.nanoTime();
		if (now < this.a1sLogAt) return;
		this.a1sLogAt = now + 10_000_000_000L;
		System.out.printf("mcopt-own-frag tl.a1Split: frames split %d; unsplit: kernel unavailable %d, not written %d, pass unsupported %d%n", this.a1sSplit,
			this.a1sNoKernel, this.a1sNotWritten, this.a1sPassRefused);
	}
	/**
	 * -Dmcopt.own.frag.bRedraw (uocc; probe key frag.bRedraw): phase B redraws, in its cutout pass, the cutout units phase A drew that
	 * may hold a coincident partner of a solid unit phase B draws (the same section and facing bucket, boxes overlapping). Vanilla draws
	 * all of SOLID before all of CUTOUT with GREATER_EQUAL, so a cutout overlay wins its exact depth tie with the solid quad under it
	 * (grass_block_side_overlay over grass_block_side: 99.7% of a frame's depth ties). A solid unit that comes back
	 * through the test into phase B is drawn after phase A's cutout and would win those ties; drawn again after it, the overlay wins as
	 * in vanilla. Every depth compare stays GREATER_EQUAL. The redraw is idempotent where nothing changed: same pipeline, same vertices
	 * and frame, same depth and colour, no blending on the cutout layer. The cull marks the cutout units it lists for phase A (epoch and
	 * list id, in occVis's second region, FragFrame.recCap); the test's first visit claims each, so a unit is redrawn at most once.
	 * Work only where phase B has solid units (disocclusion frames), none in a still frame.
	 */
	static final boolean B_REDRAW = Boolean.getBoolean("mcopt.own.frag.bRedraw");
	/**
	 * Measurement: -Dmcopt.own.frag.dropSolidA (uocc; probe key frag.dropSolidA): no solid unit is drawn in phase A, every visible one
	 * comes through the test into phase B (FragFrame flag 16384, terrain.metal fragInAL), while cutout keeps its marks: every solid
	 * unit is disoccluded at once, every frame. Without bRedraw each grass side's base covers its overlay; with it the picture must
	 * be the base's. Slow (no pyramid depth from solid) and for checks only.
	 */
	static final boolean DROP_SOLID_A = Boolean.getBoolean("mcopt.own.frag.dropSolidA");
	/**
	 * -Dmcopt.own.frag.tieClose (uocc; probe key frag.tieClose, which can turn it off in-run; the components are built only with the
	 * static on): promotion closure over tie components (terrain.metal fragTiePromote). Vanilla draws SOLID then CUTOUT with
	 * GREATER_EQUAL, so an equal-depth pair's later quad wins; uocc keeps that order within each phase but not across them (a
	 * grass_block_side overlay in phase A, its newly visible base in phase B after it). With tieClose, if any unit of a tie component
	 * (OwnTieGroups' tolerant corner groups, no winding split, as record rings: OwnTerrain.tieRing) is phase A's by its mark, every
	 * unit of it the cull passes is drawn in phase A too, in its ordered place: a component's drawn units are in one phase. A frame
	 * draws without uocc (FLAGS_NO_UOCC, exact) when that can't be relied on: OwnTerrain.tieCloseFallback (grouping off with
	 * inFlight > 2, a cross-section candidate, a section not grouped, the float32 bound, list capacity). Not with frag.dropSolidA.
	 */
	static final boolean TIE_CLOSE = OwnTieGroups.CLOSE;
	/** -Dmcopt.own.frag.tieCloseVerify (check): from the submitted lists, records in both phase A and B, or twice in A (both must stay 0). */
	static final boolean TIE_VERIFY = TIE_CLOSE && Boolean.getBoolean("mcopt.own.frag.tieCloseVerify");
	/**
	 * (fault injection, -Dmcopt.own.frag.capFault=N) every phase A and B list holds at most N entries this run (FragFrame.capLimit):
	 * the capacity counters (A / T / B entries lost, terrain.metal CAP_CNT) must see it. Lists are otherwise sized so nothing is lost.
	 */
	static final int CAP_FAULT = Math.max(0, Integer.getInteger("mcopt.own.frag.capFault", 0));
	/** (fault injection, -Dmcopt.own.frag.tCapFault=N) the unit test's list holds at most N entries: the T loss counter must see it. */
	static final int T_CAP_FAULT = Math.max(0, Integer.getInteger("mcopt.own.frag.tCapFault", 0));
	/** tieClose frames by OwnTerrain.tieCloseFallback reason (0 = closure drawn), since the last log. */
	private static final long[] TIE_FRAMES = new long[6];
	/**
	 * -Dmcopt.own.frag.a1Exact (with uocc; probe key frag.a1Exact): phase A's first part (A1: the nearer bank's solid lists, the pyramid's
	 * depth, on the frame's serial chain) draws each unit with exactly its quads. A size class draws every unit with the class's quad
	 * count (bucketClass=suffix: issued / real ~1.24 in A1), the rest degenerate vertices that still take SIMD lanes. Here the cull, as it
	 * appends an A1 unit to its list, also writes the unit's real quads into its list's region of a per-quad table (entry = record << 6 |
	 * quad; the same ordered append, so a section's units keep their record order), and frag_finish makes each region a draw: each A1
	 * list draws once over its region (F_QFLAT, kind bit 12 = K_EXACT): the same units, the same in-section order, the same quads, no
	 * padding. A region that overflows draws its class draw instead. A2, phase B and translucent are unchanged. (Earlier forms: an
	 * indirect command buffer of a draw a unit, ~52 ns a draw on the Neo and no textures in its pipelines; a table
	 * built by separate kernels after the cull, which put ~+200 us on the Neo's chain.) Probe key frag.a1Table (measurement): the table
	 * written, the class draws drawn.
	 */
	static final boolean A1_EXACT = Boolean.getBoolean("mcopt.own.frag.a1Exact");
	/**
	 * -Dmcopt.own.frag.a2Exact (with uocc and splitNear; probe key frag.a2Exact): the same exact-count table for phase A's later part,
	 * A2: the farther bank's solid lists and both banks' cutout lists (cutout units draw at the full run, RUN quads, as class draws; late
	 * flight's A2 is ~29% padding). Each such list draws over its table region; one past its region (A1_REGION blocks) or
	 * past listCap draws its class draw (the same pipeline, listBase bit 31). Independent of a1Exact.
	 * -Dmcopt.own.frag.exactMaxCores=N (rig gate, as uoccMaxMpCores): a1Exact / a2Exact only on GPUs of at most N cores (the Neo has
	 * 5; 0: every GPU).
	 */
	static final boolean A2_EXACT = Boolean.getBoolean("mcopt.own.frag.a2Exact");
	static final int EXACT_MAX_CORES = Integer.getInteger("mcopt.own.frag.exactMaxCores", 0);
	/** -Dmcopt.own.frag.a2ExactMaxCores=N: a2Exact only on GPUs of at most N cores (an unknown core count: off; 0: every GPU). */
	static final int A2_MAX_CORES = Integer.getInteger("mcopt.own.frag.a2ExactMaxCores", 0);
	private static boolean coreGated(int max) {
		int cores = mcopt.metal.Profile.gpuCores();
		return max > 0 && (cores <= 0 || cores > max);
	}
	private static final boolean EXACT_GATED = coreGated(EXACT_MAX_CORES), A2_GATED = coreGated(A2_MAX_CORES);
	/**
	 * -Dmcopt.own.frag.vGroup=N (2, 4 or 8; default 0, off): phase A's class draws as instances of N consecutive units of their list
	 * instead of one (FragFrame flags bits 24-25 = log2 N; mcown.m picks the grouped vertex stage, kind bit 11 / terrain.metal F_GROUP,
	 * and frag_finish's grouped draw arguments). Same primitives in the same order: exact. On the M4 an instanced class draw of one
	 * unit an instance runs in a slow mode (gpumodel suite v1/v2: A1-like set 250 -> 175-180 us vertex grouped by 2, rich 255 -> 195).
	 * -Dmcopt.own.frag.vGroupMinCores=N: only on GPUs of at least N cores (an unknown count: off; 0: every GPU).
	 */
	static final int VGROUP = Integer.getInteger("mcopt.own.frag.vGroup", 0), VGROUP_MIN_CORES = Integer.getInteger("mcopt.own.frag.vGroupMinCores", 0);
	private static final int VGROUP_SH = vGroupShift();
	/** -Dmcopt.own.frag.vArgsCopy (measurement): the class draws read their arguments from the finishes' private copy, ungrouped. */
	static final boolean VARGS_COPY = Boolean.getBoolean("mcopt.own.frag.vArgsCopy");
	/**
	 * -Dmcopt.own.frag.vGroupFailA / vGroupFailB (test only): phase A's / phase B's grouped-argument writer treated as impossible to make.
	 * vGroup fails closed: native's preflight needs both writers before a frame groups at all, and a draw reads grouped arguments only
	 * when its writer was encoded that frame (mcown.m vg[].written); otherwise the ordinary finishes' arguments, ungrouped.
	 */
	static final boolean VGROUP_FAIL_A = Boolean.getBoolean("mcopt.own.frag.vGroupFailA"), VGROUP_FAIL_B = Boolean.getBoolean("mcopt.own.frag.vGroupFailB");
	private boolean vgTestSet;
	private final long vgTel = MemoryUtil.nmemCalloc(6, 8);
	private long vgLogAt;

	/** vGroup's counts (every 10 s while it is requested): culls grouped / not, class draws grouped from A / B / as split halves, ungrouped. */
	private void vgLog(int sh) {
		long now = System.nanoTime();
		if (sh == 0 || now < this.vgLogAt) return;
		this.vgLogAt = now + 10_000_000_000L;
		OwnNative.vGroupStats(this.own, this.vgTel);
		long[] v = new long[6];
		for (int i = 0; i < 6; i++) v[i] = MemoryUtil.memGetLong(this.vgTel + 8L * i);
		System.out.printf("mcopt-own-frag vGroup: culls grouped %d, requested but ungrouped %d; class draws grouped: phase A %d, phase B %d, split halves %d; drawn ungrouped %d%n",
			v[0], v[1], v[2], v[3], v[4], v[5]);
	}
	private static int vGroupShift() {
		if (VGROUP != 2 && VGROUP != 4 && VGROUP != 8) return 0;
		int cores = mcopt.metal.Profile.gpuCores();
		if (VGROUP_MIN_CORES > 0 && (cores <= 0 || cores < VGROUP_MIN_CORES)) return 0;
		return Integer.numberOfTrailingZeros(VGROUP);
	}
	/** -Dmcopt.own.frag.exactShift=S (0-3; default 2): a table entry stands for 2^S quads of a unit (S 0: per quad, no padding left). */
	static final int EXACT_SHIFT = Math.max(0, Math.min(3, Integer.getInteger("mcopt.own.frag.exactShift", 2)));
	private static final int K_EXACT = 1 << 12;
	/** a1Exact / a2Exact this frame (decided at the cull, read by the draws), and the frame counter for the command buffers' two sets. */
	private boolean a1Exact, a2Exact, a1Req, a2Req;
	/** The effective-mode line (every 5 s while either is requested): the native telemetry's 16 words, and when it was last printed. */
	private final long exactTel = MemoryUtil.nmemCalloc(32, 4);
	private long exactLogAt;
	private int a1Frame;
	/**
	 * -Dmcopt.own.frag.pyrAuto=W (uocc): the pyramid starts one or two levels up (texels of 4 x 4 or 8 x 8 pixels instead of 2 x 2) while
	 * the level above the first built one stays at least W texels wide; a coarser texel is the farthest depth of more pixels, so the test
	 * only gets more conservative. A fixed level: -Dmcopt.own.frag.pyrSkip=1|2 (probe options pyr4 / pyr8), which wins over this.
	 */
	static final int PYR_AUTO = Integer.getInteger("mcopt.own.frag.pyrAuto", 0);
	/**
	 * Measurement only: -Dmcopt.own.frag.aKind (or the in-run probe key frag.aKind) draws phase A's first solid draw (A1 with splitNear),
	 * or without uocc (vanilla order) the whole solid layer, with another fragment stage, to split its fragment cost: tex = own_fs without the fade mix and the fog (own_fs_tex), nofade / nofog = own_fs
	 * without the fade mix / without the fog (own_fs_nofade / own_fs_nofog), flat = the
	 * vertex colour only (own_fs_flat), pos = no varyings and a constant colour, skip = not drawn. Pictures are wrong by design.
	 */
	static final String A_KIND = System.getProperty("mcopt.own.frag.aKind", "");
	/** Measurement: -Dmcopt.own.frag.fogLog logs vanilla's fog block (environmental and render-distance start / end) when it changes. */
	static final boolean FOG_LOG = Boolean.getBoolean("mcopt.own.frag.fogLog");
	/**
	 * Measurement only: -Dmcopt.own.frag.tKind (or the in-run probe key frag.tKind) for our translucent draw: fogvertall = the fog value
	 * per vertex (environmental and render-distance terms, max) in its pipeline, not exact; the saving's upper bound there.
	 */
	static final String T_KIND = System.getProperty("mcopt.own.frag.tKind", "");

	/**
	 * -Dmcopt.own.frag.fogVert (in-run probe key frag.fogVert): our translucent draw takes the fog per vertex where that is exact. One
	 * pipeline and one draw, order unchanged: the vertex stage tests its section's whole box against three classes (wholly inside the
	 * render-distance fog start: that term 0, the environmental one unclamped; wholly in the band with the render-distance term above the
	 * environmental one, both unclamped; wholly beyond the render-distance end: fog 1) and passes a flat flag with the fog value; the
	 * fragment stage uses it in class (the interpolated value is the same affine function vanilla's per-pixel fog takes there) and vanilla's
	 * per-pixel fog elsewhere (F_FOGVERT_T). Exact up to float rounding (settle.png max diff 1). Mini 1080p flight -1.9%, spin +0.5%.
	 */
	static final boolean FOG_VERT = Boolean.getBoolean("mcopt.own.frag.fogVert");

	/** The translucent draw's kind bits this frame (T_KIND / the probe's frag.tKind; else -Dmcopt.own.frag.fogVert). */
	static int tKind() {
		return switch (OwnProbe.string("frag.tKind", T_KIND)) {
			case "fogvertall" -> K_FOGVERT | K_FOGVERT_ALL;  // (every translucent fragment: the upper bound, not exact)
			case "fogvert" -> K_FOGVERT_T;  // (sections in the exact class per vertex, the rest per pixel: exact)
			default -> OwnProbe.bool("frag.fogVert", FOG_VERT) ? K_FOGVERT_T : 0;
		};
	}
	private String fogLogged = "";

	/** Phase A's first solid draw's kind this frame (A_KIND / the probe's frag.aKind); -1: not drawn. */
	private static int aKind(int base) {
		return switch (OwnProbe.string("frag.aKind", A_KIND)) {
			case "tex" -> base | 8;
			case "nofade" -> base | 9;  // (own_fs without the fade mix: exact for fully faded-in units)
			case "nofog" -> base | 10;  // (own_fs without the fog)
			case "fogvert" -> base | K_FOGVERT;  // (the fog value per vertex in lean pipelines: exact where no clamp is active)
			case "fogvertall" -> base | K_FOGVERT | K_FOGVERT_ALL;  // (in every pipeline: the saving's upper bound, not exact)
			case "flat" -> base | 2;
			case "pos" -> base | 7 | K_POSONLY;
			case "skip" -> -1;
			default -> base;
		};
	}
	/**
	 * -Dmcopt.own.frag.a2Enc=1|2 (measurement, with splitNear): phase A's later part in render encoders of its own per layer, so a GPU
	 * trace times each: 1 = farther solid | all cutout, 2 = farther solid | nearer cutout | farther cutout (the same draws in the same order).
	 */
	static final int A2_ENC = Integer.getInteger("mcopt.own.frag.a2Enc", 0);
	/** -Dmcopt.own.frag.newInA2 (with splitNear): newInA, its untested units in phase A's later part (A2), not before the test. */
	static final boolean NEW_IN_A2 = Boolean.getBoolean("mcopt.own.frag.newInA2");
	/**
	 * -Dmcopt.own.frag.newInA (with uocc): the test marks every unit it tests, visible or not, and phase A also draws the units with
	 * no test in their mark set: those just come into the frustum (a spin's leading edge) or just meshed. Without it they wait for the
	 * test and phase B, whose vertex work is on the frame's serial chain. Exact: phase A only draws more (FragFrame flag 4096).
	 */
	static final boolean NEW_IN_A = Boolean.getBoolean("mcopt.own.frag.newInA") || NEW_IN_A2;

	/** The lists the cull writes: Config.listCount(), twice with splitNear (two banks of phase A's lists). */
	private static int lists(Config c) {
		return c.listCount() * (c.uocc && SPLIT_NEAR > 0 ? 2 : 1);
	}

	/** splitNear this frame: the static, or the in-run probe's positive override (OwnProbe; the banks are sized by the static). */
	private static int splitNear() {
		if (SPLIT_NEAR <= 0) return SPLIT_NEAR;
		int v = OwnProbe.integer("frag.splitNear", SPLIT_NEAR);
		return v > 0 ? v : SPLIT_NEAR;
	}

	/**
	 * -Dmcopt.own.oracle (debug only, never in a profile): every ORACLE_EVERY-th frame, after the layers' draws (base: the
	 * lists; uocc: phase A's and phase B's lists), the same lists are drawn again with depth EQUAL against the frame's final depth,
	 * early fragment tests, no colour and no depth written (kind bit 30: terrain.metal F_ORACLE, own_fs_oracle). The vertex stage marks
	 * every drawn unit's record, the fragment stage every unit that owns a visible pixel, with the sample's number; ORACLE_READ frames
	 * later the CPU counts both (units, quads, per layer) and bins the drawn-but-invisible units by distance and box size. Cutout's
	 * alpha-killed texels fail EQUAL (the pixel's depth is what lies behind them), so they are not counted as visible.
	 */
	static final boolean ORACLE = Boolean.getBoolean("mcopt.own.oracle");
	private static final int K_ORACLE = 1 << 30, ORACLE_EVERY = 64, ORACLE_READ = 8;

	/** The per-quad path's draw kind bits for its list draws (QMESH: mesh shading of the lists). */
	static int qmeshKind(boolean on) {
		return on ? K_MESH | K_QMESH : 0;
	}

	record Config(int order, boolean sizes, boolean lean, boolean half, boolean box, boolean nonidx, boolean mesh, boolean sort, boolean mesh4,
		boolean tight, boolean posonly, boolean occ, boolean occa, boolean uocc, int fine, boolean nosub, boolean hull, int diag, boolean rpyr, int pyr) {
		static @Nullable Config parse(String opts) {
			int order = VANILLA;
			boolean sizes = false, lean = false, half = false, box = false, nonidx = false, mesh = false, sort = false, mesh4 = false, tight = false,
				posonly = false, occ = false, occa = false, uocc = false;
			int fine = 0;
			boolean nosub = false, hull = false;
			int diag = 0;
			boolean rpyr = false;
			int pyr = 0;
			for (String o : opts.split("\\+")) {
				switch (o.trim()) {
					case "", "vanilla" -> {
					}
					case "cutfirst" -> order = CUTFIRST;
					case "bpre" -> order = BPRE;
					case "split" -> order = SPLIT;
					case "sizes" -> sizes = true;
					case "lean" -> lean = true;
					case "half" -> half = true;
					case "box" -> box = true;
					case "nonidx" -> nonidx = true;
					case "mesh" -> mesh = OwnTerrain.RUN <= 32;
					case "sort" -> sort = true;
					case "mesh4" -> mesh4 = mesh = OwnTerrain.RUN <= 32;
					case "tight" -> tight = true;
					case "posonly" -> posonly = true;
					case "occ" -> occ = OwnTerrain.RUN <= 32;
					case "occa" -> occ = occa = OwnTerrain.RUN <= 32;  // probe only: phase A alone (the masks as the last occ frame left them)
					case "uocc" -> uocc = true;
					case "fine1", "fine2", "fine3", "fine4" -> fine = o.trim().charAt(4) - '0';  // (uocc's finer test, k levels down)
					case "hull" -> hull = true;  // (uocc's test: only the texels the box's projected hull touches)
					case "rpyr" -> rpyr = true;  // (uocc: the pyramid's mip 0 drawn by a render pass, not read by compute)
					case "pyr4" -> pyr = 1;  // (uocc: the pyramid from 4 x 4 pixel texels up, -Dmcopt.own.frag.pyrSkip=1)
					case "pyr8" -> pyr = 2;  // (uocc: from 8 x 8, pyrSkip=2)
					case "nopyr" -> diag = 1;  // (diagnostic, not exact: uocc without this frame's pyramid build)
					case "nosplit" -> diag = 2;  // (diagnostic, not exact: uocc's phase A alone, no split, marks frozen)
					case "nosub" -> nosub = true;  // (probe: uocc without the mesher's sub-boxes, -Dmcopt.own.frag.msub)
					case "nosolid", "noquads" -> {
						// (read by OwnTerrain: solid-section occlusion, the per-quad path off this frame)
					}
					default -> throw new IllegalArgumentException("mcopt-own-frag: unknown option " + o);
				}
			}
			if (occ) {
				// (own_mesh only, in vanilla's layer order; the phases decide the order within a layer)
				mesh = true;
				mesh4 = false;
				order = VANILLA;
			}
			if (uocc) {
				// (instanced draws in vanilla's layer order; the phases decide the order within a layer)
				occ = occa = mesh = mesh4 = nonidx = posonly = false;
				order = VANILLA;
			}
			return new Config(order, sizes, lean, half, box, nonidx, mesh, sort, mesh4, tight, posonly, occ, occa, uocc, fine, nosub, hull, diag, rpyr, pyr);
		}

		int sizeClasses() {
			return this.sizes ? Math.max(1, OwnTerrain.RUN / SIZE_QUADS) : 1;
		}

		/** Classes per layer (list id = (layer * classes + class) * 2 + lean). */
		int classes() {
			return Math.max(this.sizeClasses(), this.sort ? BINS : 1);
		}

		int listCount() {
			return 2 * this.classes() * 2;
		}
	}

	/** The flags' configuration: null (the base path) unless one of -Dmcopt.own.frag.{order,sizes,lean,half,box} is set. */
	static final @Nullable Config FLAGS = flags();
	/**
	 * -Dmcopt.own.frag.uoccMaxMp=X: above X megapixels of framebuffer the frames draw the flags' configuration without uocc (every
	 * unit the cull passes, no split, pyramid or test): uocc's split and pyramid cost grows with pixels and on the M6 it loses from
	 * ~3 MP up (OWN-RENDERER.md, the M6 lane). Exact either way. Checked every frame, so a resize or fullscreen switch takes effect.
	 */
	static final double UOCC_MAX_MP = Double.parseDouble(System.getProperty("mcopt.own.frag.uoccMaxMp", "0"));
	/**
	 * -Dmcopt.own.frag.uoccMaxMpCores=N: the size switch applies only on a GPU with at least N cores (IORegistry gpu-core-count; unreadable:
	 * not applied). Measured: the M6 (12 cores) and the M4 Max (40) draw faster without uocc from ~4 MP up; the M4 (10) and the A18 Pro
	 * (5) keep needing it at every size tried (OWN-RENDERER.md, uocc by GPU).
	 */
	static final int UOCC_MAX_MP_CORES = Integer.getInteger("mcopt.own.frag.uoccMaxMpCores", 0);
	private static final boolean SIZE_SWITCH = UOCC_MAX_MP > 0 && (UOCC_MAX_MP_CORES <= 0 || mcopt.metal.Profile.gpuCores() >= UOCC_MAX_MP_CORES);
	private static final @Nullable Config FLAGS_NO_UOCC = FLAGS != null && FLAGS.uocc() && (SIZE_SWITCH || OwnProbe.ON || TIE_CLOSE)
		? Config.parse(flagString().replace("+uocc", "")) : null;

	/** The flags' configuration this frame: without uocc above -Dmcopt.own.frag.uoccMaxMp (or with the in-run probe's frag.uocc=false). */
	private static @Nullable Config drawFlags() {
		if (FLAGS_NO_UOCC == null) return FLAGS;
		if (!OwnProbe.bool("frag.uocc", true)) return FLAGS_NO_UOCC;
		if (!SIZE_SWITCH) return FLAGS;
		var w = net.minecraft.client.Minecraft.getInstance().getWindow();
		return (double) w.getWidth() * w.getHeight() > UOCC_MAX_MP * 1e6 ? FLAGS_NO_UOCC : FLAGS;
	}

	private static @Nullable Config flags() {
		String s = flagString();
		return s.equals("vanilla") ? null : Config.parse(s);
	}

	private static String flagString() {
		String order = System.getProperty("mcopt.own.frag.order", "vanilla");
		StringBuilder b = new StringBuilder(order);
		for (String f : new String[] {"sizes", "lean", "half", "box", "mesh", "sort", "mesh4", "tight", "occ", "uocc", "hull", "nopyr", "nosplit", "rpyr"}) if (Boolean.getBoolean("mcopt.own.frag." + f)) b.append('+').append(f);
		int fine = Integer.getInteger("mcopt.own.frag.fine", 0);  // (uocc's finer test, -Dmcopt.own.frag.fine=k)
		if (fine >= 1 && fine <= 4) b.append("+fine").append(fine);
		int skip = Integer.getInteger("mcopt.own.frag.pyrSkip", 0);  // (uocc's pyramid from level k up: 1 = 4 x 4 pixel texels, 2 = 8 x 8)
		if (skip == 1 || skip == 2) b.append(skip == 1 ? "+pyr4" : "+pyr8");
		return b.toString();
	}

	/**
	 * The per-quad path (-Dmcopt.own.quads) this frame: cutout first? (the cutfirst order applies there too; the quads' order comes from
	 * the GPU-built lists, so only the strict depth test of solid is needed to keep cutout winning its ties).
	 */
	static boolean cutFirst(String mode) {
		// Off: on the per-quad path the strict test flips solid's own depth ties (its lists are filled in no fixed order): Neo n1,
		// quads + cutfirst 0.004% px > 8 vs vanilla against quads alone 0.002%. Probe mode "f-qcutfirst" keeps it measurable.
		return mode.equals("f-qcutfirst");
	}

	/** The per-quad path this frame: extra draw kind bits (half: the colour varying as half4, a pipeline bit only). */
	static int quadsKind(String mode) {
		Config c = quadsConfig(mode);
		return c == null ? 0 : (c.half() ? K_HALF : 0) | (c.tight() ? K_TIGHT : 0);
	}

	private static @Nullable Config quadsConfig(String mode) {
		if (mode.startsWith("f-")) return Config.parse(mode.substring(2));
		return mode.equals("draw") ? FLAGS : null;
	}

	/** This frame's configuration for probe mode `mode` (null: the base path). The two-phase occlusion and fat lists stay on the base path. */
	static @Nullable Config config(String mode, boolean occOrFat) {
		return config(mode, occOrFat, 0);
	}

	/** config, with this frame's OwnTerrain.tieCloseFallback reason: a uocc frame that can't rely on tieClose draws without uocc. */
	static @Nullable Config config(String mode, boolean occOrFat, int tieReason) {
		if (occOrFat) return null;
		if (mode.startsWith("f-")) return Config.parse(mode.substring(2));
		if (!mode.equals("draw")) return null;
		Config c = drawFlags();
		if (c == null || !c.uocc() || !tieCloseOn()) return c;
		TIE_FRAMES[Math.min(tieReason, 5)]++;
		return tieReason != 0 && FLAGS_NO_UOCC != null ? FLAGS_NO_UOCC : c;
	}

	/** tieClose this frame (the static, unless the in-run probe turns it off; never with dropSolidA). */
	static boolean tieCloseOn() {
		// (OwnTieGroups.ON false: -Dmcopt.metal.inFlight > 2, tieClose is simply off and frames draw as production)
		return TIE_CLOSE && OwnTieGroups.ON && OwnProbe.bool("frag.tieClose", true) && !OwnProbe.bool("frag.dropSolidA", DROP_SOLID_A);
	}

	private final OwnTerrain terrain;
	private final long ctx, enc, own;
	private long lists, args, argsAddress;
	private int listCap, listCount;
	/**
	 * -Dmcopt.own.int.cullR=true: the classified cull a SIMD group per section, lanes over its records (frag_cull_s, frag_cull's list order
	 * kept), instead of a thread per section; =verify runs
	 * both every frame and compares order-free signatures of every list (count, sum, xor, hashed sum), read back FRAMES frames later.
	 * Not for the orders that need a section's units in turn (cutfirst's reverse, distance bins): those keep frag_cull.
	 */
	private static final String CULL_R = System.getProperty("mcopt.own.int.cullR", "false");
	private static final boolean CULL_R_ON = "true".equals(CULL_R), CULL_R_VERIFY = "verify".equals(CULL_R);
	/**
	 * -Dmcopt.own.int.cullW=32|16|8|4: cullR's lanes per section (32 / W sections share a SIMD group). 8 by default: the cull encoder at
	 * the static settle, mini 32 lanes 74-111 us, 16: 113-133, 8: 73-89, 4: 103-106 (section cull 152-166); Neo 32: 292-359, 16: 197-240,
	 * 8: 163-170, 4: 150-153 (section cull 230-247).
	 */
	private static final int CULL_W = Integer.getInteger("mcopt.own.int.cullW", 8);
	/** -Dmcopt.own.int.preEarly=true: the pre command buffer (this cull) committed as soon as it's encoded (mcown.m mco_pre_commit). */
	private static final boolean PRE_EARLY = Boolean.getBoolean("mcopt.own.int.preEarly");
	private long vLists, vArgs;
	private final long[] sigs = new long[3], sigAddrs = new long[3];
	private final int[] sigLists = new int[3];
	private long sigFrame, sigChecked, sigBad, sigLog;
	/** The occlusion's per-record masks (a uint per unit record), sized to the record buffer. */
	private long masks;
	private int masksCap;
	private final long[] statOcc = new long[4];
	/**
	 * The unit occlusion (uocc): occVis (a uint per unit record: the frame number it is drawn in phase A), tList (uint2 per tested unit),
	 * phase B's lists and draw arguments (the same layout as lists / args), this frame's number, and what the test needs at draw time
	 * (the cull frame as OwnTerrain wrote it, the FragFrame, the clip matrix).
	 */
	private long occVis, tList, listsB, bArgs, bArgsAddress;
	private int occVisCap, tCap, bCount;
	/** PIPE / LISTS2: the other frame's set of the per-frame buffers and their sizes (swapped in at each cull). */
	private long pLists, pArgs, pArgsAddress, pOccVis, pTList, pListsB, pBArgs, pBArgsAddress;
	private final long[] statSlots = new long[8];  // (stats: quad slots by bank * 4 + layer * 2 + lean)
	private int pListCount, pListCap, pOccVisCap, pTCap, pBCount;
	private int epoch;
	private long cullFrame, fragFrame = MemoryUtil.nmemCalloc(1, FRAG_FRAME_BYTES), clipAddr = MemoryUtil.nmemCalloc(1, 64);
	private int cullFrameLength;
	/** tieClose: per set of buffers, the list count of the last cull that counted promotions in its bArgs (0 none); totals since the log. */
	private int tieSetN, pTieSetN;
	private long tiePromoted, tiePromotedQuads, tieCounted, tieLogAt = System.nanoTime();
	/** Since the log: entries lost to capacity (A, T, B), and (tieCloseVerify) records in both A and B, records twice in A; and in all. */
	private final long[] tieLost = new long[5], tieLostAll = new long[5];
	private long statTested, statB, statQuadsA, statQuadsB, statRedraw, statRedrawQuads, statRedrawFrames, statA1Quads, statA1Units, statA1Slots, statA1Over;
	private final long[] statFace = new long[5];
	private long statFunnelIn, statFunnelFacing;
	private @Nullable Config last;
	/** This frame's atlas, sampler and lightmap handles (resolved once per frame, not per draw). */
	private long hAtlas, hSampler, hLight, hLightSampler;
	/** A draw of this frame's sequence already bound every resource on the open encoder (later draws pass K_BOUND). */
	private boolean bound;
	/** Draw kinds whose pipeline could not be made (logged once; their draws are skipped, the frame goes on). */
	private final java.util.Set<Integer> failed = new java.util.HashSet<>();
	private long statFrames, statSolid, statCutout, statLean, statLast = System.nanoTime();
	private final long[] statBuckets = new long[14];
	/** The oracle (ORACLE): drawn and visible marks (a uint per unit record, shared), the sample's number and frame, the camera then. */
	private long oKept, oVis, oKeptAddr, oVisAddr, oDraws, oSampleAt = -1, oTest, oTestAddr;
	/** The sample frame's uocc epoch (phase A = units the test passed last frame; this frame's test writes epoch + 1). */
	private int oEpoch;
	private boolean oTested;
	private int oCap, oFrame;
	private double oCamX, oCamY, oCamZ, oCullX, oCullY, oCullZ;
	private @Nullable Config oConfig;
	private long oLogAt;

	OwnFrag(OwnTerrain terrain, long ctx, long enc, long own) {
		this.terrain = terrain;
		this.ctx = ctx;
		this.enc = enc;
		this.own = own;
		System.out.println("mcopt-own-frag: on" + (FLAGS != null ? " (" + FLAGS + ")" : " (probe modes only)") + (LISTS2 ? ", two sets of the cull's lists" : "") + (SPLIT_A ? ", phase A split around the test" : "") + (SPLIT_NEAR > 0 ? ", phase A split at " + SPLIT_NEAR + " blocks" + (NEAR_ONLY ? ", farther units tested, not drawn in phase A" : "") : "") + (TEST_LATE ? ", test after phase A's later part" : "") + (NEW_IN_A ? ", untested units in phase A" + (NEW_IN_A2 ? "'s later part" : "") : "") + (PYR_AUTO > 0 ? ", uocc pyramid coarser while >= " + PYR_AUTO + " texels wide" : "") + (SIZE_SWITCH && FLAGS_NO_UOCC != null ? ", no uocc above " + UOCC_MAX_MP + " MP" + (UOCC_MAX_MP_CORES > 0 ? " (gpu cores " + mcopt.metal.Profile.gpuCores() + " >= " + UOCC_MAX_MP_CORES + ")" : "") : UOCC_MAX_MP > 0 ? ", uocc at every size (gpu cores " + mcopt.metal.Profile.gpuCores() + " < " + UOCC_MAX_MP_CORES + ")" : ""));
	}

	/**
	 * The classified cull into this frame's pre command buffer. f: the CullFrame (terrain.metal) as OwnTerrain filled it; listCap:
	 * OwnTerrain's per-list capacity; fog: vanilla's fog uniform (render-distance fog start); nowMs, fadeMs: as the DrawFrame's.
	 */
	void cull(Config c, long f, int frameBytes, long table, long recs, long mask, int sectionCount, int listCap, GpuBufferSlice fog, int nowMs, int fadeMs,
		int renderDistance, org.joml.Matrix4f clip) {
		if (PIPE && c.uocc || LISTS2 && !c.uocc) this.swapSets();
		int n = lists(c);
		if (n > this.listCount || listCap != this.listCap) {
			long oldLists = this.lists, oldArgs = this.args;
			if (oldLists != 0) this.terrain.later(() -> {
				OwnNative.release(oldLists);
				OwnNative.release(oldArgs);
			});
			int count = Math.max(n, this.listCount);
			this.lists = OwnNative.buffer(this.ctx, 4L * count * listCap, 0);
			// per list 5 indexed draw arguments, then per list 3 mesh dispatch arguments, then the per-bucket stats (14), the occlusion's
			// emitted-quad counters (4), the unit occlusion's T count and test dispatch (4), the facing tally (5)
			this.args = OwnNative.buffer(this.ctx, 32L * count + 128, 2);
			this.argsAddress = OwnNative.contents(this.args);
			this.listCount = count;
			this.listCap = listCap;
		}
		this.last = c;
		if (ORACLE) {
			// the camera of this frame's cull (camBlock - camOffset), kept for the oracle's distance bins
			this.oCullX = MemoryUtil.memGetInt(f + 64) - MemoryUtil.memGetFloat(f + 80);
			this.oCullY = MemoryUtil.memGetInt(f + 68) - MemoryUtil.memGetFloat(f + 84);
			this.oCullZ = MemoryUtil.memGetInt(f + 72) - MemoryUtil.memGetFloat(f + 88);
		}
		if (c.uocc) {
			int cap = this.terrain.recCapacity();
			if (cap > this.occVisCap) {
				long old = this.occVis;
				if (old != 0) this.terrain.later(() -> OwnNative.release(old));
				// (a uint per record, then bRedraw's uint2 and uint per record, then tieClose's TIE_PROMO and TIE_SEEN per record)
				this.occVis = OwnNative.buffer(this.ctx, 24L * cap, 0);
				// (zeroed before the cull reads it, REVIEW-tieclose2: TIE_PROMO / TIE_SEEN hold this frame's number only where written; the
				// number is never 0. Every growth is a new buffer, cleared the same way)
				OwnNative.fragClear(this.own, this.occVis);
				this.occVisCap = cap;
			}
			if (listCap != this.tCap || this.listCount > this.bCount) {
				long oldT = this.tList, oldL = this.listsB, oldA = this.bArgs;
				if (oldT != 0) this.terrain.later(() -> {
					OwnNative.release(oldT);
					OwnNative.release(oldL);
					OwnNative.release(oldA);
				});
				this.tList = OwnNative.buffer(this.ctx, 8L * listCap, 0);
				this.listsB = OwnNative.buffer(this.ctx, 4L * this.listCount * listCap, 0);
				// (then the stats' phase-B quad count, testFast's checks, bRedraw's units and quads, tieClose's, capacity and verify counters)
				this.bArgs = OwnNative.buffer(this.ctx, 20L * this.listCount + 64, 2);
				this.bArgsAddress = OwnNative.contents(this.bArgs);
				this.tCap = listCap;
				this.bCount = this.listCount;
			}
			if (this.tieSetN > 0 && this.bArgsAddress != 0) {
				// (tieClose: the promotions the last cull on this set counted, after its phase B arguments; complete with two frames in flight)
				this.tiePromoted += Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 6) * 4));
				this.tiePromotedQuads += Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 7) * 4));
				for (int k = 0; k < 5; k++) {
					long v = Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 8 + k) * 4));
					this.tieLost[k] += v;
					this.tieLostAll[k] += v;
				}
				this.tieCounted++;
			}
			if (++this.epoch == 0) this.epoch = 1;  // (never 0, the value a cleared occVis holds)
			this.cullFrame = f;
			this.cullFrameLength = frameBytes;
			clip.getToAddress(this.clipAddr);
			OwnNative.fragUoccBuffers(this.own, this.occVis, this.tList, this.bArgs, this.listsB);
		} else if (this.occVis != 0) {
			OwnNative.fragUoccBuffers(this.own, 0, 0, 0, 0);
		}
		if (c.occ) {
			int cap = this.terrain.recCapacity();
			if (cap > this.masksCap) {
				long old = this.masks;
				if (old != 0) this.terrain.later(() -> OwnNative.release(old));
				this.masks = OwnNative.buffer(this.ctx, 4L * cap, 0);
				this.masksCap = cap;
			}
			OwnNative.fragMasks(this.own, this.masks, this.args, (n * 8L + 14) * 4, OwnTerrain.STATS);
		}
		float rdStart = 0;
		long fa = MetalBridge.bufferAddress(fog.buffer());
		if (fa != 0) rdStart = MemoryUtil.memGetFloat(fa + fog.offset() + 24);
		if (FOG_LOG && fa != 0) {
			// (measurement: vanilla's fog block as the frame sets it, logged when it changes)
			long b = fa + fog.offset();
			String v = String.format("env %.2f..%.2f, render distance %.2f..%.2f, sky %.2f, clouds %.2f, colour a %.3f", MemoryUtil.memGetFloat(b + 16),
				MemoryUtil.memGetFloat(b + 20), MemoryUtil.memGetFloat(b + 24), MemoryUtil.memGetFloat(b + 28), MemoryUtil.memGetFloat(b + 32),
				MemoryUtil.memGetFloat(b + 36), MemoryUtil.memGetFloat(b + 12));
			if (!v.equals(this.fogLogged)) {
				System.out.println("mcopt-own-frag: fog " + v);
				this.fogLogged = v;
			}
		}
		// tieClose: the rings for the cull (none yet: no published mesh has opaque units, so no components either)
		long ring = TIE_CLOSE ? this.terrain.tieRing() : 0;
		boolean tie = c.uocc && ring != 0 && tieCloseOn();
		if (TIE_CLOSE) {
			OwnNative.fragTieRing(this.own, tie ? ring : 0);
			OwnNative.fragTieVerify(this.own, tie && TIE_VERIFY ? this.lists : 0, tie && TIE_VERIFY);
			if (c.uocc) this.tieSetN = tie ? n : 0;
			this.tieLog();
		}
		this.vgLog(OwnProbe.integer("frag.vGroupSh", VGROUP_SH) | (VARGS_COPY ? 4 : 0));
		if ((VGROUP_FAIL_A || VGROUP_FAIL_B) && !this.vgTestSet) {
			OwnNative.vGroupFailTest(VGROUP_FAIL_A, VGROUP_FAIL_B);
			this.vgTestSet = true;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long g = stack.ncalloc(16, 1, FRAG_FRAME_BYTES);
			MemoryUtil.memPutFloat(g, rdStart);
			MemoryUtil.memPutInt(g + 4, nowMs);
			MemoryUtil.memPutInt(g + 8, fadeMs);
			MemoryUtil.memPutInt(g + 12, (c.lean && fa != 0 ? 1 : 0) | (c.sizes ? 2 : 0) | (c.box ? 4 : 0) | (c.order == CUTFIRST ? 8 : 0) | (c.sort ? 16 : 0)
				| (OwnTerrain.STATS ? 32 : 0) | (c.uocc ? 64 : 0) | (c.uocc && OwnProbe.bool("frag.msub", MSUB) && !c.nosub ? 256 : 0) | (c.uocc && PIPE ? 512 : 0) | (c.uocc && SPLIT_NEAR > 0 ? 1024 : 0)
				| (c.uocc && SPLIT_NEAR > 0 && NEAR_ONLY ? 2048 : 0) | (c.uocc && NEW_IN_A ? 4096 : 0) | (c.uocc && NEW_IN_A2 && SPLIT_NEAR > 0 ? 8192 : 0)
				| (c.uocc && OwnProbe.bool("frag.dropSolidA", DROP_SOLID_A) ? 16384 : 0)
				| (c.uocc && OwnProbe.bool("frag.bRedraw", B_REDRAW) && !tieCloseOn() ? 32768 : 0)  // (bRedraw off whenever tieClose is on, REVIEW-tieclose2)
				| (OwnQuads.BUCKET_CLASS ? 128 : 0) | (tie ? 1 << 20 : 0) | (OwnProbe.integer("frag.vGroupSh", VGROUP_SH) & 3) << 24 | (VARGS_COPY ? 1 << 26 : 0));
			MemoryUtil.memPutInt(g + 16, c.sizeClasses());
			MemoryUtil.memPutInt(g + 20, OwnTerrain.RUN / c.sizeClasses());
			MemoryUtil.memPutInt(g + 24, n);
			MemoryUtil.memPutInt(g + 28, c.classes());
			MemoryUtil.memPutFloat(g + 32, Math.max(16, renderDistance * 16f / BINS));
			MemoryUtil.memPutInt(g + 36, OwnTerrain.RUN);
			MemoryUtil.memPutInt(g + 40, this.epoch);
			MemoryUtil.memPutInt(g + 44, T_CAP_FAULT > 0 ? Math.min(this.tCap, T_CAP_FAULT) : this.tCap);  // (tCapFault: fault injection)
			MemoryUtil.memPutFloat(g + 48, splitNear());
			MemoryUtil.memPutInt(g + 52, this.occVisCap);  // (FragFrame.recCap: bRedraw's marks follow occVis in its buffer)
			MemoryUtil.memPutInt(g + 56, CAP_FAULT);  // (FragFrame.capLimit: 0 unless the capacity fault is injected)
			MemoryUtil.memCopy(g, this.fragFrame, FRAG_FRAME_BYTES);  // (the unit test reads it at draw time)
			// a1Exact: the cull writes A1's exact-count draws (mode 1); with only -Dmcopt.own.stats the same kernel counts A1's real quads (mode 2)
			// (not with tight / the oracle: their vertex paths read the list as records; not with cutfirst's reversed solid order)
			boolean able = c.uocc && !c.mesh && !c.mesh4 && !c.nonidx && !c.posonly && !c.tight && !ORACLE && c.order != CUTFIRST && !Boolean.getBoolean("mcopt.own.fat");
			this.a1Req = OwnProbe.bool("frag.a1Exact", A1_EXACT);
			this.a2Req = OwnProbe.bool("frag.a2Exact", A2_EXACT);
			this.a1Exact = able && !EXACT_GATED && this.a1Req;
			this.a2Exact = able && !EXACT_GATED && !A2_GATED && this.a2Req && SPLIT_NEAR > 0;
			// (probe key frag.a1Table, measurement: the table is built but A1 draws its class draws: the table's cost alone)
			boolean tableOnly = !this.a1Exact && c.uocc && !c.mesh && !c.mesh4 && !c.nonidx && OwnProbe.bool("frag.a1Table", false);
			int a1Mode = this.a1Exact || this.a2Exact || tableOnly ? 1 : c.uocc && OwnTerrain.STATS ? 2 : 0;
			if (a1Mode != 0 || this.a1Frame > 0) {
				// the lists with a table: A1 = the nearer bank's solid lists (ids 0 .. 2 x classes - 1); A2 = the farther bank's solid
				// lists and both banks' cutout lists (a bank holds listCount() ids: solid first, then cutout)
				long lmask = 0;
				int cls2 = c.classes() * 2, bank = c.uocc && SPLIT_NEAR > 0 ? c.listCount() : lists(c);
				if (this.a1Exact || tableOnly || a1Mode == 2) lmask |= bits(0, cls2);
				if (this.a2Exact) lmask |= bits(bank, bank + cls2) | bits(cls2, bank) | bits(bank + cls2, 2 * bank);
				OwnNative.fragExactMask(this.own, (int) lmask, (int) (lmask >>> 32));
				OwnNative.fragA1Exact(this.own, a1Mode, cls2, listCap, this.a1Frame++);
			}
			if (FACESTATS) OwnNative.fragFace(this.own, this.terrain.arenaBuffer(), true);
			// tl.a1Split: A1's largest list (its biggest size class, lean when lean is on: A1 = layer 0 of bank 0) written as two draws too
			int a1p = c.uocc && SPLIT_NEAR > 0 && !this.a1Exact && c.order != CUTFIRST && !c.sort && !TL_A1_GATED ? OwnProbe.integer("tl.a1Split", TL_A1_SPLIT) : 0;
			this.a1sNow = a1p > 0 && a1p < 100 ? a1p : 0;
			if (this.a1sNow > 0) this.a1sLog();
			if (this.a1sNow > 0) {
				int k = this.a1sFrame++ % 3;
				if (this.a1sBuf[k] == 0) this.a1sBuf[k] = OwnNative.buffer(this.ctx, 64, 0);
				this.a1sCur = this.a1sBuf[k];
				this.a1sId = (c.sizeClasses() - 1) * 2 + (c.lean ? 1 : 0);
				if (A1S_FAIL_TEST && !this.a1sTestSet) {
					OwnNative.tlA1SplitTest(true);
					this.a1sTestSet = true;
				}
				if (!OwnNative.tlA1SplitReq(this.own, this.a1sCur, this.a1sId, this.a1sNow, 100)) {
					this.a1sNow = 0;
					this.a1sNoKernel++;
					this.a1sOnce("the split kernel is unavailable");
				}
			}
			boolean ordered = c.order == CUTFIRST || c.sort;
			if ((CULL_R_ON || CULL_R_VERIFY && !c.uocc) && !ordered) {
				int lanes = CULL_W;
				if (CULL_R_VERIFY) {
					if (this.vArgs == 0 || this.vListsCap != (long) this.listCount * this.listCap) {
						long oa = this.vArgs, ol = this.vLists;
						if (oa != 0) this.terrain.later(() -> {
							OwnNative.release(oa);
							OwnNative.release(ol);
						});
						this.vArgs = OwnNative.buffer(this.ctx, 32L * this.listCount + 128, 0);
						this.vLists = OwnNative.buffer(this.ctx, 4L * this.listCount * this.listCap, 0);
						this.vListsCap = (long) this.listCount * this.listCap;
					}
					int k = (int) (this.sigFrame++ % 3);
					if (this.sigs[k] == 0 || this.sigLists[k] < n) {
						long old = this.sigs[k];
						if (old != 0) this.terrain.later(() -> OwnNative.release(old));
						this.sigs[k] = OwnNative.buffer(this.ctx, 32L * Math.max(n, 64), 2);
						this.sigAddrs[k] = OwnNative.contents(this.sigs[k]);
						this.sigLists[k] = Math.max(n, 64);
						MemoryUtil.memSet(this.sigAddrs[k], 0, 32L * this.sigLists[k]);
					} else if (this.sigFrame > 3) {
						this.checkSignatures(this.sigAddrs[k], n);  // (written three frames ago: complete)
					}
					OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n, lanes,
						this.vArgs, this.vLists, this.sigs[k], this.listCap);
				} else {
					OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n, lanes, 0,
						0, 0, this.listCap);
				}
			} else {
				OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n);
			}
			if (this.a1sNow > 0 && !OwnNative.tlA1SplitWritten(this.own)) {
				this.a1sNow = 0;
				this.a1sNotWritten++;
				this.a1sOnce("the cull didn't write the split arguments");
			}
			if (PRE_EARLY) OwnNative.preCommit(this.enc);
			if (this.terrain.tracing()) {
				System.out.println("mcopt-metal trace: compute encoder 'own frag cull' #" + OwnNative.lastComputeGroup() + " (pre command buffer, profiled like a pass)");
			}
		}
	}

	/** tieClose: every 5 s, the frames by fallback reason, the promotions a counted frame, the rings. */
	private void tieLog() {
		long now = System.nanoTime();
		if (now - this.tieLogAt < 5_000_000_000L) return;
		this.tieLogAt = now;
		long[] t = TIE_FRAMES;
		long all = t[0] + t[1] + t[2] + t[3] + t[4] + t[5];
		System.out.printf("mcopt-own-frag tieClose: %s; uocc frames %d: closure %d, without uocc %d (grouping off, inFlight > 2: %d, cross-section candidate %d, unknown section %d, "
			+ "float scope %d, lists %d); promoted a frame %.2f units, %.1f quads (%d frames counted); entries lost to capacity A %d T %d B %d (run %d / %d / %d)%s; %s%n",
			tieCloseOn() ? "on" : "off (probe)", all, t[0], all - t[0], t[1], t[2], t[3], t[4], t[5], this.tieCounted == 0 ? 0.0 : (double) this.tiePromoted / this.tieCounted,
			this.tieCounted == 0 ? 0.0 : (double) this.tiePromotedQuads / this.tieCounted, this.tieCounted, this.tieLost[0], this.tieLost[1], this.tieLost[2],
			this.tieLostAll[0], this.tieLostAll[1], this.tieLostAll[2], TIE_VERIFY ? String.format("; verify: records in A and B %d, twice in A %d (run %d / %d)",
				this.tieLost[3], this.tieLost[4], this.tieLostAll[3], this.tieLostAll[4]) : "", this.terrain.tieRingStats());
		java.util.Arrays.fill(this.tieLost, 0);
		java.util.Arrays.fill(t, 0);
		this.tiePromoted = this.tiePromotedQuads = this.tieCounted = 0;
	}

	/** PIPE or LISTS2: this frame's set of per-frame buffers becomes the other frame's, and the other's this frame's (sizes with them). */
	private void swapSets() {
		long t;
		int i;
		t = this.lists; this.lists = this.pLists; this.pLists = t;
		t = this.args; this.args = this.pArgs; this.pArgs = t;
		t = this.argsAddress; this.argsAddress = this.pArgsAddress; this.pArgsAddress = t;
		t = this.occVis; this.occVis = this.pOccVis; this.pOccVis = t;
		t = this.tList; this.tList = this.pTList; this.pTList = t;
		t = this.listsB; this.listsB = this.pListsB; this.pListsB = t;
		t = this.bArgs; this.bArgs = this.pBArgs; this.pBArgs = t;
		t = this.bArgsAddress; this.bArgsAddress = this.pBArgsAddress; this.pBArgsAddress = t;
		i = this.listCount; this.listCount = this.pListCount; this.pListCount = i;
		i = this.listCap; this.listCap = this.pListCap; this.pListCap = i;
		i = this.occVisCap; this.occVisCap = this.pOccVisCap; this.pOccVisCap = i;
		i = this.tCap; this.tCap = this.pTCap; this.pTCap = i;
		i = this.bCount; this.bCount = this.pBCount; this.pBCount = i;
		i = this.tieSetN; this.tieSetN = this.pTieSetN; this.pTieSetN = i;
	}

	private long vListsCap;

	/** =verify: every list's signature from frag_cull (set 0) must equal frag_cull_r's (set 1); then the buffer is cleared for reuse. */
	private void checkSignatures(long sig, int n) {
		boolean ok = true;
		long units = 0;
		for (int id = 0; id < n && ok; id++) {
			long a = sig + id * 32L;
			for (int q = 0; q < 4; q++) ok &= MemoryUtil.memGetInt(a + q * 4L) == MemoryUtil.memGetInt(a + 16 + q * 4L);
			units += Integer.toUnsignedLong(MemoryUtil.memGetInt(a));
		}
		this.sigChecked++;
		if (!ok) this.sigBad++;
		MemoryUtil.memSet(sig, 0, 32L * n);
		long now = System.nanoTime();
		if (now - this.sigLog > 5_000_000_000L) {
			System.out.println("mcopt-own cullR verify: " + this.sigChecked + " frames, list mismatches " + this.sigBad + " (last frame " + units + " units in " + n
				+ " lists)");
			this.sigLog = now;
		}
	}

	/**
	 * The layers' draws in c's order into the open render pass. d: the DrawFrame (listBase written here per draw). True if the pass was
	 * split (the caller then restores the pass's state, MetalBridge.restorePass).
	 */
	boolean draw(Config c, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler, GpuTextureView light, GpuSampler lightSampler) {
		boolean split = false;
		this.hAtlas = MetalBridge.viewHandle(atlas);
		this.hSampler = MetalBridge.samplerHandle(sampler);
		this.hLight = MetalBridge.viewHandle(light);
		this.hLightSampler = MetalBridge.samplerHandle(lightSampler);
		this.bound = false;
		int base = (c.half ? K_HALF : 0) | (c.nonidx ? K_NONIDX : 0) | (c.mesh ? K_MESH : 0) | (c.mesh4 ? K_MESH4 : 0) | (c.tight ? K_TIGHT : 0);
		boolean oSample = false;
		if (ORACLE) {
			this.oDraws++;
			if (this.oSampleAt > 0 && this.oDraws == this.oSampleAt + ORACLE_READ) this.oracleRead();
			// a sample at most every second (its lines are logged: per bench phase window, medians by the oracle's medians script.py)
			oSample = !c.posonly && !c.mesh && !c.occ && this.oDraws % ORACLE_EVERY == 0 && System.nanoTime() - this.oLogAt > 1_000_000_000L;
			if (oSample) this.oLogAt = System.nanoTime();
		}
		if (c.posonly) {
			// probe: both layers with no varyings at all and a constant fragment stage (the geometry's floor)
			this.layer(c, 0, base | 7 | K_POSONLY, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 7 | K_POSONLY, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (OwnTerrain.STATS) this.stats(c);
			return false;
		}
		if (c.uocc) {
			// phase A: the units visible at the last test (this frame's cull listed only those); the split, the pyramid and every unit's
			// test; phase B: the visible units phase A did not draw
			int a1 = aKind(base);
			this.exactLog();
			if (a1 >= 0 && this.a1Exact) {
				this.layer(c, 0, a1 | K_EXACT, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				this.bound = false;  // (after the command buffers' draws: bind everything again)
			} else if (a1 >= 0) {
				this.a1sDidSplit = false;
				this.layer(c, 0, a1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				if (this.a1sDidSplit) split = true;
			}
			if (SPLIT_A || SPLIT_NEAR > 0) oSample = false;
			else this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
			// the oracle's phase A redraw before the split: the unit test between the phases rewrites phase A's arguments (its depth
			// then lacks only phase B's quads, a handful in steady state)
			if (oSample) {
				this.oracleBegin();
				this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				// this frame's unit-test verdicts, copied right after the test: which kept units the test still passes now
				if (this.oTest != 0) OwnNative.oracleTest(this.own, this.oTest);
				this.oEpoch = this.epoch;
				this.oTested = this.oTest != 0;
			}
			if (c.diag == 2) {
				// (diagnostic nosplit: phase A alone)
				if (OwnTerrain.STATS) this.stats(c);
				return false;
			}
			int pyr = Math.max(0, Math.min(2, OwnProbe.integer("frag.pyrSkip", c.pyr)));
			int uoccMode = OwnProbe.integer("frag.fine", c.fine) | (c.hull ? 256 : 0) | (c.diag == 1 ? 512 : 0) | (c.rpyr ? 1024 : 0) | (pyr << 13)
				| (pyr == 0 && PYR_AUTO > 0 ? 1 << 15 | Math.min(255, PYR_AUTO / 64) << 16 : 0);
			boolean late = OwnProbe.bool("frag.testLate", TEST_LATE) && (SPLIT_NEAR > 0 || SPLIT_A) && !c.rpyr && c.diag != 1;  // (the test reads the pyramid the first call built)
			if (!late) this.terrain.tOccArm();
			split = OwnNative.fragUocc(this.own, this.enc, this.cullFrame, this.cullFrameLength, this.fragFrame, FRAG_FRAME_BYTES, this.clipAddr, table, recs,
				this.args, lists(c), uoccMode | (late ? 2048 : 0)) == 1;
			this.terrain.tOccDisarm();
			if (this.terrain.tracing()) {
				System.out.println("mcopt-metal trace: compute encoder 'own frag unit occlusion' #" + OwnNative.lastComputeGroup() + " (the split, profiled like a pass)");
			}
			this.bound = false;  // (a new encoder: nothing bound)
			if (SPLIT_NEAR > 0) {
				// phase A's farther sections' solid units, then all of phase A's cutout units (both banks), in an encoder of their own;
				// then phase B in another (it reads the test's arguments)
				int bank = c.listCount(), ex = this.a2Exact ? K_EXACT : 0;
				this.layer(c, 0, base | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, bank);
				if (ex != 0) this.bound = false;  // (after the table draws: bind everything again)
				if (A2_ENC > 0 && split) this.a2Split();
				this.layer(c, 1, base | 1 | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, 0);
				if (ex != 0) this.bound = false;
				if (A2_ENC > 1 && split) this.a2Split();
				this.layer(c, 1, base | 1 | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, bank);
				if (ex != 0) this.bound = false;
				this.afterLater(c, split, late, uoccMode, table, recs);
			} else if (SPLIT_A) {
				// phase A's cutout units after the test, in an encoder of their own, then phase B in another (it reads the test's arguments)
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				this.afterLater(c, split, late, uoccMode, table, recs);
			}
			this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
			this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
			if (oSample) {
				this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
				this.oracleEnd(c);
			}
			if (OwnTerrain.STATS) this.stats(c);
			return split;
		}
		if (c.occ) {
			// phase A: what each unit's mask says was visible; the split and the pyramid; phase B: the rest that is visible, new masks
			this.layer(c, 0, base | K_OCCA, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 1 | K_OCCA, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (c.occa) {
				if (OwnTerrain.STATS) this.stats(c);
				return false;
			}
			split = OwnNative.fragOcc(this.own, this.enc) == 1;
			this.bound = false;  // (a new encoder: nothing bound)
			this.layer(c, 0, base | K_OCCB, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 1 | K_OCCB, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (OwnTerrain.STATS) this.stats(c);
			return split;
		}
		switch (c.order) {
			case CUTFIRST -> {
				// solid after cutout, strict and in exactly reversed order (the cull lists solid units last to first, F_REVERSE their
				// quads): cutout still wins its depth ties with solid, and solid's own ties go as in vanilla's order (later wins)
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 0, base | K_GREATER | K_REVERSE, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			case BPRE -> {
				this.layer(c, 1, base | 6 | K_BIAS, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 1, base | K_EQUAL | K_BIAS, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			case SPLIT -> {
				this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				split = OwnNative.split(this.own, this.enc) == 1;
				this.bound = false;  // (a new encoder: nothing bound)
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			default -> {
				// (measurement: frag.aKind also takes this path's solid draw, the whole solid layer of a frame without uocc, e.g. at 5K
				// where -Dmcopt.own.frag.uoccMaxMp turns uocc off; off = base, unchanged)
				int s0 = aKind(base);
				if (s0 >= 0) this.layer(c, 0, s0, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
		}
		if (oSample) {
			this.oracleBegin();
			this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
			this.oracleEnd(c);
		}
		if (OwnTerrain.STATS) this.stats(c);
		return split;
	}

	/**
	 * After splitNear's / splitA's later part of phase A: with testLate the unit test in an encoder of its own (the split above built
	 * only the pyramid), else just a new render encoder; either way phase B then draws in a render encoder after the test's.
	 */
	/** (a2Enc) the open render encoder ended and reopened on the same attachments: nothing bound. */
	private void a2Split() {
		OwnNative.split(this.own, this.enc);
		this.bound = false;
	}

	private void afterLater(Config c, boolean split, boolean late, int uoccMode, long table, long recs) {
		if (split && late) {
			this.terrain.tOccArm();
			OwnNative.fragUocc(this.own, this.enc, this.cullFrame, this.cullFrameLength, this.fragFrame, FRAG_FRAME_BYTES, this.clipAddr, table, recs, this.args,
				lists(c), uoccMode | 4096);
			this.terrain.tOccDisarm();
			if (this.terrain.tracing()) System.out.println("mcopt-metal trace: compute encoder 'own frag unit occlusion' (testLate's test) #" + OwnNative.lastComputeGroup());
		} else if (split) {
			OwnNative.split(this.own, this.enc);
		}
		this.bound = false;
	}

	/**
	 * Every list of one layer (0 solid, 1 cutout): its size classes, rich and (with c.lean) lean. Size classes largest first: only a
	 * bucket's last unit is short, so its quads still come after the bucket's full units (vanilla's order, which decides depth ties);
	 * reversed (K_REVERSE) smallest first.
	 */
	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler) {
		this.layer(c, layer, kind, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
	}

	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args) {
		this.layer(c, layer, kind, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args, 0);
	}

	/** bank: an offset added to every list id (splitNear: c.listCount() for phase A's farther sections). */
	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args, int bank) {
		boolean bins = layer == 1 && c.sort;
		int classes = c.classes(), used = bins ? BINS : c.sizeClasses();
		for (int i = 0; i < used; i++) {
			int cls = bins || (kind & K_REVERSE) != 0 ? i : used - 1 - i;  // bins near to far; sizes largest first (reversed: smallest)
			for (int lean = 0; lean < (c.lean ? 2 : 1); lean++) {
				int id = (layer * classes + cls) * 2 + lean + bank;
				MemoryUtil.memPutInt(d, (kind & K_EXACT) != 0 ? 0 : id * this.listCap);  // (a1Exact: the table's entries are absolute)
				long argsOffset = c.mesh ? (lists(c) * 5L + id * 3L) * 4 : id * 20L;
				int k = kind | (lean == 1 ? K_LEAN : 0);
				if (this.failed.contains(k)) continue;
				try {
					long ar = args;
					if (this.a1sNow > 0 && layer == 0 && bank == 0 && lists == this.lists && id == this.a1sId && !c.mesh && (kind & K_EXACT) == 0
						&& this.a1sPassOk()) {
						// (tl.a1Split: this list as its two draws, the render encoder ended and reopened between them)
						this.bound = OwnNative.draw(this.own, this.enc, k | (this.bound ? K_BOUND : 0), d, OwnTerrain.DRAW_FRAME_BYTES, arena, table, recs, lists,
							this.a1sCur, 0, u, this.hAtlas, this.hSampler, this.hLight, this.hLightSampler) == 1;
						if (OwnNative.tlRestart(this.enc, 1) > 0) {
							this.a1sDidSplit = true;
							this.bound = false;
							this.a1sSplit++;
						}
						// (the second draw from the split arguments whether or not the encoder could be reopened: together the whole list)
						ar = this.a1sCur;
						argsOffset = 20;
					}
					this.bound = OwnNative.draw(this.own, this.enc, k | (this.bound ? K_BOUND : 0), d, OwnTerrain.DRAW_FRAME_BYTES, arena, table, recs, lists, ar,
						argsOffset, u, this.hAtlas, this.hSampler, this.hLight, this.hLightSampler) == 1;
				} catch (IllegalStateException e) {
					this.failed.add(k);
					System.out.println("mcopt-own-frag: draw kind " + k + " failed, skipped from now on: " + e.getMessage());
				}
			}
		}
	}

	/** Bits from .. to - 1 of a list mask (ids past 63 dropped). */
	private static long bits(int from, int to) {
		long m = 0;
		for (int i = from; i < Math.min(to, 64); i++) m |= 1L << i;
		return m;
	}

	/** (every 5 s while a1Exact or a2Exact is requested) the effective mode, from the native telemetry (mco_frag_exact_telemetry). */
	private void exactLog() {
		long now = System.nanoTime();
		if (!(this.a1Req || this.a2Req) || now - this.exactLogAt < 5_000_000_000L) return;
		this.exactLogAt = now;
		OwnNative.fragExactTelemetry(this.own, this.exactTel);
		int cores = mcopt.metal.Profile.gpuCores();
		StringBuilder b = new StringBuilder("mcopt-own-frag exact: requested a1 " + this.a1Req + " a2 " + this.a2Req + "; effective a1 " + this.a1Exact + " a2 "
			+ this.a2Exact + (EXACT_GATED ? " (gated: exactMaxCores " + EXACT_MAX_CORES + ", gpu cores " + cores + ")" : "")
			+ (A2_GATED && this.a2Req ? " (a2 gated: a2ExactMaxCores " + A2_MAX_CORES + ", gpu cores " + cores + ")" : "")
			+ "; block " + (1 << EXACT_SHIFT) + " quads");
		// (per group, the last completed frame: lists drawn from the table, lists that fell back (region full / list past listCap), table
		// blocks, units, real quads, the class draws' quad slots (input), the table draws' quad slots (emitted), removed = input - emitted)
		String[] names = {"A1", "A2 solid", "A2 cutout"};
		for (int k = 0; k < 3; k++) {
			long t = this.exactTel + k * 32L;
			long in = MemoryUtil.memGetInt(t + 24) & 0xFFFFFFFFL, out = MemoryUtil.memGetInt(t + 28) & 0xFFFFFFFFL;
			b.append(String.format("; %s: table lists %d, fallback lists %d region / %d listCap, blocks %d, units %d, real quads %d, input slots %d, emitted %d, removed %d",
				names[k], MemoryUtil.memGetInt(t), MemoryUtil.memGetInt(t + 4), MemoryUtil.memGetInt(t + 8), MemoryUtil.memGetInt(t + 12),
				MemoryUtil.memGetInt(t + 16), MemoryUtil.memGetInt(t + 20), in, out, in - out));
		}
		b.append(String.format("; draws drawn as class draws (no pipeline / no table) %d, table allocation failed %d, table %d KB a set (%d blocks a list)",
			MemoryUtil.memGetInt(this.exactTel + 96), MemoryUtil.memGetInt(this.exactTel + 100), MemoryUtil.memGetInt(this.exactTel + 104) / 1024,
			MemoryUtil.memGetInt(this.exactTel + 108)));
		System.out.println(b);
	}

	/** The oracle's sample (ORACLE): its buffers (grown to the record capacity) and a new sample number for the marks. */
	private void oracleBegin() {
		int cap = this.terrain.recCapacity();
		if (cap > this.oCap) {
			long oldK = this.oKept, oldV = this.oVis;
			if (oldK != 0) this.terrain.later(() -> {
				OwnNative.release(oldK);
				OwnNative.release(oldV);
			});
			if (this.oTest != 0) {
				long oldT = this.oTest;
				this.terrain.later(() -> OwnNative.release(oldT));
			}
			this.oTest = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oTestAddr = OwnNative.contents(this.oTest);
			MemoryUtil.memSet(this.oTestAddr, 0, 4L * cap);
			this.oKept = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oVis = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oKeptAddr = OwnNative.contents(this.oKept);
			this.oVisAddr = OwnNative.contents(this.oVis);
			MemoryUtil.memSet(this.oKeptAddr, 0, 4L * cap);
			MemoryUtil.memSet(this.oVisAddr, 0, 4L * cap);
			this.oCap = cap;
			this.oSampleAt = -1;
		}
		this.oFrame++;
		OwnNative.oracle(this.own, this.oKept, this.oVis, this.oFrame);
	}

	/** The oracle's redraw of one list set (both layers) as drawn this frame: depth EQUAL, no writes, marks only. */
	private void oracleLists(Config c, int base, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args) {
		int k = (base & ~(K_MESH | K_MESH4)) | K_ORACLE | K_EQUAL;
		this.layer(c, 0, k, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args);
		this.layer(c, 1, k | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args);
	}

	private void oracleEnd(Config c) {
		this.oSampleAt = this.oDraws;
		this.oCamX = this.oCullX;
		this.oCamY = this.oCullY;
		this.oCamZ = this.oCullZ;
		this.oConfig = c;
	}

	private static final double[] O_DIST = {16, 32, 64, 128, 256};
	private static final int[] O_EXT = {1, 2, 4, 8, 16};

	/** The oracle's count (ORACLE_READ frames after a sample: its GPU work is done, and no later sample overwrote its marks). */
	private void oracleRead() {
		int s = this.oFrame, n = Math.min(this.oCap, this.terrain.recCapacity()), slots = this.terrain.slotCapacity();
		long recs = this.terrain.recAddress(), master = this.terrain.masterAddress();
		long[] drawnU = new long[2], drawnQ = new long[2], visU = new long[2], visQ = new long[2];
		long[][] hd = new long[2][O_DIST.length + 1], he = new long[2][O_EXT.length + 1];  // drawn but invisible: [units, quads][bin]
		long[][] hdAll = new long[2][O_DIST.length + 1], heAll = new long[2][O_EXT.length + 1];  // drawn: [units, quads][bin]
		// uocc: kept-but-invisible units this frame's own test no longer passes (kept only on last frame's verdict: stale), by distance
		long[][] hStale = new long[2][O_DIST.length + 1];
		boolean tested = this.oConfig != null && this.oConfig.uocc && this.oTested;
		for (int r = 0; r < n; r++) {
			if (MemoryUtil.memGetInt(this.oKeptAddr + 4L * r) != s) continue;
			long a = recs + (long) r * OwnTerrain.REC_BYTES;
			int meta = MemoryUtil.memGetInt(a + 8), slot = MemoryUtil.memGetInt(a + 4), lo = MemoryUtil.memGetInt(a + 16), hi = MemoryUtil.memGetInt(a + 20);
			int quads = (meta & 63) + 1, layer = Math.min(1, (meta >> 9) & 3);
			boolean vis = MemoryUtil.memGetInt(this.oVisAddr + 4L * r) == s;
			drawnU[layer]++;
			drawnQ[layer] += quads;
			if (vis) {
				visU[layer]++;
				visQ[layer] += quads;
			}
			if (slot < 0 || slot >= slots) continue;
			long m = master + (long) slot * OwnTerrain.SECTION_BYTES;
			int lx = (lo & 255) - 16, ly = (lo >>> 8 & 255) - 16, lz = (lo >>> 16 & 255) - 16, hx = (hi & 255) - 16, hy = (hi >>> 8 & 255) - 16, hz = (hi >>> 16 & 255) - 16;
			double cx = MemoryUtil.memGetInt(m) + (lx + hx) / 2.0, cy = MemoryUtil.memGetInt(m + 4) + (ly + hy) / 2.0, cz = MemoryUtil.memGetInt(m + 8) + (lz + hz) / 2.0;
			double dist = Math.sqrt((cx - this.oCamX) * (cx - this.oCamX) + (cy - this.oCamY) * (cy - this.oCamY) + (cz - this.oCamZ) * (cz - this.oCamZ));
			int ext = Math.max(hx - lx, Math.max(hy - ly, hz - lz));
			int db = 0, eb = 0;
			while (db < O_DIST.length && dist >= O_DIST[db]) db++;
			while (eb < O_EXT.length && ext > O_EXT[eb]) eb++;
			hdAll[0][db]++;
			hdAll[1][db] += quads;
			heAll[0][eb]++;
			heAll[1][eb] += quads;
			if (!vis) {
				hd[0][db]++;
				hd[1][db] += quads;
				he[0][eb]++;
				he[1][eb] += quads;
				if (tested && MemoryUtil.memGetInt(this.oTestAddr + 4L * r) != this.oEpoch + 1) {
					hStale[0][db]++;
					hStale[1][db] += quads;
				}
			}
		}
		long du = drawnU[0] + drawnU[1], dq = drawnQ[0] + drawnQ[1], vu = visU[0] + visU[1], vq = visQ[0] + visQ[1];
		System.out.printf("mcopt-own oracle: sample %d (%s): drawn units %d quads %d (solid %d/%d, cutout %d/%d); visible units %d quads %d (%.1f%% of drawn quads; solid %d/%d, cutout %d/%d)%n",
			s, this.oConfig, du, dq, drawnU[0], drawnQ[0], drawnU[1], drawnQ[1], vu, vq, dq > 0 ? 100.0 * vq / dq : 0, visU[0], visQ[0], visU[1], visQ[1]);
		StringBuilder b = new StringBuilder("mcopt-own oracle: drawn but invisible by unit-centre distance (units/quads of drawn units/quads):");
		String[] dn = {"<16", "16-32", "32-64", "64-128", "128-256", ">=256"};
		for (int i = 0; i <= O_DIST.length; i++) b.append(String.format(" %s %d/%d of %d/%d", dn[i], hd[0][i], hd[1][i], hdAll[0][i], hdAll[1][i]));
		System.out.println(b);
		b = new StringBuilder("mcopt-own oracle: drawn but invisible by box extent in blocks (units/quads of drawn units/quads):");
		String[] en = {"<=1", "2", "3-4", "5-8", "9-16", ">16"};
		for (int i = 0; i <= O_EXT.length; i++) b.append(String.format(" %s %d/%d of %d/%d", en[i], he[0][i], he[1][i], heAll[0][i], heAll[1][i]));
		System.out.println(b);
		// one machine-readable line per sample (the oracle's medians script: medians per bench phase window)
		StringBuilder m = new StringBuilder("mcopt-own oracle data: uocc=").append(this.oConfig != null && this.oConfig.uocc ? 1 : 0)
			.append(" drawnU=").append(du).append(" drawnQ=").append(dq).append(" visU=").append(vu).append(" visQ=").append(vq);
		for (int i = 0; i <= O_DIST.length; i++) m.append(" invU").append(i).append('=').append(hd[0][i]).append(" invQ").append(i).append('=').append(hd[1][i])
			.append(" allU").append(i).append('=').append(hdAll[0][i]).append(" allQ").append(i).append('=').append(hdAll[1][i]);
		if (tested) for (int i = 0; i <= O_DIST.length; i++) m.append(" staleU").append(i).append('=').append(hStale[0][i]).append(" staleQ").append(i).append('=').append(hStale[1][i]);
		System.out.println(m);
	}

	/** Units drawn per layer (and of them lean), from the arguments the GPU last wrote (a frame or two behind). */
	private void stats(Config c) {
		int classes = c.classes();
		for (int layer = 0; layer < 2; layer++) {
			for (int cls = 0; cls < classes; cls++) {
				for (int lean = 0; lean < 2; lean++) {
					int id = (layer * classes + cls) * 2 + lean, units = MemoryUtil.memGetInt(this.argsAddress + id * 20L + 4);
					if (layer == 0) this.statSolid += units;
					else this.statCutout += units;
					if (lean == 1) this.statLean += units;
				}
			}
		}
		// quad slots drawn (vertex count / 6 x instances, padding included) by bank (splitNear: 1 = the farther sections), layer, lean
		for (int bank = 0; bank < (c.uocc && SPLIT_NEAR > 0 ? 2 : 1); bank++) {
			for (int layer = 0; layer < 2; layer++) {
				for (int cls = 0; cls < classes; cls++) {
					for (int lean = 0; lean < 2; lean++) {
						long a = this.argsAddress + (bank * (long) c.listCount() + (layer * classes + cls) * 2 + lean) * 20L;
						this.statSlots[bank * 4 + layer * 2 + lean] += (long) (MemoryUtil.memGetInt(a) / 6) * MemoryUtil.memGetInt(a + 4);
					}
				}
			}
		}
		for (int i = 0; i < 14; i++) this.statBuckets[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + i) * 4);
		if (c.occ) for (int i = 0; i < 4; i++) this.statOcc[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 14 + i) * 4);
		if (FACESTATS) for (int i = 0; i < 5; i++) this.statFace[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 22 + i) * 4);
		this.statFunnelIn += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 27) * 4);
		this.statFunnelFacing += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 28) * 4);
		if (c.uocc) {
			this.statTested += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 18) * 4);
			// A1 (layer 0 of the nearer bank): real quads and units (frag_a1_icb), and the quad slots its instanced draws issue
			this.statA1Quads += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 29) * 4) & 0xFFFFFFFFL;
			this.statA1Units += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 30) * 4) & 0xFFFFFFFFL;
			this.statA1Over += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 31) * 4) & 0xFFFFFFFFL;
			for (int id = 0; id < c.classes() * 2; id++)
				this.statA1Slots += (long) (MemoryUtil.memGetInt(this.argsAddress + id * 20L) / 6) * MemoryUtil.memGetInt(this.argsAddress + id * 20L + 4);
			for (int i = 0; i < lists(c); i++) this.statB += MemoryUtil.memGetInt(this.bArgsAddress + i * 20L + 4);
			this.statQuadsA += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 14) * 4);
			this.statQuadsB += MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L);
			int rd = MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L + 16);  // (bRedraw: fragRedraw's units, then quads; included in B's)
			this.statRedraw += rd;
			this.statRedrawQuads += MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L + 20);
			if (rd > 0) this.statRedrawFrames++;
		}
		this.statFrames++;
		long now = System.nanoTime();
		if (now - this.statLast < 5_000_000_000L) return;
		System.out.printf("mcopt-own-frag stats: %s, units a frame solid %.0f cutout %.0f, lean %.0f%n", c, (double) this.statSolid / this.statFrames,
			(double) this.statCutout / this.statFrames, (double) this.statLean / this.statFrames);
		StringBuilder b = new StringBuilder("mcopt-own-frag buckets (units/quads a frame, +X -X +Y -Y +Z -Z any):");
		for (int i = 0; i < 7; i++) b.append(String.format(" %.0f/%.0f", (double) this.statBuckets[i * 2] / this.statFrames, (double) this.statBuckets[i * 2 + 1] / this.statFrames));
		System.out.println(b);
		{
			// the culling funnel (quads a frame): sections passed in (solid + cutout, all facings), after the facing test, listed (after the unit
			// box frustum test; = drawn on the plain frag path; uocc draws its phase A + B of these)
			long listed = 0;
			for (int i = 0; i < 7; i++) listed += this.statBuckets[i * 2 + 1];
			System.out.printf("mcopt-own-frag funnel: quads a frame in the sections passed in %.0f, after the facing test %.0f, after the unit box test %.0f%n",
				(double) this.statFunnelIn / this.statFrames, (double) this.statFunnelFacing / this.statFrames, (double) listed / this.statFrames);
			this.statFunnelIn = this.statFunnelFacing = 0;
		}
		if (FACESTATS) {
			double n = this.statFrames, quads = this.statFace[0] / n, back = (this.statFace[1] + this.statFace[2]) / n;
			System.out.printf("mcopt-own-frag faces: quads listed a frame %.0f, back-facing %.0f (%.2f%%): in facing buckets %.0f, in the any bucket %.0f of its %.0f; degenerate %.0f%n",
				quads, back, quads > 0 ? 100 * back / quads : 0, this.statFace[1] / n, this.statFace[2] / n, this.statFace[3] / n, this.statFace[4] / n);
			java.util.Arrays.fill(this.statFace, 0);
		}
		if (c.uocc) {
			long listed = 0;
			for (int i = 0; i < 7; i++) listed += this.statBuckets[i * 2 + 1];
			System.out.printf("mcopt-own-frag uocc: units a frame tested %.0f, phase A %.0f, phase B %.0f; quads drawn A %.0f + B %.0f of %.0f listed%n",
				(double) this.statTested / this.statFrames, (double) (this.statSolid + this.statCutout) / this.statFrames, (double) this.statB / this.statFrames,
				(double) this.statQuadsA / this.statFrames, (double) this.statQuadsB / this.statFrames, (double) listed / this.statFrames);
			if (OwnProbe.bool("frag.bRedraw", B_REDRAW) || this.statRedraw > 0) {
				System.out.printf("mcopt-own-frag bRedraw: phase A cutout units redrawn in B %.1f a frame (%.0f quads), in %.1f%% of frames%n",
					(double) this.statRedraw / this.statFrames, (double) this.statRedrawQuads / this.statFrames, 100.0 * this.statRedrawFrames / this.statFrames);
			}
			this.statTested = this.statB = this.statQuadsA = this.statQuadsB = 0;
			System.out.printf("mcopt-own-frag a1: %s, a frame: units %.0f, real quads %.0f, class draws' slots %.0f (%.3f a real quad), overflowed lists %.2f%n", this.a1Exact ? "table draws" : "class draws",
				this.statA1Units / (double) this.statFrames, this.statA1Quads / (double) this.statFrames, this.statA1Slots / (double) this.statFrames,
				this.statA1Quads > 0 ? this.statA1Slots / (double) this.statA1Quads : 0, this.statA1Over / (double) this.statFrames);
			this.statA1Over = 0;
			this.statA1Quads = this.statA1Units = this.statA1Slots = 0;
			this.statRedraw = this.statRedrawQuads = this.statRedrawFrames = 0;
			double n = this.statFrames;
			long[] q = this.statSlots;
			System.out.printf("mcopt-own-frag slots at %d (quad slots a frame, rich/lean): near solid %.0f/%.0f, far solid %.0f/%.0f, near cutout %.0f/%.0f, far cutout %.0f/%.0f%n",
				System.currentTimeMillis(), q[0] / n, q[1] / n, q[4] / n, q[5] / n, q[2] / n, q[3] / n, q[6] / n, q[7] / n);
			java.util.Arrays.fill(this.statSlots, 0);
			if ((Integer.getInteger("mcopt.own.frag.uoccDebug", 0) & 8) != 0) {
				// (testFast's verify mode: frag_uocc_test_f's cumulative counts in both sets of phase B's arguments)
				long diff = 0, tested = 0, vis = 0;
				for (long a : new long[] {this.bArgsAddress, this.pBArgsAddress}) {
					if (a == 0) continue;
					diff += MemoryUtil.memGetInt(a + lists(c) * 20L + 4) & 0xFFFFFFFFL;
					tested += MemoryUtil.memGetInt(a + lists(c) * 20L + 8) & 0xFFFFFFFFL;
					vis += MemoryUtil.memGetInt(a + lists(c) * 20L + 12) & 0xFFFFFFFFL;
				}
				System.out.printf("mcopt-own-frag testFast verify: units tested %d; frag_uocc_test passes, testFast doesn't: %d (must be 0); testFast passes, frag_uocc_test doesn't: %d%n", tested, diff, vis);
			}
		}
		if (c.occ) {
			System.out.printf("mcopt-own-frag occ: quads a frame, phase A solid %.0f cutout %.0f, phase B solid %.0f cutout %.0f%n", (double) this.statOcc[0] / this.statFrames,
				(double) this.statOcc[1] / this.statFrames, (double) this.statOcc[2] / this.statFrames, (double) this.statOcc[3] / this.statFrames);
			java.util.Arrays.fill(this.statOcc, 0);
		}
		java.util.Arrays.fill(this.statBuckets, 0);
		this.statFrames = this.statSolid = this.statCutout = this.statLean = 0;
		this.statLast = now;
	}
}
