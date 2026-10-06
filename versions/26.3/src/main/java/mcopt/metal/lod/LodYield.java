package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Back-pressure for the far terrain's generation (-Dmcopt.lod.yield=server|missing[:N], default off): the game's own chunks
 * first. Measured: with far-terrain generation on, a 60 b/s flight leaves twice as many of the render distance's chunks
 * missing as with it off (36-40% vs 19% on the mini), drawing or not; the far terrain's workers take the cores the server's
 * chunk generation needs. With this on, a worker about to generate a tile (and at each stage of it) waits while the server
 * has chunk work near the player:
 * - server: the integrated server's worldgen dispatcher has queued chunk tasks (ChunkMap.worldgenTaskDispatcher.hasWork());
 * - missing[:N]: the client lacks more than N chunks within the render distance (what the real-chunk gate measures).
 * - spare[:M]: under pressure (the client lacks chunks in the render distance), a fine tile may still run while fewer far
 *   workers generate than the cores the server's worldgen pool leaves idle (cores - its active threads - M, M default 2: the
 *   render and server threads), measured at admission from Util.backgroundExecutor()'s ForkJoinPool.
 * -Dmcopt.lod.yield.budget=K lets K workers keep generating under pressure (default 0: the far terrain only fills when the
 * server is idle; the coarsest level's tiles always pass, they are the fallback under everything). Disk-cache loads and real
 * chunks' summaries never wait. Pressure is sampled on the render thread every 3 frames.
 */
final class LodYield {
	private LodYield() {
	}

	static final String SPEC = System.getProperty("mcopt.lod.yield", "");
	static final int MODE = SPEC.startsWith("server") ? 1 : SPEC.startsWith("missing") ? 2 : SPEC.startsWith("spare") ? 3 : 0;
	static final boolean ON = MODE != 0;
	static final int THRESH = SPEC.contains(":") ? Integer.parseInt(SPEC.substring(SPEC.indexOf(':') + 1).trim()) : MODE == 3 ? 2 : 0;
	private static final int CORES = Runtime.getRuntime().availableProcessors();
	private static final AtomicInteger spareRunning = new AtomicInteger();
	private static java.util.concurrent.@Nullable ForkJoinPool pool;
	static final int BUDGET = Integer.getInteger("mcopt.lod.yield.budget", 0);

	static volatile boolean pressure;
	/** The coarsest level (its tiles never wait), set by Lod with the clipmap. */
	static volatile int top = Integer.MAX_VALUE;
	private static final AtomicInteger holders = new AtomicInteger(), paused = new AtomicInteger();
	static final AtomicLong pausedNanos = new AtomicLong(), pressureFrames = new AtomicLong(), sampledFrames = new AtomicLong();
	private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[2]);   // nesting depth, holds a budget slot
	private static long frames;
	private static @Nullable Object dispatcher;
	private static @Nullable Object dispatcherOwner;
	private static @Nullable Field dispatcherField;
	private static java.lang.reflect.Method hasWork;

	/** Render thread: the pressure now. */
	static void frame(Minecraft mc, double camX, double camZ, int rd) {
		if (frames++ % 3 != 0 || mc.level == null) return;
		boolean p;
		if (MODE == 1) {
			p = serverHasWork(mc);
		} else {
			p = LodGenStats.missing(mc.level, camX, camZ, rd) > (MODE == 3 ? 0 : THRESH);
		}
		pressure = p;
		sampledFrames.incrementAndGet();
		if (p) pressureFrames.incrementAndGet();
	}

	private static boolean serverHasWork(Minecraft mc) {
		try {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server == null || mc.level == null) return false;
			ServerLevel sl = server.getLevel(mc.level.dimension());
			if (sl == null) return false;
			ChunkMap map = sl.getChunkSource().chunkMap;
			if (dispatcherOwner != map) {
				if (dispatcherField == null) {
					dispatcherField = ChunkMap.class.getDeclaredField("worldgenTaskDispatcher");
					dispatcherField.setAccessible(true);
				}
				dispatcher = dispatcherField.get(map);
				hasWork = dispatcher.getClass().getMethod("hasWork");
				dispatcherOwner = map;
			}
			return (boolean) hasWork.invoke(dispatcher);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * -Dmcopt.lod.yield.at=take (default): the gate is LodField's worker loop, before a generation job is taken on (admit);
	 * a job that may not run goes back into the queue, so no worker ever holds a waiting tile and the coarsest level's tiles
	 * (first in the queue's order) keep flowing. tile: the gate is inside LodNoise.generate (enter, checkpoints), the first
	 * version: a worker blocks holding its tile (holes when all of them do).
	 */
	static final boolean AT_TAKE = !"tile".equals(System.getProperty("mcopt.lod.yield.at", "take"));

	/** Per worker: the budget (2) or spare-capacity (3) slot its last admitted tile held, released when it asks again. */
	private static final ThreadLocal<int[]> SLOT = ThreadLocal.withInitial(() -> new int[1]);

	/**
	 * LodField's worker, about to generate a tile of this level: whether it may now (else the job goes back into the queue).
	 * Without pressure, or for the coarsest level (the fallback under everything, never yet published where it is queued),
	 * always; under pressure only with a budget slot or (spare) an idle core. A slot is held until the same worker asks
	 * again: its tile is done by then.
	 */
	static boolean admit(int level) {
		if (!AT_TAKE) return true;
		int[] slot = SLOT.get();
		if (slot[0] == 2) holders.decrementAndGet();
		else if (slot[0] == 3) spareRunning.decrementAndGet();
		slot[0] = 0;
		if (!pressure || level >= top) return true;
		int h = holders.get();
		if (h < BUDGET && holders.compareAndSet(h, h + 1)) {
			slot[0] = 2;
			return true;
		}
		if (MODE == 3) {
			int idle = CORES - serverActive() - THRESH, r = spareRunning.get();
			if (r < idle && spareRunning.compareAndSet(r, r + 1)) {
				slot[0] = 3;
				return true;
			}
		}
		return false;
	}

	/** The server's worldgen pool (Worker-Main): threads busy now (approximate, any thread may ask). */
	private static int serverActive() {
		try {
			java.util.concurrent.ForkJoinPool p = pool;
			if (p == null) {
				if (!(net.minecraft.util.Util.backgroundExecutor().service() instanceof java.util.concurrent.ForkJoinPool f)) return CORES;
				pool = p = f;
			}
			return p.getActiveThreadCount();
		} catch (RuntimeException e) {
			return CORES;
		}
	}

	/** A worker whose job was requeued parks a moment (the pressure is re-sampled every 3 frames). */
	static void idle() {
		long t0 = System.nanoTime();
		paused.incrementAndGet();
		try {
			Thread.sleep(3);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	/** A worker starts generating a tile of the given level (nested calls on the same thread pass through). */
	static void enter(int level, int top) {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0]++ > 0 || level >= top) return;
		await(s);
	}

	/** Between a tile's stages: wait if pressure came up and this worker holds no budget slot. */
	static void checkpoint() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0] == 1 && s[1] == 0 && pressure) await(s);
	}

	static void exit() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (--s[0] == 0 && s[1] != 0) {
			s[1] = 0;
			holders.decrementAndGet();
		}
	}

	private static void await(int[] s) {
		long t0 = 0;
		while (pressure) {
			int h = holders.get();
			if (h < BUDGET && holders.compareAndSet(h, h + 1)) {
				s[1] = 1;
				break;
			}
			if (t0 == 0) {
				t0 = System.nanoTime();
				paused.incrementAndGet();
			}
			try {
				Thread.sleep(3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (t0 != 0) {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	static int pausedNow() {
		return paused.get();
	}

	static String summary() {
		long n = Math.max(1, sampledFrames.get());
		return String.format(Locale.ROOT, "pressure %.0f%% of samples, %.1f worker-s paused", 100.0 * pressureFrames.get() / n, pausedNanos.get() / 1e9);
	}
}
