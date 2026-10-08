package mcopt.metal.chunkio;

import java.io.IOException;
import mcopt.metal.mixin.chunkio.RegionFileStorageAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.jspecify.annotations.Nullable;

public final class RegionFiles {
	private RegionFiles() {
	}

	public static @Nullable RegionFile existing(RegionFileStorage storage, ChunkPos pos) throws IOException {
		return ((RegionFileStorageAccess) (Object) storage).mcopt$getRegionFile(pos, false);
	}
}
