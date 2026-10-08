package mcopt.metal.lod;

import org.lwjgl.system.MemoryUtil;

/**
 * The far terrain's clipmap, in shared buffers the GPU walks directly (Apple silicon: CPU writes are GPU reads, no upload).
 * Level L is an N x N window of cells 2^L blocks wide, centered on the camera's tile and addressed toroidally (cell (x, z)
 * lives at (x mod N, z mod N)), filled by whole tiles of 64 x 64 cells. Per cell two words: geometry (top y + 512, wet,
 * valid) and color (top RGB565 | side RGB565 << 16); per 8 x 8 cells the highest top (+ 512, 0 when empty), which lets the
 * walk skip blocks under its y-buffer.
 *
 * When the window moves, the tiles it leaves are cleared before the frame that uses the new window is recorded: a slot
 * reads as "no data" (the walk falls back to the next coarser level) until its new tile is written. The tile a slot gave
 * up lay past the switch distance of the old window too, so frames still in flight never read a recycled slot.
 */
final class LodClip {
	static final int TILE = 64;
	private static final int MARGIN = 4;
	final int n, logN, levels, tilesPerSide;
	final long geomBuf, colorBuf, mipBuf, crownBuf, texBuf;
	final long geom, color, mip, crown, tex;
	/** Levels below this have a crown word per cell (trees floating over their ground). */
	final int crownLevels;
	/** Level 0 has plants (grass, flowers): two words per cell after the texture words (columns.metal: GEOM_PLANT_BLOCKS). */
	final boolean plants = LodConfig.PLANTS;
	final long levelWords, levelMipWords;
	/** Per level: the window's first tile (x, z). */
	final int[] winTx, winTz;
	/** Per level, per slot: the resident tile's key, or -1. */
	final long[][] slotKey;
	/** Per level, per slot: the tile's lowest and highest top (valid while resident). */
	final short[][] slotMin, slotMax;
	/** Per level: past this distance (blocks) the walk moves on to the next coarser level. */
	final float[] switchDist;
	/** Bumped whenever a tile is written or cleared. */
	long version;
	/** Per level, per slot: bumped whenever the slot's words change (written, refreshed, cleared). */
	final long[][] slotVer;
	/**
	 * -Dmcopt.lod.publish=pair (mesh path): a resident tile's words never change under its installed mesh. Real chunks' cells go
	 * to a staged copy of the tile; the mesh is built from a snapshot of it, and the snapshot's words are written into the
	 * clipmap in the same step (at a frame boundary, on the render thread) as its mesh is installed. Mesh workers read the live
	 * words under a per-slot sequence lock (odd while the render thread writes the slot), so they never mesh a torn tile.
	 */
	static final boolean PAIRS = "pair".equals(System.getProperty("mcopt.lod.publish", "")) || LodPublish.ON;
	/** publish=gpu: the publisher staged words go through (set by Lod), else null. */
	@org.jspecify.annotations.Nullable LodPublish publisher;
	/** Per level, per slot: bumped by every put and clear (a staged snapshot of an earlier residency is void). */
	final long[][] epoch;
	/** Per level: per slot, odd while the render thread writes the slot's live words (mesh workers retry around it). */
	final java.util.concurrent.atomic.AtomicLongArray[] writing;
	/** Staged copies of resident tiles that real chunks changed (PAIRS), by tile key. Render thread only. */
	private final java.util.HashMap<Long, Snapshot> staged = new java.util.HashMap<>();

	/** A tile's words (crown, runs, tex, plants where the level has them), and the staged version they are. */
	static final class Snapshot {
		final int level, tx, tz;
		final int[] g = new int[TILE * TILE], c = new int[TILE * TILE];
		final int @org.jspecify.annotations.Nullable [] cr, runs, tw, pl;
		long version;
		/** The slot's residency epoch it was staged in (a clear or a new put makes it void). */
		long epoch;

		Snapshot(int level, int tx, int tz, boolean crowns, boolean texture, boolean plants) {
			this.level = level;
			this.tx = tx;
			this.tz = tz;
			this.cr = crowns ? new int[TILE * TILE] : null;
			this.runs = crowns ? new int[TILE * TILE] : null;
			this.tw = texture ? new int[TILE * TILE] : null;
			this.pl = plants ? new int[2 * TILE * TILE] : null;
		}

