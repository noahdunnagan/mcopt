package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Far terrain: the fog as the game would set it for a render distance reaching the far terrain's edge. The render-distance
 * fade moves out to the reach, the fog's color mixes with the sky as at a long render distance (the game caps that at 32
 * chunks), and the sky's fog end follows. Priority 500 so this runs before Sodium's own RETURN hook, which copies the fog
 * into its chunk shader's parameters.
 */
@Mixin(value = FogRenderer.class, priority = 500)
abstract class LodFogMixin {
	@ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/fog/FogRenderer;computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IFLorg/joml/Vector4f;)V"),
		index = 3)
	private int mcopt$lodFogColorDistance(int renderDistance) {
		float reach = Lod.activeReach();
		return reach > 0 ? Math.max(renderDistance, Math.min(32, (int) (reach / 16))) : renderDistance;
	}

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void mcopt$lodFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
		CallbackInfoReturnable<FogData> cir) {
		FogType fluid = camera.getFluidInCamera();
		FogData fog = cir.getReturnValue();
		float reach = Lod.activeReach();
		if (reach > 0 && fluid == FogType.NONE) {
			float skyFogEnd = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, deltaTracker.getGameTimeDeltaPartialTick(false));
			fog.skyEnd = Math.max(fog.skyEnd, Math.min(reach, skyFogEnd));
		}
		Lod.adjustFog(fog, fluid == FogType.NONE ? FogType.ATMOSPHERIC : fluid);
	}
}
