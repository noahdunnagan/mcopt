package mcopt.metal.lod;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Position-keyed candidate lists (-Dmcopt.lod.pk=true, with -Dmcopt.lod.render=mesh): the mesh's cull, split in two
 * (columns.metal, lod_pk_*).
 *
 * What the cull decides from the camera's position alone holds for any view direction:
 * - each block's level and the real terrain's hand-off;
 * - facing;
 * - the horizon.
 * With margins it also holds for any camera within {@link #EPS} blocks. A position pass makes those decisions all around
 * the camera and keeps what survives as records in 128 sectors: 64 azimuth wedges, each split into the hand-off band (out
 * to {@link #BAND} blocks past where the real terrain's hand-off ends) and the far band. Chunks loading and the hand-off
 * moving change only the hand-off band, and rebuilding that alone looks no farther. Each frame, only the sectors in view go
 * through the view's own tests. The sectors are rebuilt a few at a time, each from the camera of the moment:
 * - when the camera has moved too far from where it was built;
 * - when a tile it holds was meshed again or dropped, or the real terrain's hand-off changed under it;
 * - when a new generation starts.
 *
 * A generation is the selection camera every sector's levels and home sectors are decided from, so that sectors built
 * from different cameras agree. It moves when the camera has gone {@link #SEL} blocks from it, and every sector is then
 * rebuilt.
 *
 * On a frame with sectors in view that aren't valid even after its rebuilds (at most {@link #BUDGET}), a live cull (the
 * full cull's kernels) draws their blocks, and the lists draw the rest: a stale sector costs its share of a full cull, never
 * pixels. Above {@link #FAST} blocks a second the full cull draws everything (the hand-off's and the margins' churn would
 * cost more than the lists save).
 */
final class LodPk {
	static final boolean ENABLED = Boolean.getBoolean("mcopt.lod.pk");
	/**
	 * -Dmcopt.lod.pkEps: blocks a sector's lists hold for around the camera they were built from, at least (a moving camera's
	 * rebuilds take 12 frames of its speed, up to 4 blocks).
	 */
	static final float EPS = Float.parseFloat(System.getProperty("mcopt.lod.pkEps", "0.5"));
	/** -Dmcopt.lod.pkSel: blocks (horizontally) the camera may go from the selection camera before a new generation. */
	static final double SEL = Double.parseDouble(System.getProperty("mcopt.lod.pkSel", "32"));
	/**
	 * -Dmcopt.lod.pkFast: blocks a second (the camera's speed, smoothed over ~0.25 s) above which the full cull draws
	 * instead of the lists (back to the lists under 3/4 of it); 0: the lists always. The default, 0.5: the lists only for
	 * a camera at rest or turning in place, where they win (spin +27% on the mini); a moving camera (walk, sprint, cruise,
	 * flight) spends on rebuilds what they save, and the full cull with the real terrain occluding is as fast or faster.
	 */
	static final double FAST = Double.parseDouble(System.getProperty("mcopt.lod.pkFast", "0.5"));
	/**
	 * -Dmcopt.lod.pkFarEps: the far band's margin, up to this many blocks (0: the hand-off band's). A moving camera wears
	 * the far band's margins out as fast as the hand-off band's, but there a wide margin costs little (its faces and the
	 * horizon's distant occluders barely turn): 4x the hand-off band's, capped here. A rebuild batch holds one band.
	 */
	static final float FAR_EPS = Float.parseFloat(System.getProperty("mcopt.lod.pkFarEps", "0"));
	/**
	 * -Dmcopt.lod.pkVis: the lists' visible set. A few built sectors a frame are pruned to the records a depth facet from their
	 * build camera shows (columns.metal lod_pk_facet_*), held for any camera within the sector's margin: the same picture,
	 * except through gaps narrower than a facet texel. -Dmcopt.lod.pkVisRes: the facets' angular resolution against the
	 * screen's; -Dmcopt.lod.pkVisPerFrame: sectors pruned a frame at most.
	 */
	static final boolean VIS = ENABLED && Boolean.getBoolean("mcopt.lod.pkVis");
	static final double VIS_RES = Double.parseDouble(System.getProperty("mcopt.lod.pkVisRes", "1"));
	static final int VIS_PER_FRAME = Integer.getInteger("mcopt.lod.pkVisPerFrame", 4);
	/**
	 * -Dmcopt.lod.pkVisEps=B (debug: mark mode's positive control): the prune's margin B blocks instead of the sector's. A
	 * negative one prunes records that show, which -Dmcopt.lod.hzMark then draws pure blue.
	 */
	static final String VIS_EPS = System.getProperty("mcopt.lod.pkVisEps");
	/** -Dmcopt.lod.pkBudget: sectors rebuilt in a frame at most. */
	static final int BUDGET = Integer.getInteger("mcopt.lod.pkBudget", 32);
	/** -Dmcopt.lod.pkBand: the hand-off band reaches this many blocks past the real terrain's (the mask's) farthest chunk. */
	static final float BAND = Float.parseFloat(System.getProperty("mcopt.lod.pkBand", "64"));
	/** Azimuth sectors, bands; sector a + AZ * band (0 the hand-off band, 1 the far band). */
	static final int AZ = 64, BANDS = 2, SECTORS = AZ * BANDS, WORDS = 16, PARAMS_BYTES = 96;
	private static final int PLANT_CAP = 4096, RING = 4;
	/**
	 * Stand-in instances a sector holds: the instance buffer past STAND_BASE (LodMesh.MAX_INSTANCES), shared out. A cold
	 * join stands coarse blocks in for most of the far terrain, so this is generous (2048 overflowed there).
	 */
	private static final int STAND_CAP = ((1 << 21) - (1 << 18)) / (64 * 2);
	/** How far a block of the hand-off band can reach past the band (its center is in it): the margin of its in-view test. */
	private static final double BAND_REACH = 256;
	/** Where the sectors' stand-in instances start in the mesh's instance buffer (the culls' own instances stay below). */
	static final int STAND_BASE = 1 << 18;
	private static final long ALL = -1L;

	private final long ctx;
	private int recCap = Integer.getInteger("mcopt.lod.pkCap", 1 << 17), recCap0 = Integer.getInteger("mcopt.lod.pkCap0", 1 << 15);
	private long recBuf, plantBuf;
	private final long secBuf, secAddr, listBuf, argsBuf;
	final long params = MemoryUtil.nmemCalloc(1, PARAMS_BYTES);
	private final long bufs = MemoryUtil.nmemCalloc(17, 8);
	private final java.util.ArrayDeque<long[]> releaseLater = new java.util.ArrayDeque<>();
	// per sector: built in this generation, at which camera; dirty (what it holds changed since)
	private final boolean[] built = new boolean[SECTORS], dirty = new boolean[SECTORS];
	private final double[] bx = new double[SECTORS], by = new double[SECTORS], bz = new double[SECTORS];
	private boolean selSet;
	private double selX, selZ;
	/** This generation's band radius (blocks from the selection camera; MeshFrame hz.w). */
	private double bandR = BAND;
	// this frame (sector masks: [0] the hand-off band's azimuths, [1] the far band's)
	private final long[] rebuild = new long[2], drawable = new long[2];
	// stats (since the last report)
	int statFrames, statFallbacks, statRebuilt, statGenerations, statOverflows;

	LodPk(long ctx) {
		this.ctx = ctx;
		this.allocate();
		this.secBuf = LodNative.sharedTrackedBuffer(ctx, (long) SECTORS * WORDS * 4);
		this.secAddr = LodNative.contents(this.secBuf);
		MemoryUtil.memSet(this.secAddr, 0, (long) SECTORS * WORDS * 4);
		this.listBuf = LodNative.privateBuffer(ctx, 512 * 4);
		this.argsBuf = LodNative.privateBuffer(ctx, 512);
	}

	private void allocate() {
		this.recBuf = LodNative.privateBuffer(this.ctx, (long) AZ * (this.recCap0 + this.recCap) * 16);
		this.plantBuf = LodNative.privateBuffer(this.ctx, (long) SECTORS * PLANT_CAP * 32);
	}

	// ---- what changed (render thread) ----

	/** Tile (tx, tz) of level L was meshed again or dropped: the sectors around its area (one block of that level wider). */
	void tileChanged(int level, int tx, int tz) {
		double s = 64.0 * (1 << level), b = 16.0 * (1 << level);
		this.areaChanged(level, tx * s - b, tz * s - b, (tx + 1) * s + b, (tz + 1) * s + b, false);
	}

	/**
	 * A mesh of level L changed in the area [x0, x1] x [z0, z1] (blocks, already one block of the level wider). Skipped where
	 * the real terrain draws every chunk of the level's blocks there, and the chunks around them (skirts look across): nothing
	 * the lists hold changes.
	 */
	void areaChanged(int level, double x0, double z0, double x1, double z1) {
		this.areaChanged(level, x0, z0, x1, z1, true);
	}

	/**
	 * refresh: an already drawn tile's blocks changed (else a tile arrived or left, which near the camera can mean a coarser
	 * level standing in for a finer one's missing tile, so it isn't skipped for being nearer than its level is drawn).
	 */
	void areaChanged(int level, double x0, double z0, double x1, double z1, boolean refresh) {
		if (this.selSet) {
			double fx = Math.max(Math.abs(x0 - this.selX), Math.abs(x1 - this.selX)), fz = Math.max(Math.abs(z0 - this.selZ), Math.abs(z1 - this.selZ));
			double far = Math.sqrt(fx * fx + fz * fz);
			// wholly inside the live ring (the live cull redraws it every frame), or nearer than this level is ever drawn
			// (its blocks of the selection camera's levels start at the next finer level's switch distance)
			double nx = Math.max(0, Math.max(x0 - this.selX, this.selX - x1)), nz = Math.max(0, Math.max(z0 - this.selZ, this.selZ - z1));
			double near = Math.sqrt(nx * nx + nz * nz);
			// (a level-0 tile arriving or leaving inside the ring changes the real terrain's occluders, when they count)
			boolean ring = far < this.liveRing - 16 && (refresh || !(level == 0 && LodMesh.REAL_OCC));
			// this level is drawn from the next finer level's switch distance (from the selection camera) out to its own (the top
			// level: to the reach); a change wholly nearer or farther than that changes nothing drawn (window edges scrolling)
			float[] sw = this.switchDist;
			boolean undrawn = sw != null && (refresh && level > 0 && far < sw[level - 1] - (32 << level) - SEL
				|| near > (level < sw.length - 1 ? sw[level] : this.reach) + (32 << level) + SEL);
			if (ring || undrawn) {
				this.statSkipped++;
				return;
			}
		}
		this.markArea(x0, z0, x1, z1, false);
	}

	/**
	 * A block's occluders dropped: what the horizon hid behind it, in its azimuth range from the selection camera (two sectors
	 * wider), may show now. Level 0 inside the live ring counts when the real terrain occludes (LodMesh.REAL_OCC); elsewhere,
	 * blocks of a level not drawn there don't occlude.
	 */
	void occluderDropped(int level, double x0, double z0, double x1, double z1) {
		if (this.selSet && !(level == 0 && LodMesh.REAL_OCC)) {
			double fx = Math.max(Math.abs(x0 - this.selX), Math.abs(x1 - this.selX)), fz = Math.max(Math.abs(z0 - this.selZ), Math.abs(z1 - this.selZ));
			double nx = Math.max(0, Math.max(x0 - this.selX, this.selX - x1)), nz = Math.max(0, Math.max(z0 - this.selZ, this.selZ - z1));
			double far = Math.sqrt(fx * fx + fz * fz), near = Math.sqrt(nx * nx + nz * nz);
			float[] sw = this.switchDist;
			boolean undrawn = far < this.liveRing - 16 || sw != null && (level > 0 && far < sw[level - 1] - (32 << level) - SEL
				|| near > (level < sw.length - 1 ? sw[level] : this.reach) + (32 << level) + SEL);
			if (undrawn) {
				this.statSkipped++;
				return;
			}
		}
		this.statOccDrops++;
		this.markArea(x0, z0, x1, z1, true);
	}

	int statOccDrops;

	/** The real terrain's hand-off changed at chunk (cx, cz): the blocks there and their neighbors. */
	void chunkChanged(int cx, int cz) {
		// (with the visible set, the far blocks there may have hidden other sectors' records: those behind them too)
		this.markArea(cx * 16.0 - 16, cz * 16.0 - 16, cx * 16.0 + 32, cz * 16.0 + 32, VIS);
	}

	/** Set by the owner: the levels' switch distances (LodClip.switchDist). */
	float @org.jspecify.annotations.Nullable [] switchDist;
	/** This frame's live ring (blocks from the selection camera), and the reach. */
	private double liveRing, reach = Double.MAX_VALUE;
	int statSkipped, statLive;
	/** Whether this frame runs the live cull (stale sectors in view). */
	boolean liveNow;

	/**
	 * The sectors whose blocks or horizon bins can reach the area [x0, x1] x [z0, z1] (blocks): its azimuth range around the
	 * selection camera, two sectors wider. That covers the blocks' own width, and the build cameras' distance from the
	 * selection camera that the horizon bins are measured from. Bands: those the area's distance range touches (a block's band
	 * is its center's); when occluders dropped (drop), every band past the area's nearest point, whose horizon they raised.
	 */
	private void markArea(double x0, double z0, double x1, double z1, boolean drop) {
		if (!this.selSet) return;
		x0 -= this.selX;
		x1 -= this.selX;
		z0 -= this.selZ;
		z1 -= this.selZ;
		double m = SEL + EPS + 2;
		if (x0 <= m && x1 >= -m && z0 <= m && z1 >= -m) {
			java.util.Arrays.fill(this.dirty, true);
			java.util.Arrays.fill(this.full, false);
			return;
		}
		double nx = Math.max(0, Math.max(x0, -x1)), nz = Math.max(0, Math.max(z0, -z1));
		double fx = Math.max(Math.abs(x0), Math.abs(x1)), fz = Math.max(Math.abs(z0), Math.abs(z1));
		double near = Math.sqrt(nx * nx + nz * nz), far = Math.sqrt(fx * fx + fz * fz);
		boolean band0 = near < this.bandR + (drop ? BAND_REACH : 1), band1 = drop || far >= this.bandR - 1;
		double[][] c = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}};
		double a0 = diamond(c[0][0], c[0][1]), lo = 0, hi = 0;
		for (int i = 1; i < 4; i++) {
			double d = diamond(c[i][0], c[i][1]) - a0;
			d = d > 2 ? d - 4 : d < -2 ? d + 4 : d;
			lo = Math.min(lo, d);
			hi = Math.max(hi, d);
		}
		int s0 = (int) Math.floor((a0 + lo) * (AZ / 4.0)) - 2, s1 = (int) Math.floor((a0 + hi) * (AZ / 4.0)) + 2;
		for (int s = s0; s <= s1; s++) {
			int a = Math.floorMod(s, AZ);
			if (band0) this.dirty[a] = true;
			if (band1) this.dirty[AZ + a] = true;
			if (band0) this.full[a] = false;
			if (band1) this.full[AZ + a] = false;
			// (for the load switch: sectors in view dirtied)
			if (band0 && this.inViewS[a]) this.dirtyMarks++;
			if (band1 && this.inViewS[AZ + a]) this.dirtyMarks++;
		}
	}

	/** columns.metal's hzAzimuth: a "diamond angle" in [0, 4), counterclockwise from +x toward +z. */
	static double diamond(double x, double z) {
		double q = x / Math.max(Math.abs(x) + Math.abs(z), 1e-20);
		return z >= 0 ? 1 - q : 3 + q;
	}

	private static double[] dir(double a) {
		a -= 4 * Math.floor(a / 4);
		double q = a < 2 ? 1 - a : a - 3, z = a < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		double l = Math.sqrt(q * q + z * z);
		return new double[] {q / l, z / l};
	}

	// ---- a frame ----

	/**
	 * Decides this frame's rebuilds and whether the frame can draw from the lists (else the caller runs the full cull), and
	 * writes PkParams. reach: blocks; viewProj: camera-relative, as MeshFrame's; maskDist: the real terrain's farthest chunk
	 * (MeshFrame maskDist.x; 0: none).
	 */
	void prepare(Matrix4f viewProj, double cx, double cy, double cz, double reach, float fwdX, float fwdZ, float maskDist) {
		this.statFrames++;
		this.checkOverflow();
		while (!this.releaseLater.isEmpty() && this.statFramesTotal - this.releaseLater.peek()[0] >= RING) LodNative.release(this.releaseLater.poll()[1]);
		this.statFramesTotal++;
		// the camera's speed (blocks a frame, smoothed): the margin of this frame's rebuilds lasts ~12 frames of it
		if (this.lastSet) {
			double dx = cx - this.lastX, dy = cy - this.lastY, dz = cz - this.lastZ;
			double v = Math.sqrt(dx * dx + dy * dy + dz * dz);
			this.speed = v > 8 ? 0 : this.speed * 0.9 + v * 0.1;
		}
		this.lastSet = true;
		this.lastX = cx;
		this.lastY = cy;
		this.lastZ = cz;
		float nearEps = (float) Math.min(4.0, Math.max(EPS, this.speed * 12));
		float farEps = FAR_EPS > 0 ? (float) Math.max(nearEps, Math.min(FAR_EPS, this.speed * 48)) : nearEps;
		this.batchEps = nearEps;
		// (the band radius holds for a generation: a block's band is part of its home)
		double bandAt = maskDist + BAND;
		if (!this.selSet || Math.hypot(cx - this.selX, cz - this.selZ) > SEL || bandAt != this.bandR) {
			this.selSet = true;
			this.selX = cx;
			this.selZ = cz;
			this.bandR = bandAt;
			java.util.Arrays.fill(this.built, false);
			java.util.Arrays.fill(this.dirty, false);
			java.util.Arrays.fill(this.full, false);
			this.statGenerations++;
		}
		this.reach = reach;
		long inNear = this.inView(viewProj, cx, cy, cz, this.bandR + BAND_REACH), inFar = this.inView(viewProj, cx, cy, cz, reach * 1.5 + 1024);
		boolean[] inView = this.inViewS, valid = this.validS, rb = this.rbS;
		double[] stale = this.stale;
		for (int s = 0; s < SECTORS; s++) {
			inView[s] = ((s < AZ ? inNear : inFar) >>> (s & (AZ - 1)) & 1) != 0;
			double dx = cx - this.bx[s], dy = cy - this.by[s], dz = cz - this.bz[s];
			stale[s] = Math.sqrt(dx * dx + dy * dy + dz * dz) / this.eps[s];
			valid[s] = this.built[s] && !this.dirty[s] && stale[s] <= 0.98 && !this.full[s];
			rb[s] = false;
			if (!valid[s] && inView[s]) {
				if (!this.built[s]) this.statUnbuilt++;
				else if (this.dirty[s]) this.statDirty++;
				else this.statExpired++;
			}
		}
		// Rebuilds come in batches (each has a fixed cost): when a sector in view isn't valid, or the oldest in view has used
		// 60% of its margin. A batch takes the sectors in view that aren't valid, nearest the view's center first; then those in
		// view past 30% of their margin, most stale first; then a few out of view that aren't valid, so a turn finds them ready
		// (also every 16th frame, for a camera at rest).
		double fa = diamond(fwdX, fwdZ);
		boolean must = false;
		double oldest = 0;
		for (int s = 0; s < SECTORS; s++) {
			must |= inView[s] && !valid[s] && !this.full[s];
			if (inView[s] && valid[s]) oldest = Math.max(oldest, stale[s]);
		}
		// (out of view: only for a camera at rest; a moving one would see them expire unseen)
		boolean due = must || oldest >= 0.6, background = this.speed < 0.005 && (due || this.statFramesTotal % 16 == 0);
		int n = 0, band = -1;
		while (due && n < BUDGET) {
			int best = -1;
			double bestD = 0;
			for (int s = 0; s < SECTORS; s++) {
				if (!inView[s] || valid[s] || rb[s] || this.full[s] || (band >= 0 && s / AZ != band)) continue;
				double d = Math.abs(((s & (AZ - 1)) + 0.5) * (4.0 / AZ) - fa);
				d = Math.min(d, 4 - d);
				if (best < 0 || d < bestD) {
					best = s;
					bestD = d;
				}
			}
			if (best < 0) break;
			rb[best] = true;
			n++;
			// (one band a batch: its margin is the frame's)
			if (FAR_EPS > 0) band = best / AZ;
		}
		while (due && n < BUDGET) {
			int best = -1;
			for (int s = 0; s < SECTORS; s++)
				if (inView[s] && !rb[s] && valid[s] && stale[s] > 0.3 && (band < 0 || s / AZ == band) && (best < 0 || stale[s] > stale[best])) best = s;
			if (best < 0) break;
			rb[best] = true;
			n++;
			if (FAR_EPS > 0) band = best / AZ;
		}
		for (int s = 0, bg = 0; background && s < SECTORS && n < BUDGET + 4 && bg < 4; s++)
			if (!valid[s] && !rb[s] && !this.full[s] && (band < 0 || s / AZ == band) && (FAR_EPS <= 0 || band >= 0 || (band = s / AZ) >= 0)) {
				rb[s] = true;
				n++;
				bg++;
			}
		if (band == 1) this.batchEps = farEps;
		long r0 = 0, r1 = 0, d0 = 0, d1 = 0, l0 = 0, l1 = 0;
		for (int s = 0; s < SECTORS; s++) {
			long bit = 1L << (s & (AZ - 1));
			boolean draw = valid[s] || rb[s], live = inView[s] && !draw;
			if (s < AZ) {
				if (rb[s]) r0 |= bit;
				if (draw) d0 |= bit;
				if (live) l0 |= bit;
			} else {
				if (rb[s]) r1 |= bit;
				if (draw) d1 |= bit;
				if (live) l1 |= bit;
			}
		}
		this.rebuild[0] = r0;
		this.rebuild[1] = r1;
		this.drawable[0] = d0;
		this.drawable[1] = d1;
		// the sectors in view still not valid: the live cull draws their blocks this frame
		this.liveNow = (l0 | l1) != 0;
		if (this.liveNow) this.statFallbacks++;
		this.statLive += Long.bitCount(l0) + Long.bitCount(l1);
		this.statRebuilt += Long.bitCount(r0) + Long.bitCount(r1);
		this.statRebuiltNear += Long.bitCount(r0);
		// (only the hand-off band: nothing past it counts, its blocks nor occluders that hide only what's farther)
		float nearFar = (float) (this.bandR + BAND_REACH + SEL + 8);
		long p = this.params;
		MemoryUtil.memPutLong(p, r0);
		MemoryUtil.memPutLong(p + 8, r1);
		MemoryUtil.memPutLong(p + 16, d0);
		MemoryUtil.memPutLong(p + 24, d1);
		MemoryUtil.memPutLong(p + 32, l0);
		MemoryUtil.memPutLong(p + 40, l1);
		MemoryUtil.memPutLong(p + 48, widen(r0 | r1, 2));
		MemoryUtil.memPutLong(p + 56, widen(l0 | l1, 2));
		MemoryUtil.memPutInt(p + 64, this.recCap);
		MemoryUtil.memPutInt(p + 68, PLANT_CAP);
		MemoryUtil.memPutInt(p + 72, STAND_CAP);
		MemoryUtil.memPutInt(p + 76, STAND_BASE);
		MemoryUtil.memPutInt(p + 80, this.recCap0);
		MemoryUtil.memPutFloat(p + 84, r1 != 0 ? 1e30F : nearFar);
		MemoryUtil.memPutFloat(p + 88, l1 != 0 ? 1e30F : nearFar);
		MemoryUtil.memPutInt(p + 92, 0);
	}

	/** Whether this frame rebuilds any sector. */
	boolean rebuilding() {
		return (this.rebuild[0] | this.rebuild[1]) != 0;
	}

	private final boolean[] inViewS = new boolean[SECTORS], validS = new boolean[SECTORS], rbS = new boolean[SECTORS];
	int statRebuiltNear;

	private boolean lastSet;
	private double lastX, lastY, lastZ, speed;
	// the camera's speed in blocks a second (smoothed over ~0.25 s), and which path draws
	private long trackNanos;
	private double tx, ty, tz, bps;
	private boolean fast;
	int statFastFrames, statSwitches;

	/**
	 * -Dmcopt.lod.pkSwitch: what decides which path draws. speed (the default): the full cull above FAST b/s; load: the
	 * cheaper of the two by the GPU time the culls' encoders measure (where the GPU can't sample it: the speed switch).
	 */
	static final boolean LOAD = "load".equals(System.getProperty("mcopt.lod.pkSwitch", "speed"));
	/**
	 * The load switch: the lists draw while their cull costs at most 1 - MARGIN of the full cull's (they draw more survivors,
	 * which the cull's time doesn't see), and come back once they'd cost under 1 - 2 MARGIN of it; a switch waits for
	 * SWITCH_HOLD_S of that, and a path draws MIN_DWELL_S at least.
	 */
	/** The load switch samples one frame's cull time in TIME_EVERY (sampling it may cost the encoder its overlap). */
	static final int TIME_EVERY = 8;
	private static final double MARGIN = 0.12, SWITCH_HOLD_S = 0.15, MIN_DWELL_S = 0.5, WARM_S = 0.25;
	/**
	 * On the lists, every PROBE-th frame draws with the full cull, whose cost the switch keeps measuring (PROBE_FAR-th while
	 * the lists cost under 70% of it).
	 */
	private static final int PROBE = 32, PROBE_FAR = 128;

	/**
	 * Every frame, before anything else: the camera's speed, and whether the lists draw this frame (else the full cull does;
	 * the lists keep their state, and coming back their stale sectors in view are drawn live while the budget rebuilds them).
	 * load: the load switch decides (the GPU can sample the culls' time); timed: this frame's cull samples it
	 * (LodNative.cullTimeSlot).
	 */
	boolean lists(double cx, double cy, double cz, Matrix4f viewProj, double reach, boolean load, boolean timed) {
		long now = System.nanoTime();
		if (this.trackNanos != 0) {
			double dt = Math.max(1e-4, (now - this.trackNanos) / 1e9);
			// (3D: a camera rising or falling wears the lists' margins out as moving does)
			double dx = cx - this.tx, dz = cz - this.tz, dy = cy - this.ty, d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double a = Math.min(1, dt / 0.25);
			// (a jump of more than 16 blocks in a frame is a teleport, not speed)
			this.bps = d3 > 16 ? 0 : this.bps + (d3 / dt - this.bps) * a;
			this.perFrame = d3 > 16 ? 0 : this.perFrame + (d3 - this.perFrame) * 0.1;
		}
		this.trackNanos = now;
		this.tx = cx;
		this.ty = cy;
		this.tz = cz;
		this.probing = false;
		boolean was = this.fast;
		this.framesSinceTimed++;
		if (load && this.tFull > 0) this.fast = !this.loadSwitch(now, cx, cy, cz, viewProj, reach);
		else if (FAST < 0) this.fast = true;   // (a control: the lists kept up to date by nothing, never drawn)
		else if (FAST > 0) this.fast = this.fast ? this.bps > FAST * 0.75 : this.bps > FAST;
		if (was != this.fast) {
			this.statSwitches++;
			this.switchedAt = now;
		}
		if (this.fast) {
			this.statFastFrames++;
			this.lastSet = false;
			return false;
		}
		// (a probe: this frame the full cull draws, and is measured)
		// (a probe is a timed frame: its full cull is measured)
		if (load && ++this.sinceProbe >= (this.tLists > 0 && this.tLists < 0.7 * this.tFull ? PROBE_FAR : PROBE) && timed) {
			this.sinceProbe = 0;
			this.probing = true;
			this.statProbes++;
			return false;
		}
		return true;
	}

	/**
	 * The load switch: whether the lists draw. On the lists, their measured cost (smoothed) against the full cull's (measured
	 * every PROBE frames); on the full cull, the lists' cost estimated from what they would rebuild here (sectors in view,
	 * how fast the camera wears their margins out, the sectors chunk churn dirties) at the cost a sector's rebuild measured.
	 */
	private boolean loadSwitch(long now, double cx, double cy, double cz, Matrix4f viewProj, double reach) {
		boolean onLists = !this.fast;
		double since = (now - this.switchedAt) / 1e9;
		double costL, costF = this.tFull;
		// what the lists would rebuild a frame here, by the model: the sectors in view, worn out by the camera's speed against
		// their margin (rebuilt past 30-60% of it) and by new generations, and those chunk churn dirties
		int inView = 0;
		if (onLists) {
			for (int s = 0; s < SECTORS; s++) if (this.inViewS[s]) inView++;
		} else {
			if (!this.selSet) {
				this.selX = cx;
				this.selZ = cz;
			}
			long near = this.inView(viewProj, cx, cy, cz, this.bandR + BAND_REACH), far = this.inView(viewProj, cx, cy, cz, reach * 1.5 + 1024);
			inView = Long.bitCount(near) + Long.bitCount(far);
			for (int s = 0; s < SECTORS; s++) this.inViewS[s] = ((s < AZ ? near : far) >>> (s & (AZ - 1)) & 1) != 0;
		}
		double v = this.perFrame, e = Math.min(4.0, Math.max(EPS, v * 12));
		double demand = inView * (v / (0.45 * e) + v / SEL) + this.dirtyRate;
		this.estDemand = demand;
		if (onLists) {
			// (the model against what the lists rebuild: its correction)
			if (since >= WARM_S && demand > 0.05) this.demandScale += (Math.clamp(this.rebuildRate / demand, 0.25, 4.0) - this.demandScale) * 0.002;
			costL = since < WARM_S || this.tLists <= 0 ? -1 : this.tLists;
		} else {
			costL = this.tDraw + demand * this.demandScale * this.cSector;
		}
		this.estLists = costL;
		boolean other = costL >= 0 && (onLists ? costL > costF * (1 - MARGIN) : costL < costF * (1 - 2 * MARGIN));
		// (back to the lists only after a backoff that doubles each time they lost again soon after)
		double dwell = onLists ? MIN_DWELL_S : Math.max(MIN_DWELL_S, this.backoff);
		if (!other || since < dwell) {
			this.otherSince = 0;
			return onLists;
		}
		if (this.otherSince == 0) this.otherSince = now;
		if ((now - this.otherSince) / 1e9 < SWITCH_HOLD_S) return onLists;
		this.otherSince = 0;
		if (onLists) this.backoff = since < 2.0 ? Math.min(8.0, Math.max(1.0, this.backoff * 2)) : 0;
		else this.tLists = -1;
		return !onLists;
	}

	/** The load switch: sectors the lists rebuild a frame (smoothed), the model's correction, the wait before trying them again. */
	private double rebuildRate, demandScale = 1, backoff;

	// the load switch's measurements (ms of GPU time, smoothed): the full cull; the lists, all frames and those that rebuilt
	// nothing (the draw alone); a sector's rebuild; the sectors chunk churn dirties a frame (in view)
	private double tFull = -1, tLists = -1, tDraw = 0.25, cSector = 0.02, dirtyRate, perFrame, estLists, estDemand;
	private long switchedAt, otherSince;
	private int sinceProbe, dirtyMarks, framesSinceTimed;
	private boolean probing;
	int statProbes;
	// per timing slot (LodNative.cullTimeSlot): the path that ran (0 none, 1 the full cull, 2 the lists), sectors rebuilt, live cull
	private final byte[] slotPath = new byte[16];
	private final int[] slotRebuilt = new int[16];
	private final boolean[] slotLive = new boolean[16];

	/** After the frame's cull was encoded with its GPU time sampled into slot: which path it was. */
	void timedPath(int slot, boolean listsRan) {
		this.slotPath[slot] = (byte) (listsRan ? 2 : 1);
		this.slotRebuilt[slot] = listsRan ? Long.bitCount(this.rebuild[0]) + Long.bitCount(this.rebuild[1]) : 0;
		if (listsRan) this.rebuildRate += (this.slotRebuilt[slot] - this.rebuildRate) * 0.08;
		this.slotLive[slot] = listsRan && this.liveNow;
		// the sectors chunk churn dirtied a frame since the last timed frame (in view), smoothed
		this.dirtyRate += (this.dirtyMarks / (double) Math.max(1, this.framesSinceTimed) - this.dirtyRate) * 0.3;
		this.dirtyMarks = 0;
		this.framesSinceTimed = 0;
	}

	/** A finished frame's cull time (slot), into the switch's measurements. */
	void cullTime(int slot, double ms) {
		byte path = this.slotPath[slot];
		this.slotPath[slot] = 0;
		if (path == 0 || ms < 0) return;
		if (path == 1) {
			this.tFull = this.tFull < 0 ? ms : this.tFull + (ms - this.tFull) * (this.fast ? 0.3 : 0.5);
			return;
		}
		int n = this.slotRebuilt[slot];
		if (!this.slotLive[slot]) {
			if (n == 0) this.tDraw += (ms - this.tDraw) * 0.3;
			else this.cSector += (Math.max(0, ms - this.tDraw) / n - this.cSector) * 0.3;
		}
		if (!this.fast && (System.nanoTime() - this.switchedAt) / 1e9 >= WARM_S) this.tLists = this.tLists < 0 ? ms : this.tLists + (ms - this.tLists) * 0.15;
	}

	/** The switch's state for the stats line. */
	String loadState() {
		return String.format("load switch: full %.3f ms, lists %.3f (draw %.3f + %.4f a sector rebuilt; estimate %.3f at %.2f sectors a frame x %.2f, %.2f"
			+ " rebuilt), %d probes, backoff %.1f s, on the %s", this.tFull, this.tLists, this.tDraw, this.cSector, this.estLists, this.estDemand, this.demandScale,
			this.rebuildRate, this.statProbes, this.backoff, this.fast ? "full cull" : "lists");
	}

	/** This frame's rebuilds' margin (blocks). */
	private float batchEps = EPS;
	private final float[] eps = new float[SECTORS];
	private final double[] stale = new double[SECTORS];
	int statUnbuilt, statDirty, statExpired;


	private long statFramesTotal;

	/** After the frame's passes are encoded: the sectors rebuilt now hold for this camera. */
	void encoded(double cx, double cy, double cz) {
		for (int s = 0; s < SECTORS; s++) {
			if ((this.rebuild[s / AZ] >>> (s & (AZ - 1)) & 1) == 0) continue;
			this.built[s] = true;
			this.dirty[s] = false;
			this.eps[s] = this.batchEps;
			this.bx[s] = cx;
			this.by[s] = cy;
			this.bz[s] = cz;
			this.visPending[s] = VIS;
		}
	}

	// ---- the visible set (VIS) ----

	private final boolean[] visPending = new boolean[SECTORS];
	private final long visFacet = MemoryUtil.nmemCalloc(1, 512), visUse = MemoryUtil.nmemCalloc(1, 16);
	int statVisPruned;

	/**
	 * After prepare, before the frame's cull: prunes up to VIS_PER_FRAME sectors built in earlier frames and still valid (in
	 * view first), each against a facet from its build camera. comp: this frame's CompFrame (the facets copy the rest of it).
	 */
	void prune(long lod, long enc, long frame, int frameLength, long comp, int compLength, long bufs) {
		if (!VIS) return;
		long u0 = 0, u1 = 0;
		for (int s = 0; s < SECTORS; s++)
			if (this.built[s] && !this.dirty[s]) {
				if (s < AZ) u0 |= 1L << s;
				else u1 |= 1L << (s - AZ);
			}
		MemoryUtil.memPutLong(this.visUse, u0);
		MemoryUtil.memPutLong(this.visUse + 8, u1);
		// the screen's focal length in pixels (CompFrame tex.x: radians a pixel spans)
		double texX = MemoryUtil.memGetFloat(comp + 240);
		double focal = texX > 0 ? 1.0 / texX : 1000;
		for (int n = 0; n < VIS_PER_FRAME; n++) {
			int best = -1;
			for (int s = 0; s < SECTORS; s++) {
				if (!this.visPending[s] || !this.validS[s] || this.rbS[s]) continue;
				if (best < 0 || this.inViewS[s] && !this.inViewS[best]) best = s;
			}
			if (best < 0) break;
			this.visPending[best] = false;
			int a = best & (AZ - 1);
			// the facet: along the sector's middle, a sector wide past each edge, 35 degrees up and down
			double ex = dirX(a), ez = dirZ(a), fx = dirX(a + 0.5), fz = dirZ(a + 0.5), gx = dirX(a + 1), gz = dirZ(a + 1);
			double half = Math.acos(Math.min(1, ex * gx + ez * gz)), th = Math.tan(half), tv = Math.tan(Math.toRadians(35));
			int fw = (int) Math.ceil(th * focal * 2 * VIS_RES), fh = (int) Math.ceil(tv * focal * 2 * VIS_RES);
			MemoryUtil.memCopy(comp, this.visFacet, compLength);
			long f = this.visFacet;
			double sx = 1 / th, sy = 1 / tv, rx = -fz, rz = fx;
			float[] m = {(float) (rx * sx), 0, 0, (float) fx, 0, (float) sy, 0, 0, (float) (rz * sx), 0, 0, (float) fz, 0, 0, 1, 0};
			for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(f + i * 4L, m[i]);
			MemoryUtil.memPutFloat(f + 112, fw);
			MemoryUtil.memPutFloat(f + 116, fh);
			double ox = Math.floor(this.bx[best]), oy = Math.floor(this.by[best]), oz = Math.floor(this.bz[best]);
			MemoryUtil.memPutInt(f + 208, (int) ox);
			MemoryUtil.memPutInt(f + 212, (int) oy);
			MemoryUtil.memPutInt(f + 216, (int) oz);
			MemoryUtil.memPutFloat(f + 224, (float) (this.bx[best] - ox));
			MemoryUtil.memPutFloat(f + 228, (float) (this.by[best] - oy));
			MemoryUtil.memPutFloat(f + 232, (float) (this.bz[best] - oz));
			float e = VIS_EPS != null ? Float.parseFloat(VIS_EPS) : this.eps[best];
			LodNative.pkVis(lod, enc, frame, frameLength, f, compLength, this.params, PARAMS_BYTES, bufs, a, best < AZ ? 1 : 2, e, e,
				this.visUse, fw, fh, Math.max(this.recCap, this.recCap0));
			this.statVisPruned++;
		}
	}

	/** The direction (x, z: unit) of diamond angle a x 4 / AZ (sector a's start; columns.metal hzAzimuth's inverse). */
	private static double dirX(double a) {
		double d = a * (4.0 / AZ);
		d -= 4 * Math.floor(d / 4);
		double q = d < 2 ? 1 - d : d - 3, z = d < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		return q / Math.hypot(q, z);
	}

	private static double dirZ(double a) {
		double d = a * (4.0 / AZ);
		d -= 4 * Math.floor(d / 4);
		double q = d < 2 ? 1 - d : d - 3, z = d < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		return z / Math.hypot(q, z);
	}

	/**
	 * MeshFrame's position-keyed fields: maskDist.yz the selection camera from the camera, hz.z the margin, hz.w the band
	 * radius, caps.z records per sector.
	 */
	void frameFields(long frame, double cx, double cz) {
		// (no live ring: a camera fast enough to make the hand-off's changes dirty sectors all the time draws with the full cull)
		this.liveRing = -1;
		MemoryUtil.memPutFloat(frame + 116, (float) (this.selX - cx));
		MemoryUtil.memPutFloat(frame + 120, (float) (this.selZ - cz));
		MemoryUtil.memPutFloat(frame + 124, (float) this.liveRing);
		// the culls' own instances stay under the sectors' stand-ins
		MemoryUtil.memPutInt(frame + 136, STAND_BASE);
		MemoryUtil.memPutFloat(frame + 408, this.batchEps);
		MemoryUtil.memPutFloat(frame + 412, (float) this.bandR);
		MemoryUtil.memPutInt(frame + 424, this.recCap);
	}

	/** mcl_pk_cull's buffer handles, in its order. */
	long bufs(long table, long arena, long mask, LodMesh mesh, LodClip clip) {
		long[] b = {table, arena, mask, this.argsBuf, mesh.instBuf, this.plantBuf, this.recBuf, mesh.horizonBuf, mesh.listBuf, clip.geomBuf, clip.crownBuf,
			this.secBuf, mesh.instBuf, this.listBuf, mesh.argsBuf, mesh.survBuf, mesh.plantBuf};
		for (int i = 0; i < b.length; i++) MemoryUtil.memPutLong(this.bufs + i * 8L, b[i]);
		return this.bufs;
	}

	/**
	 * Azimuths in view out to r blocks from the selection camera, conservatively. A sector's blocks lie within its azimuth
	 * range around the selection camera, widened by half a sector each way, between y -520 and 520. They are tested as a
	 * triangle (apex at the selection camera) against the frustum's four side planes.
	 */
	private long inView(Matrix4f m, double cx, double cy, double cz, double r) {
		double p0x = m.m03() + m.m00(), p0y = m.m13() + m.m10(), p0z = m.m23() + m.m20(), p0w = m.m33() + m.m30();
		double p1x = m.m03() - m.m00(), p1y = m.m13() - m.m10(), p1z = m.m23() - m.m20(), p1w = m.m33() - m.m30();
		double p2x = m.m03() + m.m01(), p2y = m.m13() + m.m11(), p2z = m.m23() + m.m21(), p2w = m.m33() + m.m31();
		double p3x = m.m03() - m.m01(), p3y = m.m13() - m.m11(), p3z = m.m23() - m.m21(), p3w = m.m33() - m.m31();
		double ax = this.selX - cx, az = this.selZ - cz, y0 = -520 - cy, y1 = 520 - cy;
		long out = 0;
		for (int s = 0; s < AZ; s++) {
			double[] px = this.ptX, pz = this.ptZ;
			px[0] = ax;
			pz[0] = az;
			px[1] = ax + W0X[s] * r;
			pz[1] = az + W0Z[s] * r;
			px[2] = ax + W1X[s] * r;
			pz[2] = az + W1Z[s] * r;
			px[3] = ax + WMX[s] * r;
			pz[3] = az + WMZ[s] * r;
			if (outside(px, pz, y0, y1, p0x, p0y, p0z, p0w) || outside(px, pz, y0, y1, p1x, p1y, p1z, p1w) || outside(px, pz, y0, y1, p2x, p2y, p2z, p2w)
				|| outside(px, pz, y0, y1, p3x, p3y, p3z, p3w)) continue;
			out |= 1L << s;
		}
		return out;
	}

	private final double[] ptX = new double[4], ptZ = new double[4];

	/** Whether all 8 points (4 xz, 2 y) are on the plane's outer side. */
	private static boolean outside(double[] px, double[] pz, double y0, double y1, double a, double b, double c, double d) {
		for (int k = 0; k < 4; k++)
			if (a * px[k] + b * y0 + c * pz[k] + d >= 0 || a * px[k] + b * y1 + c * pz[k] + d >= 0) return false;
		return true;
	}

	// per sector: its widened azimuth range's ends and a point covering the arc's bulge (unit directions, the bulge's 1 / cos)
	private static final double[] W0X = new double[AZ], W0Z = new double[AZ], W1X = new double[AZ], W1Z = new double[AZ], WMX = new double[AZ],
		WMZ = new double[AZ];

	static {
		for (int s = 0; s < AZ; s++) {
			double a0 = (s - 0.5) * (4.0 / AZ), a1 = (s + 1.5) * (4.0 / AZ);
			double[] u0 = dir(a0), u1 = dir(a1), um = dir((a0 + a1) * 0.5);
			double ch = Math.sqrt(Math.max(0.5 * (1 + u0[0] * u1[0] + u0[1] * u1[1]), 1e-4));
			W0X[s] = u0[0];
			W0Z[s] = u0[1];
			W1X[s] = u1[0];
			W1Z[s] = u1[1];
			WMX[s] = um[0] / ch;
			WMZ[s] = um[1] / ch;
		}
	}

	private static long widen(long m, int w) {
		long out = 0;
		for (int s = 0; s < AZ; s++)
			if ((m >>> s & 1) != 0)
				for (int k = -w; k <= w; k++) out |= 1L << Math.floorMod(s + k, AZ);
		return out;
	}

	/**
	 * A sector whose records, plants or stand-ins overflowed its share: log it, grow the records' share (plants' and
	 * stand-ins' are generous), and rebuild everything.
	 */
	private void checkOverflow() {
		int flags = 0, near = 0;
		for (int s = 0; s < SECTORS; s++) {
			int f = MemoryUtil.memGetInt(this.secAddr + ((long) s * WORDS + 15) * 4);
			flags |= f;
			if (s < AZ) near |= f;
		}
		if (flags == 0) return;
		this.statOverflows++;
		boolean grow0 = (near & 1) != 0 && this.recCap0 < (1 << 20), grow1 = (flags & ~near & 1) != 0 && this.recCap < (1 << 20);
		// (logged at most every 10 s: a print on the render thread costs)
		long now = System.nanoTime();
		if (now - this.overflowLogged > 10_000_000_000L) {
			this.overflowLogged = now;
			System.out.println("mcopt-lod: position-keyed lists overflowed (" + ((flags & 1) != 0 ? "records " : "") + ((flags & 2) != 0 ? "plants " : "")
				+ ((flags & 4) != 0 ? "stand-ins " : "") + "); records per sector " + this.recCap0 + (grow0 ? " -> " + this.recCap0 * 2 : "") + " (hand-off band), "
				+ this.recCap + (grow1 ? " -> " + this.recCap * 2 : "") + " (far band); " + this.statOverflows + " overflows so far");
		}
		if (grow0 || grow1) {
			for (int s = 0; s < SECTORS; s++) MemoryUtil.memPutInt(this.secAddr + ((long) s * WORDS + 15) * 4, 0);
			this.releaseLater.add(new long[] {this.statFramesTotal, this.recBuf});
			if (grow0) this.recCap0 *= 2;
			if (grow1) this.recCap *= 2;
			this.recBuf = LodNative.privateBuffer(this.ctx, (long) AZ * (this.recCap0 + this.recCap) * 16);
			java.util.Arrays.fill(this.built, false);
			return;
		}
		// what can't grow (plants, stand-ins, records at their largest): those sectors draw by the live cull until what they hold
		// changes (rebuilding them now would only overflow again, every frame)
		for (int s = 0; s < SECTORS; s++) {
			long w = this.secAddr + ((long) s * WORDS + 15) * 4;
			if (MemoryUtil.memGetInt(w) != 0) {
				this.full[s] = true;
				MemoryUtil.memPutInt(w, 0);
			}
		}
	}

	/** Sectors that overflowed what can't grow: never drawable (the live cull draws them) until dirtied or a new generation. */
	private final boolean[] full = new boolean[SECTORS];
	private long overflowLogged;

	double recordsMb() {
		return (double) AZ * (this.recCap0 + this.recCap) * 16 / 1048576.0;
	}

	/** GPU buffers, for release by the owner once frames in flight are done. */
	long[] buffers() {
		long[] b = new long[5 + this.releaseLater.size()];
		int i = 0;
		b[i++] = this.recBuf;
		b[i++] = this.plantBuf;
		b[i++] = this.secBuf;
		b[i++] = this.listBuf;
		b[i++] = this.argsBuf;
		for (long[] o : this.releaseLater) b[i++] = o[1];
		return b;
	}
}
