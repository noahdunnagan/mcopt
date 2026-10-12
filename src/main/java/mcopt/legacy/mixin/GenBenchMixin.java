package mcopt.legacy.mixin;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import mcopt.legacy.NativeNoise;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.SurfaceChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev bench, off unless {@code -Dmcopt.gen.bench=true}: times each overworld terrain chunk with native noise or Java
 * noise, picked at random per chunk in the same process, and keeps an order-independent hash of every chunk's blocks.
 * Logs a line every 100 chunks.
 */
@Mixin(SurfaceChunkGenerator.class)
abstract class GenBenchMixin {
	@Unique private static final boolean BENCH = Boolean.getBoolean("mcopt.gen.bench");
	@Unique private static boolean mcopt$on;
	@Unique private static long mcopt$start;
	@Unique private static final long[] mcopt$nanos = new long[2];
	@Unique private static final int[] mcopt$count = new int[2];
	@Unique private static long mcopt$hash;
	@Unique private static boolean mcopt$wasActive;
	@Unique private static boolean mcopt$hadLib;

	@Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"))
	private void mcopt$benchStart(int x, int z, CallbackInfoReturnable<Chunk> cir) {
		if (!BENCH) return;
		if (!mcopt$hadLib) {
			mcopt$hadLib = true;
			mcopt$wasActive = NativeNoise.active;
		}
		mcopt$on = mcopt$wasActive && ThreadLocalRandom.current().nextBoolean();
		NativeNoise.active = mcopt$on;
		mcopt$start = System.nanoTime();
	}

	@Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"))
	private void mcopt$benchEnd(int x, int z, CallbackInfoReturnable<Chunk> cir) {
		if (!BENCH) return;
		long dt = System.nanoTime() - mcopt$start;
		int m = mcopt$on ? 1 : 0;
		mcopt$nanos[m] += dt;
		mcopt$count[m]++;
		long h = 31L * x + z;
		for (ChunkSection s : cir.getReturnValue().getBlockStorage()) {
			h = h * 1000003L + (s == null ? 0 : Arrays.hashCode(s.getBlockStates()));
		}
		mcopt$hash += h * 0x9E3779B97F4A7C15L;
		NativeNoise.active = mcopt$wasActive;
		if ((mcopt$count[0] + mcopt$count[1]) % 100 == 0) {
			System.out.printf("[mcopt] gen bench: java %d chunks %.3f ms, native %d chunks %.3f ms, hash %016x%n", mcopt$count[0],
				mean(0), mcopt$count[1], mean(1), mcopt$hash);
		}
	}

	@Unique
	private static double mean(int m) {
		return mcopt$count[m] == 0 ? 0 : mcopt$nanos[m] / 1e6 / mcopt$count[m];
	}
}
