package mcopt.legacy.mixin;

import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * More chunk-mesh workers. 1.8.9 builds chunk meshes on two threads with five buffer sets, whatever the CPU, so at a
 * far render distance the rebuild backlog drains slowly and the main thread spends every frame re-sorting it. Here the
 * worker count follows the core count (cores - 3, between 2 and 8: the client and server threads keep a core each) and
 * the buffer sets follow the workers (workers + 3, the same headroom as vanilla's 2 and 5).
 */
@Mixin(ChunkBuilder.class)
abstract class ChunkBuilderMixin {
	@Unique private static final int WORKERS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 3));
	@Unique private static final int BUFFERS = WORKERS + 3;

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 2))
	private int mcopt$workers(int two) {
		return WORKERS;
	}

	// The buffer queue's capacity and the loop that fills it.
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 5))
	private int mcopt$buffers(int five) {
		return BUFFERS;
	}

	// stop() waits until every buffer set is back.
	@ModifyConstant(method = "stop", constant = @Constant(intValue = 5))
	private int mcopt$buffersBack(int five) {
		return BUFFERS;
	}
}
