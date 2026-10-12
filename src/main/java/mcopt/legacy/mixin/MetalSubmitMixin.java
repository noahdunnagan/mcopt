package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts the Metal terrain early in renderWorld: right after the sky, once the terrain's projection and fog are set.
 * The GPU then draws the opaque layers while the client thread draws the clouds, walks the visible sections and uploads
 * finished meshes; the solid layer's renderLayer only waits for it and composites.
 */
@Mixin(GameRenderer.class)
abstract class MetalSubmitMixin {
	@Shadow @Final private MinecraftClient client;

	@Inject(method = "renderWorld(IFJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderFog(IF)V",
		ordinal = 1, shift = At.Shift.AFTER))
	private void mcopt$submit(int anaglyphFilter, float tickDelta, long limitTime, CallbackInfo ci) {
		if (!MetalTerrain.drawing()) return;
		Entity e = this.client.getCameraEntity();
		// the camera position setupTerrain gives the chunk render manager
		MetalTerrain.submit(e.prevTickX + (e.x - e.prevTickX) * tickDelta, e.prevTickY + (e.y - e.prevTickY) * tickDelta,
			e.prevTickZ + (e.z - e.prevTickZ) * tickDelta);
	}
}
