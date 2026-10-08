package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

record MetalUniform(String name, Kind kind, @Nullable GpuFormat format) {
	enum Kind { UNIFORM_BUFFER, SAMPLED_IMAGE, TEXEL_BUFFER }

	record Texture(GpuTextureView view, GpuSampler sampler) {
	}
}
