package mcopt.metal.lod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mcopt.metal.MetalBridge;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The far terrain's transition probe, -Dmcopt.lod.seam=DIR (instrumentation, off by default; nothing of it runs without the
 * switch). Once a frame at the end of the level (before the GUI), mcopt/lod/seam.metal compares the frame with the three
 * before it over the far terrain's region (reprojected with each frame's camera) and counts flashes (a pixel that changes
 * and changes back within 3 frames), pops (changes that hold) and the frame-to-frame change by size. Per frame, with the
 * engine's own state (tiles on screen drawn exactly, by a coarser stand-in, or not at all; tiles whose mesh is older than
 * their words), one line of DIR/frames.csv; durations of stand-ins and holes on screen in DIR/durations.csv; crops of the
 * frames around the worst flashes in DIR/shots; -Dmcopt.lod.seam.strip=FIRST,COUNT,STEP,SCALE frames scaled down for a
 * contact sheet.
 */
final class LodSeam {
	static final String DIR = System.getProperty("mcopt.lod.seam");
	static final boolean ON = DIR != null;
	/** Ring of frames kept on the GPU (the probe uses 4; the rest let crops of a flash's neighbors be read once it is known). */
	private static final int RING = 12;
	/** Frames between a frame's dispatch and reading its counters back (it is done by then). */
	private static final int LAG = 8;
	private static final int STATS = 32, S_N = 30;
	private static final int CHANGE = Integer.getInteger("mcopt.lod.seam.change", 32), BACK = Integer.getInteger("mcopt.lod.seam.back", 12);
	/** Flash pixels in a frame that make a crop (at most SHOTS of them, SHOT_GAP frames apart). */
	private static final int SHOT_MIN = Integer.getInteger("mcopt.lod.seam.shotMin", 200), SHOTS = Integer.getInteger("mcopt.lod.seam.shots", 24),
		SHOT_GAP = Integer.getInteger("mcopt.lod.seam.shotGap", 20);
	/** -Dmcopt.lod.seam.shotFrom=F: no crops before probe frame F (keep the budget for a later phase). */
	private static final long SHOT_FROM = Long.getLong("mcopt.lod.seam.shotFrom", 0);
	private static final int[] STRIP = parseStrip(System.getProperty("mcopt.lod.seam.strip", ""));
	/**
	 * -Dmcopt.lod.seam.stripZ=Z0,Z1,STEP,SCALE: a frame scaled down by SCALE each time the camera's z passes Z0 + k x STEP (up to
	 * Z1): the bench's flight is a function of z, so two runs give pictures of the same views (before/after sheets).
	 */
	private static final double[] STRIP_Z = parseStripZ(System.getProperty("mcopt.lod.seam.stripZ", ""));
	private double nextZ = Double.NaN;
	static final int FRAME_BYTES = 64 + 3 * 64 + 3 * 16 + 16 + 16 + 16 + 16;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mcl_seam_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BUFFER = fn("mcl_seam_buffer", JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PROBE = fn("mcl_seam_probe", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);

	private final long ctx, seam;
	private final long frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
	private long ring, marks, stats, changes, ringAddr, marksAddr, statsAddr;
	private int w, h;
	/** Probe frames since the ring was (re)made. */
	private long t, jumpAt;
	private final Matrix4f[] vp = new Matrix4f[RING];
	private final double[][] cam = new double[RING][3];
	private final long[] nanos = new long[RING], epochMs = new long[RING];
	private final String[] engine = new String[RING];
	private final BufferedWriter csv, durations;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread th = new Thread(r, "mcopt-lod-seam");
		th.setDaemon(true);
		return th;
	});
	private int shots;
	private long lastShot = -1_000_000;
	// totals for the summary lines
	private long sumFlash, sumPop, sumRegion, framesLogged, maxChange, flashFrames;
	private long lastSummary = System.nanoTime();

	LodSeam(long ctx) {
		this.ctx = ctx;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			String source;
			try (var in = LodSeam.class.getResourceAsStream("/mcopt/lod/seam.metal")) {
				if (in == null) throw new IllegalStateException("seam.metal missing");
				source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			try {
				this.seam = (long) NEW.invokeExact(ctx, src, err, 4096);
			} finally {
				MemoryUtil.nmemFree(src);
			}
			if (this.seam == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			Path dir = Path.of(DIR);
			Files.createDirectories(dir.resolve("shots"));
			this.csv = Files.newBufferedWriter(dir.resolve("frames.csv"));
			this.durations = Files.newBufferedWriter(dir.resolve("durations.csv"));
		} catch (Throwable e) {
			throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
		}
		try {
			this.csv.write("frame,nanos,epochMs,camX,camY,camZ,region,flash,flashSky,flashHandoff,flashFar,flashNear,change16,change32,change64,change128,"
				+ "maxChange,pop,flashCx,flashCy,valid,popFromSky,popToSky,flash2,farPx,skyLost,solidFlash,solidFlashSky,solidFlashHandoff,solidFlashFar,solidPop,"
				+ "solidChange64,solidMaxChange,solidPopSky,fogPx,fogHandoffPx," + EngineState.HEADER + "\n");
			this.durations.write("frameEnd,level,tx,tz,kind,frames,ms,holeFrames\n");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		for (int i = 0; i < RING; i++) this.vp[i] = new Matrix4f();
		System.out.printf("mcopt-lod seam: probe on, writing %s (change > %d, back <= %d)%n", DIR, CHANGE, BACK);
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
	}

	private static double[] parseStripZ(String s) {
		if (s.isBlank()) return null;
		String[] p = s.split(",");
		double[] v = {0, 0, 16, 4};
		for (int i = 0; i < Math.min(4, p.length); i++) v[i] = Double.parseDouble(p[i].trim());
		return v;
	}

	private static int[] parseStrip(String s) {
		if (s.isBlank()) return null;
		String[] p = s.split(",");
		int[] v = {0, 0, 1, 4};
		for (int i = 0; i < Math.min(4, p.length); i++) v[i] = Integer.parseInt(p[i].trim());
		return v;
	}

	/** A shared buffer with hazard tracking (LodClip with -Dmcopt.lod.trackedClip). */
	static long trackedBuffer(long ctx, long size) {
		try {
			long b = (long) BUFFER.invokeExact(ctx, size);
			if (b == 0) throw new IllegalStateException("tracked buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable e) {
			throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
		}
	}

	private long buffer(long size) {
		try {
			long b = (long) BUFFER.invokeExact(this.ctx, size);
			if (b == 0) throw new IllegalStateException("seam buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable e) {
			throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
		}
	}

	private void ensure(int width, int height) {
		if (width == this.w && height == this.h && this.ring != 0) return;
		// (frames in flight may still use the old ones: leak them rather than free them early; a resize is rare)
		long px = (long) width * height;
		this.ring = this.buffer(px * 4 * RING);
		this.marks = this.buffer(px * RING);
		this.stats = this.buffer((long) RING * STATS * 4);
		this.changes = this.buffer(px);
		this.ringAddr = LodNative.contents(this.ring);
		this.marksAddr = LodNative.contents(this.marks);
		this.statsAddr = LodNative.contents(this.stats);
		this.w = width;
		this.h = height;
		this.t = 0;
	}

	/**
	 * End of the level: probe frame t. vp: this frame's camera-relative view-projection; cam: the camera; rd: the render
	 * distance in chunks (the hand-off zone is around rd x 16 blocks).
	 */
	void frame(long enc, long colorTex, long depthTex, int width, int height, Matrix4f viewProj, double cx, double cy, double cz, int rd, float fogR, float fogG, float fogB, EngineState es) {
		this.ensure(width, height);
		long t = this.t;
		int slot = (int) (t % RING);
		// this frame's counters start from zero (the slot was read back RING - LAG frames ago)
		MemoryUtil.memSet(this.statsAddr + (long) slot * STATS * 4, 0, STATS * 4);
		// (the previous frame's camera is read before this slot is overwritten: RING > 1)
		this.vp[slot].set(viewProj);
		this.cam[slot][0] = cx;
		this.cam[slot][1] = cy;
		this.cam[slot][2] = cz;
		this.nanos[slot] = System.nanoTime();
		this.epochMs[slot] = System.currentTimeMillis();
		this.engine[slot] = es.csv();
		long f = this.frameBuf;
		new Matrix4f(viewProj).invert().getToAddress(f);
		// a teleport (more than 32 blocks in a frame) is not a transition: the history starts again after it
		if (t > 0) {
			double[] prev = this.cam[(int) ((t - 1) % RING)];
			if (Math.abs(cx - prev[0]) + Math.abs(cy - prev[1]) + Math.abs(cz - prev[2]) > 32) this.jumpAt = t;
		}
		int hist = (int) Math.min(3, t - this.jumpAt);
		for (int k = 1; k <= 3; k++) {
			int s = (int) ((t - k + RING * 4L) % RING);
			(k <= hist ? this.vp[s] : viewProj).getToAddress(f + 64L * k);
			long cd = f + 256 + 16L * (k - 1);
			MemoryUtil.memPutFloat(cd, k <= hist ? (float) (cx - this.cam[s][0]) : 0);
			MemoryUtil.memPutFloat(cd + 4, k <= hist ? (float) (cy - this.cam[s][1]) : 0);
			MemoryUtil.memPutFloat(cd + 8, k <= hist ? (float) (cz - this.cam[s][2]) : 0);
			MemoryUtil.memPutFloat(cd + 12, 0);
		}
		MemoryUtil.memPutInt(f + 304, width);
		MemoryUtil.memPutInt(f + 308, height);
		MemoryUtil.memPutInt(f + 312, slot);
		MemoryUtil.memPutInt(f + 316, RING);
		for (int k = 1; k <= 3; k++) MemoryUtil.memPutInt(f + 316 + 4L * k, (int) ((t - k + RING * 4L) % RING));
		MemoryUtil.memPutInt(f + 332, hist);
		// zones (cylindrical distance): the hand-off band from a chunk inside the render distance to 3 chunks past it
		MemoryUtil.memPutFloat(f + 336, rd * 16.0F - 24.0F);
		MemoryUtil.memPutFloat(f + 340, rd * 16.0F + 48.0F);
		MemoryUtil.memPutFloat(f + 344, CHANGE);
		MemoryUtil.memPutFloat(f + 348, BACK);
		MemoryUtil.memPutFloat(f + 352, fogR);
		MemoryUtil.memPutFloat(f + 356, fogG);
		MemoryUtil.memPutFloat(f + 360, fogB);
		MemoryUtil.memPutFloat(f + 364, 1);
		int r;
		try {
			r = (int) PROBE.invokeExact(this.seam, enc, colorTex, depthTex, f, FRAME_BYTES, this.ring, this.marks, this.stats, this.changes, width, height);
		} catch (Throwable e) {
			throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
		}
		if (r != 0) return;
		this.t++;
		if (t >= LAG) this.collect(t - LAG);
		if (STRIP != null && t >= LAG - 2) this.strip(t - (LAG - 2));
		if (STRIP_Z != null && t >= LAG - 2) this.stripZ(t - (LAG - 2));
	}

	private void collect(long frame) {
		int slot = (int) (frame % RING);
		long s = this.statsAddr + (long) slot * STATS * 4;
		int[] v = new int[S_N];
		for (int i = 0; i < S_N; i++) v[i] = MemoryUtil.memGetInt(s + i * 4L);
		StringBuilder b = new StringBuilder(256);
		b.append(frame).append(',').append(this.nanos[slot]).append(',').append(this.epochMs[slot]);
		b.append(String.format(",%.2f,%.2f,%.2f", this.cam[slot][0], this.cam[slot][1], this.cam[slot][2]));
		for (int i = 0; i < S_N; i++) {
			if (i == 12 || i == 13) b.append(',').append(v[1] > 0 ? (long) Integer.toUnsignedLong(v[i]) * 16 / v[1] : -1);
			else b.append(',').append(Integer.toUnsignedLong(v[i]));
		}
		b.append(',').append(this.engine[slot]).append('\n');
		try {
			this.csv.write(b.toString());
		} catch (IOException e) {
			// instrumentation: never fatal
		}
		this.framesLogged++;
		this.sumFlash += v[20];
		this.sumPop += v[11];
		this.sumRegion += v[0];
		if (v[20] > 0) this.flashFrames++;
		this.maxChange = Math.max(this.maxChange, v[10]);
		// the flash happened in frame - 2: frames frame - 5 .. frame + 3 are all still in the ring and done
		if (v[20] >= SHOT_MIN && frame >= SHOT_FROM && this.shots < SHOTS && frame - this.lastShot >= SHOT_GAP) {
			this.shots++;
			this.lastShot = frame;
			int cx = (int) ((long) Integer.toUnsignedLong(v[12]) * 16 / v[1]), cy = (int) ((long) Integer.toUnsignedLong(v[13]) * 16 / v[1]);
			this.crop(frame, cx, cy, v[1]);
		}
		long now = System.nanoTime();
		if (now - this.lastSummary > 5_000_000_000L) {
			this.lastSummary = now;
			System.out.printf("mcopt-lod seam: %d frames, solid flash px %d in %d frames, pop px %d, region px/frame %.0f, max change %d%n", this.framesLogged, this.sumFlash,
				this.flashFrames, this.sumPop, this.sumRegion / (double) Math.max(1, this.framesLogged), this.maxChange);
			try {
				this.csv.flush();
				this.durations.flush();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	/** Crops (full resolution, 768 x 432) of frames flash-5 .. flash+3 around the flash's centroid, with its marks beside. */
	private void crop(long frame, int cx, int cy, int px) {
		int cw = Math.min(768, this.w), ch = Math.min(432, this.h);
		int x0 = Math.clamp(cx - cw / 2, 0, this.w - cw), y0 = Math.clamp(cy - ch / 2, 0, this.h - ch);
		List<int[]> imgs = new ArrayList<>();
		List<Long> ids = new ArrayList<>();
		for (long k = frame - 5; k <= frame + 3; k++) {
			if (k < 0) continue;
			int slot = (int) (k % RING);
			int[] img = new int[cw * ch * 2];
			for (int y = 0; y < ch; y++) {
				// (rows are stored bottom up: the image's top row is the crop's last)
				long row = (long) slot * this.w * this.h + (long) (y0 + ch - 1 - y) * this.w + x0;
				for (int x = 0; x < cw; x++) {
					int v = MemoryUtil.memGetInt(this.ringAddr + (row + x) * 4);
					img[y * cw + x] = (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16 & 0xFF);
					int m = MemoryUtil.memGetByte(this.marksAddr + row + x);
					int zone = v >>> 24;
					int base = zone == 3 ? 0x202040 : zone == 2 ? 0x204020 : zone == 1 ? 0x404000 : 0x101010;
					img[cw * ch + y * cw + x] = m == 1 ? 0xFF2020 : m == 2 ? 0x20A0FF : base;
				}
			}
			imgs.add(img);
			ids.add(k);
		}
		Path dir = Path.of(DIR, "shots");
		this.writer.execute(() -> {
			for (int i = 0; i < imgs.size(); i++) {
				var bi = new java.awt.image.BufferedImage(cw, ch * 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
				bi.setRGB(0, 0, cw, ch * 2, imgs.get(i), 0, cw);
				try {
					javax.imageio.ImageIO.write(bi, "png", dir.resolve(String.format("flash-%06d-f%06d.png", frame - 2, ids.get(i))).toFile());
				} catch (IOException e) {
					// ignore
				}
			}
		});
		System.out.printf("mcopt-lod seam: flash of %d px in frame %d around %d,%d: crops at %d,%d%n", px, frame - 2, cx, cy, x0, y0);
	}

	/** -Dmcopt.lod.seam.strip=FIRST,COUNT,STEP,SCALE: frame FIRST + i x STEP (i < COUNT) scaled down by SCALE. */
	private void strip(long frame) {
		int first = STRIP[0], count = STRIP[1], step = Math.max(1, STRIP[2]), scale = Math.max(1, STRIP[3]);
		if (frame < first || (frame - first) % step != 0 || (frame - first) / step >= count) return;
		int slot = (int) (frame % RING);
		int sw = this.w / scale, sh = this.h / scale;
		int[] img = new int[sw * sh];
		for (int y = 0; y < sh; y++) {
			long row = (long) slot * this.w * this.h + (long) (this.h - 1 - (y * scale + scale / 2)) * this.w;
			for (int x = 0; x < sw; x++) {
				int v = MemoryUtil.memGetInt(this.ringAddr + (row + x * scale + scale / 2) * 4);
				img[y * sw + x] = (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16 & 0xFF);
			}
		}
		Path p = Path.of(DIR, "shots", String.format("strip-%06d.png", frame));
		this.writer.execute(() -> {
			var bi = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
			bi.setRGB(0, 0, sw, sh, img, 0, sw);
			try {
				javax.imageio.ImageIO.write(bi, "png", p.toFile());
			} catch (IOException e) {
				// ignore
			}
		});
	}

	private void stripZ(long frame) {
		int slot = (int) (frame % RING);
		double z = this.cam[slot][2];
		// armed once the camera has been before the first step (the bench starts at the flight's end, then goes back)
		if (Double.isNaN(this.nextZ)) {
			if (z < STRIP_Z[0]) this.nextZ = STRIP_Z[0];
			return;
		}
		if (this.nextZ > STRIP_Z[1] || z < this.nextZ) return;
		int k = (int) Math.round((this.nextZ - STRIP_Z[0]) / STRIP_Z[2]);
		while (this.nextZ <= z) this.nextZ += STRIP_Z[2];
		int scale = Math.max(1, (int) STRIP_Z[3]);
		int sw = this.w / scale, sh = this.h / scale;
		int[] img = new int[sw * sh];
		for (int y = 0; y < sh; y++) {
			long row = (long) slot * this.w * this.h + (long) (this.h - 1 - (y * scale + scale / 2)) * this.w;
			for (int x = 0; x < sw; x++) {
				int v = MemoryUtil.memGetInt(this.ringAddr + (row + x * scale + scale / 2) * 4);
				img[y * sw + x] = (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16 & 0xFF);
			}
		}
		Path p = Path.of(DIR, "shots", String.format("z-%03d-f%06d.png", k, frame));
		this.writer.execute(() -> {
			var bi = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
			bi.setRGB(0, 0, sw, sh, img, 0, sw);
			try {
				javax.imageio.ImageIO.write(bi, "png", p.toFile());
			} catch (IOException e) {
				// ignore
			}
		});
	}

	void duration(long frameEnd, int level, int tx, int tz, String kind, long frames, double ms, int holeFrames) {
		try {
			this.durations.write(frameEnd + "," + level + "," + tx + "," + tz + "," + kind + "," + frames + "," + String.format("%.1f", ms) + "," + holeFrames + "\n");
		} catch (IOException e) {
			// ignore
		}
	}

	long probeFrame() {
		return this.t;
	}

	void close() {
		try {
			this.csv.flush();
			this.durations.flush();
		} catch (IOException e) {
			// ignore
		}
	}

	/**
	 * The engine's side of a frame, for the CSV: tiles on screen that their own level should draw, drawn exactly (their mesh is
	 * installed), by a coarser stand-in, or not at all (a hole), with their projected areas; tiles whose installed mesh was
	 * built from older words than the clipmap holds now (stale); and the streaming queues.
	 */
	static final class EngineState {
		static final String HEADER = "tilesOnScreen,exact,fallback,hole,fallbackPx,holePx,stale,stalePx,meshPending,fieldMissing,maskChanged";
		int onScreen, exact, fallback, hole, stale, meshPending, fieldMissing, maskChanged;
		double fallbackPx, holePx, stalePx;

		String csv() {
			return this.onScreen + "," + this.exact + "," + this.fallback + "," + this.hole + "," + Math.round(this.fallbackPx) + "," + Math.round(this.holePx) + ","
				+ this.stale + "," + Math.round(this.stalePx) + "," + this.meshPending + "," + this.fieldMissing + "," + this.maskChanged;
		}
	}

	/** Whether the real terrain draws chunk (cx, cz) this frame (the far terrain steps back there). */
	interface ChunkMask {
		boolean masked(int cx, int cz);
	}

	// ---- tiles on screen (CPU, from the clipmap's and the mesh's tables) ----

	private long[][] episodeStart;
	private long[][] episodeNanos;
	private byte[][] episodeKind;
	private int[][] episodeHoles;
	private final Vector4f corner = new Vector4f();

	/**
	 * Classifies every tile of every level's ring that is in the frustum: exact, stand-in, hole, stale. mesh may be null (the
	 * walk: words resident = exact). Records how long each stand-in or hole episode lasted on screen.
	 */
	EngineState engineState(LodClip clip, LodField field, LodMesh mesh, FrustumIntersection frustum, Matrix4f viewProj, double cx, double cy, double cz,
		int screenW, int screenH, ChunkMask chunkMasked) {
		EngineState es = new EngineState();
		int tps = clip.tilesPerSide;
		if (this.episodeStart == null) {
			this.episodeStart = new long[clip.levels][tps * tps];
			this.episodeNanos = new long[clip.levels][tps * tps];
			this.episodeKind = new byte[clip.levels][tps * tps];
			this.episodeHoles = new int[clip.levels][tps * tps];
		}
		long frame = this.t;
		long now = System.nanoTime();
		double reach = LodConfig.reachBlocks();
		int top = clip.levels - 1;
		for (int l = 0; l <= top; l++) {
			int span = clip.span(l);
			double inner = l == 0 ? -1 : clip.switchDist[l - 1];
			double outer = Math.min(clip.switchDist[l], reach);
			for (int z = 0; z < tps; z++) {
				for (int x = 0; x < tps; x++) {
					int tx = clip.winTx[l] + x, tz = clip.winTz[l] + z;
					int slot = clip.slot(tx, tz);
					double x0 = (double) tx * span, z0 = (double) tz * span;
					double dx = Math.max(0, Math.max(x0 - cx, cx - (x0 + span))), dz = Math.max(0, Math.max(z0 - cz, cz - (z0 + span)));
					double near = Math.sqrt(dx * dx + dz * dz);
					double fx = Math.max(Math.abs(x0 - cx), Math.abs(x0 + span - cx)), fz = Math.max(Math.abs(z0 - cz), Math.abs(z0 + span - cz));
					double far = Math.sqrt(fx * fx + fz * fz);
					boolean needed = near < outer && far > inner;
					boolean resident = clip.resident(l, tx, tz);
					float ylo = resident ? clip.slotMin[l][slot] : -64, yhi = resident ? clip.slotMax[l][slot] + 1 : 320;
					boolean visible = needed && frustum.testAab((float) (x0 - cx), (float) (ylo - cy), (float) (z0 - cz), (float) (x0 + span - cx), (float) (yhi - cy),
						(float) (z0 + span - cz));
					if (visible && l == 0 && this.allMasked(tx, tz, chunkMasked)) visible = false;
					int kind = 0;   // 1 exact, 2 stand-in, 3 hole
					if (visible) {
						es.onScreen++;
						boolean exact = mesh != null ? mesh.installed(l, tx, tz) : resident;
						if (exact) {
							kind = 1;
							es.exact++;
							if (mesh != null && mesh.stale(l, tx, tz)) {
								es.stale++;
								es.stalePx += this.area(viewProj, x0 - cx, ylo - cy, z0 - cz, span, yhi - ylo, screenW, screenH);
							}
						} else {
							boolean cover = false;
							for (int k = l + 1; k <= top && !cover; k++) {
								int ks = clip.span(k);
								int ktx = Math.floorDiv(tx * span, ks), ktz = Math.floorDiv(tz * span, ks);
								cover = mesh != null ? mesh.installed(k, ktx, ktz) : clip.resident(k, ktx, ktz);
							}
							double a = this.area(viewProj, x0 - cx, ylo - cy, z0 - cz, span, yhi - ylo, screenW, screenH);
							if (cover) {
								kind = 2;
								es.fallback++;
								es.fallbackPx += a;
							} else {
								kind = 3;
								es.hole++;
								es.holePx += a;
							}
						}
					}
					// episodes: a stand-in or hole on screen until the tile draws exactly (or leaves the screen: dropped)
					byte was = this.episodeKind[l][slot];
					if (kind >= 2) {
						if (this.episodeStart[l][slot] == 0) {
							this.episodeStart[l][slot] = frame + 1;
							this.episodeNanos[l][slot] = now;
							this.episodeKind[l][slot] = (byte) kind;
							this.episodeHoles[l][slot] = 0;
						} else if (kind == 3) {
							this.episodeKind[l][slot] = 3;   // an episode with any hole frame counts as a hole
						}
						if (kind == 3) this.episodeHoles[l][slot]++;
					} else if (this.episodeStart[l][slot] != 0) {
						if (kind == 1) this.duration(frame, l, tx, tz, was == 3 ? "hole" : "standin", frame + 1 - this.episodeStart[l][slot],
							(now - this.episodeNanos[l][slot]) / 1e6, this.episodeHoles[l][slot]);
						this.episodeStart[l][slot] = 0;
						this.episodeKind[l][slot] = 0;
					}
				}
			}
		}
		double screen = (double) screenW * screenH;
		es.holePx = Math.min(es.holePx, screen);
		es.fallbackPx = Math.min(es.fallbackPx, screen);
		es.stalePx = Math.min(es.stalePx, screen);
		es.meshPending = mesh != null ? mesh.pending() : 0;
		es.fieldMissing = field.missing;
		return es;
	}

	private boolean allMasked(int tx, int tz, ChunkMask chunkMasked) {
		int c0x = tx * 4, c0z = tz * 4;
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				if (!chunkMasked.masked(c0x + x, c0z + z)) return false;
			}
		}
		return true;
	}

	/** Screen area (pixels, clipped to the screen) of a box's projected corners' bounds; the whole screen when it reaches behind the camera. */
	private double area(Matrix4f m, double x0, double y0, double z0, double span, double height, int sw, int sh) {
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			this.corner.set((float) (x0 + ((i & 1) != 0 ? span : 0)), (float) (y0 + ((i & 2) != 0 ? height : 0)), (float) (z0 + ((i & 4) != 0 ? span : 0)), 1);
			m.transform(this.corner);
			if (this.corner.w <= 1e-3F) return (double) sw * sh;
			float nx = this.corner.x / this.corner.w, ny = this.corner.y / this.corner.w;
			minX = Math.min(minX, nx);
			maxX = Math.max(maxX, nx);
			minY = Math.min(minY, ny);
			maxY = Math.max(maxY, ny);
		}
		double ax = Math.max(0, Math.min(1, maxX) - Math.max(-1, minX)) * 0.5 * sw, ay = Math.max(0, Math.min(1, maxY) - Math.max(-1, minY)) * 0.5 * sh;
		return ax * ay;
	}
}
