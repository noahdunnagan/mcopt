package mcopt.legacy.mixin;

import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** WorldRenderer.ChunkInfo's section, for TerrainSetupMixin. */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer$ChunkInfo")
public interface ChunkInfoAccess {
	@Accessor("chunk")
	BuiltChunk mcopt$chunk();
}
