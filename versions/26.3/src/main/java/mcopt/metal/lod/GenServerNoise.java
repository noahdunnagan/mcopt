package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;

/**
 * The server-side door of LodNativeNoise (-Dmcopt.gen.nativeNoise=neon|java): GenNoiseChunkMixin passes the sampler set a
 * NoiseChunk takes from its RandomState through here, so the integrated server's chunk generation evaluates the same copied
 * noise leaves as the far terrain. Off: the same set is returned.
 */
public final class GenServerNoise {
	private GenServerNoise() {
	}

	public static DensitySamplerSet wrap(DensitySamplerSet set, Object randomState) {
		return LodNativeNoise.wrapServer(set, randomState);
	}
}
