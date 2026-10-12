package mcopt.legacy;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Dev only: with {@code -Dmcopt.vanilla=true} the mod applies nothing but the fps bench (and the accessors its log line
 * reads), so the bench measures the unmodified game under the same launch, options and world as the mod.
 */
public final class MixinPlugin implements IMixinConfigPlugin {
	private static final boolean VANILLA = Boolean.getBoolean("mcopt.vanilla");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!VANILLA) return true;
		return mixinClassName.endsWith(".FpsBenchMixin") || mixinClassName.endsWith(".ProfilerMixin")
			|| mixinClassName.endsWith("Access");
	}

	@Override
	public void onLoad(String mixinPackage) {
		if (VANILLA) System.out.println("[mcopt] vanilla bench: only the fps bench is applied");
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
