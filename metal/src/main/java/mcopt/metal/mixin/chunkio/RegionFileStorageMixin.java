package mcopt.metal.mixin.chunkio;

import java.io.DataInputStream;
import java.io.IOException;
import mcopt.metal.chunkio.ChunkIo;
import mcopt.metal.chunkio.RegionFiles;
import mcopt.metal.chunkio.RegionSkip;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C2 (-Dmcopt.chunk.saveSkip=true|probe): don't rewrite a chunk whose serialized bytes already sit in the region file, apart from
 * the LastUpdate stamp (see RegionSkip for why that is exact). Runs on the storage's IO thread at the moment vanilla would write,
 * so the stored bytes are the result of every earlier write of the chunk. Any doubt falls through to vanilla's write.
 */
@Mixin(RegionFileStorage.class)
abstract class RegionFileStorageMixin {
	@Shadow @Final private RegionStorageInfo info;

	@Inject(method = "write", at = @At("HEAD"), cancellable = true)
	private void mcopt$skipUnchanged(ChunkPos pos, CompoundTag value, CallbackInfo ci) {
		if (value == null || SharedConstants.DEBUG_DONT_SAVE_WORLD) return;
		String type = this.info.type();
		long t0 = System.nanoTime();
		RegionSkip.Result result;
		RegionSkip.Buffers buffers = null;
		try {
			RegionFile region = RegionFiles.existing((RegionFileStorage) (Object) this, pos);
			if (region == null || !region.hasChunk(pos)) {
				result = RegionSkip.Result.NO_STORED;
			} else {
				buffers = RegionSkip.serialize(value);
				try (DataInputStream in = region.getChunkDataInputStream(pos)) {
					if (in == null) {
						result = RegionSkip.Result.NO_STORED;
					} else if (ChunkIo.SAVE_PROBE) {
						RegionSkip.readStored(buffers, in); // the whole stream, for the probe's diff of differing keys
						result = RegionSkip.compare(buffers);
					} else {
						result = RegionSkip.compareStream(buffers, in);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// Can't prove anything: write as vanilla does.
			ChunkIo.count("save." + type + ".compareFailed");
			return;
		}
		long dt = System.nanoTime() - t0;
		if (ChunkIo.STATS) {
			ChunkIo.count("save." + type + ".writes");
			ChunkIo.count("save." + type + "." + result.name().toLowerCase(java.util.Locale.ROOT));
			ChunkIo.count("save." + type + ".compareNs", dt);
			if (buffers != null) ChunkIo.count("save." + type + ".bytes", buffers.storedLength);
			if (ChunkIo.SAVE_PROBE && result == RegionSkip.Result.DIFFERENT) {
				for (String k : RegionSkip.diffKeys(buffers, value)) ChunkIo.count("save." + type + ".diff." + k);
			}
		}
		if (ChunkIo.SAVE_SKIP_ON && (result == RegionSkip.Result.IDENTICAL || result == RegionSkip.Result.LAST_UPDATE_ONLY)) {
			ci.cancel();
			return;
		}
		if (ChunkIo.STATS) MCOPT_WRITE_START.get()[0] = System.nanoTime();
	}

	@Unique
	private static final ThreadLocal<long[]> MCOPT_WRITE_START = ThreadLocal.withInitial(() -> new long[1]);

	/** Stats only: what vanilla's own write (serialize + compress + region write) costs, for the probe's arithmetic. */
	@Inject(method = "write", at = @At("RETURN"))
	private void mcopt$timeWrite(ChunkPos pos, CompoundTag value, CallbackInfo ci) {
		if (!ChunkIo.STATS || value == null) return;
		long[] start = MCOPT_WRITE_START.get();
		if (start[0] != 0) {
			ChunkIo.count("save." + this.info.type() + ".vanillaWriteNs", System.nanoTime() - start[0]);
			ChunkIo.count("save." + this.info.type() + ".vanillaWrites");
			start[0] = 0;
		}
	}
}
