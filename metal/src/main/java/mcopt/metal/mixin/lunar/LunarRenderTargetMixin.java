package mcopt.metal.mixin.lunar;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import mcopt.metal.MetalBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
abstract class LunarRenderTargetMixin {
	@Inject(method = "bridge$blitToRenderTarget", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void mcopt$blitOnMetal(@Coerce Object target, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
		int dstY1, boolean linear, CallbackInfo ci) {
		if (!MetalBridge.metalActive()) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		ci.cancel();
		GpuTexture src = ((RenderTarget) (Object) this).getColorTexture();
		GpuTexture dst = target instanceof RenderTarget rt ? rt.getColorTexture() : null;
		int width = srcX1 - srcX0, height = srcY1 - srcY0;
		if (src == null || dst == null || src == dst || src.getFormat() != dst.getFormat()) return;
		if (width <= 0 || height <= 0 || width != dstX1 - dstX0 || height != dstY1 - dstY0) return;
		if (srcX0 < 0 || srcY0 < 0 || srcX1 > src.getWidth(0) || srcY1 > src.getHeight(0)) return;
		if (dstX0 < 0 || dstY0 < 0 || dstX1 > dst.getWidth(0) || dstY1 > dst.getHeight(0)) return;
		encoder.copyTextureToTexture(src, dst, 0, dstX0, dstY0, srcX0, srcY0, width, height);
	}

	@Inject(method = "bridge$unbindFrameBuffer", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void mcopt$unbindOnMetal(CallbackInfo ci) {
		if (MetalBridge.metalActive()) ci.cancel();
	}
}
