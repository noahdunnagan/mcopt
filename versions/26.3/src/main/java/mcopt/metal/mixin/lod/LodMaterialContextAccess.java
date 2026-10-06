package mcopt.metal.mixin.lod;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Far terrain evaluates the dimension's material rules per column, as the game's surface pass does per block. */
@Mixin(MaterialRuleContext.class)
public interface LodMaterialContextAccess {
	@Invoker("<init>")
	static MaterialRuleContext create(MaterialSystem system, RandomState randomState, DensityVolume expectedVolume, DensitySamplerSet densitySamplers,
		Function<BlockPos, Holder<Biome>> biomeGetter, WorldGenerationContext context, @Nullable Set<Holder<Biome>> possibleBiomes) {
		throw new AssertionError();
	}

	@Invoker("updateXZ")
	void mcopt$updateXZ(int blockX, int blockZ, int surfaceGradientX, int surfaceGradientZ);

	@Invoker("updateY")
	void mcopt$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockY);

	/** The preliminary surface is the far terrain's own surface: set it instead of letting the rules scan for it per column. */
	@Accessor("lastUpdateXZ")
	long mcopt$lastUpdateXZ();

	@Accessor("lastMinSurfaceLevelUpdate")
	void mcopt$setLastMinSurfaceLevelUpdate(long update);

	@Accessor("minSurfaceLevel")
	void mcopt$setMinSurfaceLevel(int level);
}
