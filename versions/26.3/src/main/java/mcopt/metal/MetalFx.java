package mcopt.metal;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Render scale with MetalFX temporal upscaling: the world renders into a target -Dmcopt.fx.scale times the window size
 * with a sub-pixel camera jitter, and MetalFX rebuilds full resolution from it, its depth and camera motion before the
 * GUI draws on top at native resolution. Fragment cost drops with the square of the scale.
 *
 * GameRendererFxMixin swaps the world target in for the world part of each frame, so everything that draws the world
 * (vanilla, Sodium, post effects) renders at the lower resolution without knowing.
 */
public final class MetalFx {
	public static final float SCALE = Float.parseFloat(System.getProperty("mcopt.fx.scale", "1"));
	/**
	 * Jitter and motion are both in texture space (x right, y down the rows), which is what MetalFX reads; only their direction
	 * convention is in question, settled by comparing against native renders on the bench.
	 */
	private static final float JITTER_SIGN = Float.parseFloat(System.getProperty("mcopt.fx.jitterSign", "1"));
	private static final float MOTION_SIGN = Float.parseFloat(System.getProperty("mcopt.fx.motionSign", "1"));
	/** More jitter phases than the upscale ratio squared lets every output pixel see every sample position (MetalFX/FSR guidance: 8 x ratio^2). */
	private static final int PHASES = Math.max(8, Math.round(8 / (SCALE * SCALE)));
	/** A camera that moves further than this between frames was teleported: history is useless. */
	private static final double TELEPORT = 8;

	private static @Nullable MetalFx instance;
	private static boolean failed;

	private final MetalEncoder encoder;
	private final RenderTarget world = new MainTarget(1, 1);
	private long fx;
	private int outWidth, outHeight;
	private int frame;
	private float jitterX, jitterY;
	private final Matrix4f viewProjection = new Matrix4f(), previousViewProjection = new Matrix4f();
	private @Nullable Vec3 camera, previousCamera;
	private boolean reset = true;

	private MetalFx(MetalEncoder encoder) {
		this.encoder = encoder;
	}

	/** The upscaler, when render scale is on and the game runs on the Metal backend; null otherwise. */
	public static @Nullable MetalFx get() {
		if (instance != null || failed || SCALE >= 1) return instance;
		if (!(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend() instanceof MetalEncoder encoder)) {
			failed = true;
			return null;
		}
		return instance = new MetalFx(encoder);
	}

	/** Called as the camera state is extracted: shifts the projection by this frame's sub-pixel jitter. */
	public void jitter(Matrix4f projection) {
		if (this.outWidth == 0) return; // no world target yet, so no pixel size; the first upscale resets history anyway
		this.frame = (this.frame + 1) % PHASES;
		this.jitterX = halton(this.frame + 1, 2) - 0.5F;
		this.jitterY = halton(this.frame + 1, 3) - 0.5F;
		new Matrix4f().translation(2 * this.jitterX / this.world.width, 2 * this.jitterY / this.world.height, 0).mul(projection, projection);
	}

	/** Called with the world's final projection (jittered, view bobbing applied), its view rotation and camera position. */
	public void camera(Matrix4fc projection, Matrix4fc viewRotation, Vec3 position) {
		// Motion vectors come from unjittered matrices, so take the jitter back out.
		new Matrix4f().translation(-2 * this.jitterX / this.world.width, -2 * this.jitterY / this.world.height, 0).mul(projection, this.viewProjection)
			.mul(viewRotation);
		this.camera = position;
	}

	/** Swaps in before the world draws: returns the scaled target, sized for a window of mainTarget's size. */
	public RenderTarget beginWorld(RenderTarget mainTarget, RenderTarget hud3DTarget) {
		if (mainTarget.width != this.outWidth || mainTarget.height != this.outHeight) this.resize(mainTarget, hud3DTarget);
		return this.world;
	}

	private void resize(RenderTarget mainTarget, RenderTarget hud3DTarget) {
		this.outWidth = mainTarget.width;
		this.outHeight = mainTarget.height;
		int w = Math.max(1, Math.round(this.outWidth * SCALE)), h = Math.max(1, Math.round(this.outHeight * SCALE));
		this.world.resize(w, h);
		// Everything the world pass pairs with the main target has to match its size.
		hud3DTarget.resize(w, h);
		Minecraft.getInstance().levelRenderer.resize(w, h);
		if (this.fx != 0) Native.fxFree(this.fx);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 1024);
			this.fx = Native.fxNew(this.encoder.ctx, w, h, this.outWidth, this.outHeight, MetalConst.pixelFormat(this.world.getColorTexture().getFormat()),
				MetalConst.pixelFormat(this.world.getDepthTexture().getFormat()), err, 1024);
			if (this.fx == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
		}
		System.out.printf("mcopt-metal: MetalFX upscaling %dx%d -> %dx%d, %d jitter phases%n", w, h, this.outWidth, this.outHeight, PHASES);
		this.reset = true;
	}

	/** After the world drew: upscales it into mainTarget, where the GUI draws next. */
	public void endWorld(RenderTarget mainTarget) {
		boolean cut = this.reset || this.previousCamera == null || this.camera == null || this.camera.distanceTo(this.previousCamera) > TELEPORT;
		// This frame's clip space -> world relative to this camera -> relative to last frame's camera -> last frame's clip space.
		Matrix4f reproject = new Matrix4f();
		if (!cut) {
			Vec3 d = this.camera.subtract(this.previousCamera);
			reproject.set(this.previousViewProjection).translate((float) d.x, (float) d.y, (float) d.z).mul(new Matrix4f(this.viewProjection).invert());
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long m = stack.nmalloc(16, 64);
			reproject.getToAddress(m);
			this.encoder.upscale(this.fx, (MetalTexture) this.world.getColorTexture(), (MetalTexture) this.world.getDepthTexture(),
				(MetalTexture) mainTarget.getColorTexture(), m, JITTER_SIGN * this.jitterX, JITTER_SIGN * this.jitterY, MOTION_SIGN, cut);		}
		this.previousViewProjection.set(this.viewProjection);
		this.previousCamera = this.camera;
		this.reset = false;
	}

	private static float halton(int index, int base) {
		float result = 0, f = 1;
		for (int i = index; i > 0; i /= base) {
			f /= base;
			result += f * (i % base);
		}
		return result;
	}
}
