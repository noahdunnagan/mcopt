package mcopt.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import com.mojang.blaze3d.vulkan.glsl.SpvSampler;
import com.mojang.blaze3d.vulkan.glsl.SpvUniformBuffer;
import com.mojang.blaze3d.vulkan.glsl.SpvVariable;
import com.mojang.logging.LogUtils;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mcopt.metal.Msl.Translated;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

final class MetalPipeline implements CompiledRenderPipeline {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static @Nullable GlslCompiler glsl;

	enum Kind { UNIFORM_BUFFER, SAMPLED_IMAGE, TEXEL_BUFFER }

	record Slot(String name, Kind kind, @Nullable GpuFormat format) {
	}

	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<Slot> slots;
	final Map<String, Integer> slotIndex = new HashMap<>();
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	private final MetalEncoder encoder;
	private boolean closed;

	private MetalPipeline(MetalEncoder encoder, RenderPipeline pipeline, long withDepth, long withoutDepth, long depthState, List<Slot> slots) {
		this.encoder = encoder;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.depthState = depthState;
		this.slots = slots;
		for (int i = 0; i < slots.size(); i++) this.slotIndex.put(slots.get(i).name(), i);
		this.name = pipeline.getLocation().toString();
		this.cull = pipeline.isCull();
		this.wireframe = pipeline.getPolygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = pipeline.getDepthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(pipeline.getPrimitiveTopology());
	}

	@Override
	public boolean isValid() {
		return this.withDepth != 0;
	}

	static MetalPipeline invalid(MetalEncoder encoder, RenderPipeline pipeline) {
		return new MetalPipeline(encoder, pipeline, 0, 0, 0, List.of());
	}

	static synchronized MetalPipeline compile(MetalEncoder encoder, RenderPipeline pipeline, ShaderSource source) {
		if (glsl == null) glsl = new GlslCompiler();
		IntermediaryShaderModule vertex = null, fragment = null;
		try {
			vertex = module(source, pipeline, pipeline.getVertexShader(), ShaderType.VERTEX);
			fragment = module(source, pipeline, pipeline.getFragmentShader(), ShaderType.FRAGMENT);
			List<Slot> slots = new ArrayList<>();
			addSlots(slots, vertex, pipeline);
			addSlots(slots, fragment, pipeline);
			List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
			for (Slot s : slots) {
				VulkanBindGroupLayout.VulkanBindGroupEntryType type = switch (s.kind()) {
					case UNIFORM_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER;
					case SAMPLED_IMAGE -> VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE;
					case TEXEL_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER;
				};
				entries.add(new VulkanBindGroupLayout.Entry(type, s.name(), s.format()));
			}
			List<String> inputs = new ArrayList<>();
			for (VertexFormat format : pipeline.getVertexFormatBindings()) {
				if (format != null) for (VertexFormatElement element : format.getElements()) inputs.add(element.name());
			}
			List<String> outputs = new ArrayList<>();
			for (SpvVariable output : vertex.outputs()) outputs.add(output.name());
			vertex.rebind(inputs, entries);
			fragment.rebind(outputs, entries);
			Translated v = Msl.translate(vertex.spirv().asIntBuffer(), true, slots.size());
			Translated f = Msl.translate(fragment.spirv().asIntBuffer(), false, slots.size());
			return build(encoder, pipeline, slots, v, f);
		} catch (ShaderCompileException | IllegalStateException e) {
			LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return invalid(encoder, pipeline);
		} finally {
			if (vertex != null) vertex.close();
			if (fragment != null) fragment.close();
		}
	}

	private static IntermediaryShaderModule module(ShaderSource source, RenderPipeline pipeline, Identifier id, ShaderType type) throws ShaderCompileException {
		String text = source.get(id, type);
		if (text == null) throw new ShaderCompileException("Couldn't find source for " + type + " shader " + id);
		return glsl.createIntermediary(id.toDebugFileName(), GlslPreprocessor.injectDefines(text, pipeline.getShaderDefines()), type);
	}

