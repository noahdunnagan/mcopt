package mcopt.metal.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.FrameGen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererFrameGenMixin {
	@Shadow @Final private RenderTarget mainRenderTarget;
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V", ordinal = 0))
	private void mcopt$frameGenLevel(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState camera) {
		if (FrameGen.enabled()) FrameGen.levelDrawn(this.mainRenderTarget, projectionMatrix, camera, false);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$frameGenBeforeGui(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (FrameGen.enabled()) FrameGen.beforeGui(this.mainRenderTarget, advanceGameTime && this.minecraft.isGameLoadFinished() && this.minecraft.level != null);
	}
}
