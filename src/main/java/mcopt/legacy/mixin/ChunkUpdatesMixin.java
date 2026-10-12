package mcopt.legacy.mixin;

import java.util.Iterator;
import java.util.Set;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Feeds the chunk-mesh workers. Vanilla's updateChunks gets a time budget of a quarter frame minus what the frame has
 * already used, which with an unlimited frame rate is nearly always zero, so it hands the workers one chunk per frame
 * and uploads about one finished mesh per frame; the next frame's setupTerrain then cancels whatever the workers had not
 * started. At render distance 32 that leaves tens of thousands of sections waiting for minutes, and the main thread
 * re-sorts that backlog every frame. Here the worker queue is filled every frame (it holds 100, so this is bounded) and
 * finished meshes get at least 1.5 ms of upload time. With no backlog both loops end at once, as in vanilla.
 */
@Mixin(WorldRenderer.class)
abstract class ChunkUpdatesMixin {
	@Shadow private ChunkBuilder chunkBuilder;
	@Shadow private Set<BuiltChunk> chunksToRebuild;
	@Shadow private boolean needsTerrainUpdate;

	/**
	 * @author mcopt
	 * @reason A real upload budget and a full worker queue; see the class comment.
	 */
	@Overwrite
	public void updateChunks(long limitTime) {
		this.needsTerrainUpdate |= this.chunkBuilder.upload(Math.max(limitTime, System.nanoTime() + 1_500_000L));
		for (Iterator<BuiltChunk> it = this.chunksToRebuild.iterator(); it.hasNext();) {
			BuiltChunk chunk = it.next();
			if (!this.chunkBuilder.send(chunk)) break;
			chunk.method_10162(false);
			it.remove();
		}
	}
}
