package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.FrameGen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation (FrameGen, -Dmcopt.metal.framegen): the level's camera and depth once the level has drawn (before the
 * hand pass clears the depth), and its image just before the GUI draws.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererFrameGenMixin {
	@Shadow @Final private RenderTarget mainRenderTarget;
	@Shadow @Final private GameRenderState gameRenderState;

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void mcopt$frameGenLevel(GameRenderer self, CameraRenderState camera, PlayerRenderState player, OptionsRenderState options, boolean consistentDepth,
		Operation<Void> original, @Local Matrix4f projectionMatrix) {
		if (FrameGen.ENABLED) FrameGen.levelDrawn(this.mainRenderTarget, projectionMatrix, camera, consistentDepth);
		original.call(self, camera, player, options, consistentDepth);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$frameGenBeforeGui(CallbackInfo ci) {
		if (FrameGen.ENABLED) FrameGen.beforeGui(this.mainRenderTarget, this.gameRenderState.shouldRenderLevel);
	}
}