		Snapshot copy() {
			Snapshot s = new Snapshot(this.level, this.tx, this.tz, this.cr != null, this.tw != null, this.pl != null);
			System.arraycopy(this.g, 0, s.g, 0, this.g.length);
			System.arraycopy(this.c, 0, s.c, 0, this.c.length);
			if (this.cr != null) System.arraycopy(this.cr, 0, s.cr, 0, this.cr.length);
			if (this.runs != null) System.arraycopy(this.runs, 0, s.runs, 0, this.runs.length);
			if (this.tw != null) System.arraycopy(this.tw, 0, s.tw, 0, this.tw.length);
			if (this.pl != null) System.arraycopy(this.pl, 0, s.pl, 0, this.pl.length);
			s.version = this.version;
			s.epoch = this.epoch;
			return s;
		}
	}

	/** Who hears of tiles written or cleared (the mesh path's LodMesh), on the render thread. */
	interface Listener {
		/** A tile's words were written (refreshed: real chunks changed some of an already resident tile). */
		void tilePut(int level, int tx, int tz, boolean refreshed);

		void tileCleared(int level, int slot);

		/** PAIRS: real chunks changed a resident tile; its words wait in the snapshot until published with its mesh. */
		default void tileStaged(Snapshot s) {
		}
	}

	@org.jspecify.annotations.Nullable Listener listener;

	LodClip(long ctx, int n, double reach) {
		if (Integer.bitCount(n) != 1 || n < 256) throw new IllegalArgumentException("mcopt.lod.n must be a power of two >= 256: " + n);
		this.n = n;
		this.logN = Integer.numberOfTrailingZeros(n);
		this.tilesPerSide = n / TILE;
		// a ray stays inside level L's window up to (N / 2 - TILE) cells from the camera, wherever it is in its tile; the walk
		// leaves a level at the first boundary aligned to the next past its switch distance (up to 2 cells on): 4 cells to spare
		int lv = 1;
		while ((n / 2.0 - TILE - MARGIN) * (1L << (lv - 1)) < reach && lv < 12) lv++;
		this.levels = lv;
		this.switchDist = new float[lv];
		for (int l = 0; l < lv; l++) this.switchDist[l] = (float) ((n / 2.0 - TILE - MARGIN) * (1L << l));
		this.levelWords = (long) n * n;
		this.levelMipWords = (long) (n / 8) * (n / 8);
		this.geomBuf = buffer(ctx, this.levelWords * 4 * lv);
		this.colorBuf = buffer(ctx, this.levelWords * 4 * lv);
		// per level the 8 x 8 maxima, then (after every level's) the per-tile maxima (64 x 64 cells)
		this.mipBuf = LodNative.buffer(ctx, this.mipBytes(lv));
		this.crownLevels = Math.min(lv, Math.max(0, LodConfig.CROWN_LEVELS));
		// crown words for the crown levels, then their run words (a crown's tiers)
		this.crownBuf = buffer(ctx, Math.max(16, this.levelWords * 8 * this.crownLevels));
		this.crown = LodNative.contents(this.crownBuf);
		// level 0's texture words (palette numbers of each column's top, side and under-the-top blocks)
		// level 0's texture words (palette numbers of each column's top, side and under-the-top blocks), then its plant words
		long texBytes = LodConfig.TEXTURES ? this.levelWords * 4 * (this.plants ? 3 : 1) : 16;
		this.texBuf = buffer(ctx, texBytes);
		this.tex = LodNative.contents(this.texBuf);
		MemoryUtil.memSet(this.tex, 0, texBytes);
		this.geom = LodNative.contents(this.geomBuf);
		this.color = LodNative.contents(this.colorBuf);
		this.mip = LodNative.contents(this.mipBuf);
		MemoryUtil.memSet(this.geom, 0, this.levelWords * 4 * lv);
		MemoryUtil.memSet(this.color, 0, this.levelWords * 4 * lv);
		MemoryUtil.memSet(this.mip, 0, this.mipBytes(lv));
		this.winTx = new int[lv];
		this.winTz = new int[lv];
		java.util.Arrays.fill(this.winTx, Integer.MIN_VALUE);
		this.slotKey = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.slotMin = new short[lv][this.tilesPerSide * this.tilesPerSide];
		this.slotMax = new short[lv][this.tilesPerSide * this.tilesPerSide];
		for (long[] k : this.slotKey) java.util.Arrays.fill(k, -1L);
		this.slotVer = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.epoch = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.writing = new java.util.concurrent.atomic.AtomicLongArray[lv];
		for (int l = 0; l < lv; l++) this.writing[l] = new java.util.concurrent.atomic.AtomicLongArray(this.tilesPerSide * this.tilesPerSide);
	}

