package mcopt.metal.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import mcopt.metal.MetalFx;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
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

@Mixin(GameRenderer.class)
abstract class GameRendererFxMixin {
	@Shadow @Final @Mutable private RenderTarget mainRenderTarget;
	@Shadow @Final private Minecraft minecraft;
	@Unique private @Nullable RenderTarget mcopt$fullTarget;

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", ordinal = 0))
	private void mcopt$beginWorld(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		MetalFx fx = MetalFx.get();
		if (fx == null || !advanceGameTime || !this.minecraft.isGameLoadFinished() || this.minecraft.level == null) return;
		this.mcopt$fullTarget = this.mainRenderTarget;
		this.mainRenderTarget = fx.beginWorld(this.mainRenderTarget, null);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void mcopt$endWorld(CallbackInfo ci) {
		if (this.mcopt$fullTarget == null) return;
		this.mainRenderTarget = this.mcopt$fullTarget;
		this.mcopt$fullTarget = null;
		MetalFx.get().endWorld(this.mainRenderTarget);
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"), index = 0)
	private int mcopt$worldWidth(int width) {
		return this.mcopt$fullTarget != null ? this.mainRenderTarget.width : width;
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJLnet/minecraft/client/DeltaTracker;ILnet/minecraft/world/phys/Vec3;Z)V"), index = 1)
	private int mcopt$worldHeight(int height) {
		return this.mcopt$fullTarget != null ? this.mainRenderTarget.height : height;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
	private void mcopt$worldCamera(CallbackInfo ci, @Local Matrix4f projectionMatrix, @Local CameraRenderState cameraState) {
		if (this.mcopt$fullTarget != null) MetalFx.get().camera(projectionMatrix, cameraState.viewRotationMatrix, cameraState.pos);
	}
}
