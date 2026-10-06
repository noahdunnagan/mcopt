package mcopt.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeCocoa;

final class Versioned {
	private Versioned() {
	}

	static @Nullable MetalEncoder encoder() {
		return MetalBackend.encoder;
	}

	static boolean metalActive() {
		return MetalBackend.isActive();
	}

	static TextureTarget target(String label, RenderTarget like) {
		return new TextureTarget(label, like.width, like.height, true, like.getColorTexture().getFormat());
	}

	static RenderPass.RenderArea renderArea(RenderPassDescriptor descriptor) {
		return descriptor.renderArea;
	}

	static long createView(long window) {
		return Native.layerAttach(GLFWNativeCocoa.glfwGetCocoaWindow(window));
	}

	static long layer(long view) {
		return view;
	}

	static void destroyView(long view) {
		Native.release(view);
	}
}
