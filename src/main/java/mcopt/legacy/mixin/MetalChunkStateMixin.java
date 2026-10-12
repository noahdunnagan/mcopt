package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells MetalTerrain when a section's ChunkAssemblyHelper changes: set after a rebuild (possibly on a worker thread), or
 * reset when the section moves or is deleted. Its meshes are then drawn or not by which layers the new state has.
 */
@Mixin(BuiltChunk.class)
abstract class MetalChunkStateMixin {
	@Inject(method = "method_10159", at = @At("TAIL"))
	private void mcopt$built(CallbackInfo ci) {
		MetalTerrain.changed((BuiltChunk) (Object) this);
	}

	@Inject(method = "method_10171", at = @At("TAIL"))
	private void mcopt$reset(CallbackInfo ci) {
		MetalTerrain.changed((BuiltChunk) (Object) this);
	}
}
