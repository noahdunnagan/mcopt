package mcopt.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

class MetalTexture extends GpuTexture {
	final long handle;
	private final MetalEncoder encoder;
	private boolean closed;
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

	static final class View extends GpuTextureView {
		final long handle;
		private final boolean ownsHandle;
		private final MetalEncoder encoder;
		private boolean closed;

		View(MetalEncoder encoder, MetalTexture texture, int baseMip, int mips) {
			super(texture, baseMip, mips);
			this.encoder = encoder;
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
}
