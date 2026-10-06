package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import mcopt.metal.MetalBridge;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * -Dmcopt.lod.taaTile=ALPHA (e.g. 0.1), or =empty: the far terrain's temporal filter as a tile function dispatched inside the
 * open level encoder at the end of the level (mcopt/lod/seam.metal seam_tile), so the level's colour stays in tile memory
 * (no store and reload, which is what the compute version paid). The far terrain's mesh fragments leave a distance code in the
 * colour's alpha (columns.metal, SEAM_TAA_TILE); the tile function reprojects by direction x distance, clamps the history to the
 * pixel's 3 x 3 neighbourhood (within its tile) grown by -Dmcopt.lod.taaGrow, blends at ALPHA and restores alpha 1. "empty":
 * the dispatch alone with an empty kernel (to price tile dispatch itself). Opt-in.
 */
final class LodTaaTile {
	private static final String MODE = System.getProperty("mcopt.lod.taaTile", "");
	static final boolean EMPTY = "empty".equals(MODE);
	static final float ALPHA = EMPTY || MODE.isEmpty() ? 0 : Float.parseFloat(MODE);
	static final boolean ON = EMPTY || ALPHA > 0;
	/** The mesh fragments write the distance code (the filter runs). */
	static final boolean CODES = ALPHA > 0 || Boolean.getBoolean("mcopt.lod.taaTileCodes");
	/** -Dmcopt.lod.taaTileDebug=BITS (pricing only): 1 no history sample, 2 no history write, 4 no neighbourhood, 8 no
	 * reprojection, 16 return after reading, 64 no flat skip, 128 no dithered history. */
	static final int DEBUG = Integer.getInteger("mcopt.lod.taaTileDebug", 0);
	static final float GROW = Float.parseFloat(System.getProperty("mcopt.lod.taaGrow", "0.25"));
	private static final int FRAME_BYTES = 9 * 16;
	private static final MethodHandle NEW = fn("mcl_seam_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TILE = fn("mcl_seam_tile", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);

	private final long seam;
	private final long frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES), err = MemoryUtil.nmemCalloc(1, 1024);
	private final Matrix4d prevVP = new Matrix4d(), inv = new Matrix4d();
	private final Vector4d va = new Vector4d(), vb = new Vector4d();
	private final Vector3d r00 = new Vector3d(), r10 = new Vector3d(), r01 = new Vector3d(), r11 = new Vector3d();
	private final Vector3d d0 = new Vector3d(), dx = new Vector3d(), dy = new Vector3d();
	private boolean affineWarned;
	private double px, py, pz;
	private boolean valid, warned;
	private int skipped, frameNo;

