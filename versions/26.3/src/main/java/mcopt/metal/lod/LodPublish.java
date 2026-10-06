package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * -Dmcopt.lod.publish=gpu: staged tile words go to the clipmap through the GPU (mcopt/lod/seam.metal seam_publish), in the
 * frame that installs their mesh, before its cull. The clipmap's word buffers are hazard-tracked in this mode, so Metal
 * runs the copy after every earlier frame's reads of them: frames already in flight keep drawing old meshes with old
 * words, and this frame draws the new mesh with the new words. A CPU write at the frame boundary (publish=pair) can't
 * promise that: frames still on the GPU would read the new words under their old meshes.
 */
final class LodPublish {
	static final boolean ON = "gpu".equals(System.getProperty("mcopt.lod.publish", ""));
	private static final int RING = 4;
	/** Tiles a frame publishes at most (LodMesh holds the rest for the next frame). */
	static final int CAP = 32;
	private static final int TILE_WORDS = 4096 * 7, TILE_BYTES = 32, FRAME_BYTES = 16;
	private static final MethodHandle NEW = fn("mcl_seam_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle PUBLISH = fn("mcl_seam_publish", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);

	private final long seam;
	private final long[] staging = new long[RING], tiles = new long[RING];
	private final long frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
	private int slot, count, words;
	final java.util.concurrent.atomic.AtomicLong tilesPublished = new java.util.concurrent.atomic.AtomicLong();

	LodPublish(long ctx) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			String source;
			try (var in = LodPublish.class.getResourceAsStream("/mcopt/lod/seam.metal")) {
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
		} catch (Throwable e) {
			throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
		}
		for (int i = 0; i < RING; i++) {
			this.staging[i] = LodNative.buffer(ctx, (long) CAP * TILE_WORDS * 4);
			this.tiles[i] = LodNative.buffer(ctx, (long) CAP * TILE_BYTES);
		}
		System.out.println("mcopt-lod: tile words published by the GPU (hazard-tracked clipmap)");
	}

	private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return Linker.nativeLinker().downcallHandle(symbol, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
	}

	/** Start of a frame's installs: this frame's staging slot (written by the CPU now, read by this frame's GPU work only). */
	void begin(long frame) {
		this.slot = (int) (frame % RING);
		this.count = 0;
		this.words = 0;
	}

	boolean full() {
		return this.count >= CAP;
	}

	/** Stages one tile's words (already masked as LodClip writes them). */
	void stage(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] runs,
		int @org.jspecify.annotations.Nullable [] tw, int @org.jspecify.annotations.Nullable [] pl) {
		long base = LodNative.contents(this.staging[this.slot]);
		int src = this.words;
		long at = base + (long) src * 4;
		at = put(at, g, 0);
		at = put(at, c, 0);
		int flags = 0;
		if (cr != null) {
			at = put(at, cr, 0);
			at = runs != null ? put(at, runs, 0) : zero(at);
			flags |= 1;
		}
		if (tw != null) {
			at = put(at, tw, 0);
			flags |= 2;
		}
		if (pl != null) {
			at = put(at, pl, 0);
			at = put(at, pl, 4096);
			flags |= 4;
		}
		this.words += (int) ((at - (base + (long) src * 4)) / 4);
		long t = LodNative.contents(this.tiles[this.slot]) + (long) this.count * TILE_BYTES;
		MemoryUtil.memPutInt(t, level);
		MemoryUtil.memPutInt(t + 4, tx);
		MemoryUtil.memPutInt(t + 8, tz);
		MemoryUtil.memPutInt(t + 12, flags);
		MemoryUtil.memPutInt(t + 16, src);
		this.count++;
		this.tilesPublished.incrementAndGet();
	}

	private static long put(long at, int[] a, int from) {
		for (int i = 0; i < 4096; i++) MemoryUtil.memPutInt(at + i * 4L, a[from + i]);
		return at + 4096L * 4;
	}

	private static long zero(long at) {
		MemoryUtil.memSet(at, 0, 4096L * 4);
		return at + 4096L * 4;
	}

	/** Encodes this frame's copies into the pre command buffer (before the cull). */
	void encode(long enc, LodClip clip) {
		if (this.count == 0) return;
		long f = this.frameBuf;
		MemoryUtil.memPutInt(f, clip.logN);
		MemoryUtil.memPutInt(f + 4, (int) clip.levelWords);
		MemoryUtil.memPutInt(f + 8, (int) clip.runsOffset());
		MemoryUtil.memPutInt(f + 12, this.count);
		try {
			PUBLISH.invokeExact(this.seam, enc, f, FRAME_BYTES, this.tiles[this.slot], this.staging[this.slot], clip.geomBuf, clip.colorBuf, clip.crownBuf, clip.texBuf,
				this.count);
		} catch (Throwable e) {
			throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
		}
		this.count = 0;
	}
}
