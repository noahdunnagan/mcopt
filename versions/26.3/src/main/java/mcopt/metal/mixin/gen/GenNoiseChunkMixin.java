package mcopt.metal.mixin.gen;

import mcopt.metal.lod.GenServerNoise;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dmcopt.gen.nativeNoise: a NoiseChunk's samplers (its fill, aquifer and surface) through LodNativeNoise's copies. */
@Mixin(NoiseChunk.class)
abstract class GenNoiseChunkMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/RandomState;samplersWithContext(Lnet/minecraft/world/level/levelgen/densityfunction/SamplerContext;)Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;"))
	private DensitySamplerSet mcopt$nativeNoise(RandomState random, SamplerContext ctx) {
		return GenServerNoise.wrap(random.samplersWithContext(ctx), random);
	}
}
