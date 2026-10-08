package mcopt.metal.mixin.lunar;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class LunarMixinPlugin implements IMixinConfigPlugin {
	private static final boolean LUNAR = detect();

	private static boolean detect() {
		String forced = System.getProperty("mcopt.lunar", "");
		if (!forced.isEmpty()) return Boolean.parseBoolean(forced);
		if (FabricLoader.getInstance().isModLoaded("ichor")) return true;
		ClassLoader loader = LunarMixinPlugin.class.getClassLoader();
		return loader.getResource("com/moonsworth/lunar/genesis/Genesis.class") != null;
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return LUNAR;
	}
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
