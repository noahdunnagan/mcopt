package mcopt.legacy.mixin;

import net.minecraft.client.render.world.AbstractChunkRenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The camera position chunk sections are drawn relative to, for MetalTerrain. */
@Mixin(AbstractChunkRenderManager.class)
public interface ChunkRenderManagerAccess {
	@Accessor("viewX")
	double mcopt$viewX();

	@Accessor("viewY")
	double mcopt$viewY();

	@Accessor("viewZ")
	double mcopt$viewZ();
}
