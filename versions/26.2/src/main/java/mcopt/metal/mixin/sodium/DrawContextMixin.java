package mcopt.metal.mixin.sodium;

import mcopt.metal.MetalBackend;
import mcopt.metal.MetalDrawContext;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DrawContext.class, remap = false)
abstract class DrawContextMixin {
	@Inject(method = "create", at = @At("HEAD"), cancellable = true)
	private static void mcopt$metal(CallbackInfoReturnable<DrawContext> cir) {
		if (MetalBackend.isActive()) cir.setReturnValue(new MetalDrawContext());
	}
}
