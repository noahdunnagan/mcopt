package mcopt.legacy.mixin;

import mcopt.legacy.SectionTimes;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: feeds SectionTimes while the fps bench measures. */
@Mixin(Profiler.class)
abstract class ProfilerMixin {
	// off unless the bench asks for section times: a constant the JIT folds, so normal play skips the volatile read
	@org.spongepowered.asm.mixin.Unique private static final boolean SECTIONS = Boolean.getBoolean("mcopt.fps.sections");

	@Inject(method = "push", at = @At("HEAD"))
	private void mcopt$push(String location, CallbackInfo ci) {
		if (SECTIONS && SectionTimes.on) SectionTimes.push(location);
	}

	@Inject(method = "pop", at = @At("HEAD"))
	private void mcopt$pop(CallbackInfo ci) {
		if (SECTIONS && SectionTimes.on) SectionTimes.pop();
	}
}
