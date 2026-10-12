package mcopt.legacy.mixin;

import java.util.List;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The entity pass looks up the world chunk of every visible chunk section, every frame: some 20,000 hash lookups at
 * render distance 32, for about 1,300 distinct chunk columns (16 sections share one). This keeps the columns already
 * looked up in this pass in a small open-addressed table, so each column is fetched from the world once per frame.
 * Chunks don't load or unload during the pass, so the result is the same chunk the world would return.
 *
 * Most sections hold no entities at all, so before the pass the sections that do are marked, from the world's entity list
 * (an entity sits in the section list given by its chunkX, chunkY and chunkZ; its current position is marked too, to be
 * safe). For an unmarked section the lookup returns an empty chunk instead, whose entity lists are empty, as the real
 * section's are. The pass then skips it exactly as it would have.
 */
@Mixin(WorldRenderer.class)
abstract class EntityChunkCacheMixin {
	@Unique private static final int SIZE = 1 << 14;
	@Unique private final long[] mcopt$keys = new long[SIZE];
	@Unique private final Chunk[] mcopt$chunks = new Chunk[SIZE];
	@Unique private final int[] mcopt$epochs = new int[SIZE];
	@Unique private int mcopt$epoch;
	@Unique private int mcopt$used;

	@Shadow private ClientWorld world;
	@Shadow private BuiltChunkStorage chunks;
	@Unique private int[] mcopt$occupied = new int[0];
	@Unique private int mcopt$sx, mcopt$sy, mcopt$sz;
	@Unique private Chunk mcopt$empty;
	@Unique private ClientWorld mcopt$emptyWorld;

	// the cached chunks hold their world: dropped on a world change so the old one can be freed
	@Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;)V", at = @At("HEAD"))
	private void mcopt$newWorld(CallbackInfo ci) {
		java.util.Arrays.fill(this.mcopt$chunks, null);
		this.mcopt$epoch++;
		this.mcopt$empty = null;
		this.mcopt$emptyWorld = null;
	}

	@Inject(method = "renderEntities", at = @At("HEAD"))
	private void mcopt$newPass(CallbackInfo ci) {
		this.mcopt$epoch++;
		this.mcopt$used = 0;
		if (this.chunks == null || this.world == null) return;
		BuiltChunkStorageAccess grid = (BuiltChunkStorageAccess) this.chunks;
		int sx = grid.mcopt$sizeX(), sy = grid.mcopt$sizeY(), sz = grid.mcopt$sizeZ();
		if (this.mcopt$occupied.length != sx * sy * sz) {
			this.mcopt$occupied = new int[sx * sy * sz];
		}
		this.mcopt$sx = sx;
		this.mcopt$sy = sy;
		this.mcopt$sz = sz;
		if (this.mcopt$emptyWorld != this.world) {
			this.mcopt$empty = new EmptyChunk(this.world, 0, 0);
			this.mcopt$emptyWorld = this.world;
		}
		int epoch = this.mcopt$epoch;
		List<Entity> entities = this.world.loadedEntities;
		for (int n = 0, size = entities.size(); n < size; n++) {
			Entity entity = entities.get(n);
			if (entity.updateNeeded) {
				this.mcopt$mark(entity.chunkX, entity.chunkY, entity.chunkZ, epoch);
			}
			this.mcopt$mark(MathHelper.floor(entity.x / 16.0), MathHelper.clamp(MathHelper.floor(entity.y / 16.0), 0, sy - 1),
				MathHelper.floor(entity.z / 16.0), epoch);
		}
	}

	@Unique
	private void mcopt$mark(int cx, int cy, int cz, int epoch) {
		if (cy < 0 || cy >= this.mcopt$sy) return;
		this.mcopt$occupied[this.mcopt$slot(cx, cy, cz)] = epoch;
	}

	@Unique
	private int mcopt$slot(int cx, int cy, int cz) {
		int i = cx % this.mcopt$sx;
		if (i < 0) i += this.mcopt$sx;
		int k = cz % this.mcopt$sz;
		if (k < 0) k += this.mcopt$sz;
		return (k * this.mcopt$sy + cy) * this.mcopt$sx + i;
	}

	@Redirect(method = "renderEntities", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/world/ClientWorld;getChunk(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/Chunk;"))
	private Chunk mcopt$chunk(ClientWorld world, BlockPos pos) {
		int cx = pos.getX() >> 4, cy = pos.getY() >> 4, cz = pos.getZ() >> 4;
		if (this.mcopt$empty != null && cy >= 0 && cy < this.mcopt$sy && this.mcopt$occupied[this.mcopt$slot(cx, cy, cz)] != this.mcopt$epoch) {
			return this.mcopt$empty;
		}
		long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
		int h = (int) (key * 0x9E3779B97F4A7C15L >>> 50);
		for (int probe = 0; probe < SIZE; probe++) {
			int i = (h + probe) & (SIZE - 1);
			if (this.mcopt$epochs[i] != this.mcopt$epoch) {
				Chunk chunk = world.getChunk(pos);
				// Keep the table at most half full; past that, just ask the world.
				if (this.mcopt$used < SIZE / 2) {
					this.mcopt$epochs[i] = this.mcopt$epoch;
					this.mcopt$keys[i] = key;
					this.mcopt$chunks[i] = chunk;
					this.mcopt$used++;
				}
				return chunk;
			}
			if (this.mcopt$keys[i] == key) return this.mcopt$chunks[i];
		}
		return world.getChunk(pos);
	}
}
