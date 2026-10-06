package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: chunks the client loads, and unloads with the player's changes, become far terrain with their real surface. */
@Mixin(ClientLevel.class)
abstract class LodClientLevelMixin {
	@Inject(method = "onChunkLoaded", at = @At("TAIL"))
	private void mcopt$lodChunkLoaded(ChunkPos pos, CallbackInfo ci) {
		Lod.chunk(((ClientLevel) (Object) this).getChunkSource().getChunk(pos.x(), pos.z(), false));
	}

	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mcopt$lodBlockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
		net.minecraft.world.level.block.state.BlockState newState, int flags, CallbackInfo ci) {
		// (only what can change the real terrain's occluders: a block that hides what's behind it, appearing or going)
		if (oldState != newState && oldState.canOcclude() != newState.canOcclude()) Lod.blockChanged(pos, oldState, newState);
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void mcopt$lodChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
		Lod.chunk(chunk);
		Lod.unloaded(chunk);
	}
}
