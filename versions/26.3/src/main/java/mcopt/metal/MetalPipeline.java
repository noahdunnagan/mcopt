package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import mcopt.metal.Msl.Translated;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * A pipeline is the frontend's SPIR-V run through SPIRV-Cross into Metal Shading Language, compiled by Metal.
 * Resource slots are fixed: uniform i lives in buffer/texture/sampler slot i, push constants in slot 26, vertex
 * buffers from slot 27. Vertex Y is flipped so render targets keep the same row order as OpenGL and Vulkan.
 */
final class MetalPipeline implements BackendRenderPipeline {
	/** Metal bakes the depth attachment format into the pipeline, so like Vulkan there's one for passes with depth and one without. */
	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	/** MTLCompareFunction of depthState, for MetalProbe. */
	final int depthCompare;
	/** Sodium solid/cutout terrain only, with MetalTerrain on: the same pipeline fetching its own vertices; 0 otherwise. */
	final long pulled;
	private final MetalEncoder encoder;
	private boolean closed;

	private MetalPipeline(MetalEncoder encoder, long withDepth, long withoutDepth, long pulled, long depthState, int depthCompare, CreateInfo info) {
		this.encoder = encoder;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.pulled = pulled;
		this.depthState = depthState;
		this.depthCompare = depthCompare;
		this.name = info.name();
		this.uniforms = info.uniforms();
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.primitiveTopology());
	}

	/** Time spent creating pipelines so far, by step, for the bench's startup breakdown (read reflectively). */
	private static long compiled, translateNs, libraryNs, stateNs;

	static synchronized Map<String, Object> compileStats() {
		return Map.of("count", compiled, "translateMs", translateNs / 1e6, "libraryMs", libraryNs / 1e6, "stateMs", stateNs / 1e6, "mslCacheHits", mslHits, "mslCacheMismatches", mslMismatches);
	}

	static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info) {
		long ctx = encoder.ctx;
		long t0 = System.nanoTime(), t1 = t0, t2 = t0;
		CreateInfo.Shader vertex = shader(info, ShaderType.VERTEX), fragment = shader(info, ShaderType.FRAGMENT);
		long vlib = 0, flib = 0, plib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			Translated v = translate(vertex.module(), info.uniforms().size());
			Translated f = translate(fragment.module(), info.uniforms().size());
			if (DUMP_DIR != null) dump(info.name(), v, f);
			String defines = BUILTIN_MSL ? SODIUM_TERRAIN.get(info.name()) : null;
			if (defines != null) {
				v = new Translated(resource("sodium_terrain.vs.metal"), v.entry());
				f = new Translated(defines + resource("sodium_terrain.fs.metal"), f.entry());
			}
			if (OVERRIDE_DIR != null) {
				v = override(info.name() + ".vs.metal", v);
				f = override(info.name() + ".fs.metal", f);
			}
			if (UNCACHED) {
				v = new Translated("// " + UNCACHED_NONCE + "\n" + v.msl(), v.entry());
				f = new Translated("// " + UNCACHED_NONCE + "\n" + f.msl(), f.entry());
			}
			t1 = System.nanoTime();
			vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(v.msl())), err, 4096);
			if (vlib == 0) throw new IllegalStateException(info.name() + " vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl());
			flib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(f.msl())), err, 4096);
			if (flib == 0) throw new IllegalStateException(info.name() + " fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl());
			t2 = System.nanoTime();
			long vname = MemoryUtil.memAddress(stack.UTF8(v.entry())), fname = MemoryUtil.memAddress(stack.UTF8(f.entry()));
			DepthStencilState depth = info.depthStencilState();
			long withDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, info, true, true), err, 4096);
			if (withDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			long withoutDepth = 0;
			if (depth == null) {
				withoutDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, info, false, true), err, 4096);
				if (withoutDepth == 0) throw new IllegalStateException(info.name() + ": " + MemoryUtil.memUTF8(err));
			}
			long pulled = 0;
			if (defines != null && MetalTerrain.OCC && !info.name().contains("translucent")) {
				// Translucent stays on Sodium's own draws: its quads are drawn in sorted index-buffer order.
				String twin = "#define PULLED\n" + resource("sodium_terrain.vs.metal");
				plib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(twin)), err, 4096);
				if (plib == 0) throw new IllegalStateException(info.name() + " pulled vertex: " + MemoryUtil.memUTF8(err));
				pulled = Native.pipelineNew(ctx, plib, vname, flib, fname, describe(stack, info, true, false), err, 4096);
				if (pulled == 0) throw new IllegalStateException(info.name() + " pulled: " + MemoryUtil.memUTF8(err));
			}
			int compare = depth == null ? 7 : MetalConst.compare(depth.depthTest());
			long depthState = Native.depthStateNew(ctx, compare, depth != null && depth.writeDepth() ? 1 : 0);
			MetalPipeline pipeline = new MetalPipeline(encoder, withDepth, withoutDepth, pulled, depthState, compare, info);
			return pipeline;
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
			if (plib != 0) Native.release(plib);
			long t3 = System.nanoTime();
			synchronized (MetalPipeline.class) {
				compiled++;
				translateNs += t1 - t0;
				libraryNs += t2 - t1;
				stateNs += t3 - t2;
			}
		}
	}

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

	/** -Dmcopt.metal.builtinMsl=false compiles Sodium's terrain from its own SPIR-V again, for A/B runs. */
	private static final boolean BUILTIN_MSL = Boolean.parseBoolean(System.getProperty("mcopt.metal.builtinMsl", "true"));
	/**
	 * Pipelines that use the hand-written MSL in resources/mcopt/metal/msl, with the ALPHA_CUTOUT define Sodium's
	 * ShaderChunkRenderer gives each. Only these exact names: Sodium's OIT variants are named differently and keep their SPIR-V.
	 */
	private static final Map<String, String> SODIUM_TERRAIN = Map.of(
		"sodium:pipeline/solid_terrain", "",
		"sodium:pipeline/cutout_terrain", "#define ALPHA_CUTOUT 0.5\n",
		"sodium:pipeline/translucent_terrain", "#define ALPHA_CUTOUT 0.01\n");

	private static String resource(String name) {
		try (InputStream in = MetalPipeline.class.getResourceAsStream("/mcopt/metal/msl/" + name)) {
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

	private static CreateInfo.Shader shader(CreateInfo info, ShaderType type) {
		return info.shaders().stream().filter(s -> s.module().type() == type).findFirst()
			.orElseThrow(() -> new IllegalStateException(info.name() + " has no " + type + " shader"));
	}

	/** Packs the pipeline layout into the int array mc_pipeline_new reads (see mcmetal.m); no vertex input for shaders that pull their own. */
	private static long describe(MemoryStack stack, CreateInfo info, boolean hasDepth, boolean vertexInput) {
		IntBuffer d = stack.mallocInt(5 + info.vertexBuffers().size() * 3 + info.attribBindings().size() * 4 + info.colorTargetStates().size() * 9);
		List<CreateInfo.VertexBuffer> buffers = vertexInput ? info.vertexBuffers() : List.of();
		List<CreateInfo.AttribBinding> attributes = vertexInput ? info.attribBindings() : List.of();
		d.put(buffers.size());
		for (CreateInfo.VertexBuffer vb : buffers) d.put(MetalConst.VERTEX_BUFFER_BASE + vb.bufferSlot()).put(vb.stride()).put(vb.stepRate());
		d.put(attributes.size());
		for (CreateInfo.AttribBinding a : attributes) {
			d.put(a.location()).put(MetalConst.VERTEX_BUFFER_BASE + a.bufferSlot()).put(a.offset()).put(MetalConst.vertexFormat(a.format()));
		}
		d.put(info.colorTargetStates().size());
		for (@Nullable ColorTargetState c : info.colorTargetStates()) {
			if (c == null) {
				d.put(new int[9]);
				continue;
			}
			// MTLColorWriteMask is ABGR-ordered (red = 8), renderpearl's is RGBA-ordered (red = 1).
			int mask = (c.writeRed() ? 8 : 0) | (c.writeGreen() ? 4 : 0) | (c.writeBlue() ? 2 : 0) | (c.writeAlpha() ? 1 : 0);
			d.put(MetalConst.pixelFormat(c.format())).put(mask);
			if (c.blendFunction().isPresent()) {
				var b = c.blendFunction().get();
				d.put(1).put(MetalConst.blendFactor(b.color().sourceFactor())).put(MetalConst.blendFactor(b.color().destFactor())).put(MetalConst.blendOp(b.color().op()))
					.put(MetalConst.blendFactor(b.alpha().sourceFactor())).put(MetalConst.blendFactor(b.alpha().destFactor())).put(MetalConst.blendOp(b.alpha().op()));
			} else {
				d.put(new int[] {0, 1, 0, 0, 1, 0, 0});
			}
		}
		d.put(hasDepth ? MetalConst.pixelFormat(com.mojang.renderpearl.api.GpuFormat.D32_FLOAT) : 0);
		d.put(MetalConst.topologyClass(info.primitiveTopology()));
		return MemoryUtil.memAddress(d.flip());
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
		try (java.io.InputStream in = MetalPipeline.class.getResourceAsStream("MetalPipeline.class")) {
			byte[] code = in.readAllBytes();
			return "mcopt-msl-1|" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(code))
				+ "|" + org.lwjgl.Version.getVersion();
		} catch (Exception e) {
			throw new IllegalStateException("mslCache salt", e);
		}
	}
	static int mslHits, mslMisses;

	private static Translated translate(SpvModule module, int uniformCount) {
		if (!MSL_CACHE) return translateNow(module, uniformCount);
		String key;
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(MSL_KEY_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			md.update((byte) module.type().ordinal());
			md.update(java.nio.ByteBuffer.allocate(4).putInt(uniformCount).array());
			md.update(module.spv().duplicate());
			key = java.util.HexFormat.of().formatHex(md.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			return translateNow(module, uniformCount);
		}
		java.nio.file.Path file = MSL_DIR.resolve(key);
		try {
			String text = java.nio.file.Files.readString(file);
			int nl = text.indexOf('\n');
			if (nl > 0) {
				mslHits++;
				Translated hit = new Translated(text.substring(nl + 1), text.substring(0, nl));
				if (MSL_MODE.equals("verify") && !hit.equals(translateNow(module, uniformCount))) mslMismatches++;
				return hit;
			}
		} catch (java.io.IOException missing) {
			// not cached yet
		}
		Translated t = translateNow(module, uniformCount);
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

	private static Translated translateNow(SpvModule module, int uniformCount) {
		return Msl.translate(module.spv().asIntBuffer(), module.type() == ShaderType.VERTEX, uniformCount);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.withDepth);
			if (this.withoutDepth != 0) this.encoder.releaseLater(this.withoutDepth);
			if (this.pulled != 0) this.encoder.releaseLater(this.pulled);
			this.encoder.releaseLater(this.depthState);
		}
	}
}
