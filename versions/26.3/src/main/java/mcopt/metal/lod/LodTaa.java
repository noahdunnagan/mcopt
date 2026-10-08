package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * -Dmcopt.lod.taa=ALPHA (e.g. 0.1): the far terrain's own temporal filter, against shimmer (thin far features, a spruce tier's
 * snow top, a ledge, a pixel or less on screen, blinking as they slide under the pixel grid). It runs in the mesh's fragment
 * stage (columns.metal seamTaa, compiled in only with this flag), so the level's render pass is never split:
 * - at the top of the level the previous frame's far band is still in the main target (nothing has cleared it yet): one
 *   blit copies those rows into the history texture;
 * - each far fragment reprojects its exact hit point into the previous frame, clamps the history there to the box of its own
 *   colour and its cell's lit top and side colours (grown by -Dmcopt.lod.taaGrow of the box) and blends at weight ALPHA.
 * Real terrain, sky, the hand-off band and the GUI are untouched (GUI pixels in the history are outside every cell's box).
 */
final class LodTaa {
	static final float ALPHA = Float.parseFloat(System.getProperty("mcopt.lod.taa", "0"));
	static final boolean ON = ALPHA > 0;
	/** -Dmcopt.lod.taaGrow=G: the clamp box grown by G of its own size (default 0.25). */
	static final float GROW = Float.parseFloat(System.getProperty("mcopt.lod.taaGrow", "0.25"));
	private static final int FRAME_BYTES = 96;
	private static final MethodHandle HISTORY = fn("mcl_seam_taa_history", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle SET = fn("mcl_seam_taa_set", null, JAVA_LONG, JAVA_INT);

	private final long ctx;
	private final long frameBuf = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
	private final Matrix4f prevVP = new Matrix4f();
	private double px, py, pz;
	/** The history holds the previous frame (set when it was copied over a valid band, cleared on a resize or a skipped frame). */
	private boolean valid, prevDrawn;
	private int lastW = -1, lastH = -1;

	LodTaa(long ctx) {
		this.ctx = ctx;
		System.out.printf("mcopt-lod: far terrain temporal filter on (alpha %.2f, box growth %.2f)%n", ALPHA, GROW);
	}

	private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return Linker.nativeLinker().downcallHandle(symbol, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
	}

	/** Top of the level: the previous frame's far band (rows r0..r1 of last frame) into the history. */
	void beginLevel(long enc, long colorTex, int width, int height, int r0, int r1) {
		boolean resized = width != this.lastW || height != this.lastH;
		this.lastW = width;
		this.lastH = height;
		r0 = Math.clamp(r0, 0, height - 1);
		r1 = Math.clamp(r1, r0, height - 1);
		int made;
		try {
			made = (int) HISTORY.invokeExact(this.ctx, enc, colorTex, this.prevDrawn ? r0 : 0, this.prevDrawn ? r1 - r0 + 1 : 0);
		} catch (Throwable e) {
			throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
		}
		this.valid = this.prevDrawn && !resized && made == 0;
		this.prevDrawn = false;
	}

	/** Right before the mesh's draw: this frame's constants for the fragment stage. farStart: blocks (cylindrical). */
	void beforeDraw(double cx, double cy, double cz, float farStart) {
		long f = this.frameBuf;
		this.prevVP.getToAddress(f);
		boolean teleported = Math.abs(cx - this.px) + Math.abs(cy - this.py) + Math.abs(cz - this.pz) > 32;
		MemoryUtil.memPutFloat(f + 64, (float) (cx - this.px));
		MemoryUtil.memPutFloat(f + 68, (float) (cy - this.py));
		MemoryUtil.memPutFloat(f + 72, (float) (cz - this.pz));
		MemoryUtil.memPutFloat(f + 76, this.valid && !teleported ? 1 : 0);
		MemoryUtil.memPutFloat(f + 80, ALPHA);
		MemoryUtil.memPutFloat(f + 84, GROW);
		MemoryUtil.memPutFloat(f + 88, farStart);
		MemoryUtil.memPutFloat(f + 92, 0);
		try {
			SET.invokeExact(f, FRAME_BYTES);
		} catch (Throwable e) {
			throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
		}
	}

	/** After the draw: this frame is the next one's history (its camera is the one to reproject into). */
	void afterDraw(Matrix4f viewProj, double cx, double cy, double cz) {
		this.prevVP.set(viewProj);
		this.px = cx;
		this.py = cy;
		this.pz = cz;
		this.prevDrawn = true;
	}
}
