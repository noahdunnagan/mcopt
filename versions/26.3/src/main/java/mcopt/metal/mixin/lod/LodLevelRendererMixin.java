package mcopt.metal.mixin.lod;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import mcopt.metal.lod.Lod;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: the camera at the top of the level's frame, the draw at the end of its opaque phase (after the real terrain). */
@Mixin(LevelRenderer.class)
abstract class LodLevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$lodBegin(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		Lod.beginLevel(cameraState);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void mcopt$lodEnd(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		Lod.endLevel();
	}

	@Inject(method = "executeSolid", at = @At("RETURN"))
	private void mcopt$lodDraw(CallbackInfo ci) {
		Lod.drawSolid();
	}
}
