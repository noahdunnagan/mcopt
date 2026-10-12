package mcopt.legacy.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The lightmap's pixels, which MetalTerrain copies to Metal each frame. */
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
	@Accessor("lightmapTexturePixels")
	int[] mcopt$lightmapPixels();
}
