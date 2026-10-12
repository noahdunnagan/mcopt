package mcopt.legacy.mixin;

import net.minecraft.world.gen.class_1779;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The permutation table of the game's improved noise, for NativeNoise's self-test. */
@Mixin(class_1779.class)
public interface ImprovedNoiseAccess {
	@Accessor("field_7551")
	int[] mcopt$perm();
}
