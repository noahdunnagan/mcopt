package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Downcalls into mclod.m. Pointers cross as longs, as everywhere in the backend. */
final class LodNative {
	private static final Linker LINKER = Linker.nativeLinker();

	private static final MethodHandle COLS_NEW = fn("mcl_cols_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ATTACHMENTS = fn("mcl_attachments", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle ENC_SIZE = fn("mcl_enc_size", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle COLS_MARCH = fn("mcl_cols_march", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle COLS_COMPOSITE = fn("mcl_cols_composite", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MESH_TILE = fn("mcl_mesh_tile", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
		JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MESH_CULL = fn("mcl_mesh_cull", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle MESH_DRAW = fn("mcl_mesh_draw", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
		JAVA_LONG, JAVA_INT);
	private static final MethodHandle PK_CULL = fn("mcl_pk_cull", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle PK_VIS = fn("mcl_pk_vis", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle CULL_TIME_SLOT = fn("mcl_cull_time_slot", false, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CULL_TIME_READ = fn("mcl_cull_time_read", false, JAVA_DOUBLE, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SHARED_TRACKED = fn("mcl_shared_tracked_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BUFFER = fn("mcl_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRIVATE_BUFFER = fn("mcl_private_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRIVATE_UNTRACKED = fn("mcl_private_untracked_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CONTENTS = fn("mcl_contents", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mcl_release", false, null, JAVA_LONG);

	private LodNative() {
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	static long create(long ctx, String source) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 16384);
			MemoryUtil.memPutByte(err, (byte) 0);
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			long lod;
			try {
				lod = (long) COLS_NEW.invokeExact(ctx, src, err, 16384);
			} finally {
				MemoryUtil.nmemFree(src);
			}
			if (lod == 0) throw new IllegalStateException("lod: " + MemoryUtil.memUTF8(err));
			return lod;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int attachments(long enc) {
		try {
			return (int) ATTACHMENTS.invokeExact(enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The open render encoder's viewport: width << 16 | height (0: none open). */
	static int encSize(long enc) {
		try {
			return (int) ENC_SIZE.invokeExact(enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void march(long lod, long enc, long frame, int frameLength, long geom, long color, long mip, long mask, long out, long dbg, long crowns,
		int columns, int segments, long segMax, long texWords, long palette) {
		try {
			COLS_MARCH.invokeExact(lod, enc, frame, frameLength, geom, color, mip, mask, out, dbg, crowns, columns, segments, segMax, texWords, palette);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Returns 0, or throws with the pipeline error. */
	static void composite(long lod, long enc, boolean gbuffer, long frame, int frameLength, long out, long geom, long color, long crowns, long texWords,
		long palette, long atlas, long err, int errCap) {
		int r;
		try {
			r = (int) COLS_COMPOSITE.invokeExact(lod, enc, gbuffer ? 1 : 0, frame, frameLength, out, geom, color, crowns, texWords, palette, atlas, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (r != 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
	}

	/** Meshes one tile (lodmesh.c) from native memory into native memory; any thread. The quad count, or -1 when cap is too small. */
	static int meshTile(long geom, long crown, long runs, long plantA, long plantB, int logN, int level, int tx, int tz, int neighbors, int block, long header,
		long quads, int cap) {
		try {
			return (int) MESH_TILE.invokeExact(geom, crown, runs, plantA, plantB, logN, level, tx, tz, neighbors, block, header, quads, cap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void meshCull(long lod, long enc, long frame, int frameLength, long comp, int compLength, long table, long arena, long mask, long args, long inst,
		long plantInst, long survivors, long horizon, long list, long geom, long crowns, int blocks, boolean fences) {
		try {
			MESH_CULL.invokeExact(lod, enc, frame, frameLength, comp, compLength, table, arena, mask, args, inst, plantInst, survivors, horizon, list, geom, crowns,
				blocks, fences ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Returns normally, or throws with the pipeline error. */
	static void meshDraw(long lod, long enc, boolean gbuffer, long comp, int compLength, long arena, long args, long inst, long plantInst, long survivors,
		long geom, long color, long crowns, long texWords, long palette, long atlas, long table, long meshFrame, int meshFrameLength, boolean fences, long err,
		int errCap) {
		int r;
		try {
			r = (int) MESH_DRAW.invokeExact(lod, enc, gbuffer ? 1 : 0, comp, compLength, arena, args, inst, plantInst, survivors, geom, color, crowns, texWords,
				palette, atlas, table, meshFrame, meshFrameLength, fences ? 1 : 0, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (r != 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
	}

	/**
	 * The position-keyed lists' passes (mclod.m mcl_pk_cull); bufs: the buffers' handles in its order; draw: 0 none, 1 the
	 * lists, 2 the live cull and the lists. Throws when a pipeline is missing.
	 */
	static void pkCull(long lod, long enc, long frame, int frameLength, long comp, int compLength, long params, int paramsLength, long bufs, boolean rebuild,
		int draw, int blocks) {
		int r;
		try {
			r = (int) PK_CULL.invokeExact(lod, enc, frame, frameLength, comp, compLength, params, paramsLength, bufs, rebuild ? 1 : 0, draw, blocks);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (r != 0) throw new IllegalStateException("lod: the position-keyed lists' pipelines are missing");
	}

	/**
	 * The lists' visible set (mclod.m mcl_pk_vis): prunes azimuth az's sectors in bandMask (1 the hand-off band, 2 the far
	 * band) against a depth facet drawn from facet (a CompFrame, fw x fh) of the sectors in use (use: 4 x 32-bit masks).
	 * Throws when a pipeline is missing.
	 */
	static void pkVis(long lod, long enc, long frame, int frameLength, long facet, int facetLength, long params, int paramsLength, long bufs, int az,
		int bandMask, float eps0, float eps1, long use, int fw, int fh, int stride) {
		int r;
		try {
			r = (int) PK_VIS.invokeExact(lod, enc, frame, frameLength, facet, facetLength, params, paramsLength, bufs, az, bandMask, eps0, eps1, use, fw, fh,
				stride);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (r != 0) throw new IllegalStateException("lod: the visible set's pipelines are missing");
	}

	/** The next cull (meshCull or pkCull) samples its GPU time into slot (0-15); false when the GPU can't. */
	static boolean cullTimeSlot(long lod, int slot) {
		try {
			return (int) CULL_TIME_SLOT.invokeExact(lod, slot) == 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The GPU time (ms) of the cull that sampled into slot, once its frame completed; -1 without a new sample. */
	static double cullTime(long lod, int slot) {
		try {
			return (double) CULL_TIME_READ.invokeExact(lod, slot);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A CPU-readable, hazard-tracked buffer. */
	static long sharedTrackedBuffer(long ctx, long size) {
		try {
			long b = (long) SHARED_TRACKED.invokeExact(ctx, size);
			if (b == 0) throw new IllegalStateException("lod: buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long privateBuffer(long ctx, long size) {
		try {
			long b = (long) PRIVATE_BUFFER.invokeExact(ctx, size);
			if (b == 0) throw new IllegalStateException("lod: private buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A GPU-only buffer without hazard tracking (fences order its uses). */
	static long privateUntrackedBuffer(long ctx, long size) {
		try {
			long b = (long) PRIVATE_UNTRACKED.invokeExact(ctx, size);
			if (b == 0) throw new IllegalStateException("lod: private buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long buffer(long ctx, long size) {
		try {
			long b = (long) BUFFER.invokeExact(ctx, size);
			if (b == 0) throw new IllegalStateException("lod: buffer of " + size + " bytes failed");
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long contents(long buffer) {
		try {
			return (long) CONTENTS.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void release(long obj) {
		if (obj == 0) return;
		try {
			RELEASE.invokeExact(obj);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
