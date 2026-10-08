package mcopt.metal;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

final class MetalPass extends MetalRenderPass {
	MetalPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate) {
		super(encoder, hasDepth, x, y, width, height, delegate);
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		this.setMetalPipeline((MetalPipeline) pipeline);
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		this.setUniformAt(index, value instanceof TextureViewAndSampler ts ? new MetalUniform.Texture(ts.view(), ts.sampler()) : value);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		this.pushConstants(MemoryUtil.memAddress(value), value.remaining());
	}
}
