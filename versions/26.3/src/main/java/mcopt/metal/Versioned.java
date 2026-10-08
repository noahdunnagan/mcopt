package mcopt.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMetal;

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

	static RenderPass.RenderArea renderArea(RenderPassDescriptor descriptor) {
		return descriptor.renderArea();
	}

	static long createView(long window) {
		return SDLMetal.SDL_Metal_CreateView(window);
	}

	static long layer(long view) {
		return SDLMetal.SDL_Metal_GetLayer(view);
	}

	static void destroyView(long view) {
		SDLMetal.SDL_Metal_DestroyView(view);
	}
}
