package mcopt.metal;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class MetalPass extends MetalRenderPass {
	private final Map<String, Object> values = new HashMap<>();

	MetalPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate) {
		super(encoder, hasDepth, x, y, width, height, delegate);
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		MetalPipeline p = MetalBackend.device.pipeline(renderPipeline);
		this.setMetalPipeline(p.isValid() ? p : null);
		if (this.pipeline == null) return;
		for (int i = 0; i < p.slots.size(); i++) {
			Object value = this.values.get(p.slots.get(i).name());
			if (value != null) this.setUniformAt(i, value);
		}
	}

	private void set(String name, Object value) {
		this.values.put(name, value);
		MetalPipeline p = this.pipeline;
		if (p == null) return;
		Integer index = p.slotIndex.get(name);
		if (index != null) this.setUniformAt(index, value);
	}

	@Override
	public void bindTexture(String name, GpuTextureView view, GpuSampler sampler) {
		this.set(name, new MetalUniform.Texture(view, sampler));
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		this.set(name, value.slice());
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		this.set(name, value);
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
}
