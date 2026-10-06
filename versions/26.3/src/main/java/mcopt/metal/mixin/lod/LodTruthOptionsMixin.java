package mcopt.metal.mixin.lod;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Ground truth for the far terrain (test only, -Dmcopt.lod.truthRd=N): the render and simulation distance options go up
 * to N chunks instead of 32, so real chunks can be rendered as far as the far terrain's comparisons need. Only the 32s
 * after the render distance's name in the constructor (its range, then the simulation distance's): key codes of 32 earlier
 * in the constructor stay. Applied only with that switch.
 */
@Mixin(Options.class)
abstract class LodTruthOptionsMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 32), require = 2,
		slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")))
	private int mcopt$truthRange(int max) {
		return Math.max(max, Integer.getInteger("mcopt.lod.truthRd", max));
	}
}
