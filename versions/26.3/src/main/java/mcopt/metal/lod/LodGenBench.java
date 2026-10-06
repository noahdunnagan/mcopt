package mcopt.metal.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * -Dmcopt.lod.genBench=PHASES (e.g. plain+neon+java+vector, or commas): once the far terrain has settled, the same fixed set of tiles
 * (several regions far from the player, every level) is generated once per phase with that noise backend, in the order
 * given and then in reverse (ABBA), each phase on fresh worker threads (fresh sampler caches and tree caches), outside the
 * clipmap and the disk cache. Logs per phase: tiles a second, ms a tile by level, where the time went, and whether every
 * tile's clipmap words are identical to the first plain phase's (the whole-tile exactness check).
 *
 * -Dmcopt.lod.genBench.threads=N (default the far terrain's worker count), .regions=N (4), .delay=S (seconds after the
 * field settles, 3). Run with the camera held still (-Dmcopt.bench.waitLod=true -Dmcopt.bench.settleHold=S) for at-rest
 * numbers. The vector phase needs --add-modules jdk.incubator.vector (skipped without it).
 */
final class LodGenBench {
	private LodGenBench() {
	}

	static final String SPEC = System.getProperty("mcopt.lod.genBench");
	static final boolean ENABLED = SPEC != null && !SPEC.isBlank();
	private static final int THREADS = Integer.getInteger("mcopt.lod.genBench.threads", LodConfig.THREADS);
	private static final int REGIONS = Integer.getInteger("mcopt.lod.genBench.regions", 4);
	private static final double DELAY_S = Double.parseDouble(System.getProperty("mcopt.lod.genBench.delay", "3"));
	/** Tiles per region by level: level 0 and 1 cost the most (final density, trees at 0), coarse levels are many in a ring. */
	private static final int[] PER_LEVEL = {6, 6, 6, 5, 4, 3, 2};

	private static volatile boolean started;

	static void start(LodNoise noise, LodField field) {
		if (!ENABLED || started) return;
		started = true;
		Thread t = new Thread(() -> {
			try {
				run(noise, field);
			} catch (Throwable e) {
				System.out.println("mcopt-lod genbench: failed: " + e);
				e.printStackTrace(System.out);
			}
		}, "mcopt-lod-genbench");
		t.setDaemon(true);
		t.start();
	}

