package mcopt.metal.mixin.gen;

import mcopt.metal.lod.LodGenQos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.gen.worldgenQos=CLASS: a thread filling a chunk from the noise puts itself in that QoS class (once). */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class GenWorldgenQosMixin {
	@Inject(method = "doFill", at = @At("HEAD"))
	private void mcopt$qos(CallbackInfo ci) {
		LodGenQos.once(LodGenQos.WORLDGEN, "chunk generation");
	}
}
