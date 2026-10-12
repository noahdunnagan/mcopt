package mcopt.legacy;

import net.fabricmc.api.ModInitializer;

/** The 1.8.9 port's entrypoint, on the client and on a dedicated server. */
public final class Mcopt implements ModInitializer {
	@Override
	public void onInitialize() {
		System.out.println("[mcopt] mcopt for 1.8.9 loaded");
		if (!Boolean.getBoolean("mcopt.vanilla")) NativeNoise.init();  // dev: see MixinPlugin
	}
}