	private void beginWrite(int level, int slot) {
		this.writing[level].incrementAndGet(slot);
	}

	private void endWrite(int level, int slot) {
		this.writing[level].incrementAndGet(slot);
	}

	/** Whether real chunks' changes to the tile wait in a staged copy (PAIRS). */
	boolean hasStaged(int level, int tx, int tz) {
		return this.staged.containsKey(LodTile.key(level, tx, tz));
	}

	private boolean staging() {
		return PAIRS && this.listener != null;
	}

	/**
	 * -Dmcopt.lod.trackedClip=true: the word buffers the mesh shades from (geometry, color, crowns, textures) with Metal's hazard
	 * tracking (a GPU-written publish would then be ordered after earlier frames' reads by Metal itself); default untracked.
	 */
	static final boolean TRACKED = Boolean.getBoolean("mcopt.lod.trackedClip") || LodPublish.ON;

	private static long buffer(long ctx, long size) {
		return TRACKED ? LodSeam.trackedBuffer(ctx, size) : LodNative.buffer(ctx, size);
	}

	private long mipBytes(int lv) {
		return (this.levelMipWords + (long) this.tilesPerSide * this.tilesPerSide) * 2 * lv;
	}

	/** Where level l's per-tile maxima start in the max-mip buffer (in 16-bit words). */
	long tileMaxOffset(int level) {
		return this.levels * this.levelMipWords + (long) level * this.tilesPerSide * this.tilesPerSide;
	}

	/** Where the crowns' run words start in the crown buffer (in words). */
	long runsOffset() {
		return this.levelWords * this.crownLevels;
	}

	long bytes() {
		return this.levelWords * 8 * this.levels + this.mipBytes(this.levels) + this.levelWords * 8 * this.crownLevels
			+ (LodConfig.TEXTURES ? this.levelWords * 4 * (this.plants ? 3 : 1) : 0);
	}

	int span(int level) {
		return TILE << level;
	}

	/** Moves every level's window to the camera's tile; clears the slots of tiles that left. Returns whether any moved. */
	boolean recenter(double camX, double camZ) {
		boolean moved = false;
		int h = this.tilesPerSide / 2;
		for (int l = 0; l < this.levels; l++) {
			int span = this.span(l);
			int tx = Math.floorDiv((int) Math.floor(camX), span) - h, tz = Math.floorDiv((int) Math.floor(camZ), span) - h;
			if (tx == this.winTx[l] && tz == this.winTz[l]) continue;
			moved = true;
			this.winTx[l] = tx;
			this.winTz[l] = tz;
			long[] keys = this.slotKey[l];
			for (int s = 0; s < keys.length; s++) {
				long k = keys[s];
				if (k == -1L) continue;
				if (!this.inWindow(l, LodTile.txOf(k), LodTile.tzOf(k))) this.clear(l, s);
			}
		}
		return moved;
	}

	boolean inWindow(int level, int tx, int tz) {
		int x = tx - this.winTx[level], z = tz - this.winTz[level];
		return x >= 0 && z >= 0 && x < this.tilesPerSide && z < this.tilesPerSide;
	}

	int slot(int tx, int tz) {
		int m = this.tilesPerSide - 1;
		return (tz & m) * this.tilesPerSide + (tx & m);
	}

