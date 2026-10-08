package mcopt.metal.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.MetalFx;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render scale (see MetalFx): the world part of each frame draws into the scaled target, then gets upscaled into the real one. */
@Mixin(GameRenderer.class)
abstract class GameRendererFxMixin {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;
	@Shadow @Final private RenderTarget hud3DTarget;
	@Shadow @Final private GameRenderState gameRenderState;
	/** The real main target while the scaled one stands in for it; null outside the world part of the frame. */
	@Unique private @Nullable RenderTarget mcopt$fullTarget;

	// The first device call in render() clears the main target: the world target takes its place from there on.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/renderpearl/api/device/GpuDevice;", ordinal = 0))
	private void mcopt$beginWorld(CallbackInfo ci) {
		MetalFx fx = MetalFx.get();
		if (fx == null || !this.gameRenderState.shouldRenderLevel) return;
		this.mcopt$fullTarget = this.mainRenderTarget;
		this.mainRenderTarget = fx.beginWorld(this.mainRenderTarget, this.hud3DTarget);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$endWorld(CallbackInfo ci) {
		if (this.mcopt$fullTarget == null) return;
		this.mainRenderTarget = this.mcopt$fullTarget;
		this.mcopt$fullTarget = null;
		MetalFx.get().endWorld(this.mainRenderTarget);
	}

	// ScreenSize is only read by world shaders (lines, texture sampling), so it follows the world target.
	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"), index = 0)
	private int mcopt$worldWidth(int width) {
		return this.mcopt$fullTarget != null ? this.mainRenderTarget.width : width;
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"), index = 1)
	private int mcopt$worldHeight(int height) {
		return this.mcopt$fullTarget != null ? this.mainRenderTarget.height : height;
	}

	// The projection every world draw uses (jittered, view bobbing applied), for motion vectors.
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V"))
	private void mcopt$worldCamera(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		if (this.mcopt$fullTarget != null) MetalFx.get().camera(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
}
