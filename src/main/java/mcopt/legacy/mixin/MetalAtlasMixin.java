package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.TextureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Tells MetalTerrain when the block atlas gets its GL texture, so it can make the Metal copy. */
@Mixin(SpriteAtlasTexture.class)
abstract class MetalAtlasMixin {
	@Redirect(method = "method_7005", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/texture/TextureUtil;prepareImage(IIII)V"))
	private void mcopt$metal(int id, int maxLevel, int width, int height) {
		TextureUtil.prepareImage(id, maxLevel, width, height);
		MetalTerrain.atlas(id, maxLevel, width, height);
	}
}
