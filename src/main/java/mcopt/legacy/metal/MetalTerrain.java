package mcopt.legacy.metal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import mcopt.legacy.Config;
import mcopt.legacy.mixin.ChunkRenderManagerAccess;
import mcopt.legacy.mixin.GameRendererAccess;
import mcopt.legacy.mixin.WorldRendererAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexBuffer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkAssemblyHelper;
import net.minecraft.util.math.BlockPos;

/**
 * The opaque chunk layers (solid, cutout mipped, cutout) drawn by Metal instead of OpenGL; see src/main/native/metal.m.
 * Chunk meshes of those layers go into Metal buffers instead of GL vertex buffers when they're uploaded, the block
 * atlas is mirrored into a Metal texture as the game uploads it, and WorldRenderer.renderLayer for the three layers is
 * replaced by one Metal pass composited into the game's framebuffer. Water, entities and everything else stay on GL.
 * <p>
 * On by default on macOS; {@code metal=false} in config/mcopt.properties (or {@code -Dmcopt.metal=false}) turns it off.
 * Where Metal can't start, the game draws its terrain as before.
 */
public final class MetalTerrain {
	/** True once Metal started; chunk uploads and the opaque layers then go through here. */
	public static boolean active;
	private static boolean tried;

	// a quad as the game builds it: 4 BLOCK vertices of 28 bytes (position float3, color, uv float2, lightmap short2)
	private static final int SRC_QUAD_INTS = 28;
	// and as Metal draws it: 4 vertices of 16 bytes (position ushort3 in 1/2048 blocks from 8 below the section's
	// origin, the lightmap uv as 2 bytes, color, uv ushort2 in 1/65536)
	private static final int QUAD_BYTES = 4 * 16;
	private static final int QUAD_INTS = QUAD_BYTES / 4;
	private static int[] scratch = new int[0];
	private static final int[] packed = new int[QUAD_INTS];
	private static byte[] facing = new byte[0];
	private static int[] order = new int[0];
	private static final int UNIT_QUADS = Math.max(1, Math.min(64, Integer.getInteger("mcopt.metal.unit", 64)));
	private static final int UNIT_BYTES = 32;
	private static final int UNIT_INTS = UNIT_BYTES / 4;
	// vertex pages of 2^21 quads (128 MiB); a range is page << 21 | quad
	private static final int PAGE_SHIFT = 21;
	private static final int PAGE_QUADS = 1 << PAGE_SHIFT;
	private static final int MIN_CLASS = 4;

	private static final List<ByteBuffer> pages = new ArrayList<>();
	private static long[] pageAddr = new long[8];
	private static int pageTop = PAGE_QUADS;  // the last page's first free quad
	// free ranges per size class (2^c quads)
	private static final int[][] free = new int[PAGE_SHIFT + 1][];
	private static final int[] freeCount = new int[PAGE_SHIFT + 1];
	private static long liveQuads, reservedQuads;

	private static final ByteBuffer[] units = new ByteBuffer[3];
	private static final java.nio.IntBuffer[] unitInts = new java.nio.IntBuffer[3];
	private static int[] lightPixels;
	private static int failures;

	private MetalTerrain() {
	}

	static native String nInit(String shaderSource);

	static native String nDevice();

	static native ByteBuffer nPage(int bytes, long[] addr);

	static native ByteBuffer nUnits(int layer, int bytes);

	static native void nAtlas(int glId, int maxLevel, int width, int height);

	static native void nAtlasSub(int[][] levels, int width, int height, int x, int y);

	static native int nSubmit(int solid, int mipped, int cutout, int[] lightPixels, double camX, double camY, double camZ);

	static native int nFinish(boolean draw);

	static native int nRetire();

	static native void nShutdown();

	static native void nStats(double[] out);

	static native void nDump(String path);

	/** Dev: writes the next frame's terrain draw to a file, for the standalone bench. */
	public static void dump(String path) {
		if (active) nDump(path);
	}

	// The dev flags in bits 0-23 (native masks them with 0xFFFFFF) and UNIT_QUADS above them: a flag past bit 23 would
	// land in the unit size. Native's UNIT_QUADS is the most a unit may hold; keep this side's at or under it.
	static native void nDev(int flags);