	LodTaaTile(long ctx) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long e = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(e, (byte) 0);
			String source;
			try (var in = LodTaaTile.class.getResourceAsStream("/mcopt/lod/seam.metal")) {
				if (in == null) throw new IllegalStateException("seam.metal missing");
				source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			try {
				this.seam = (long) NEW.invokeExact(ctx, src, e, 4096);
			} finally {
				MemoryUtil.nmemFree(src);
			}
			if (this.seam == 0) throw new IllegalStateException(MemoryUtil.memUTF8(e));
		} catch (Throwable t) {
			throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
		}
		System.out.printf("mcopt-lod: far terrain tile filter on (%s, box growth %.2f)%n", EMPTY ? "empty dispatch" : "alpha " + ALPHA, GROW);
	}

	private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return Linker.nativeLinker().downcallHandle(symbol, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
	}

	private void ray(double nx, double ny, Vector3d out) {
		this.inv.transform(this.va.set(nx, ny, 1, 1));
		this.inv.transform(this.vb.set(nx, ny, 0.5, 1));
		out.set(this.vb.x / this.vb.w - this.va.x / this.va.w, this.vb.y / this.vb.w - this.va.y / this.va.w, this.vb.z / this.vb.w - this.va.z / this.va.w);
	}

	private static void putVec(long at, Vector3d v, float w) {
		MemoryUtil.memPutFloat(at, (float) v.x);
		MemoryUtil.memPutFloat(at + 4, (float) v.y);
		MemoryUtil.memPutFloat(at + 8, (float) v.z);
		MemoryUtil.memPutFloat(at + 12, w);
	}

	/** The previous frame's clip of (v, w), folded to (u w, v w, w) of the history texture (u = (x / w + 1) / 2). */
	private void putPrev(long at, Vector3d v, double w) {
		this.prevVP.transform(this.va.set(v.x, v.y, v.z, w));
		MemoryUtil.memPutFloat(at, (float) (0.5 * (this.va.x + this.va.w)));
		MemoryUtil.memPutFloat(at + 4, (float) (0.5 * (this.va.y + this.va.w)));
		MemoryUtil.memPutFloat(at + 8, (float) this.va.w);
		MemoryUtil.memPutFloat(at + 12, 0);
	}

	/** End of the level, inside its open encoder. Returns whether it dispatched (the caller restores the game's pipeline). */
	boolean frame(long enc, int width, int height, Matrix4f viewProj, double cx, double cy, double cz) {
		long f = this.frameBuf;
		boolean teleported = Math.abs(cx - this.px) + Math.abs(cy - this.py) + Math.abs(cz - this.pz) > 32;
		// The view ray through pixel (x, y) (GL rows, bottom up) is affine in the pixel for a perspective camera: from the
		// near plane's point to the one at half depth (reversed z), camera-relative.
		this.inv.set(viewProj).invert();
		ray(-1, -1, this.r00);
		ray(1, -1, this.r10);
		ray(-1, 1, this.r01);
		this.dx.set(this.r10).sub(this.r00).div(width);
		this.dy.set(this.r01).sub(this.r00).div(height);
		this.d0.set(this.r00).fma(0.5, this.dx).fma(0.5, this.dy);
		if (!this.affineWarned) {
			ray(1, 1, this.r11);
			double err = this.r11.distance(this.r10.x + this.r01.x - this.r00.x, this.r10.y + this.r01.y - this.r00.y, this.r10.z + this.r01.z - this.r00.z);
			if (err > 1e-3 * this.r11.length()) {
				this.affineWarned = true;
				System.out.printf("mcopt-lod: tile filter: view ray not affine in the pixel (error %.2g), reprojection is approximate%n", err / this.r11.length());
			}
		}
		putVec(f, this.d0, 0);
		putVec(f + 16, this.dx, 0);
		putVec(f + 32, this.dy, 0);
		putPrev(f + 48, this.d0, 0);
		putPrev(f + 64, this.dx, 0);
		putPrev(f + 80, this.dy, 0);
		putPrev(f + 96, this.r11.set(cx - this.px, cy - this.py, cz - this.pz), 1);
		MemoryUtil.memPutFloat(f + 108, this.valid && !teleported ? 1 : 0);
		MemoryUtil.memPutFloat(f + 112, ALPHA);
		MemoryUtil.memPutFloat(f + 116, GROW);
		MemoryUtil.memPutFloat(f + 120, EMPTY ? 1 : 2);
		MemoryUtil.memPutFloat(f + 124, this.frameNo++ & 63);
		MemoryUtil.memPutInt(f + 128, width);
		MemoryUtil.memPutInt(f + 132, height);
		MemoryUtil.memPutInt(f + 136, DEBUG);
		int r;
		try {
			r = (int) TILE.invokeExact(this.seam, enc, f, FRAME_BYTES, this.err, 1024);
		} catch (Throwable t) {
			throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
		}
		if (r < 0) {
			this.skipped++;
			if (!this.warned && MemoryUtil.memGetByte(this.err) != 0) {
				this.warned = true;
				System.out.println("mcopt-lod: tile filter: " + MemoryUtil.memUTF8(this.err));
			}
			if (this.skipped == 1 || this.skipped % 1000 == 0) System.out.println("mcopt-lod: tile filter skipped " + this.skipped + " frames (no open level encoder)");
			this.valid = false;
			return false;
		}
		this.valid = r == 0;
		this.prevVP.set(viewProj);
		this.px = cx;
		this.py = cy;
		this.pz = cz;
		return true;
	}
}
