package mcopt.metal;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;
import org.lwjgl.glfw.GLFWNativeCocoa;

final class MetalSurface implements GpuSurfaceBackend {
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcopt.metal.pace", "true"));
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcopt.metal.paceMarginMs", "2")) / 1000;
	private static final int DRAWABLES = Integer.getInteger("mcopt.metal.drawables", 0);
	private boolean paced;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.layer = Native.layerAttach(GLFWNativeCocoa.glfwGetCocoaWindow(window));
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.paced = !vsync && PACE;
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync ? 1 : 0) | DRAWABLES << 8);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		if (this.paced && !Native.pace(PACE_MARGIN_S)) return;
		long drawable = Native.layerNext(this.layer);
		if (drawable == 0) return;
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		Native.release(this.layer);
	}
}