	/** Starts Metal on the first call (client thread, GL context current). */
	public static boolean ensure() {
		if (tried) return active;
		tried = true;
		if (!Config.flag("metal", true)) {
			System.out.println("[mcopt] Metal terrain: off (config)");
			return false;
		}
		String why = load();
		if (why == null) {
			try {
				why = nInit(resource("/mcopt/metal/terrain.metal"));
			} catch (Throwable e) {
				why = e.toString();
			}
		}
		if (why != null) {
			System.out.println("[mcopt] Metal terrain: off (" + why + ")");
			return false;
		}
		active = true;
		// the terrain goes through the VBO chunk path (BuiltChunk's VertexBuffers), which vanilla leaves off by default
		MinecraftClient.getInstance().options.vbo = true;
		nDev((Boolean.getBoolean("mcopt.metal.scissor") ? 1 : 0) | (Boolean.getBoolean("mcopt.metal.solidOnly") ? 2 : 0)
			| (Boolean.getBoolean("mcopt.metal.noTris") ? 4 : 0) | (Boolean.getBoolean("mcopt.metal.finish") ? 8 : 0)
			| (Boolean.getBoolean("mcopt.metal.noCull") ? 16 : 0) | (Boolean.getBoolean("mcopt.metal.onePass") ? 32 : 0)
			| (Boolean.getBoolean("mcopt.metal.noDepthOut") ? 64 : 0) | (Boolean.getBoolean("mcopt.metal.noAlphaTest") ? 128 : 0)
			| (Boolean.getBoolean("mcopt.metal.depthPass") ? 256 : 0)
			| (Boolean.getBoolean("mcopt.metal.privTargets") ? 512 : 0)
			| (Boolean.getBoolean("mcopt.metal.minCutout") ? 1024 : 0) | (Boolean.getBoolean("mcopt.metal.stages") ? 2048 : 0)
			| (Boolean.getBoolean("mcopt.metal.noComposite") ? 4096 : 0) | (Boolean.getBoolean("mcopt.metal.waitGpu") ? 8192 : 0)
			| (Boolean.getBoolean("mcopt.metal.raceTest") ? 16384 : 0) | (Boolean.getBoolean("mcopt.metal.noSync") ? 32768 : 0)
			| UNIT_QUADS << 24);
		System.out.println("[mcopt] Metal terrain: on (" + nDevice() + ")");
		return true;
	}

	private static String load() {
		String os = System.getProperty("os.name", "");
		if (!os.startsWith("Mac")) return "macOS only";
		try (InputStream in = MetalTerrain.class.getResourceAsStream("/mcopt/native/libmcoptmetal.dylib")) {
			if (in == null) return "library missing from the jar";
			Path tmp = Files.createTempFile("mcoptmetal", ".dylib");
			try {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				System.load(tmp.toAbsolutePath().toString());
			} finally {
				Files.deleteIfExists(tmp);  // loaded and mapped: the file isn't needed any more
			}
			return null;
		} catch (Throwable e) {
			return e.toString();
		}
	}

