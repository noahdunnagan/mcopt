package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Ab;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Any -Dmcopt.cpu.*=ab: counts frames for Ab's per-frame arms. */
@Mixin(GameRenderer.class)
abstract class AbFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$frame(CallbackInfo ci) {
		Ab.frame++;
		if (mcopt.metal.cpu.Cpu.AB_SHOTS) mcopt.metal.cpu.AbShots.frameStart();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void mcopt$frameEnd(CallbackInfo ci) {
		if (mcopt.metal.cpu.Cpu.AB_SHOTS) mcopt.metal.cpu.AbShots.frameEnd();
	}
}
