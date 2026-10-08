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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

final class MetalPipeline implements CompiledRenderPipeline {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static @Nullable GlslCompiler glsl;
	private static final List<String> TERRAIN_SLOTS = List.of("u_Globals", "u_SectionTimeInfo", "u_LightTex", "u_BlockTex");

	final long withDepth;
	final long withoutDepth;
	final long depthState;
	final List<MetalUniform> slots;
	final List<BindGroupLayout.UniformDescription> uniforms;
	final long pulled;
	final int depthCompare;
	final Map<String, Integer> slotIndex = new HashMap<>();
	final boolean cull;
	final boolean wireframe;
	final float depthBiasConstant;
	final float depthBiasSlope;
	final int primitive;
	final String name;
	private final MetalEncoder encoder;
	private boolean closed;

	private MetalPipeline(MetalEncoder encoder, RenderPipeline pipeline, long withDepth, long withoutDepth, long pulled, long depthState, int depthCompare, List<MetalUniform> slots) {
		this.encoder = encoder;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.pulled = pulled;
		this.depthState = depthState;
		this.depthCompare = depthCompare;
		this.slots = slots;
		this.uniforms = slots.stream().map(u -> new BindGroupLayout.UniformDescription(u.name(), u.format())).toList();
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
		return new MetalPipeline(encoder, pipeline, 0, 0, 0, 0, 7, List.of());
	}

	static synchronized MetalPipeline compile(MetalEncoder encoder, RenderPipeline pipeline, ShaderSource source) {
		if (glsl == null) glsl = new GlslCompiler();
		IntermediaryShaderModule vertex = null, fragment = null;
		try {
			vertex = module(source, pipeline, pipeline.getVertexShader(), ShaderType.VERTEX);
			fragment = module(source, pipeline, pipeline.getFragmentShader(), ShaderType.FRAGMENT);
			List<MetalUniform> slots = new ArrayList<>();
			addSlots(slots, vertex, pipeline);
			addSlots(slots, fragment, pipeline);
			String name = pipeline.getLocation().toString();
			if (Pipelines.builtinTerrain(name)) slots.sort(java.util.Comparator.comparingInt(u -> TERRAIN_SLOTS.indexOf(u.name())));
			List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
			for (MetalUniform s : slots) {
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
			DepthStencilState depth = pipeline.getDepthStencilState();
			Pipelines.Handles h = Pipelines.build(encoder, name, vertex.spirv(), fragment.spirv(), slots.size(), (stack, hasDepth, vertexInput) -> describe(stack, pipeline, hasDepth, vertexInput),
				depth != null, depth == null ? 7 : MetalConst.compare(depth.depthTest()), depth != null && depth.writeDepth());
			return new MetalPipeline(encoder, pipeline, h.withDepth(), h.withoutDepth(), h.pulled(), h.depthState(), h.depthCompare(), slots);
		} catch (ShaderCompileException | IllegalStateException e) {
			LOGGER.error("Couldn't compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return invalid(encoder, pipeline);
		} finally {
			if (vertex != null) vertex.close();
			if (fragment != null) fragment.close();
		}
	}

	private static IntermediaryShaderModule module(ShaderSource source, RenderPipeline pipeline, Identifier id, ShaderType type) throws ShaderCompileException {
		String key = type + " " + id;
		String text = source.get(id, type);
		if (text == null) text = injectedSource(id, type);
		if (text == null) text = KNOWN_SOURCES.get(key);
		if (text == null) throw new ShaderCompileException("Couldn't find source for " + type + " shader " + id);
		KNOWN_SOURCES.put(key, text);
		return glsl.createIntermediary(id.toDebugFileName(), GlslPreprocessor.injectDefines(text, pipeline.getShaderDefines()), type);
	}

	private static final Map<String, String> KNOWN_SOURCES = new HashMap<>();
	private static @Nullable List<ShaderSource> injected;

	private static @Nullable String injectedSource(Identifier id, ShaderType type) {
		if (injected == null) {
			injected = new ArrayList<>();
			for (String name : List.of("com.mojang.blaze3d.vulkan.VulkanDevice", "com.mojang.blaze3d.opengl.GlDevice")) {
				try {
					for (Field field : Class.forName(name).getDeclaredFields()) {
						if (!Modifier.isStatic(field.getModifiers()) || !ShaderSource.class.isAssignableFrom(field.getType())) continue;
						field.setAccessible(true);
						if (field.get(null) instanceof ShaderSource shaderSource) injected.add(shaderSource);
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					LOGGER.warn("Couldn't read shader sources from {}: {}", name, e.toString());
				}
			}
		}
		for (ShaderSource shaderSource : injected) {
			try {
				String text = shaderSource.get(id, type);
				if (text != null) return text;
			} catch (RuntimeException e) {
				LOGGER.debug("Injected shader source failed for {}: {}", id, e.toString());
			}
		}
		return null;
	}

	private static void addSlots(List<MetalUniform> slots, IntermediaryShaderModule shader, RenderPipeline pipeline) throws ShaderCompileException {
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
			if (uniforms.stream().noneMatch(d -> d.name().equals(buffer.name()))) throw new ShaderCompileException("Unable to find shader defined uniform (" + buffer.name() + ")");
			if (slots.stream().noneMatch(s -> s.kind() == MetalUniform.Kind.UNIFORM_BUFFER && s.name().equals(buffer.name()))) slots.add(new MetalUniform(buffer.name(), MetalUniform.Kind.UNIFORM_BUFFER, null));
		}
		for (SpvSampler sampler : shader.samplers()) {
			var texel = uniforms.stream().filter(d -> d.name().equals(sampler.name())).findFirst();
			MetalUniform.Kind kind = texel.isPresent() ? MetalUniform.Kind.TEXEL_BUFFER : MetalUniform.Kind.SAMPLED_IMAGE;
			if (texel.isEmpty() && !BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()).contains(sampler.name())) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + sampler.name() + ")");
			}
			if (slots.stream().noneMatch(s -> s.kind() == kind && s.name().equals(sampler.name()))) {
				slots.add(new MetalUniform(sampler.name(), kind, texel.map(BindGroupLayout.UniformDescription::gpuFormat).orElse(null)));
			}
		}
	}

	private static long describe(MemoryStack stack, RenderPipeline pipeline, boolean hasDepth, boolean vertexInput) {
		VertexFormat[] buffers = vertexInput ? pipeline.getVertexFormatBindings() : new VertexFormat[0];
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
			if (this.pulled != 0) this.encoder.releaseLater(this.pulled);
			this.encoder.releaseLater(this.depthState);
		}
	}
}