	private static String resource(String name) throws java.io.IOException {
		try (InputStream in = MetalTerrain.class.getResourceAsStream(name)) {
			if (in == null) throw new java.io.IOException(name + " missing from the jar");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	// ---- block atlas ----

	/** Metal draws the terrain: started, and VBOs still on (turning them off in the video settings falls back to GL). */
	public static boolean drawing() {
		return active && com.mojang.blaze3d.platform.GLX.supportsVbo();
	}

	/** The world is changing: waits out the frame in flight and applies the changes held back for it. */
	public static void retire() {
		if (active) finish(false);
	}

	/** SpriteAtlasTexture allocated its GL texture: levels 0..maxLevel of width x height. */
	public static void atlas(int glId, int maxLevel, int width, int height) {
		if (ensure()) nAtlas(glId, maxLevel, width, height);
	}

	/** TextureUtil.method_7027: pixels going into the bound texture; mirrored if that's the atlas. */
	public static void atlasSub(int[][] levels, int width, int height, int x, int y) {
		if (active) nAtlasSub(levels, width, height, x, y);
	}

	// ---- chunk meshes ----

	/**
	 * A finished opaque-layer mesh for a section's buffer: copied into a Metal page instead of the GL buffer, its quads
	 * grouped by facing (+X, -X, +Y, -Y, +Z, -Z, other) so a frame can skip the groups that face away from the camera.
	 */
	public static void upload(BuiltChunk chunk, int layer, VertexBuffer target, BufferBuilder builder) {
		if (target == null) return;
		MetalMesh mesh = (MetalMesh) target;
		ByteBuffer data = builder.getByteBuffer();
		int quads = data.limit() / (SRC_QUAD_INTS * 4);
		builder.reset();
		if (quads == 0) {
			release(mesh);
			return;
		}
		if (units[layer] == null && !grow(layer, 1 << 21)) {
			release(mesh);
			return;
		}
		int range = mesh.mcopt$range();
		// while the GPU draws a submitted frame, its ranges and slots stay as they are: a new mesh gets a new range
		if (range < 0 || quads > 1 << mesh.mcopt$class() || inFlight) {
			release(mesh);
			int c = Math.max(MIN_CLASS, 32 - Integer.numberOfLeadingZeros(quads - 1));
			range = allocate(c);
			if (range < 0) return;
			mesh.mcopt$set(range, c, quads);
			liveQuads += quads;
		} else {
			liveQuads += quads - mesh.mcopt$count();
			mesh.mcopt$set(range, mesh.mcopt$class(), quads);
		}

		int ints = quads * SRC_QUAD_INTS;
		if (scratch.length < ints) scratch = new int[Math.max(ints, scratch.length * 2)];
		if (facing.length < quads) facing = new byte[Math.max(quads, facing.length * 2)];
		ByteBuffer src = data.duplicate().order(ByteOrder.nativeOrder());
		src.position(0).limit(quads * SRC_QUAD_INTS * 4);
		src.asIntBuffer().get(scratch, 0, ints);
		int[] groups = mesh.mcopt$groups();
		java.util.Arrays.fill(groups, 0, 7, 0);
		float[] plane = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int q = 0; q < quads; q++) {
			int v = q * SRC_QUAD_INTS;
			float x0 = Float.intBitsToFloat(scratch[v]), y0 = Float.intBitsToFloat(scratch[v + 1]), z0 = Float.intBitsToFloat(scratch[v + 2]);
			float ax = Float.intBitsToFloat(scratch[v + 7]) - x0, ay = Float.intBitsToFloat(scratch[v + 8]) - y0;
			float az = Float.intBitsToFloat(scratch[v + 9]) - z0;
			float bx = Float.intBitsToFloat(scratch[v + 14]) - x0, by = Float.intBitsToFloat(scratch[v + 15]) - y0;
			float bz = Float.intBitsToFloat(scratch[v + 16]) - z0;
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float mx = Math.abs(nx), my = Math.abs(ny), mz = Math.abs(nz);
			int g;
			float at;
			if (my <= mx * 1e-4f && mz <= mx * 1e-4f && mx > 0) {
				g = nx > 0 ? 0 : 1;
				at = x0;
			} else if (mx <= my * 1e-4f && mz <= my * 1e-4f && my > 0) {
				g = ny > 0 ? 2 : 3;
				at = y0;
			} else if (mx <= mz * 1e-4f && my <= mz * 1e-4f && mz > 0) {
				g = nz > 0 ? 4 : 5;
				at = z0;
			} else {
				g = 6;
				at = 0;
			}
			facing[q] = (byte) g;
			groups[g]++;
			if (g < 6) plane[g] = (g & 1) == 0 ? Math.min(plane[g], at) : Math.max(plane[g], at);
		}
		for (int g = 0; g < 6; g++) groups[7 + g] = Float.floatToRawIntBits(plane[g]);

		int[] at = new int[7];
		for (int g = 1; g < 7; g++) at[g] = at[g - 1] + groups[g - 1];
		if (order.length < quads) order = new int[Math.max(quads, order.length * 2)];
		java.nio.IntBuffer page = pages.get(range >>> PAGE_SHIFT).duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		int first = (range & (PAGE_QUADS - 1)) * QUAD_INTS;
		for (int q = 0; q < quads; q++) {
			int k = at[facing[q]]++;
			order[k] = q;
			for (int c = 0, v = q * SRC_QUAD_INTS, w = 0; c < 4; c++, v += 7, w += 4) {
				int lm = scratch[v + 6];
				packed[w] = pack(scratch[v]) | pack(scratch[v + 1]) << 16;
				packed[w + 1] = pack(scratch[v + 2]) | Math.min(lm & 0xFFFF, 255) << 16 | Math.min(lm >>> 16, 255) << 24;
				packed[w + 2] = scratch[v + 3];
				packed[w + 3] = uv(scratch[v + 4]) | uv(scratch[v + 5]) << 16;
			}
			page.position(first + k * QUAD_INTS);
			page.put(packed, 0, QUAD_INTS);
		}

		// the draw units, with the boxes for the occlusion test
		int n = 0;
		for (int g = 0; g < 7; g++) n += (groups[g] + UNIT_QUADS - 1) / UNIT_QUADS;
		int[] meshUnits = mesh.mcopt$units();
		if (meshUnits.length < n * UNIT_INTS) {
			meshUnits = new int[n * UNIT_INTS];
			mesh.mcopt$units(meshUnits);
		}
		long base = pageAddr[range >>> PAGE_SHIFT] + (long) (range & (PAGE_QUADS - 1)) * QUAD_BYTES;
		BlockPos pos = chunk.getPos();
		int u = 0, start = 0;
		for (int g = 0; g < 7; g++) {
			for (int q = 0; q < groups[g]; q += UNIT_QUADS) {
				float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
				float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
				for (int k = start + q, end = start + Math.min(groups[g], q + UNIT_QUADS); k < end; k++) {
					int v = order[k] * SRC_QUAD_INTS;
					for (int c = 0; c < 4; c++, v += 7) {
						float x = Float.intBitsToFloat(scratch[v]), y = Float.intBitsToFloat(scratch[v + 1]), z = Float.intBitsToFloat(scratch[v + 2]);
						x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
						x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
					}
				}
				long addr = base + (long) (start + q) * QUAD_BYTES;
				int count = Math.min(UNIT_QUADS, groups[g] - q);
				meshUnits[u] = (int) addr;
				meshUnits[u + 1] = (int) (addr >>> 32);
				meshUnits[u + 2] = pos.getX();
				meshUnits[u + 3] = pos.getY();
				meshUnits[u + 4] = pos.getZ();
				meshUnits[u + 5] = range + start + q;
				meshUnits[u + 6] = count | g << 8 | quarter(x0, -1) << 16 | quarter(y0, -1) << 24;
				meshUnits[u + 7] = quarter(z0, -1) | quarter(x1, 1) << 8 | quarter(y1, 1) << 16 | quarter(z1, 1) << 24;
				u += UNIT_INTS;
			}
			start += groups[g];
		}

		if (inFlight) {
			int uploaded = range;
			afterFrame.add(() -> {
				if (mesh.mcopt$range() == uploaded) place(chunk, layer, mesh);
			});
		} else {
			place(chunk, layer, mesh);
		}
	}

	/** Puts a freshly uploaded mesh's units into the layer's unit buffer, drawn when the section's state has the layer. */
	private static void place(BuiltChunk chunk, int layer, MetalMesh mesh) {
		int[] groups = mesh.mcopt$groups();
		int n = 0;
		for (int g = 0; g < 7; g++) n += (groups[g] + UNIT_QUADS - 1) / UNIT_QUADS;
		int slot = mesh.mcopt$slot();
		if (slot >= 0 && n > 1 << mesh.mcopt$slotClass()) {
			freeSlots(mesh);
			slot = -1;
		}
		if (slot < 0) {
			int c = 32 - Integer.numberOfLeadingZeros(n - 1);
			slot = allocateSlots(layer, c);
			if (slot < 0) return;
			mesh.mcopt$slots(layer, slot, c, n, false);
		} else {
			writeSlots(layer, slot + n, null, mesh.mcopt$unitCount() - n);
			mesh.mcopt$slots(layer, slot, mesh.mcopt$slotClass(), n, false);
		}
		activate(mesh, !chunk.method_10170().method_10149(RenderLayer.values()[layer]));
	}

	// ---- the unit buffers: per layer, every uploaded mesh's units at fixed slots, culled and drawn by the GPU ----

	private static final int[][][] freeSlots = new int[3][32][];
	private static final int[][] freeSlotCount = new int[3][32];
	private static final int[] slotTop = new int[3];
	private static final int[] zeros = new int[UNIT_INTS * 256];
	// sections whose state (which layers they have) changed, from any thread; applied before the next frame's draw
	private static final java.util.concurrent.ConcurrentLinkedQueue<BuiltChunk> changed = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** BuiltChunk got a new ChunkAssemblyHelper (built, or reset when it moves or is deleted). */
	public static void changed(BuiltChunk chunk) {
		if (active) changed.add(chunk);
	}

	/** Writes a mesh's units into its slots (on) or empties the slots (off). */
	private static void activate(MetalMesh mesh, boolean on) {
		int slot = mesh.mcopt$slot();
		if (slot < 0) return;
		int layer = mesh.mcopt$layer(), n = mesh.mcopt$unitCount();
		writeSlots(layer, slot, on ? mesh.mcopt$units() : null, n);
		mesh.mcopt$slots(layer, slot, mesh.mcopt$slotClass(), n, on);
	}

	/** n units from src (null: empty units) into the layer's buffer at slot. */
	private static void writeSlots(int layer, int slot, int[] src, int n) {
		if (n <= 0) return;
		java.nio.IntBuffer b = unitInts[layer];
		b.position(slot * UNIT_INTS);
		if (src != null) {
			b.put(src, 0, n * UNIT_INTS);
			return;
		}
		for (int left = n * UNIT_INTS; left > 0; left -= zeros.length) b.put(zeros, 0, Math.min(left, zeros.length));
	}

	private static int allocateSlots(int layer, int c) {
		if (freeSlotCount[layer][c] > 0) return freeSlots[layer][c][--freeSlotCount[layer][c]];
		int size = 1 << c, top = slotTop[layer];
		long need = (long) (top + size) * UNIT_BYTES;
		if (need > Integer.MAX_VALUE) return -1;
		if (units[layer].capacity() < need && !grow(layer, (int) Math.min(Integer.MAX_VALUE, Math.max(need, units[layer].capacity() * 2L)))) return -1;
		slotTop[layer] = top + size;
		return top;
	}

	private static void freeSlots(MetalMesh mesh) {
		int slot = mesh.mcopt$slot();
		if (slot < 0) return;
		freeSlots(mesh.mcopt$layer(), slot, mesh.mcopt$slotClass(), mesh.mcopt$unitCount());
		mesh.mcopt$slots(mesh.mcopt$layer(), -1, 0, 0, false);
	}

	private static void freeSlots(int layer, int slot, int c, int n) {
		writeSlots(layer, slot, null, n);
		int[] list = freeSlots[layer][c];
		if (list == null) list = freeSlots[layer][c] = new int[64];
		if (freeSlotCount[layer][c] == list.length) list = freeSlots[layer][c] = java.util.Arrays.copyOf(list, list.length * 2);
		list[freeSlotCount[layer][c]++] = slot;
	}

	/** A section-relative coordinate (float bits) in 1/2048 blocks from 8 below the section's origin, as a ushort. */
	private static int pack(int bits) {
		return Math.max(0, Math.min(65535, Math.round((Float.intBitsToFloat(bits) + 8) * 2048)));
	}

	/** An atlas coordinate (float bits) in 1/65536, as a ushort. */
	private static int uv(int bits) {
		return Math.max(0, Math.min(65535, Math.round(Float.intBitsToFloat(bits) * 65536)));
	}

	/** A bound in quarter blocks from -16, one quarter further out (side -1 low, 1 high), clamped to a byte. */
	private static int quarter(float v, int side) {
		double q = (v + 16.0) * 4.0;
		int r = side < 0 ? (int) Math.floor(q) - 1 : (int) Math.ceil(q) + 1;
		return Math.max(0, Math.min(255, r));
	}

	/** The buffer was deleted or emptied: its range goes back to the free lists. */
	public static void release(MetalMesh mesh) {
		int range = mesh.mcopt$range();
		if (range < 0) return;
		int c = mesh.mcopt$class(), quads = mesh.mcopt$count();
		mesh.mcopt$set(-1, 0, 0);
		liveQuads -= quads;
		int slot = mesh.mcopt$slot(), layer = mesh.mcopt$layer(), sc = mesh.mcopt$slotClass(), su = mesh.mcopt$unitCount();
		mesh.mcopt$slots(layer, -1, 0, 0, false);
		if (inFlight) {
			// the GPU may be reading them: freed once it's done
			afterFrame.add(() -> {
				if (slot >= 0) freeSlots(layer, slot, sc, su);
				freeRange(range, c);
			});
			return;
		}
		if (slot >= 0) freeSlots(layer, slot, sc, su);
		freeRange(range, c);
	}

	private static void freeRange(int range, int c) {
		if (free[c] == null) free[c] = new int[64];
		if (freeCount[c] == free[c].length) free[c] = java.util.Arrays.copyOf(free[c], freeCount[c] * 2);
		free[c][freeCount[c]++] = range;
	}

	private static int allocate(int c) {
		if (freeCount[c] > 0) {
			return free[c][--freeCount[c]];
		}
		int size = 1 << c;
		if (pageTop + size > PAGE_QUADS) {
			if (pages.size() >= 1 << (31 - PAGE_SHIFT)) return -1;
			long[] addr = new long[1];
			ByteBuffer page = nPage(PAGE_QUADS * QUAD_BYTES, addr);
			if (page == null) {
				System.out.println("[mcopt] Metal terrain: no memory for another vertex page");
				return -1;
			}
			if (pages.size() == pageAddr.length) pageAddr = java.util.Arrays.copyOf(pageAddr, pageAddr.length * 2);
			pageAddr[pages.size()] = addr[0];
			pages.add(page);
			pageTop = 0;
		}
		int range = (pages.size() - 1) << PAGE_SHIFT | pageTop;
		pageTop += size;
		reservedQuads += size;
		return range;
	}

	// ---- the frame ----

	/**
	 * Draws the three opaque layers of the visible sections with Metal and composites them into the framebuffer, in place
	 * of WorldRenderer.renderLayer(SOLID). The other two layers' renderLayer calls then do nothing.
	 */
	public static void drawOpaque(WorldRenderer worldRenderer) {
		if (!submitted) {
			// not submitted earlier in the frame (see MetalSubmitMixin): submit now
			ChunkRenderManagerAccess view = (ChunkRenderManagerAccess) ((WorldRendererAccess) worldRenderer).mcopt$chunkRenderManager();
			submit(view.mcopt$viewX(), view.mcopt$viewY(), view.mcopt$viewZ());
		}
		finish(true);
	}

	/**
	 * Starts this frame's opaque terrain on the GPU, with the camera at (x, y, z) and the GL camera, projection and fog
	 * state current now; drawOpaque later composites it once the GPU has it scheduled. Called once the frame's terrain state is set.
	 */
	public static void submit(double x, double y, double z) {
		if (!active) return;
		if (inFlight) finish(false);
		long t0 = System.nanoTime();
		for (int l = 0; l < 3; l++) {
			if (units[l] == null && !grow(l, 1 << 21)) return;
		}
		RenderLayer[] layers = RenderLayer.values();
		for (BuiltChunk chunk; (chunk = changed.poll()) != null; ) {
			ChunkAssemblyHelper helper = chunk.method_10170();
			for (int l = 0; l < 3; l++) {
				VertexBuffer buffer = chunk.method_10165(l);
				if (buffer == null) continue;
				MetalMesh mesh = (MetalMesh) buffer;
				boolean on = mesh.mcopt$range() >= 0 && !helper.method_10149(layers[l]);
				if (on != mesh.mcopt$active()) activate(mesh, on);
			}
		}
		if (lightPixels == null) {
			lightPixels = ((GameRendererAccess) MinecraftClient.getInstance().gameRenderer).mcopt$lightmapPixels();
		}
		long t1 = System.nanoTime();
		int err = nSubmit(slotTop[0], slotTop[1], slotTop[2], lightPixels, x, y, z);
		buildNanos += t1 - t0;
		drawNanos += System.nanoTime() - t1;
		if (err != 0) {
			if (failures++ < 5) System.out.println("[mcopt] Metal terrain: frame not drawn (" + err + ")");
			return;
		}
		inFlight = submitted = true;
		timedFrames++;
		timedUnits += slotTop[0] + slotTop[1] + slotTop[2];
		drawnQuads += liveQuads;
	}

	/**
	 * Composites the submitted frame when draw is set. Unless that composite went ahead of the GPU finishing (native
	 * returns 1; the next submit retires the frame), waits for the GPU and applies the changes held back meanwhile.
	 */
	private static void finish(boolean draw) {
		if (!inFlight) return;
		long t0 = System.nanoTime();
		int err = 0;
		if (submitted) {
			submitted = false;
			err = nFinish(draw);
		}
		if (err == 1) {
			// composited, the GPU still on it: the next submit (or retire) waits
			drawNanos += System.nanoTime() - t0;
			return;
		}
		if (err == 0) err = nRetire();
		drawNanos += System.nanoTime() - t0;
		inFlight = false;
		for (int k = 0; k < afterFrame.size(); k++) afterFrame.get(k).run();
		afterFrame.clear();
		if (err != 0 && failures++ < 5) System.out.println("[mcopt] Metal terrain: frame not drawn (" + err + ")");
	}

	// true from submit until the frame is retired (by the next submit, or retire): the GPU may be reading the pages and
	// unit buffers until then
	private static boolean inFlight;
	// true from submit to the composite
	private static boolean submitted;
	private static final List<Runnable> afterFrame = new ArrayList<>();

	// dev bench: opaque meshes uploaded and the time spent on them, read and reset by the fps bench per frame
	public static int benchUploads;
	public static long benchUploadNanos;

	// dev bench stats since the last memory() call
	private static long buildNanos, drawNanos, timedFrames, timedUnits, drawnQuads;

	private static boolean grow(int layer, int bytes) {
		ByteBuffer b = nUnits(layer, bytes);
		if (b == null) return false;
		units[layer] = b.order(ByteOrder.nativeOrder());
		unitInts[layer] = units[layer].asIntBuffer();
		return true;
	}

	/** For the dev bench log: vertex memory in use and reserved, and the frame times since the last call. */
	public static String memory() {
		long f = Math.max(1, timedFrames);
		double[] n = new double[18];
		nStats(n);
		String s = String.format("%.0f/%.0f MiB in %d pages, units %d, quads %dk, list %.2f ms, draw %.2f ms (encode %.2f, wait %.2f, gpu %.2f, first draw %.2f, "
				+ "composite %.2f), gpu units %.0f+%.0f / %.0f+%.0f / %.0f+%.0f", liveQuads * QUAD_BYTES / 1048576.0, reservedQuads * QUAD_BYTES / 1048576.0, pages.size(),
			timedUnits / f, drawnQuads / f / 1000, buildNanos / 1e6 / f, drawNanos / 1e6 / f, n[0] / f, n[1] / f, n[2] / f, n[10] / f, n[3] / f, n[4] / f, n[5] / f, n[6] / f, n[7] / f, n[8] / f, n[9] / f);
		if (n[11] + n[12] + n[13] > 0) s += String.format(", stages: cull %.2f, draw 1 vertex %.2f fragment %.2f, pyramid %.2f, draw 2 vertex %.2f fragment %.2f, quad slots %.0fk",
			n[11] / f, n[12] / f, n[13] / f, n[14] / f, n[15] / f, n[16] / f, n[17] / f / 1000);
		buildNanos = drawNanos = timedFrames = timedUnits = drawnQuads = 0;
		return s;
	}
}
