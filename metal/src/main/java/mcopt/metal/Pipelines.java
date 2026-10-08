package mcopt.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import mcopt.metal.Msl.Translated;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class Pipelines {
	interface Describer {
		long describe(MemoryStack stack, boolean hasDepth, boolean vertexInput);
	}

	record Handles(long withDepth, long withoutDepth, long pulled, long depthState, int depthCompare) {
	}

	private Pipelines() {
	}

	/** Time spent creating pipelines so far, by step, for the bench's startup breakdown (read reflectively). */
	private static long compiled, translateNs, libraryNs, stateNs;

	static synchronized Map<String, Object> compileStats() {
		return Map.of("count", compiled, "translateMs", translateNs / 1e6, "libraryMs", libraryNs / 1e6, "stateMs", stateNs / 1e6, "mslCacheHits", mslHits, "mslCacheMismatches", mslMismatches);
	}

	/**
	 * Pipelines that use the hand-written MSL in resources/mcopt/metal/msl, with the ALPHA_CUTOUT define Sodium's
	 * ShaderChunkRenderer gives each. Only these exact names: Sodium's OIT variants are named differently and keep their SPIR-V.
	 */
	private static final Map<String, String> SODIUM_TERRAIN = Map.of(
		"sodium:pipeline/solid_terrain", "",
		"sodium:pipeline/cutout_terrain", "#define ALPHA_CUTOUT 0.5\n",
		"sodium:pipeline/translucent_terrain", "#define ALPHA_CUTOUT 0.01\n");

	/** -Dmcopt.metal.builtinMsl=false compiles Sodium's terrain from its own SPIR-V again, for A/B runs. */
	private static final boolean BUILTIN_MSL = Boolean.parseBoolean(System.getProperty("mcopt.metal.builtinMsl", "true"));
	/** -Dmcopt.metal.dumpMsl=DIR writes every pipeline's generated Metal source there, for reading what the GPU actually runs. */
	private static final @Nullable String DUMP_DIR = System.getProperty("mcopt.metal.dumpMsl");
	/** -Dmcopt.metal.overrideMsl=DIR uses DIR/NAME.{vs,fs}.metal instead of the generated source when present: shader experiments without a rebuild. */
	private static final @Nullable String OVERRIDE_DIR = System.getProperty("mcopt.metal.overrideMsl");
	/**
	 * -Dmcopt.metal.uncachedMsl=true gives every source a per-launch prefix so Metal's on-disk shader cache misses: what the
	 * first launch after install or a macOS update pays (about +0.7 s of boot on the M4 mini).
	 */
	private static final boolean UNCACHED = Boolean.getBoolean("mcopt.metal.uncachedMsl");
	private static final long UNCACHED_NONCE = System.nanoTime();

	static boolean builtinTerrain(String name) {
		return BUILTIN_MSL && SODIUM_TERRAIN.containsKey(name);
	}

	static Handles build(MetalEncoder encoder, String name, ByteBuffer vertexSpv, ByteBuffer fragmentSpv, int uniformCount, Describer describe,
		boolean hasDepthState, int compare, boolean writeDepth) {
		long ctx = encoder.ctx;
		long t0 = System.nanoTime(), t1 = t0, t2 = t0;
		long vlib = 0, flib = 0, plib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			Translated v = translate(vertexSpv, true, uniformCount);
			Translated f = translate(fragmentSpv, false, uniformCount);
			if (DUMP_DIR != null) dump(name, v, f);
			String defines = BUILTIN_MSL ? SODIUM_TERRAIN.get(name) : null;
			if (defines != null) {
				v = new Translated(resource("sodium_terrain.vs.metal"), v.entry());
				f = new Translated(defines + resource("sodium_terrain.fs.metal"), f.entry());
			}
			if (OVERRIDE_DIR != null) {
				v = override(name + ".vs.metal", v);
				f = override(name + ".fs.metal", f);
			}
			if (UNCACHED) {
				v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl(), v.entry());
				f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl(), f.entry());
			}
			t1 = System.nanoTime();
			vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(v.msl())), err, 4096);
			if (vlib == 0) throw new IllegalStateException(name + " vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl());
			flib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(f.msl())), err, 4096);
			if (flib == 0) throw new IllegalStateException(name + " fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl());
			t2 = System.nanoTime();
			long vname = MemoryUtil.memAddress(stack.UTF8(v.entry())), fname = MemoryUtil.memAddress(stack.UTF8(f.entry()));
			long withDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe.describe(stack, true, true), err, 4096);
			if (withDepth == 0) throw new IllegalStateException(name + ": " + MemoryUtil.memUTF8(err));
			long withoutDepth = 0;
			if (!hasDepthState) {
				withoutDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe.describe(stack, false, true), err, 4096);
				if (withoutDepth == 0) throw new IllegalStateException(name + ": " + MemoryUtil.memUTF8(err));
			}
			long pulled = 0;
			if (defines != null && MetalTerrain.OCC && !name.contains("translucent")) {
				// Translucent stays on Sodium's own draws: its quads are drawn in sorted index-buffer order.
				String twin = "#define PULLED\n" + resource("sodium_terrain.vs.metal");
				plib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(twin)), err, 4096);
				if (plib == 0) throw new IllegalStateException(name + " pulled vertex: " + MemoryUtil.memUTF8(err));
				pulled = Native.pipelineNew(ctx, plib, vname, flib, fname, describe.describe(stack, true, false), err, 4096);
				if (pulled == 0) throw new IllegalStateException(name + " pulled: " + MemoryUtil.memUTF8(err));
			}
			int depthCompare = hasDepthState ? compare : 7;
			long depthState = Native.depthStateNew(ctx, depthCompare, hasDepthState && writeDepth ? 1 : 0);
			return new Handles(withDepth, withoutDepth, pulled, depthState, depthCompare);
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			if (plib != 0) Native.release(plib);
			long t3 = System.nanoTime();
			synchronized (Pipelines.class) {
				compiled++;
				translateNs += t1 - t0;
				libraryNs += t2 - t1;
				stateNs += t3 - t2;
			}
		}
	}

	private static String resource(String name) {
		try (InputStream in = Pipelines.class.getResourceAsStream("/mcopt/metal/msl/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String fileName(String pipelineName) {
		return pipelineName.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void dump(String name, Translated v, Translated f) {
		try {
			Files.createDirectories(Path.of(DUMP_DIR));
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".vs.metal"), v.msl());
			Files.writeString(Path.of(DUMP_DIR, fileName(name) + ".fs.metal"), f.msl());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Translated override(String name, Translated generated) {
		Path file = Path.of(OVERRIDE_DIR, fileName(name));
		if (!Files.exists(file)) return generated;
		try {
			System.out.println("mcopt-metal: using " + file);
			return new Translated(Files.readString(file), generated.entry());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * -Dmcopt.startup.mslCache=true: SPIRV-Cross's output for a module is a pure function of its SPIR-V, stage and uniform count
	 * (and of this mod's and SPIRV-Cross's versions), so it is kept on disk under <game dir>/mcopt-cache/msl/ and read back on the
	 * next launch instead of translated again (the translation is ~0.15-0.3 s of every startup on the main thread).
	 */
	private static final String MSL_MODE = System.getProperty("mcopt.startup.mslCache", "false");
	/** true: use the cache; verify: use it and also translate every hit, counting any difference (mslCacheMismatches). */
	private static final boolean MSL_CACHE = MSL_MODE.equals("true") || MSL_MODE.equals("verify");
	static int mslMismatches;
	private static final java.nio.file.Path MSL_DIR = MSL_CACHE ? (System.getProperty("mcopt.startup.mslCacheDir") != null
		? java.nio.file.Path.of(System.getProperty("mcopt.startup.mslCacheDir"))
		: net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mcopt-cache").resolve("msl")) : null;
	/** What the translation depends on besides its inputs: this class's code (options, bindings) and the SPIRV-Cross build. */
	private static final String MSL_KEY_SALT = MSL_CACHE ? mslSalt() : null;

	private static String mslSalt() {
		try (java.io.InputStream in = Pipelines.class.getResourceAsStream("Pipelines.class")) {
			byte[] code = in.readAllBytes();
			return "mcopt-msl-1|" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code))
				+ "|" + org.lwjgl.Version.getVersion();
		} catch (Exception e) {
			throw new IllegalStateException("mslCache salt", e);
		}
	}
	static int mslHits, mslMisses;

	private static Translated translate(ByteBuffer spv, boolean vertex, int uniformCount) {
		if (!MSL_CACHE) return Msl.translate(spv.asIntBuffer(), vertex, uniformCount);
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(MSL_KEY_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update((byte) (vertex ? 0 : 1));
			md.update(java.nio.ByteBuffer.allocate(4).putInt(uniformCount).array());
			md.update(spv.duplicate());
			key = java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			return Msl.translate(spv.asIntBuffer(), vertex, uniformCount);
		}
		java.nio.file.Path file = MSL_DIR.resolve(key);
		try {
			String text = java.nio.file.Files.readString(file);
			int nl = text.indexOf('\n');
			if (nl > 0) {
				mslHits++;
				Translated hit = new Translated(text.substring(nl + 1), text.substring(0, nl));
				if (MSL_MODE.equals("verify") && !hit.equals(Msl.translate(spv.asIntBuffer(), vertex, uniformCount))) mslMismatches++;
				return hit;
			}
		} catch (java.io.IOException missing) {
			// not cached yet
		}
		Translated t = Msl.translate(spv.asIntBuffer(), vertex, uniformCount);
		mslMisses++;
		try {
			java.nio.file.Files.createDirectories(MSL_DIR);
			java.nio.file.Path tmp = java.nio.file.Files.createTempFile(MSL_DIR, key, ".tmp");
			java.nio.file.Files.writeString(tmp, t.entry() + "\n" + t.msl());
			java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			// a cache that can't be written is just a slower launch
		}
		return t;
	}
}
