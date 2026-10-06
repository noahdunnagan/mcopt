package mcopt.metal.mixin.probe;

import com.mojang.blaze3d.platform.SDLEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.Latency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.metal.latency: the input poll's time, for the frame that follows it. */
@Mixin(RenderSystem.class)
abstract class PollProbeMixin {
	@Inject(method = "pollEvents", at = @At("HEAD"))
	private static void mcopt$poll(SDLEventHandler handler, CallbackInfo ci) {
		Latency.lastPollNs = System.nanoTime();
	}
}
