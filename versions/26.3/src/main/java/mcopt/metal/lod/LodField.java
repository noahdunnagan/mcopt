package mcopt.metal.lod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.FrustumIntersection;

/**
 * The far terrain of one dimension of one world: keeps the clipmap's windows filled. Each level needs the tiles of its
 * ring (between the finer level's switch distance and its own); the coarsest level needs its whole window, so whatever a
 * finer level still lacks falls back to it. Missing tiles go to the workers, most urgent first (the larger on screen, the
 * sooner; tripled when out of view); a worker loads the tile from the disk cache or generates it from the world
 * generator's noise, packs it into the clipmap's words, and the render thread writes it into the window.
 *
 * The game's own chunks overwrite the generated estimate: level 0 cell for cell, coarser levels at their sample points.
 * Tiles that took real chunks are saved back to the cache from the clipmap every few seconds.
 */
final class LodField {
	static final int FORMAT = 10;
	final LodNoise noise;
	final LodClip clip;
	/** The game's own trees on level-0 tiles (null: -Dmcopt.lod.trees=false). */
	final @org.jspecify.annotations.Nullable LodForest forest;
	final Path cache;
	private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>();
	private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean closed;
	private final AtomicLong seq = new AtomicLong();
	/** Window origins per level as the workers see them (a job whose tile left its window is dropped). */
	private final int[] winTx, winTz;

	// stats
	final AtomicLong generated = new AtomicLong(), loaded = new AtomicLong(), genNanos = new AtomicLong(), chunksSummarized = new AtomicLong();
	/** -Dmcopt.lod.chunkTiles: level-0 tiles built from the game's chunks alone, generations skipped because of them. */
	final AtomicLong chunkTiles = new AtomicLong(), chunkSkipped = new AtomicLong();
	/**
	 * -Dmcopt.lod.chunkTiles=true: a level-0 tile not yet generated whose 16 chunks the client already has is built from those
	 * chunks alone, and its generation is skipped. The words are what generation followed by the chunks would leave (the chunks
	 * write every level-0 column), sooner and without the noise: in a flight the tiles under the render distance stop
	 * competing with the ones ahead.
	 */
	static final boolean CHUNK_TILES = Boolean.getBoolean("mcopt.lod.chunkTiles");
	/** Level-0 tiles built from chunks (render thread adds; workers read). */
	private final java.util.Set<Long> chunkBuilt = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Render thread: chunks snapshotted and the time it took (ns). */
	long snapshots, snapNanos;
	long startNanos = System.nanoTime(), settledNanos, firstSettledNanos;
	boolean settled;
	int needed, missing;

	private record Job(double priority, long seq, long key, Runnable task) implements Comparable<Job> {
		@Override
		public int compareTo(Job o) {
			int c = Double.compare(this.priority, o.priority);
			return c != 0 ? c : Long.compare(this.seq, o.seq);
		}
	}

