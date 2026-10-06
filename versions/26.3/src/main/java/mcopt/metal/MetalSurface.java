package mcopt.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dmcopt.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcopt.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dmcopt.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcopt.metal.paceMarginMs", "2")) / 1000;
	/** -Dmcopt.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("mcopt.metal.drawables", 0);
	/**
	 * -Dmcopt.metal.paceSync=true: the paced presents (vsync off) go out with display sync on, so each latches at a vblank. On
	 * the laptop's 120 Hz 5K display at ~1800 fps, paced presents without display sync reach the screen in pairs ~1.9 ms apart
	 * every 16.7 ms (a 60 Hz cadence); with vsync the grid is a clean 8.33 ms.
	 */
	private static final boolean PACE_SYNC = Boolean.getBoolean("mcopt.metal.paceSync");
	/**
	 * -Dmcopt.metal.paceAdapt=true: the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 */
	static final boolean PACE_ADAPT = Boolean.getBoolean("mcopt.metal.paceAdapt");
	private boolean paced;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		// Frame generation schedules every present on the display's refresh grid, which needs vsync (FrameGen).
		this.paced = !vsync && PACE && !FrameGen.ENABLED;
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync || FrameGen.ENABLED || PACE_SYNC && this.paced ? 1 : 0) | DRAWABLES << 8);
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
		// mcopt.rec hook: the opt-in recorder (-Dmcopt.rec, mcopt.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (mcopt.metal.rec.Rec.ON) mcopt.metal.rec.Rec.frame(this.encoder, textureView);
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) {
			frameGen.frame(textureView, this.layer);
			return;
		}
		if (this.paced && !Native.pace(PACE_MARGIN_S)) return;
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView); // no nextDrawable here: the present side acquires it
			return;
		}
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
		// Frame generation shows the frame once its GPU work is done; here the game waits for its next frame's turn (FrameGen).
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) frameGen.afterSubmit();
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		FrameGen frameGen = FrameGen.ENABLED ? FrameGen.get(this.encoder) : null;
		if (frameGen != null) frameGen.close();
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
