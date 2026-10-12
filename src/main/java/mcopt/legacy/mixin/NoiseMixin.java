package mcopt.legacy.mixin;

import mcopt.legacy.NativeNoise;
import net.minecraft.world.gen.class_1779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends the game's improved-noise fill (one octave of world-gen density noise) to NativeNoise when it is on. */
@Mixin(class_1779.class)
abstract class NoiseMixin {
	@Shadow private int[] field_7551;
	@Shadow public double field_7548;
	@Shadow public double field_7549;
	@Shadow public double field_7550;

	@Inject(method = "method_6577", at = @At("HEAD"), cancellable = true)
	private void mcopt$populate(double[] arr, double xo, double yo, double zo, int xs, int ys, int zs, double xsc, double ysc,
		double zsc, double scale, CallbackInfo ci) {
		if (!NativeNoise.active) return;
		// A short array makes the game's code throw part way through; leave that case to it.
		if (arr == null || (long) xs * ys * zs > arr.length || this.field_7551.length < 512) return;
		if (ys != 1 && (ys > 64 || zs > 64)) return;  // past the native code's per-call tables (and its self-test)
		if (NativeNoise.verify) {
			NativeNoise.verifyBefore(arr, this.field_7551, xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, scale, this.field_7548,
				this.field_7549, this.field_7550);
			return;
		}
		NativeNoise.populate(arr, this.field_7551, xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, scale, this.field_7548, this.field_7549,
			this.field_7550);
		ci.cancel();
	}

	@Inject(method = "method_6577", at = @At("RETURN"))
	private void mcopt$verify(double[] arr, double xo, double yo, double zo, int xs, int ys, int zs, double xsc, double ysc,
		double zsc, double scale, CallbackInfo ci) {
		if (NativeNoise.verify) NativeNoise.verifyAfter(arr);
	}
}