	LodField(LodNoise noise, LodClip clip, Path cache) {
		this.noise = noise;
		this.clip = clip;
		this.cache = cache;
		this.forest = LodConfig.TREES ? new LodForest(noise) : null;
		this.winTx = new int[clip.levels];
		this.winTz = new int[clip.levels];
		for (int i = 0; i < LodConfig.THREADS; i++) {
			Thread t = new Thread(this::work, "mcopt-lod-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			this.threads.add(t);
		}
		System.out.printf("mcopt-lod: field ready: reach %d chunks, %d levels of %d x %d cells (%.0f MB), switch at %s, %d workers, cache %s%n",
			LodConfig.RADIUS_CHUNKS, clip.levels, clip.n, clip.n, clip.bytes() / 1048576.0, java.util.Arrays.toString(clip.switchDist), LodConfig.THREADS, cache);
	}

	void close() {
		this.saveDirty(true);
		this.closed = true;
		for (Thread t : this.threads) t.interrupt();
		if (this.scanner != null) this.scanner.shutdownNow();
	}

	private void work() {
		while (!this.closed) {
			Job j;
			try {
				j = this.jobs.take();
			} catch (InterruptedException e) {
				return;
			}
			if (j.task == null && LodYield.ON && !LodYield.admit(LodTile.levelOf(j.key))) {
				this.jobs.add(j);   // -Dmcopt.lod.yield: the server has chunk work near the player, so this job waits in the queue
				LodYield.idle();
				continue;
			}
			try {
				if (j.task != null) j.task.run();
				else this.generate(j.key);
			} catch (Throwable t) {
				System.out.println("mcopt-lod: job failed: " + t);
				t.printStackTrace(System.out);
				if (j.task == null) this.pending.remove(j.key);
			}
		}
	}

	private Path file(long key) {
		return this.cache.resolve("L" + LodTile.levelOf(key)).resolve(LodTile.txOf(key) + "." + LodTile.tzOf(key) + ".lod");
	}

	private boolean stillWanted(long key) {
		int l = LodTile.levelOf(key), x = LodTile.txOf(key) - this.winTx[l], z = LodTile.tzOf(key) - this.winTz[l];
		return x >= 0 && z >= 0 && x < this.clip.tilesPerSide && z < this.clip.tilesPerSide;
	}

	private void generate(long key) {
		if (!this.stillWanted(key)) {
			this.pending.remove(key);
			return;
		}
		int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
		if (CHUNK_TILES && level == 0 && this.chunkBuilt.contains(key)) {
			// built from the game's chunks meanwhile (the render thread checks again before anything is put)
			this.chunkSkipped.incrementAndGet();
			this.results.add(() -> {
				this.pending.remove(key);
				// evicted since: the next scan asks for it again, and then it's generated
				if (!this.clip.resident(0, tx, tz)) this.chunkBuilt.remove(key);
			});
			return;
		}
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		long start = System.nanoTime();
		boolean fromDisk = LodConfig.DISK_CACHE && this.load(key, g, c, cr, tw, rn, pl);
		if (!fromDisk) {
			LodTile t = new LodTile(level, tx, tz);
			LodForest forest = this.forest;
			boolean exact = level < Math.min(2, LodConfig.TREE_LEVELS) && forest != null && forest.available();
			t.impostorTrees = !exact;
			this.noise.generate(t);
			if (exact) {
				if (level == 0) forest.offer(t);
				forest.plant(t);
				this.noise.dressTrees(t);
				this.noise.dressGround(t);
			}
			pack(t, g, c, cr, rn, pl);
			if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
			this.genNanos.addAndGet(System.nanoTime() - start);
			this.generated.incrementAndGet();
			if (LodConfig.DISK_CACHE) this.save(key, g, c, cr, tw, rn, pl);
		} else {
			this.loaded.incrementAndGet();
		}
		this.results.add(() -> {
			this.pending.remove(key);
			if (CHUNK_TILES && level == 0 && this.chunkBuilt.contains(key)) {
				if (this.clip.resident(0, tx, tz)) return;
				this.chunkBuilt.remove(key);
			}
			if (this.clip.put(level, tx, tz, g, c, cr, tw, rn, pl) && level == 0) {
				List<LodChunks.Summary> real = this.waiting.remove(key);
				if (real != null) for (LodChunks.Summary s : real) this.applyChunk(s);
			}
			this.arrived(key);
		});
	}

	/**
	 * A generated tile as clipmap words. With cr (levels that keep crowns), a tree crown floating over its ground becomes a
	 * crown cell: the geometry word its top and thickness, the color word its colors, the crown word the ground under it.
	 */
	static void pack(LodTile t, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] rn,
		int @org.jspecify.annotations.Nullable [] pl) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < t.cells(); i++) {
			if (pl != null) {
				pl[i] = 0;
				pl[LodTile.CELLS + i] = 0;
			}
			boolean wet = t.water[i] != LodTile.DRY && t.water[i] > t.height[i];
			int surface = wet ? t.water[i] : t.height[i];
			if (cr != null && t.canopyHi[i] >= t.canopyLo[i] && !t.standing[i] && t.canopyHi[i] + 1 > surface) {
				// (the wet flag marks a water top: a crown's top is leaves)
				g[i] = LodClip.crownGeomWord(t.canopyHi[i] + 1, t.canopyHi[i] + 1 - t.canopyLo[i], false);
				c[i] = LodClip.colorWord(t.crownTop[i], t.crownSide[i]);
				cr[i] = LodClip.crownWord(surface, t.top[i]);
				if (rn != null) rn[i] = t.crownRuns[i];
				continue;
			}
			g[i] = LodClip.geomWord(surface, wet) | (t.fringe[i] && !wet && t.level == 0 ? LodClip.GEOM_FRINGE : 0);
			c[i] = LodClip.colorWord(t.top[i], t.side[i]);
			BlockState plant = t.plantLower[i];
			if (pl != null && plant != null && !wet && t.level == 0) {
				// the game's random offset of the plant (its position's hash), as it will be drawn there
				var off = plant.getOffset(pos.set(t.x0 + (i % t.size), surface, t.z0 + (i / t.size)));
				BlockState upper = t.plantUpper[i];
				pl[i] = LodClip.plantWordA(LodPalette.id(plant), upper != null ? LodPalette.id(upper) : 0, t.plantBlocks[i]);
				pl[LodTile.CELLS + i] = LodClip.plantWordB(t.plantColor[i], off.x(), off.y(), off.z());
				if ((pl[i] & 1023) != 0) g[i] |= LodClip.plantBits(t.plantBlocks[i]);
			}
			if (cr != null) cr[i] = LodClip.belowWord(t.below[i]);
			if (rn != null) rn[i] = 0;
		}
	}

	private boolean load(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		Path f = this.file(key);
		if (!Files.exists(f)) return false;
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(f)), new java.util.zip.Inflater(), 65536))) {
			if (in.readInt() != FORMAT) return false;
			for (int i = 0; i < LodTile.CELLS; i++) g[i] = in.readInt();
			for (int i = 0; i < LodTile.CELLS; i++) c[i] = in.readInt();
			boolean hasCrowns = in.readBoolean();
			if (hasCrowns && cr != null) for (int i = 0; i < LodTile.CELLS; i++) cr[i] = in.readInt();
			else if (cr != null) java.util.Arrays.fill(cr, 0);
			if (hasCrowns && cr == null) for (int i = 0; i < LodTile.CELLS; i++) in.readInt();
			if (hasCrowns) for (int i = 0; i < LodTile.CELLS; i++) {
				int w = in.readInt();
				if (rn != null) rn[i] = w;
			}
			else if (rn != null) java.util.Arrays.fill(rn, 0);
			// palette numbers are this session's: the cache keeps the block states' names
			boolean hasTex = in.readBoolean();
			if (hasTex) {
				int n = in.readInt();
				int[] ids = new int[n];
				for (int k = 0; k < n; k++) ids[k] = LodPalette.idOf(in.readUTF());
				for (int i = 0; i < LodTile.CELLS; i++) {
					int w = in.readInt();
					if (tw != null) tw[i] = LodPalette.word(remap(ids, w & 1023), remap(ids, (w >> 10) & 1023), remap(ids, (w >> 20) & 1023));
				}
			} else if (tw != null) {
				java.util.Arrays.fill(tw, 0);
			}
			// plants: their words, block numbers as names like the texture words
			boolean hasPlants = in.readBoolean();
			if (hasPlants) {
				int n = in.readInt();
				int[] ids = new int[n];
				for (int k = 0; k < n; k++) ids[k] = LodPalette.idOf(in.readUTF());
				for (int i = 0; i < LodTile.CELLS; i++) {
					int a = in.readInt(), b = in.readInt();
					if (pl != null) {
						pl[i] = remap(ids, a & 1023) | remap(ids, (a >> 10) & 1023) << 10 | a & ~0xFFFFF;
						pl[LodTile.CELLS + i] = b;
					}
				}
			} else if (pl != null) {
				java.util.Arrays.fill(pl, 0);
			}
			if (pl == null || !hasPlants) for (int i = 0; i < LodTile.CELLS; i++) g[i] &= ~LodClip.GEOM_PLANT_BITS;
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int remap(int[] ids, int local) {
		return local > 0 && local < ids.length ? ids[local] : 0;
	}

	private void save(long key, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		Path f = this.file(key);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)),
				new Deflater(Deflater.BEST_SPEED), 65536))) {
				out.writeInt(FORMAT);
				for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(g[i]);
				for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(c[i]);
				out.writeBoolean(cr != null);
				if (cr != null) for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(cr[i]);
				if (cr != null) for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(rn != null ? rn[i] : 0);
				out.writeBoolean(tw != null);
				if (tw != null) {
					// palette numbers as a local table of block state names (numbers differ between sessions)
					java.util.LinkedHashMap<Integer, Integer> local = new java.util.LinkedHashMap<>();
					local.put(0, 0);
					int[] words = new int[LodTile.CELLS];
					for (int i = 0; i < LodTile.CELLS; i++) {
						int w = tw[i], a = w & 1023, b = (w >> 10) & 1023, d = (w >> 20) & 1023;
						for (int id : new int[] {a, b, d}) local.putIfAbsent(id, local.size());
						words[i] = local.get(a) | local.get(b) << 10 | local.get(d) << 20;
					}
					out.writeInt(local.size());
					for (int id : local.keySet()) out.writeUTF(LodPalette.nameOf(id));
					for (int i = 0; i < LodTile.CELLS; i++) out.writeInt(words[i]);
				}
				out.writeBoolean(pl != null);
				if (pl != null) {
					java.util.LinkedHashMap<Integer, Integer> local = new java.util.LinkedHashMap<>();
					local.put(0, 0);
					int[] words = new int[LodTile.CELLS];
					for (int i = 0; i < LodTile.CELLS; i++) {
						int w = pl[i], a = w & 1023, b = (w >> 10) & 1023;
						local.putIfAbsent(a, local.size());
						local.putIfAbsent(b, local.size());
						words[i] = local.get(a) | local.get(b) << 10 | w & ~0xFFFFF;
					}
					out.writeInt(local.size());
					for (int id : local.keySet()) out.writeUTF(LodPalette.nameOf(id));
					for (int i = 0; i < LodTile.CELLS; i++) {
						out.writeInt(words[i]);
						out.writeInt(pl[LodTile.CELLS + i]);
					}
				}
			}
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("mcopt-lod: cache write failed: " + e);
		}
	}

	// ---- the game's own chunks ----

	private final java.util.HashMap<Long, List<LodChunks.Summary>> waiting = new java.util.HashMap<>();
	/** Level and tile of every tile real chunks changed since it was saved. */
	private final java.util.Set<Long> dirtyTiles = new java.util.HashSet<>();

	/** Render thread: a chunk the client loaded or is unloading becomes far terrain (colors worked out on a worker). */
	void chunk(net.minecraft.world.level.chunk.LevelChunk chunk) {
		long t0 = System.nanoTime();
		LodChunks.Snapshot snap = LodChunks.snapshot(chunk);
		this.snapNanos += System.nanoTime() - t0;
		this.snapshots++;
		this.jobs.add(new Job(-2, this.seq.incrementAndGet(), 0, () -> {
			LodChunks.Summary sum = LodChunks.summarize(snap);
			this.chunksSummarized.incrementAndGet();
			this.results.add(() -> this.applyChunk(sum));
		}));
	}

	/** Writes a chunk's columns into every resident tile that covers it: level 0 every column, coarser at sample points. */
	private void applyChunk(LodChunks.Summary s) {
		// -Dmcopt.lod.chunkHold: a chunk in view and not handed off yet keeps its coarser levels as they are for now (a rewrite
		// there would show); they're written once it's handed off or out of view (releaseHeld)
		java.util.function.LongPredicate hold = this.hold;
		long ck = LodForest.chunkKey(s.chunkX(), s.chunkZ());
		if (hold != null && this.clip.levels > 1 && hold.test(ck)) {
			this.applyChunk(s, 0, 1);
			this.held.put(ck, s);
			return;
		}
		this.held.remove(ck);
		this.applyChunk(s, 0, this.clip.levels);
	}

	/** -Dmcopt.lod.chunkHold: Lod's test (chunk in view, not handed off); null: off. Render thread. */
	java.util.function.@org.jspecify.annotations.Nullable LongPredicate hold;
	private final java.util.HashMap<Long, LodChunks.Summary> held = new java.util.HashMap<>();
	long heldReleased;

	/** Render thread, once a frame after the mask: the held chunks whose coarser levels can be written now. */
	void releaseHeld() {
		java.util.function.LongPredicate hold = this.hold;
		if (this.held.isEmpty() || hold == null) return;
		for (var it = this.held.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			if (hold.test(e.getKey())) continue;
			it.remove();
			this.applyChunk(e.getValue(), 1, this.clip.levels);
			this.heldReleased++;
		}
	}

	int heldCount() {
		return this.held.size();
	}

	private void applyChunk(LodChunks.Summary s, int fromLevel, int toLevel) {
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		for (int level = fromLevel; level < toLevel; level++) {
			int span = this.clip.span(level);
			int tx = Math.floorDiv(bx, span), tz = Math.floorDiv(bz, span);
			if (!this.clip.resident(level, tx, tz)) {
				if (level == 0 && this.clip.inWindow(0, tx, tz)) {
					List<LodChunks.Summary> list = this.waiting.computeIfAbsent(LodTile.key(0, tx, tz), k -> new ArrayList<>());
					list.removeIf(o -> o.chunkX() == s.chunkX() && o.chunkZ() == s.chunkZ());
					list.add(s);
					if (CHUNK_TILES && list.size() == (LodTile.SIZE / 16) * (LodTile.SIZE / 16)) this.buildFromChunks(tx, tz, list);
				}
				continue;
			}
			if (level == 0 && LodConfig.VERIFY) this.verify(s);
			int cell = 1 << level;
			// cells whose sample point (their corner, as generated) is in the chunk
			int c0x = Math.ceilDiv(bx, cell), c1x = Math.floorDiv(bx + 15, cell);
			int c0z = Math.ceilDiv(bz, cell), c1z = Math.floorDiv(bz + 15, cell);
			for (int cz = c0z; cz <= c1z; cz++) {
				for (int cx = c0x; cx <= c1x; cx++) {
					int k = (cz * cell - bz) * 16 + (cx * cell - bx);
					boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
					int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
					int surface = wet ? s.water()[k] : s.height()[k];
					if (s.crownLo()[k] > surface) {
						// leaves over air: a crown floating over the ground under it
						this.clip.putCell(level, cx, cz, LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | clear,
							LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.crownWord(surface, s.groundColor()[k]), s.tex()[k], s.runs()[k], 0, 0);
					} else if (level == 0) {
						int pa = s.plantA()[k];
						this.clip.putCell(level, cx, cz, LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
							| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0),
							LodClip.colorWord(s.top()[k], s.side()[k]), LodClip.belowWord(s.below()[k]), s.tex()[k], 0, pa, s.plantB()[k]);
					} else {
						// coarser cells: their walls are mostly below the top block
						this.clip.putCell(level, cx, cz, LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear,
							LodClip.colorWord(s.top()[k], LodColors.mix(s.side()[k], s.below()[k], 0.6F)), 0, 0, 0, 0, 0);
					}
				}
			}
			this.clip.refresh(level, tx, tz);
			this.dirtyTiles.add(LodTile.key(level, tx, tz));
		}
	}

	/** -Dmcopt.lod.chunkTiles: a level-0 tile from its 16 chunks' summaries, words as applyChunk writes them, in one put. */
	private void buildFromChunks(int tx, int tz, List<LodChunks.Summary> list) {
		long key = LodTile.key(0, tx, tz);
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = this.clip.crownLevels > 0 ? new int[LodTile.CELLS] : null;
		int[] tw = LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = this.clip.plants ? new int[2 * LodTile.CELLS] : null;
		int x0 = tx * LodTile.SIZE, z0 = tz * LodTile.SIZE;
		for (LodChunks.Summary s : list) {
			int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
			for (int k = 0; k < 256; k++) {
				int i = (bz + (k >> 4) - z0) * LodTile.SIZE + (bx + (k & 15) - x0);
				boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
				int clear = (s.clear()[k] ? LodClip.GEOM_CLEAR : 0) | LodClip.depthBits(s.depth()[k]);
				int surface = wet ? s.water()[k] : s.height()[k];
				int gw, cw, crw, runs = 0, pa = 0, pb = 0;
				if (s.crownLo()[k] > surface) {
					gw = LodClip.crownGeomWord(s.crownHi()[k], s.crownHi()[k] - s.crownLo()[k], false) | clear;
					cw = LodClip.colorWord(s.top()[k], s.side()[k]);
					crw = LodClip.crownWord(surface, s.groundColor()[k]);
					runs = s.runs()[k];
				} else {
					pa = s.plantA()[k];
					pb = s.plantB()[k];
					gw = LodClip.geomWord(Math.max(surface, s.crownHi()[k]), wet) | clear | (s.fringe()[k] && !wet ? LodClip.GEOM_FRINGE : 0)
						| ((pa & 1023) != 0 && !wet ? LodClip.plantBits((pa >> 20 & 3) + 1) : 0);
					cw = LodClip.colorWord(s.top()[k], s.side()[k]);
					crw = LodClip.belowWord(s.below()[k]);
				}
				// as putCell masks them
				if (pl == null) gw &= ~LodClip.GEOM_PLANT_BITS;
				if (cr == null) gw &= ~LodClip.GEOM_CROWN_BITS;
				g[i] = gw;
				c[i] = cw;
				if (cr != null) cr[i] = crw;
				if (rn != null) rn[i] = runs;
				if (tw != null) tw[i] = s.tex()[k];
				if (pl != null) {
					pl[i] = pa;
					pl[LodTile.CELLS + i] = pb;
				}
			}
		}
		if (!this.clip.put(0, tx, tz, g, c, cr, tw, rn, pl)) return;
		this.chunkBuilt.add(key);
		this.waiting.remove(key);
		this.chunkTiles.incrementAndGet();
		this.dirtyTiles.add(key);
		if (this.chunkBuilt.size() > 4096) this.chunkBuilt.removeIf(k -> !this.clip.resident(0, LodTile.txOf(k), LodTile.tzOf(k)));
		this.arrived(key);
	}

	// ---- -Dmcopt.lod.verify: generated far terrain against the real chunks, column by column ----

	private final java.util.Set<Long> verified = new java.util.HashSet<>();
	/** columns, no data, ground exact, ground off by 1, crown agree (both or neither), crowns both, crowns exact (bottom, top, runs, ground),
	 * plants agree (presence), plants both, plants same block, top block same, side block same, top color within 2 (of 31/63) */
	private final long[] vs = new long[16];
	private int verifyLogged, groundLogged, chunkLogged;
	/** Open dry ground without trees: real minus generated surface y, -8..8 (clamped). */
	private final long[] dyHist = new long[17];
	/** Columns: dry in both, wet only in the real chunk, wet only in far terrain, wet in both. */
	private final long[] wetStats = new long[4];
	/** Per verified chunk with misses: surface columns off << 16 | crown columns off. */
	private final java.util.HashMap<Long, Integer> chunkMiss = new java.util.HashMap<>();

	private void verify(LodChunks.Summary s) {
		long key = LodForest.chunkKey(s.chunkX(), s.chunkZ());
		if (!this.verified.add(key)) return;
		int bx = s.chunkX() * 16, bz = s.chunkZ() * 16;
		long groundBefore = this.vs[0] - this.vs[1] - this.vs[2], crownBefore = this.vs[0] - this.vs[1] - this.vs[4];
		for (int k = 0; k < 256; k++) {
			int x = bx + (k & 15), z = bz + (k >> 4);
			int[] w = this.clip.cellWords(x, z);
			this.vs[0]++;
			if ((w[0] & LodClip.GEOM_VALID) == 0) {
				this.vs[1]++;
				continue;
			}
			boolean wet = s.water()[k] != LodTile.DRY && s.water()[k] > s.height()[k];
			int surface = wet ? s.water()[k] : s.height()[k];
			boolean realCrown = s.crownLo()[k] > surface;
			boolean lodWet = (w[0] & LodClip.GEOM_WET) != 0;
			// (a frozen lake's top is ice in the real chunk: water all the same)
			BlockState realTopState = LodPalette.stateOf(s.tex()[k] & 1023);
			boolean realWater = wet || realTopState != null && realTopState.is(net.minecraft.world.level.block.Blocks.ICE);
			this.wetStats[(realWater ? 1 : 0) + (lodWet ? 2 : 0)]++;
			boolean lodCrown = (w[0] & LodClip.GEOM_CROWN) != 0;
			int lodTop = (w[0] & 0xFFF) - 512;
			if (realCrown == lodCrown) this.vs[4]++;
			if (realCrown && lodCrown) {
				this.vs[5]++;
				int lodGround = (w[2] & 0xFFF) - 512, thick = (w[0] >>> 15) & 63;
				if (lodGround == surface) this.vs[2]++;
				else if (Math.abs(lodGround - surface) <= 1) this.vs[3]++;
				if (lodTop == s.crownHi()[k] && lodTop - thick == s.crownLo()[k] && w[3] == s.runs()[k] && lodGround == surface) this.vs[6]++;
			} else if (!realCrown && !lodCrown) {
				int real = Math.max(surface, s.crownHi()[k]);
				if (lodTop == real) this.vs[2]++;
				else if (Math.abs(lodTop - real) <= 1) this.vs[3]++;
				// open ground (no tree in either): how far off, and what is there
				BlockState realTop = LodPalette.stateOf(s.tex()[k] & 1023), lodTopState = LodPalette.stateOf(w[4] & 1023);
				boolean tree = realTop != null && (realTop.is(net.minecraft.tags.BlockTags.LEAVES) || realTop.is(net.minecraft.tags.BlockTags.LOGS))
					|| lodTopState != null && (lodTopState.is(net.minecraft.tags.BlockTags.LEAVES) || lodTopState.is(net.minecraft.tags.BlockTags.LOGS));
				if (!tree && !wet) {
					int dy = real - lodTop;
					this.dyHist[Math.clamp(dy + 8, 0, 16)]++;
					if (Math.abs(dy) > 1 && this.groundLogged < 30) {
						this.groundLogged++;
						System.out.printf("mcopt-lod: verify: ground at %d,%d: real %d (%s), far terrain %d (%s)%n", x, z, real, realTop, lodTop, lodTopState);
					}
				}
			}
			if (realCrown != lodCrown && this.verifyLogged < 12) {
				this.verifyLogged++;
				System.out.printf("mcopt-lod: verify: crown mismatch at %d,%d: real %s (lo %d hi %d ground %d), far terrain %s (top %d thick %d)%n", x, z,
					realCrown ? "crown" : "no crown", s.crownLo()[k], s.crownHi()[k], surface, lodCrown ? "crown" : "no crown", lodTop, (w[0] >>> 15) & 63);
			}
			int realPlant = s.plantA()[k] & 1023, lodPlant = w[5] & 1023;
			if (realPlant != 0 == (lodPlant != 0)) this.vs[7]++;
			if (realPlant != 0 && lodPlant != 0) {
				this.vs[8]++;
				if (realPlant == lodPlant) this.vs[9]++;
			}
			if (!realCrown && !lodCrown && !wet && LodConfig.TEXTURES) {
				this.vs[13]++;
				if (sameSprite(s.tex()[k] & 1023, w[4] & 1023, true)) this.vs[10]++;
				if (sameSprite(s.tex()[k] >> 10 & 1023, w[4] >> 10 & 1023, false)) this.vs[11]++;
				int a = LodClip.rgb565(s.top()[k]), b = w[1] & 0xFFFF;
				if (Math.abs((a >> 11) - (b >> 11)) <= 2 && Math.abs((a >> 5 & 63) - (b >> 5 & 63)) <= 4 && Math.abs((a & 31) - (b & 31)) <= 2) this.vs[12]++;
			}
		}
		// per chunk: columns whose surface or crown disagree (clusters: structures, lakes, cascades of trees)
		int groundMiss = (int) (this.vs[0] - this.vs[1] - this.vs[2] - groundBefore), crownMiss = (int) (this.vs[0] - this.vs[1] - this.vs[4] - crownBefore);
		if (groundMiss + crownMiss > 0) this.chunkMiss.put(key, groundMiss << 16 | crownMiss);
		if (groundMiss >= 192 && this.chunkLogged < 16) {
			// a whole chunk off: what is there (its middle column and a corner)
			this.chunkLogged++;
			StringBuilder b = new StringBuilder();
			for (int k : new int[] {8 * 16 + 8, 0}) {
				int[] w = this.clip.cellWords(bx + (k & 15), bz + (k >> 4));
				b.append(String.format(" [%d,%d real h %d water %d top %s under-tex %s | far terrain y %d%s top %s]", bx + (k & 15), bz + (k >> 4), s.height()[k],
					s.water()[k] == LodTile.DRY ? -1 : s.water()[k], LodPalette.stateOf(s.tex()[k] & 1023), LodPalette.stateOf(s.tex()[k] >> 20 & 1023),
					(w[0] & 0xFFF) - 512, (w[0] & LodClip.GEOM_WET) != 0 ? " wet" : "", LodPalette.stateOf(w[4] & 1023)));
			}
			System.out.println("mcopt-lod: verify: chunk off" + b);
		}
		if (this.verified.size() % 64 == 0) {
			long n = this.vs[0] - this.vs[1], dry = Math.max(1, this.vs[13]);
			System.out.printf("mcopt-lod: verify: %d chunks, %d columns (%d without data): ground exact %.2f%% (+-1 %.2f%%), crowns agree %.2f%% (%d both, exact %.2f%%), "
					+ "plants agree %.2f%% (%d both, same block %.2f%%); dry open ground: top block %.2f%%, side block %.2f%%, top color %.2f%%%n",
				this.verified.size(), this.vs[0], this.vs[1], 100.0 * this.vs[2] / Math.max(1, n), 100.0 * (this.vs[2] + this.vs[3]) / Math.max(1, n),
				100.0 * this.vs[4] / Math.max(1, n), this.vs[5], 100.0 * this.vs[6] / Math.max(1, this.vs[5]), 100.0 * this.vs[7] / Math.max(1, n), this.vs[8],
				100.0 * this.vs[9] / Math.max(1, this.vs[8]), 100.0 * this.vs[10] / dry, 100.0 * this.vs[11] / dry, 100.0 * this.vs[12] / dry);
			System.out.println("mcopt-lod: verify: open ground real - far terrain y, -8..8: " + java.util.Arrays.toString(this.dyHist));
			System.out.println("mcopt-lod: verify: water (dry both, real only, far terrain only, both): " + java.util.Arrays.toString(this.wetStats));
			int[] buckets = new int[6];   // chunks by surface misses: 0, 1-15, 16-63, 64-127, 128-191, 192+
			int[] crownBuckets = new int[6];
			for (long v : this.verified) {
				int m = this.chunkMiss.getOrDefault(v, 0), gm = m >>> 16, cm = m & 0xFFFF;
				buckets[gm == 0 ? 0 : gm < 16 ? 1 : gm < 64 ? 2 : gm < 128 ? 3 : gm < 192 ? 4 : 5]++;
				crownBuckets[cm == 0 ? 0 : cm < 16 ? 1 : cm < 64 ? 2 : cm < 128 ? 3 : cm < 192 ? 4 : 5]++;
			}
			System.out.println("mcopt-lod: verify: chunks by columns off (0, 1-15, 16-63, 64-127, 128-191, 192+): surface " + java.util.Arrays.toString(buckets)
				+ ", crowns " + java.util.Arrays.toString(crownBuckets));
			StringBuilder worst = new StringBuilder();
			this.chunkMiss.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue() >>> 16, a.getValue() >>> 16)).limit(12)
				.forEach(e -> worst.append(' ').append((int) (e.getKey() >> 32) * 16).append(',').append((int) (long) e.getKey() * 16).append(':')
					.append(e.getValue() >>> 16).append('/').append(e.getValue() & 0xFFFF));
			System.out.println("mcopt-lod: verify: worst chunks (block x,z: surface/crown columns off):" + worst);
		}
	}

	/** Whether two palette numbers draw the same sprite on that face (a snow layer's top is a snow block's). */
	private static boolean sameSprite(int a, int b, boolean top) {
		if (a == b) return true;
		BlockState sa = LodPalette.stateOf(a), sb = LodPalette.stateOf(b);
		if (sa == null || sb == null) return false;
		float[] ua = top ? LodColors.look(sa).topUv() : LodColors.look(sa).sideUv(), ub = top ? LodColors.look(sb).topUv() : LodColors.look(sb).sideUv();
		return java.util.Arrays.equals(ua, ub);
	}

	/** Every few seconds: tiles real chunks changed go back to the disk cache (read back from the clipmap, saved on a worker). */
	void saveDirty(boolean all) {
		if (!LodConfig.DISK_CACHE || this.dirtyTiles.isEmpty() || !all && this.frame % 3000 != 0) return;
		for (long key : this.dirtyTiles) {
			int level = LodTile.levelOf(key), tx = LodTile.txOf(key), tz = LodTile.tzOf(key);
			if (!this.clip.resident(level, tx, tz)) continue;
			int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
			int[] cr = level < this.clip.crownLevels ? new int[LodTile.CELLS] : null;
			int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
			int[] rn = cr != null ? new int[LodTile.CELLS] : null;
			int[] pl = level == 0 && this.clip.plants ? new int[2 * LodTile.CELLS] : null;
			this.clip.read(level, tx, tz, g, c, cr, tw, rn, pl);
			if (all) this.save(key, g, c, cr, tw, rn, pl);
			else this.jobs.add(new Job(0, this.seq.incrementAndGet(), 0, () -> this.save(key, g, c, cr, tw, rn, pl)));
		}
		this.dirtyTiles.clear();
	}

	// ---- render thread ----

	long frame;
	private boolean dirty = true;
	private double reqX = Double.NaN, reqZ;

	/** Takes the workers' results (bounded per frame so a burst can't stall one). */
	void integrate() {
		long deadline = System.nanoTime() + 1_500_000;
		Runnable r;
		while ((r = this.results.poll()) != null) {
			r.run();
			if (System.nanoTime() > deadline) break;
		}
	}

	/**
	 * Moves the windows with the camera and queues what they lack. The scan over every level's window runs when the camera
	 * moved a few blocks or tiles arrived.
	 */
	void update(double cx, double cz, FrustumIntersection frustum, double camY) {
		boolean moved = this.clip.recenter(cx, cz);
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		if (!moved && !this.dirty && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		this.dirty = false;
		this.missingKeys.clear();
		this.reqX = cx;
		this.reqZ = cz;
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			// the ring this level draws: from the finer level's switch distance (less a tile) to its own (plus a tile)
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = this.clip.winTx[l] + x, tz = this.clip.winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					// entirely inside the inner radius: the finer level covers it
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					this.missingKeys.add(key);
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) continue;
					double priority = near / span;
					if (l == top) priority -= 4;  // the fallback for everything comes first
					if (frustum != null && !frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx),
						(float) (320 - camY), (float) (z0 + span - cz))) priority = priority * 3 + 2;
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		this.needed = needed;
		this.missing = missing;
		if (missing == 0 && !this.settled) {
			this.settled = true;
			this.settledNanos = System.nanoTime();
			if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
		}
		if (missing > 0) this.settled = false;
	}

	/**
	 * -Dmcopt.lod.prio=screen: the queue in the order the screen needs: the coarsest level's tiles in view first (the fallback
	 * under everything), then tiles in view by projected area (largest first), then tiles that come into view within a second
	 * at the camera's current velocity, then the rest by distance; level-0 tiles the real terrain covers whole last. The
	 * queue is sorted again when the camera turns (10 degrees), moves (16 blocks) or every half second while tiles are missing.
	 */
	static final boolean PRIO_SCREEN = "screen".equals(System.getProperty("mcopt.lod.prio", ""));
	private final org.joml.Vector3f scanFwd = new org.joml.Vector3f();
	private double velX, velZ, lastX = Double.NaN, lastZ;
	private long lastT, scanT;

	/** The screen-ordered scan's thread (PRIO_SCREEN): the render thread only hands it a snapshot and takes its result. */
	private final java.util.concurrent.ExecutorService scanner = PRIO_SCREEN ? java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcopt-lod-scan");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	}) : null;
	private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();
	/** The real terrain's reach (render distance x 16 blocks), set by Lod every frame: level-0 tiles wholly inside it are real chunks' to fill. */
	volatile double rdBlocks;
	/** Scan thread only: when each missing tile was first seen missing in view (its priority grows with the wait). */
	private final java.util.HashMap<Long, Long> firstSeen = new java.util.HashMap<>();
	private volatile ScanResult scanResult;

	private record ScanResult(int needed, int missing, java.util.HashSet<Long> missingKeys) {
	}

	/**
	 * As update, ordering the queue by what the screen needs (PRIO_SCREEN). The render thread moves the windows and decides
	 * when to scan; the scan itself (every level's window, projected areas, the queue re-sorted) runs on its own thread from
	 * a snapshot of the camera and the mask, and its result (what is missing) is taken over at a later frame.
	 */
	void updateScreen(double cx, double cz, FrustumIntersection frustum, double camY, org.joml.Matrix4f viewProj, org.joml.Vector3f fwd,
		java.util.function.Supplier<LodSeam.ChunkMask> maskSnapshot) {
		long now = System.nanoTime();
		if (!Double.isNaN(this.lastX) && now > this.lastT) {
			double dt = (now - this.lastT) / 1e9, a = Math.min(1, dt / 0.25);
			this.velX += ((cx - this.lastX) / dt - this.velX) * a;
			this.velZ += ((cz - this.lastZ) / dt - this.velZ) * a;
		}
		this.lastX = cx;
		this.lastZ = cz;
		this.lastT = now;
		boolean moved = this.clip.recenter(cx, cz);
		for (int l = 0; l < this.clip.levels; l++) {
			this.winTx[l] = this.clip.winTx[l];
			this.winTz[l] = this.clip.winTz[l];
		}
		ScanResult r = this.scanResult;
		if (r != null) {
			this.scanResult = null;
			r.missingKeys().removeIf(k -> this.clip.resident(LodTile.levelOf(k), LodTile.txOf(k), LodTile.tzOf(k)));
			this.missingKeys.clear();
			this.missingKeys.addAll(r.missingKeys());
			this.needed = r.needed();
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
			if (this.missing > 0) this.settled = false;
		}
		boolean turned = fwd.dot(this.scanFwd) < Math.cos(Math.toRadians(10));
		boolean stale = this.missing > 0 && now - this.scanT > 500_000_000L;
		if (!moved && !this.dirty && !turned && !stale && Math.abs(cx - this.reqX) < 16 && Math.abs(cz - this.reqZ) < 16) return;
		if (!this.scanning.compareAndSet(false, true)) return;
		this.dirty = false;
		this.scanFwd.set(fwd);
		this.scanT = now;
		this.reqX = cx;
		this.reqZ = cz;
		int[] wx = this.winTx.clone(), wz = this.winTz.clone();
		org.joml.Matrix4f vp = new org.joml.Matrix4f(viewProj);
		LodSeam.ChunkMask masked = maskSnapshot.get();
		double px = cx + this.velX, pz = cz + this.velZ;
		this.scanner.execute(() -> {
			try {
				this.scanResult = this.scanScreen(cx, cz, camY, px, pz, vp, wx, wz, masked);
			} catch (RuntimeException e) {
				System.out.println("mcopt-lod: scan failed: " + e);
			} finally {
				this.scanning.set(false);
			}
		});
	}

	private ScanResult scanScreen(double cx, double cz, double camY, double px, double pz, org.joml.Matrix4f viewProj, int[] winTx, int[] winTz,
		LodSeam.ChunkMask masked) {
		FrustumIntersection frustum = new FrustumIntersection(viewProj, false);
		double reach = LodConfig.reachBlocks();
		int needed = 0, missing = 0;
		int top = this.clip.levels - 1;
		java.util.HashSet<Long> keys = new java.util.HashSet<>();
		long now = System.nanoTime();
		java.util.HashMap<Long, Double> again = new java.util.HashMap<>();
		org.joml.Vector4f v = new org.joml.Vector4f();
		// (px, pz: where the camera is a second from now)
		boolean fast = Math.hypot(px - cx, pz - cz) > 8;
		for (int l = 0; l <= top; l++) {
			int span = this.clip.span(l);
			double inner = l == 0 || l == top ? -1 : this.clip.switchDist[l - 1] - span;
			double outer = Math.min(this.clip.switchDist[l] + span, reach + span);
			for (int z = 0; z < this.clip.tilesPerSide; z++) {
				for (int x = 0; x < this.clip.tilesPerSide; x++) {
					int tx = winTx[l] + x, tz = winTz[l] + z;
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					if (near > outer) continue;
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					if (Math.sqrt(fx * fx + fz * fz) < inner) continue;
					needed++;
					if (this.clip.resident(l, tx, tz)) continue;
					missing++;
					long key = LodTile.key(l, tx, tz);
					keys.add(key);
					double priority;
					if (l == 0 && allMasked(tx, tz, masked)) {
						priority = 1000 + near / span;
					} else if (l == 0 && fast && Math.sqrt(fx * fx + fz * fz) < this.rdBlocks - 16) {
						// moving fast, wholly inside the render distance: real chunks will overwrite it (until then level 1 stands in;
						// a camera at rest gets these first: they're what it sees while its real chunks load)
						priority = 900 + near / span;
					} else if (frustum.testAab((float) (x0 - cx), (float) (-64 - camY), (float) (z0 - cz), (float) (x0 + span - cx), (float) (320 - camY),
						(float) (z0 + span - cz))) {
						// in view: the larger first, and a tile that has waited a second counts as twice as large (none starves)
						double waited = (now - this.firstSeen.computeIfAbsent(key, k -> now)) / 1e9;
						priority = (l == top ? -100 : 0) - log2(area(viewProj, x0 - cx, 40 - camY, z0 - cz, span, 120, v)) - waited;
					} else if (frustum.testAab((float) (x0 - px), (float) (-64 - camY), (float) (z0 - pz), (float) (x0 + span - px), (float) (320 - camY),
						(float) (z0 + span - pz))) {
						priority = (l == top ? -50 : 30) - log2(area(viewProj, x0 - px, 40 - camY, z0 - pz, span, 120, v));
					} else {
						// the coarsest level (the fallback under everything) before any finer tile out of view
						priority = (l == top ? 50 : 100) + near / span;
					}
					if (this.pending.putIfAbsent(key, Boolean.TRUE) != null) {
						again.put(key, priority);
						continue;
					}
					this.jobs.add(new Job(priority, this.seq.incrementAndGet(), key, null));
				}
			}
		}
		if (!again.isEmpty()) {
			// queued generation jobs take their new priority (jobs a worker took meanwhile are simply gone from the drain)
			java.util.ArrayList<Job> all = new java.util.ArrayList<>();
			this.jobs.drainTo(all);
			for (Job j : all) {
				Double p = j.task == null ? again.get(j.key) : null;
				this.jobs.add(p == null ? j : new Job(p, j.seq, j.key, null));
			}
		}
		this.firstSeen.keySet().retainAll(keys);
		return new ScanResult(needed, missing, keys);
	}

	private static double log2(double a) {
		return Math.log(Math.max(1, a)) / Math.log(2);
	}

	private static boolean allMasked(int tx, int tz, LodSeam.ChunkMask masked) {
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				if (!masked.masked(tx * 4 + x, tz * 4 + z)) return false;
			}
		}
		return true;
	}

	/** Screen pixels of a box's projected bounds (clipped to the screen; corners behind the camera clamp to its plane). */
	static double area(org.joml.Matrix4f m, double x0, double y0, double z0, double span, double height, org.joml.Vector4f v) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			v.set((float) (x0 + ((i & 1) != 0 ? span : 0)), (float) (y0 + ((i & 2) != 0 ? height : 0)), (float) (z0 + ((i & 4) != 0 ? span : 0)), 1);
			m.transform(v);
			float w = Math.max(v.w, 1e-2F);
			float nx = v.x / w, ny = v.y / w;
			minX = Math.min(minX, nx);
			maxX = Math.max(maxX, nx);
			minY = Math.min(minY, ny);
			maxY = Math.max(maxY, ny);
		}
		double ax = Math.max(0, Math.min(1, maxX) - Math.max(-1, minX)), ay = Math.max(0, Math.min(1, maxY) - Math.max(-1, minY));
		return ax * ay * 0.25 * 3456 * 2234;
	}

	/** Tiles the last scan found missing; arrivals tick them off (settled when none is left), only a move scans again. */
	private final java.util.HashSet<Long> missingKeys = new java.util.HashSet<>();

	private void arrived(long key) {
		if (this.missingKeys.remove(key)) {
			this.missing = this.missingKeys.size();
			if (this.missing == 0 && !this.settled) {
				this.settled = true;
				this.settledNanos = System.nanoTime();
				if (this.firstSettledNanos == 0) this.firstSettledNanos = this.settledNanos;
			}
		}
	}

	int pendingJobs() {
		return this.pending.size();
	}

	int queued() {
		return this.jobs.size();
	}
}