	boolean resident(int level, int tx, int tz) {
		return this.slotKey[level][this.slot(tx, tz)] == LodTile.key(level, tx, tz);
	}

	private long cellOffset(int level, int cx, int cz) {
		int m = this.n - 1;
		return level * this.levelWords + ((long) (cz & m) << this.logN) + (cx & m);
	}

	private void clear(int level, int slot) {
		int tx = slot % this.tilesPerSide, tz = slot / this.tilesPerSide;
		if (this.slotKey[level][slot] != -1L) this.staged.remove(this.slotKey[level][slot]);
		this.epoch[level][slot]++;
		this.beginWrite(level, slot);
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, tx * TILE, tz * TILE + z) * 4;
			MemoryUtil.memSet(this.geom + at, 0, TILE * 4);
		}
		for (int z = 0; z < TILE / 8; z++) {
			long at = (level * this.levelMipWords + (long) (tz * (TILE / 8) + z) * (this.n / 8) + tx * (TILE / 8)) * 2;
			MemoryUtil.memSet(this.mip + at, 0, (TILE / 8) * 2);
		}
		MemoryUtil.memPutShort(this.mip + (this.tileMaxOffset(level) + slot) * 2, (short) 0);
		this.slotKey[level][slot] = -1L;
		this.slotVer[level][slot]++;
		this.endWrite(level, slot);
		this.version++;
		Listener l = this.listener;
		if (l != null) l.tileCleared(level, slot);
	}

	/**
	 * Writes a tile's 64 x 64 cells (row-major, x fastest) if it lies in its level's window; cr: crown words or null; pl: level
	 * 0's plant words (the first word of every cell, then the second), or null.
	 */
	boolean put(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		if (!this.inWindow(level, tx, tz)) return false;
		this.staged.remove(LodTile.key(level, tx, tz));
		this.epoch[level][this.slot(tx, tz)]++;
		this.write(level, tx, tz, g, c, cr, tw, runs, pl);
		Listener l = this.listener;
		if (l != null) l.tilePut(level, tx, tz, false);
		return true;
	}

	/** A staged snapshot's words into the clipmap (its mesh is installed in the same step); false when the tile isn't resident. */
	boolean publish(Snapshot s) {
		if (!this.resident(s.level, s.tx, s.tz) || s.epoch != this.epoch[s.level][this.slot(s.tx, s.tz)]) return false;
		if (this.publisher != null) this.writeGpu(s.level, s.tx, s.tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
		else this.write(s.level, s.tx, s.tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
		long key = LodTile.key(s.level, s.tx, s.tz);
		Snapshot st = this.staged.get(key);
		if (st != null && st.version == s.version) this.staged.remove(key);
		return true;
	}

	/** As write, the words staged for the GPU's copy this frame (LodPublish) instead of written by the CPU. */
	private void writeGpu(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		LodPublish p = this.publisher;
		int s0 = this.slot(tx, tz);
		this.beginWrite(level, s0);
		boolean crowns = level < this.crownLevels, texture = level == 0 && LodConfig.TEXTURES, plant = level == 0 && this.plants && pl != null;
		int[] gm = new int[TILE * TILE];
		for (int i = 0; i < gm.length; i++) {
			int gw = g[i];
			if (!crowns || cr == null) gw &= ~GEOM_CROWN_BITS;
			if (!plant) gw &= ~GEOM_PLANT_BITS;
			gm[i] = gw;
		}
		int[] crw = crowns ? (cr != null ? cr : new int[TILE * TILE]) : null;
		int[] tww = texture ? (tw != null ? tw : new int[TILE * TILE]) : null;
		p.stage(level, tx, tz, gm, c, crw, crowns ? runs : null, tww, plant ? pl : null);
		this.slotKey[level][s0] = LodTile.key(level, tx, tz);
		this.mips(level, tx, tz, g);
		this.slotVer[level][s0]++;
		this.version++;
		this.endWrite(level, s0);
	}

	private void write(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		int x0 = tx * TILE, z0 = tz * TILE;
		int s0 = this.slot(tx, tz);
		this.beginWrite(level, s0);
		boolean crowns = level < this.crownLevels, texture = level == 0 && LodConfig.TEXTURES, plant = level == 0 && this.plants && pl != null;
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, x0, z0 + z) * 4;
			for (int x = 0; x < TILE; x++) {
				int i = z * TILE + x;
				// the crown word before the geometry word that announces it (the GPU may read in between)
				if (crowns) {
					MemoryUtil.memPutInt(this.crown + at + x * 4L, cr != null ? cr[i] : 0);
					MemoryUtil.memPutInt(this.crown + this.runsOffset() * 4 + at + x * 4L, runs != null ? runs[i] : 0);
				}
				if (texture) MemoryUtil.memPutInt(this.tex + at + x * 4L, tw != null ? tw[i] : 0);
				// the plant words before the geometry word that announces the plant
				if (plant) {
					MemoryUtil.memPutInt(this.tex + this.levelWords * 4 + at + x * 4L, pl[i]);
					MemoryUtil.memPutInt(this.tex + this.levelWords * 8 + at + x * 4L, pl[TILE * TILE + i]);
				}
				int gw = g[i];
				if (!crowns || cr == null) gw &= ~GEOM_CROWN_BITS;
				if (!plant) gw &= ~GEOM_PLANT_BITS;
				MemoryUtil.memPutInt(this.geom + at + x * 4L, gw);
				MemoryUtil.memPutInt(this.color + at + x * 4L, c[i]);
			}
		}
		int s = this.slot(tx, tz);
		this.slotKey[level][s] = LodTile.key(level, tx, tz);
		this.mips(level, tx, tz, g);
		this.slotVer[level][s]++;
		this.version++;
		this.endWrite(level, s0);
	}

	/** Recomputes the tile's 8 x 8 maxima from its cells, and its range. */
	private void mips(int level, int tx, int tz, int[] g) {
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int bz = 0; bz < TILE / 8; bz++) {
			for (int bx = 0; bx < TILE / 8; bx++) {
				int max = 0;
				for (int z = 0; z < 8; z++) {
					for (int x = 0; x < 8; x++) {
						int w = g[(bz * 8 + z) * TILE + bx * 8 + x];
						if ((w & GEOM_VALID) == 0) continue;
						// a plant's blocks over the top (the walk skips nothing it could see)
						int y = (w & 0xFFF) + (w >>> GEOM_PLANT_SHIFT & 3);
						max = Math.max(max, y);
						lo = Math.min(lo, y);
						hi = Math.max(hi, y);
					}
				}
				int mbx = ((tx * (TILE / 8)) + bx) & (this.n / 8 - 1), mbz = ((tz * (TILE / 8)) + bz) & (this.n / 8 - 1);
				MemoryUtil.memPutShort(this.mip + (level * this.levelMipWords + (long) mbz * (this.n / 8) + mbx) * 2, (short) max);
			}
		}
		int s = this.slot(tx, tz);
		this.slotMin[level][s] = (short) (lo == Integer.MAX_VALUE ? 0 : lo - 512);
		this.slotMax[level][s] = (short) (hi == Integer.MIN_VALUE ? -512 : hi - 512);
		MemoryUtil.memPutShort(this.mip + (this.tileMaxOffset(level) + s) * 2, (short) (hi == Integer.MIN_VALUE ? 0 : hi));
	}

	/** A resident tile's words, read back (for the disk cache after real chunks changed it); cr may be null. */
	void read(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr) {
		this.read(level, tx, tz, g, c, cr, null, null, null);
	}

	void read(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		int x0 = tx * TILE, z0 = tz * TILE;
		boolean crowns = level < this.crownLevels && cr != null, texture = level == 0 && LodConfig.TEXTURES && tw != null;
		boolean plant = level == 0 && this.plants && pl != null;
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, x0, z0 + z) * 4;
			for (int x = 0; x < TILE; x++) {
				g[z * TILE + x] = MemoryUtil.memGetInt(this.geom + at + x * 4L);
				c[z * TILE + x] = MemoryUtil.memGetInt(this.color + at + x * 4L);
				if (crowns) cr[z * TILE + x] = MemoryUtil.memGetInt(this.crown + at + x * 4L);
				if (crowns && runs != null) runs[z * TILE + x] = MemoryUtil.memGetInt(this.crown + this.runsOffset() * 4 + at + x * 4L);
				if (texture) tw[z * TILE + x] = MemoryUtil.memGetInt(this.tex + at + x * 4L);
				if (plant) {
					pl[z * TILE + x] = MemoryUtil.memGetInt(this.tex + this.levelWords * 4 + at + x * 4L);
					pl[TILE * TILE + z * TILE + x] = MemoryUtil.memGetInt(this.tex + this.levelWords * 8 + at + x * 4L);
				}
			}
		}
	}

	/** One level-0 cell's words: geometry, color, crown, runs, texture, plant A, plant B (for the exactness check). */
	int[] cellWords(int cx, int cz) {
		long at = this.cellOffset(0, cx, cz) * 4;
		int[] w = new int[7];
		w[0] = MemoryUtil.memGetInt(this.geom + at);
		w[1] = MemoryUtil.memGetInt(this.color + at);
		if (this.crownLevels > 0) {
			w[2] = MemoryUtil.memGetInt(this.crown + at);
			w[3] = MemoryUtil.memGetInt(this.crown + this.runsOffset() * 4 + at);
		}
		if (LodConfig.TEXTURES) w[4] = MemoryUtil.memGetInt(this.tex + at);
		if (this.plants) {
			w[5] = MemoryUtil.memGetInt(this.tex + this.levelWords * 4 + at);
			w[6] = MemoryUtil.memGetInt(this.tex + this.levelWords * 8 + at);
		}
		return w;
	}

	/** Overwrites one cell of a resident tile (real chunks); the caller refreshes the tile's maxima with refresh(). */
	void putCell(int level, int cx, int cz, int g, int c, int cr, int tw, int runs, int plantA, int plantB) {
		if (this.staging()) {
			this.putStaged(level, cx, cz, g, c, cr, tw, runs, plantA, plantB);
			return;
		}
		int ps = this.slot(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE));
		this.beginWrite(level, ps);
		long at = this.cellOffset(level, cx, cz) * 4;
		boolean crowns = level < this.crownLevels;
		if (crowns) MemoryUtil.memPutInt(this.crown + this.runsOffset() * 4 + at, runs);
		if (level == 0 && LodConfig.TEXTURES) MemoryUtil.memPutInt(this.tex + at, tw);
		if (level == 0 && this.plants) {
			MemoryUtil.memPutInt(this.tex + this.levelWords * 4 + at, plantA);
			MemoryUtil.memPutInt(this.tex + this.levelWords * 8 + at, plantB);
		} else {
			g &= ~GEOM_PLANT_BITS;
		}
		if (crowns) MemoryUtil.memPutInt(this.crown + at, cr);
		else g &= ~GEOM_CROWN_BITS;
		MemoryUtil.memPutInt(this.geom + at, g);
		MemoryUtil.memPutInt(this.color + at, c);
		this.endWrite(level, ps);
	}

	/** putCell into the tile's staged copy (made from its live words on first use). */
	private void putStaged(int level, int cx, int cz, int g, int c, int cr, int tw, int runs, int plantA, int plantB) {
		int tx = Math.floorDiv(cx, TILE), tz = Math.floorDiv(cz, TILE);
		long key = LodTile.key(level, tx, tz);
		Snapshot s = this.staged.get(key);
		boolean crowns = level < this.crownLevels;
		if (s == null) {
			s = new Snapshot(level, tx, tz, crowns, level == 0 && LodConfig.TEXTURES, level == 0 && this.plants);
			this.read(level, tx, tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
			s.epoch = this.epoch[level][this.slot(tx, tz)];
			this.staged.put(key, s);
		}
		int i = (cz - tz * TILE) * TILE + (cx - tx * TILE);
		if (crowns) s.runs[i] = runs;
		if (s.tw != null) s.tw[i] = tw;
		if (s.pl != null) {
			s.pl[i] = plantA;
			s.pl[TILE * TILE + i] = plantB;
		} else {
			g &= ~GEOM_PLANT_BITS;
		}
		if (crowns) s.cr[i] = cr;
		else g &= ~GEOM_CROWN_BITS;
		s.g[i] = g;
		s.c[i] = c;
	}

	private final int[] scratchG = new int[TILE * TILE], scratchC = new int[TILE * TILE];

	void refresh(int level, int tx, int tz) {
		if (this.staging()) {
			Snapshot s = this.staged.get(LodTile.key(level, tx, tz));
			Listener l = this.listener;
			if (s == null || l == null) return;
			s.version++;
			l.tileStaged(s.copy());
			return;
		}
		this.read(level, tx, tz, this.scratchG, this.scratchC, null);
		this.mips(level, tx, tz, this.scratchG);
		this.slotVer[level][this.slot(tx, tz)]++;
		this.version++;
		Listener l = this.listener;
		if (l != null) l.tilePut(level, tx, tz, true);
	}

	static final int GEOM_WET = 0x1000, GEOM_VALID = 0x2000, GEOM_CROWN = 0x4000, GEOM_FRINGE = 1 << 21;
	/** The top (a real chunk's) doesn't hide what's behind it: water, glass, a fence, a slab... The mesher counts no occluder there. */
	static final int GEOM_CLEAR = 1 << 24;
	/**
	 * A real chunk's column (-Dmcopt.lod.realOcc): how deep its top solid run reaches (LodChunks.Snapshot.depth), bits 25-31; 0:
	 * solid all the way down (the generated terrain). The real terrain occludes only for a camera at or over a run's bottom.
	 */
	static final int GEOM_DEPTH_SHIFT = 25;

	static int depthBits(int depth) {
		return (depth & 127) << GEOM_DEPTH_SHIFT;
	}
	/** The crown flag and its thickness (bits 15-20). */
	static final int GEOM_CROWN_BITS = GEOM_CROWN | 63 << 15;
	/** A plant on the cell: its height in blocks (1-3; 0: none), bits 22-23. */
	static final int GEOM_PLANT_SHIFT = 22, GEOM_PLANT_BITS = 3 << GEOM_PLANT_SHIFT;

	static int plantBits(int blocks) {
		return Math.clamp(blocks, 0, 3) << GEOM_PLANT_SHIFT;
	}

	/** A plant's words: its lowest and top block's palette numbers and height; its color, the game's offset (Vec3) as nibbles. */
	static int plantWordA(int lower, int upper, int blocks) {
		return (lower & 1023) | (upper & 1023) << 10 | (Math.clamp(blocks, 1, 3) - 1) << 20;
	}

	static int plantWordB(int color, double ox, double oy, double oz) {
		int nx = (int) Math.clamp(Math.round((ox + 0.25) * 30), 0, 15), nz = (int) Math.clamp(Math.round((oz + 0.25) * 30), 0, 15);
		int ny = (int) Math.clamp(Math.round(-oy * 60), 0, 15);
		return rgb565(color) | nx << 16 | nz << 20 | ny << 24;
	}

	static int geomWord(int topY, boolean wet) {
		return ((topY + 512) & 0xFFF) | (wet ? GEOM_WET : 0) | GEOM_VALID;
	}

	/** A crown floating over its ground: the geometry word (its top, thickness) and the crown word (the ground's y and color). */
	static int crownGeomWord(int crownTopY, int thickness, boolean wet) {
		return geomWord(crownTopY, wet) | GEOM_CROWN | Math.clamp(thickness, 1, 63) << 15;
	}

	static int crownWord(int groundY, int groundColor) {
		return ((groundY + 512) & 0xFFF) | rgb565(groundColor) << 12;
	}

	/** The crown word of a cell without a crown (levels that have the word): the color under its top block. */
	static int belowWord(int belowColor) {
		return rgb565(belowColor) << 12;
	}

	static int rgb565(int rgb) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		return ((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | ((b * 31 + 127) / 255);
	}

	static int colorWord(int top, int side) {
		return rgb565(top) | rgb565(side) << 16;
	}

	void free() {
		LodNative.release(this.texBuf);
		LodNative.release(this.crownBuf);
		LodNative.release(this.geomBuf);
		LodNative.release(this.colorBuf);
		LodNative.release(this.mipBuf);
	}
}
