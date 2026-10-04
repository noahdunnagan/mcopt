package mcopt.metal.compat;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Distant Horizons on the Metal backend. DH sorts the game's backend by name: "Vulkan", or else OpenGL. Called OpenGL,
 * it takes its OpenGL-only paths (the lightmap read back as a GlTexture, glGetInteger after the level) and crashes, as
 * there is no OpenGL context. Its Blaze3D renderer (DH's default on 26.x) draws through the game's renderpearl device,
 * which the Metal backend is, so DH is told the backend isn't OpenGL: VULKAN, the one non-OpenGL value it has, and the one
 * whose conventions Metal shares (0 to 1 depth, top-left origin). See DhRenderApiMixin.
 */
public final class DistantHorizons {
	private static final String API = "com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi";
	private static Object notOpenGl;
	private static boolean logged;

	private DistantHorizons() {
	}

	/** DH's VULKAN when the game draws with the Metal backend, else null (DH's own answer stands). */
	public static Object renderingApi() {
		if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) return null;
		if (notOpenGl == null) {
			try {
				notOpenGl = enumConstant(Class.forName(API, true, DistantHorizons.class.getClassLoader()), "VULKAN");
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException("mcopt: Distant Horizons has no " + API, e);
			}
		}
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] Distant Horizons draws through the Metal backend (its Blaze3D renderer, told the backend isn't OpenGL)");
		}
		return notOpenGl;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumConstant(Class<?> type, String name) {
		return Enum.valueOf((Class) type, name);
	}
}
