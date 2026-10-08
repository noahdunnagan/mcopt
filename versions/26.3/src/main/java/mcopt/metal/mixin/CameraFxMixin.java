package mcopt.metal.mixin;

import mcopt.metal.MetalFx;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sub-pixel jitter for MetalFX, at the source: every world draw copies its projection from the camera state. */
@Mixin(Camera.class)
abstract class CameraFxMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mcopt$jitter(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		MetalFx fx = MetalFx.get();
		if (fx != null) fx.jitter(cameraState.projectionMatrix);
	}
}
