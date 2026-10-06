package mcopt.metal;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public final class MetalBackend implements GpuBackend {
	private static volatile boolean active;
	static @Nullable MetalEncoder encoder;
	static MetalDevice device;

	public static boolean isActive() {
		return active;
	}

	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
		throw new BackendCreationException(error != null ? "GLFW error " + error.error() : "Failed to create window for Metal", BackendCreationException.Reason.GLFW_ERROR);
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource shaderSource, GpuDebugOptions debugOptions, Runnable criticalShaderLoader) throws BackendCreationException {
		MetalDevice metal = new MetalDevice(shaderSource);
		GpuDevice device = new GpuDevice(metal, criticalShaderLoader);
		encoder = (MetalEncoder) metal.createCommandEncoder();
		MetalBackend.device = metal;
		active = true;
		return device;
	}
}
