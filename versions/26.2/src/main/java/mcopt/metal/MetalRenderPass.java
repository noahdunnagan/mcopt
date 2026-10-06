package mcopt.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

final class MetalRenderPass implements RenderPassBackend {
	private final MetalEncoder encoder;
	private final long enc;
	private final boolean hasDepth;
	private final int areaX, areaY, areaWidth, areaHeight;
	private @Nullable MetalPipeline pipeline;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, Texture> textures = new HashMap<>();
	private final Object[] bound = new Object[MetalConst.PUSH_CONSTANTS_INDEX];
	private boolean dirty;
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();

	private record Texture(GpuTextureView view, GpuSampler sampler) {
	}

	MetalRenderPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height) {
		this.encoder = encoder;
		this.enc = encoder.enc;
		this.hasDepth = hasDepth;
		this.areaX = x;
		this.areaY = y;
		this.areaWidth = width;
		this.areaHeight = height;
		this.enableScissor(x, y, width, height);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		MetalPipeline p = this.encoder.device.pipeline(renderPipeline);
		if (!p.isValid()) {
			this.pipeline = null;
			return;
		}
		this.pipeline = p;
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		Arrays.fill(this.bound, null);
		this.dirty = true;
	}

	@Override
	public void bindTexture(String name, GpuTextureView view, GpuSampler sampler) {
		this.textures.put(name, new Texture(view, sampler));
		this.dirty = true;
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		this.setUniform(name, value.slice());
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		this.uniforms.put(name, value);
		this.dirty = true;
	}

	void pushConstants(long address, int length) {
		Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, address, length);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		Native.scissor(this.enc, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) return;
		if (MetalConst.VERTEX_BUFFER_BASE + slot > 30) throw new IllegalArgumentException("Metal backend supports vertex buffer slots 0-3, got " + slot);
		Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vertexBuffer.buffer()).handle, vertexBuffer.offset());
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		Native.index(this.enc, this.encoder.use(indexBuffer).handle, indexType == IndexType.INT ? 1 : 0);
	}

	private boolean bindUniforms() {
		MetalPipeline p = this.pipeline;
		if (p == null) return false;
		if (!this.dirty) return true;
		for (int i = 0; i < p.slots.size(); i++) {
			MetalPipeline.Slot slot = p.slots.get(i);
			Object value = slot.kind() == MetalPipeline.Kind.SAMPLED_IMAGE ? this.textures.get(slot.name()) : this.uniforms.get(slot.name());
			if (value == null) throw new IllegalStateException("Missing uniform " + slot.name() + " for " + p.name);
			if (value.equals(this.bound[i])) continue;
			this.bound[i] = value;
			this.bind(slot, i, value);
		}
		this.dirty = false;
		return true;
	}

	private void bind(MetalPipeline.Slot slot, int index, Object value) {
		switch (slot.kind()) {
			case UNIFORM_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				Native.buffer(this.enc, index, this.encoder.use(slice.buffer()).handle, slice.offset());
			}
			case SAMPLED_IMAGE -> {
				Texture t = (Texture) value;
				Native.texture(this.enc, index, ((MetalTexture.View) t.view()).handle, ((MetalSampler) t.sampler()).handle);
			}
			case TEXEL_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				GpuFormat format = Objects.requireNonNull(slot.format());
				long view = this.texelViews.computeIfAbsent(slice, s -> {
					long handle = Native.textureBuffer(this.encoder.ctx, this.encoder.use(s.buffer()).handle, MetalConst.pixelFormat(format), s.offset(),
						s.length(), format.blockSize());
					this.encoder.releaseLater(handle);
					return handle;
				});
				Native.texture(this.enc, index, view, 0);
			}
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (!this.bindUniforms()) return;
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.multiDrawIndexed(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.multiDrawIndexedSeparate(this.enc, MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
			MemoryUtil.memAddress(vertexOffsets), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.drawIndexedIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer defaultIndexBuffer, IndexType defaultIndexType,
		Collection<String> dynamicUniforms, T uniformArgument) {
		for (RenderPass.Draw<T> draw : draws) {
			if (draw.uniformUploaderConsumer() != null) draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
			this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
			this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.bindUniforms()) return;
		Native.draw(this.enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.multiDraw(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.multiDrawSeparate(this.enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.bindUniforms()) return;
		Native.drawIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}
}
