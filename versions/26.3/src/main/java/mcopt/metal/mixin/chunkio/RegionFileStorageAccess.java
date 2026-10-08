package mcopt.metal.mixin.chunkio;

import java.io.IOException;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccess {
	@Invoker("getRegionFile")
	RegionFile mcopt$getRegionFile(ChunkPos pos, boolean create) throws IOException;
}
