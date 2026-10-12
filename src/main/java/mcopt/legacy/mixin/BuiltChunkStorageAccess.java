package mcopt.legacy.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The render-chunk grid's dimensions, for TerrainSetupMixin's neighbour lookups. */
@Mixin(BuiltChunkStorage.class)
public interface BuiltChunkStorageAccess {
	@Accessor("sizeX")
	int mcopt$sizeX();

	@Accessor("sizeY")
	int mcopt$sizeY();

	@Accessor("sizeZ")
	int mcopt$sizeZ();
}