	private static void addSlots(List<Slot> slots, IntermediaryShaderModule shader, RenderPipeline pipeline) throws ShaderCompileException {
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
			if (uniforms.stream().noneMatch(d -> d.name().equals(buffer.name()))) throw new ShaderCompileException("Unable to find shader defined uniform (" + buffer.name() + ")");
			if (slots.stream().noneMatch(s -> s.kind() == Kind.UNIFORM_BUFFER && s.name().equals(buffer.name()))) slots.add(new Slot(buffer.name(), Kind.UNIFORM_BUFFER, null));
		}
		for (SpvSampler sampler : shader.samplers()) {
			var texel = uniforms.stream().filter(d -> d.name().equals(sampler.name())).findFirst();
			Kind kind = texel.isPresent() ? Kind.TEXEL_BUFFER : Kind.SAMPLED_IMAGE;
			if (texel.isEmpty() && !BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()).contains(sampler.name())) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + sampler.name() + ")");
			}
			if (slots.stream().noneMatch(s -> s.kind() == kind && s.name().equals(sampler.name()))) {
				slots.add(new Slot(sampler.name(), kind, texel.map(BindGroupLayout.UniformDescription::gpuFormat).orElse(null)));
			}
		}
	}

	private static MetalPipeline build(MetalEncoder encoder, RenderPipeline pipeline, List<Slot> slots, Translated v, Translated f) {
		long ctx = encoder.ctx;
		long vlib = 0, flib = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(v.msl())), err, 4096);
			if (vlib == 0) throw new IllegalStateException("vertex: " + MemoryUtil.memUTF8(err));
			flib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(f.msl())), err, 4096);
			if (flib == 0) throw new IllegalStateException("fragment: " + MemoryUtil.memUTF8(err));
			long vname = MemoryUtil.memAddress(stack.UTF8(v.entry())), fname = MemoryUtil.memAddress(stack.UTF8(f.entry()));
			DepthStencilState depth = pipeline.getDepthStencilState();
			long withDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, pipeline, true), err, 4096);
			if (withDepth == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			long withoutDepth = 0;
			if (depth == null) {
				withoutDepth = Native.pipelineNew(ctx, vlib, vname, flib, fname, describe(stack, pipeline, false), err, 4096);
				if (withoutDepth == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
			int compare = depth == null ? 7 : MetalConst.compare(depth.depthTest());
			long depthState = Native.depthStateNew(ctx, compare, depth != null && depth.writeDepth() ? 1 : 0);
			return new MetalPipeline(encoder, pipeline, withDepth, withoutDepth, depthState, slots);
		} finally {
			if (vlib != 0) Native.release(vlib);
			if (flib != 0) Native.release(flib);
		}
	}

	private static long describe(MemoryStack stack, RenderPipeline pipeline, boolean hasDepth) {
		VertexFormat[] buffers = pipeline.getVertexFormatBindings();
		ColorTargetState[] targets = pipeline.getColorTargetStates();
		int attributes = 0;
		for (VertexFormat format : buffers) if (format != null) attributes += format.getElements().size();
		IntBuffer d = stack.mallocInt(5 + buffers.length * 3 + attributes * 4 + targets.length * 9);
		int bufferCount = 0;
		for (VertexFormat format : buffers) if (format != null) bufferCount++;
		d.put(bufferCount);
		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] != null) d.put(MetalConst.VERTEX_BUFFER_BASE + i).put(buffers[i].getVertexSize()).put(buffers[i].getStepRate());
		}
		d.put(attributes);
		int location = 0;
		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] == null) continue;
			for (VertexFormatElement element : buffers[i].getElements()) {
				d.put(location++).put(MetalConst.VERTEX_BUFFER_BASE + i).put(element.offset()).put(MetalConst.vertexFormat(element.format()));
			}
		}
		d.put(targets.length);
		for (@Nullable ColorTargetState c : targets) {
			if (c == null) {
				d.put(new int[9]);
				continue;
			}
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
		d.put(hasDepth ? MetalConst.pixelFormat(GpuFormat.D32_FLOAT) : 0);
		d.put(MetalConst.topologyClass(pipeline.getPrimitiveTopology()));
		return MemoryUtil.memAddress(d.flip());
	}

	void close() {
		if (!this.closed && this.isValid()) {
			this.closed = true;
			this.encoder.releaseLater(this.withDepth);
			if (this.withoutDepth != 0) this.encoder.releaseLater(this.withoutDepth);
			this.encoder.releaseLater(this.depthState);
		}
	}
}
