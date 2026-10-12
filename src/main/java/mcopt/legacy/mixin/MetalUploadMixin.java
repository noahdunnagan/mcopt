package mcopt.legacy.mixin;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.world.ChunkAssemblyHelper;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With Metal on, finished opaque-layer meshes go to MetalTerrain instead of a GL vertex buffer. */
@Mixin(ChunkBuilder.class)
abstract class MetalUploadMixin {
	@Inject(method = "upload(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/world/BuiltChunk;Lnet/minecraft/client/world/ChunkAssemblyHelper;)Lcom/google/common/util/concurrent/ListenableFuture;",
		at = @At("HEAD"), cancellable = true)
	private void mcopt$metal(RenderLayer layer, BufferBuilder builder, BuiltChunk chunk, ChunkAssemblyHelper helper,
		CallbackInfoReturnable<ListenableFuture<Object>> cir) {
		if (!MetalTerrain.drawing() || layer.ordinal() > 2 || !MinecraftClient.getInstance().isOnThread()) return;
		long t0 = System.nanoTime();
		MetalTerrain.upload(chunk, layer.ordinal(), chunk.method_10165(layer.ordinal()), builder);
		MetalTerrain.benchUploadNanos += System.nanoTime() - t0;
		MetalTerrain.benchUploads++;
		builder.offset(0.0, 0.0, 0.0);
		cir.setReturnValue(Futures.immediateFuture(null));
	}
}
