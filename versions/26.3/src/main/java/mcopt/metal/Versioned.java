package mcopt.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.jspecify.annotations.Nullable;

final class Versioned {
	private Versioned() {
	}

	static @Nullable MetalEncoder encoder() {
		return RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend && frontend.backend() instanceof MetalEncoder encoder ? encoder : null;
	}

	static boolean metalActive() {
		return encoder() != null;
	}

	static TextureTarget target(String label, RenderTarget like) {
		return new TextureTarget(label, like.width, like.height, like.getColorTexture().getFormat(), like.getDepthTexture().getFormat());
	}
}
