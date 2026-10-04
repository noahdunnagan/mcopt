package mcopt.metal.mixin.compat;

import mcopt.metal.compat.DistantHorizons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Distant Horizons' backend check, answered for the Metal backend (see DistantHorizons). Optional: a DH without it is left as is. */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper", remap = false)
abstract class DhRenderApiMixin {
	@Inject(method = "getMcRenderingApi", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcopt$metalIsNotOpenGl(CallbackInfoReturnable<Object> cir) {
		Object api = DistantHorizons.renderingApi();
		if (api != null) cir.setReturnValue(api);
	}
}