	private record Words(int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] rn, int @org.jspecify.annotations.Nullable [] pl) {
		long mismatches(Words o) {
			return diff(this.g, o.g) + diff(this.c, o.c) + diff(this.cr, o.cr) + diff(this.tw, o.tw) + diff(this.rn, o.rn) + diff(this.pl, o.pl);
		}

		private static long diff(int[] a, int[] b) {
			if (a == null || b == null) return a == b ? 0 : 1;
			long n = 0;
			for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++;
			return n;
		}
	}

	private static void run(LodNoise noise, LodField field) throws InterruptedException {
		while (field.firstSettledNanos == 0) Thread.sleep(200);
		Thread.sleep((long) (DELAY_S * 1000));
		List<LodNativeNoise.Backend> order = new ArrayList<>();
		for (String p : SPEC.split("[,+]")) {
			LodNativeNoise.Backend b = LodNativeNoise.parse(p.trim());
			if (b == LodNativeNoise.Backend.VECTOR && ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
				System.out.println("mcopt-lod genbench: vector skipped (run with --add-modules jdk.incubator.vector)");
				continue;
			}
			if (b != null || "plain".equals(p.trim())) order.add(b != null ? b : LodNativeNoise.Backend.PLAIN);
		}
		if (order.isEmpty()) return;
		if (order.get(0) != LodNativeNoise.Backend.PLAIN) order.add(0, LodNativeNoise.Backend.PLAIN);
		List<LodNativeNoise.Backend> abba = new ArrayList<>(order);
		for (int i = order.size() - 1; i >= 0; i--) abba.add(order.get(i));
		// tiles: regions far apart and far from the player (a fixed set: the same in every run of the same world)
		List<long[]> tiles = new ArrayList<>();
		int[][] origins = {{1_000_000, 1_000_000}, {-2_000_000, 1_500_000}, {3_000_000, -2_500_000}, {-700_000, -4_100_000}, {5_200_000, 300_000},
			{-6_000_000, -6_000_000}, {12_000, -9_000_000}, {8_000_000, 8_000_000}};
		for (int r = 0; r < Math.min(REGIONS, origins.length); r++) {
			for (int level = 0; level < PER_LEVEL.length; level++) {
				int span = LodTile.SIZE << level;
				int tx0 = Math.floorDiv(origins[r][0], span), tz0 = Math.floorDiv(origins[r][1], span);
				for (int k = 0; k < PER_LEVEL[level]; k++) tiles.add(new long[] {level, tx0 + k % 3, tz0 + k / 3});
			}
		}
		System.out.printf(Locale.ROOT, "mcopt-lod genbench: %d tiles (%d regions, levels 0-%d), %d threads, phases %s%n", tiles.size(),
			Math.min(REGIONS, origins.length), PER_LEVEL.length - 1, THREADS, abba);
		LodNativeNoise.Backend before = LodNativeNoise.LOD.backend;
		Words[] reference = null;
		double[][] wallByBackend = new double[LodNativeNoise.Backend.values().length][];
		int phase = 0;
		for (LodNativeNoise.Backend b : abba) {
			phase++;
			LodNativeNoise.LOD.backend = b;
			Words[] out = new Words[tiles.size()];
			long[] nanos = new long[tiles.size()];
			long[] stage0 = snapshot(noise);
			long v0 = LodNativeNoise.volumes.get(), p0 = LodNativeNoise.points.get(), vp0 = LodNativeNoise.volumePoints.get();
			long wall = runPhase(noise, tiles, out, nanos);
			long[] stage1 = snapshot(noise);
			long calls = LodNativeNoise.volumes.get() - v0, pointCalls = LodNativeNoise.points.get() - p0, volPoints = LodNativeNoise.volumePoints.get() - vp0;
			if (reference == null && b == LodNativeNoise.Backend.PLAIN) reference = out;
			long badTiles = 0, badWords = 0;
			if (reference != null) {
				for (int i = 0; i < out.length; i++) {
					long m = out[i].mismatches(reference[i]);
					if (m > 0) {
						badTiles++;
						badWords += m;
					}
				}
			}
			double[] byLevel = new double[PER_LEVEL.length];
			int[] countLevel = new int[PER_LEVEL.length];
			double sum = 0;
			for (int i = 0; i < tiles.size(); i++) {
				int l = (int) tiles.get(i)[0];
				byLevel[l] += nanos[i] / 1e6;
				countLevel[l]++;
				sum += nanos[i] / 1e6;
			}
			StringBuilder lv = new StringBuilder();
			for (int l = 0; l < byLevel.length; l++) lv.append(String.format(Locale.ROOT, "%sL%d %.1f", l == 0 ? "" : ", ", l, byLevel[l] / Math.max(1, countLevel[l])));
			long density = 0, biomes = 0, materials = 0;
			for (int l = 0; l < 16; l++) {
				density += stage1[l * 4] - stage0[l * 4];
				biomes += stage1[l * 4 + 1] - stage0[l * 4 + 1];
				materials += stage1[l * 4 + 2] - stage0[l * 4 + 2];
			}
			double total = sum * 1e6;
			System.out.printf(Locale.ROOT, "mcopt-lod genbench: phase %d/%d %s: %d tiles in %.2f s = %.1f tiles/s; ms/tile %.1f (%s); density %.0f%% biomes %.0f%% materials %.0f%% "
					+ "trees+rest %.0f%%; exact vs plain: %d/%d tiles identical (%d words differ); replaced leaves per tile: %.0f volume calls (%.0f values), %.0f point calls; load avg %.1f%n",
				phase, abba.size(), b.name().toLowerCase(Locale.ROOT), tiles.size(), wall / 1e9, tiles.size() / (wall / 1e9), sum / tiles.size(), lv,
				100 * density / total, 100 * biomes / total, 100 * materials / total, 100 * (total - density - biomes - materials) / total,
				tiles.size() - badTiles, tiles.size(), badWords, (double) calls / tiles.size(), (double) volPoints / tiles.size(), (double) pointCalls / tiles.size(),
				java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
			double[] prev = wallByBackend[b.ordinal()];
			double[] row = {tiles.size() / (wall / 1e9), sum / tiles.size()};
			wallByBackend[b.ordinal()] = prev == null ? row : new double[] {(prev[0] + row[0]) / 2, (prev[1] + row[1]) / 2};
		}
		LodNativeNoise.LOD.backend = before;
		StringBuilder sb = new StringBuilder("mcopt-lod genbench: summary (mean of each backend's phases):");
		for (LodNativeNoise.Backend b : LodNativeNoise.Backend.values()) {
			double[] r = wallByBackend[b.ordinal()];
			if (r != null) sb.append(String.format(Locale.ROOT, " %s %.1f tiles/s %.1f ms/tile;", b.name().toLowerCase(Locale.ROOT), r[0], r[1]));
		}
		System.out.println(sb);
		System.out.println("mcopt-lod genbench: done");
	}

	private static long[] snapshot(LodNoise noise) {
		long[] s = new long[noise.stageNanos.length()];
		for (int i = 0; i < s.length; i++) s[i] = noise.stageNanos.get(i);
		return s;
	}

	/** All tiles on THREADS fresh threads (their own sampler contexts and forest caches); returns the wall time. */
	private static long runPhase(LodNoise noise, List<long[]> tiles, Words[] out, long[] nanos) throws InterruptedException {
		ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
		for (int i = 0; i < tiles.size(); i++) queue.add(i);
		LodForest forest = LodConfig.TREES ? new LodForest(noise) : null;
		AtomicLong failures = new AtomicLong();
		Thread[] threads = new Thread[THREADS];
		long t0 = System.nanoTime();
		for (int k = 0; k < THREADS; k++) {
			threads[k] = new Thread(() -> {
				Integer i;
				while ((i = queue.poll()) != null) {
					long s = System.nanoTime();
					try {
						long[] tl = tiles.get(i);
						out[i] = generate(noise, forest, (int) tl[0], (int) tl[1], (int) tl[2]);
					} catch (RuntimeException e) {
						if (failures.getAndIncrement() == 0) e.printStackTrace(System.out);
						out[i] = new Words(new int[0], new int[0], null, null, null, null);
					}
					nanos[i] = System.nanoTime() - s;
				}
			}, "mcopt-lod-genbench-" + k);
			threads[k].setDaemon(true);
			threads[k].setPriority(Thread.MIN_PRIORITY + 1);
			threads[k].start();
		}
		for (Thread t : threads) t.join();
		return System.nanoTime() - t0;
	}

	/** LodField.generate's pipeline for one tile, without the disk cache or the clipmap. */
	private static Words generate(LodNoise noise, LodForest forest, int level, int tx, int tz) {
		int[] g = new int[LodTile.CELLS], c = new int[LodTile.CELLS];
		int[] cr = level < LodConfig.CROWN_LEVELS ? new int[LodTile.CELLS] : null;
		int[] tw = level == 0 && LodConfig.TEXTURES ? new int[LodTile.CELLS] : null;
		int[] rn = cr != null ? new int[LodTile.CELLS] : null;
		int[] pl = level == 0 && LodConfig.PLANTS ? new int[2 * LodTile.CELLS] : null;
		LodTile t = new LodTile(level, tx, tz);
		boolean exact = level < Math.min(2, LodConfig.TREE_LEVELS) && forest != null && forest.available();
		t.impostorTrees = !exact;
		noise.generate(t);
		if (exact) {
			if (level == 0) forest.offer(t);
			forest.plant(t);
			noise.dressTrees(t);
			noise.dressGround(t);
		}
		LodField.pack(t, g, c, cr, rn, pl);
		if (tw != null) System.arraycopy(t.tex, 0, tw, 0, LodTile.CELLS);
		return new Words(g, c, cr, tw, rn, pl);
	}

	static String describe(int[] a) {
		return Arrays.toString(a);
	}
}
