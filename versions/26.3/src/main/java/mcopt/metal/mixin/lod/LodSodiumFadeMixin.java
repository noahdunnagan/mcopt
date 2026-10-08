package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * -Dmcopt.lod.fade=instant: while far terrain draws, new sections appear at once instead of fading in from the fog. Sodium
 * fades a section in by fogging it fully at first (sodium_terrain: v_Fog.x = max(fog, 1 - fade)); with far terrain the fog
 * has moved out to its reach, so a section arriving at the render distance would draw as a fog-coloured patch in front of
 * far terrain that already shows its blocks. A section time of -1 is the shader's "no fade". Applied only with Sodium and
 * the far terrain on (LodMixinPlugin).
 */
@Mixin(value = UniformBufferManager.class, remap = false)
abstract class LodSodiumFadeMixin {
	@ModifyVariable(method = "writeMeshTimes", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int mcopt$lodNoFade(int time) {
		return Lod.noSectionFade() ? -1 : time;
	}
}
