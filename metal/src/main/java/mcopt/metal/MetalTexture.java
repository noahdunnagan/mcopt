package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

class MetalTexture extends BaseGpuTexture {
	final long handle;
	private final MetalEncoder encoder;
	private boolean closed;

	/**
	 * A clear that hasn't touched memory yet. Tile GPUs pay a full framebuffer write for a standalone clear and another
	 * read when the next pass loads it; folding the clear into the next pass's load action costs nothing.
	 */
	@Nullable Vector4fc pendingColorClear;
	double pendingDepthClear = Double.NaN;

	MetalTexture(MetalEncoder encoder, long handle, @GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height, int layers, int mips) {
		super(usage, label, format, width, height, layers, mips);
		this.encoder = encoder;
		this.handle = handle;
	}

	boolean hasPendingClear() {
		return this.pendingColorClear != null || !Double.isNaN(this.pendingDepthClear);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.handle);
		}
	}

	static final class View extends BaseGpuTextureView {
		final long handle;
		private final boolean ownsHandle;
		private final MetalEncoder encoder;
		private boolean closed;

		View(MetalEncoder encoder, MetalTexture texture, int baseMip, int mips) {
			super(texture, baseMip, mips);
			this.encoder = encoder;
			// A view of the whole texture is the texture itself; only sub-ranges need a real MTLTexture view.
			this.ownsHandle = baseMip != 0 || mips != texture.getMipLevels();
			this.handle = this.ownsHandle ? Native.textureView(texture.handle, baseMip, mips) : texture.handle;
		}

		MetalTexture metalTexture() {
			return (MetalTexture) this.texture();
		}

		@Override
		public boolean isClosed() {
			return this.closed || this.texture().isClosed();
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				if (this.ownsHandle) this.encoder.releaseLater(this.handle);
			}
		}
	}

	static MetalTexture of(GpuTextureView view) {
		return (MetalTexture) view.texture();
	}
}
