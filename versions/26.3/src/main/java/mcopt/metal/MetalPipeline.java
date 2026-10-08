package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
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
	final List<MetalUniform> slots;
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
		this.slots = info.uniforms().stream().map(u -> new MetalUniform(u.name(), switch (u.type()) {
			case UNIFORM_BUFFER -> MetalUniform.Kind.UNIFORM_BUFFER;
			case COMBINED_IMAGE_SAMPLER -> MetalUniform.Kind.SAMPLED_IMAGE;
			case TEXEL_BUFFER -> MetalUniform.Kind.TEXEL_BUFFER;
		}, u.gpuFormat())).toList();
		this.cull = info.cull();
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.depthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.primitiveTopology());
	}

	static Map<String, Object> compileStats() {
		return Pipelines.compileStats();
	}

	static @Nullable MetalPipeline compile(MetalEncoder encoder, CreateInfo info) {
		DepthStencilState depth = info.depthStencilState();
		Pipelines.Handles h = Pipelines.build(encoder, info.name(), shader(info, ShaderType.VERTEX).module().spv(), shader(info, ShaderType.FRAGMENT).module().spv(),
			info.uniforms().size(), (stack, hasDepth, vertexInput) -> describe(stack, info, hasDepth, vertexInput),
			depth != null, depth == null ? 7 : MetalConst.compare(depth.depthTest()), depth != null && depth.writeDepth());
		return new MetalPipeline(encoder, h.withDepth(), h.withoutDepth(), h.pulled(), h.depthState(), h.depthCompare(), info);
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
