package mcopt.metal.mixin;

import mcopt.metal.FrameWait;
import mcopt.metal.FrameDeadline;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FramerateLimiter.class)
abstract class FramerateLimiterMixin {
	@Shadow
	private static long lastFrameTime;
	@Unique
	private static final FrameDeadline mcopt$deadlines = new FrameDeadline();

	@Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
	private static void mcopt$preciseWait(int framerateLimit, CallbackInfo ci) {
		if (!FrameWait.ENABLED) return;
		FrameWait.sleepUntil(mcopt$deadlines.next(System.nanoTime(), lastFrameTime, framerateLimit));
		lastFrameTime = System.nanoTime();
		ci.cancel();
	}
}
