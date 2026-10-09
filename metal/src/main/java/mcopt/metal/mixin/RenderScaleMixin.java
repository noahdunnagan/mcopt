package mcopt.metal.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dmcopt.renderScale=S (0.25-1, default 1: no effect): the game's framebuffer is S times the window's pixels, so it renders
 * everything (world and GUI) at that size. The surface keeps the window's full size (Minecraft configures it from
 * queryFramebufferSize, left as is: a smaller drawable makes SDL resize it back every frame in fullscreen) and mc_present
 * upscales the smaller frame to it (MetalFX's spatial scaler; a linear stretch where MetalFX isn't supported). For Retina laptops
 * whose GPU is fill-bound at the panel's native size (ocean views on a 10-core M4 Air: about 80% of the frame waiting on the GPU
 * at 2880x1864). Mouse input maps through the window's size in points, which doesn't change.
 */
@Mixin(Window.class)
abstract class RenderScaleMixin {
	@Unique private static final double SCALE = scale();

	@Unique
	private static double scale() {
		try {
			double s = Double.parseDouble(System.getProperty("mcopt.renderScale", "1"));
			return s >= 0.25 && s < 1 ? s : 1;
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	@Unique
	private static int scaled(int pixels) {
		return Math.max(1, (int) Math.round(pixels * SCALE));
	}

	@Shadow private int framebufferWidth;
	@Shadow private int framebufferHeight;

	@Inject(method = "refreshFramebufferSize", at = @At("TAIL"))
	private void mcopt$scaleRefreshed(CallbackInfo ci) {
		if (SCALE == 1) return;
		this.framebufferWidth = scaled(this.framebufferWidth);
		this.framebufferHeight = scaled(this.framebufferHeight);
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private int mcopt$scaleResizeWidth(int width) {
		return SCALE == 1 || width <= 0 ? width : scaled(width);
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private int mcopt$scaleResizeHeight(int height) {
		return SCALE == 1 || height <= 0 ? height : scaled(height);
	}
}
