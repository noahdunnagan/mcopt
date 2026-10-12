package mcopt.legacy.mixin;

import java.util.List;
import java.util.Set;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For the dev fps bench's log line and MetalTerrain. */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccess {
	@Accessor("chunksToRebuild")
	Set<BuiltChunk> mcopt$rebuild();

	@Accessor("visibleChunks")
	List<?> mcopt$visible();

	@Accessor("chunkRenderManager")
	AbstractChunkRenderManager mcopt$chunkRenderManager();
}
