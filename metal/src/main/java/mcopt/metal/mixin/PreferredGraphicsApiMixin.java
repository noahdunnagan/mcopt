package mcopt.metal.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import mcopt.metal.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Metal goes first in every backend list; OpenGL and Vulkan stay behind it as fallbacks. -Dmcopt.metal=false opts out. */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void mcopt$metalFirst(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"))) return;
		GpuBackend[] fallbacks = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[fallbacks.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(fallbacks, 0, backends, 1, fallbacks.length);
		cir.setReturnValue(backends);
	}
}
