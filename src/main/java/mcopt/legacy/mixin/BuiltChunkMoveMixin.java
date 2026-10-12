package mcopt.legacy.mixin;

import mcopt.legacy.TerrainState;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts section moves, so TerrainSetupMixin knows when its copy of the section positions is stale. */
@Mixin(BuiltChunk.class)
abstract class BuiltChunkMoveMixin {
	@Inject(method = "method_10160", at = @At("TAIL"))
	private void mcopt$moved(BlockPos pos, CallbackInfo ci) {
		TerrainState.sectionMoves++;
	}
}
