package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.texture.TextureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sprite uploads (the stitch and every animation frame): mirrored into Metal's atlas when they go to the block atlas. */
@Mixin(TextureUtil.class)
abstract class MetalTextureMixin {
	@Inject(method = "method_7027", at = @At("HEAD"))
	private static void mcopt$metal(int[][] levels, int width, int height, int x, int y, boolean blur, boolean clamp, CallbackInfo ci) {
		MetalTerrain.atlasSub(levels, width, height, x, y);
	}
}
