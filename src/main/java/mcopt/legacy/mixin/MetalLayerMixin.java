package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With Metal on, the solid layer's renderLayer draws all three opaque layers through MetalTerrain; the cutout ones skip. */
@Mixin(WorldRenderer.class)
abstract class MetalLayerMixin {
	@Inject(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"),
		cancellable = true)
	private void mcopt$metal(RenderLayer layer, double tickDelta, int anaglyphFilter, Entity entity, CallbackInfoReturnable<Integer> cir) {
		if (!MetalTerrain.drawing() || layer.ordinal() > 2) return;
		if (layer == RenderLayer.SOLID) MetalTerrain.drawOpaque((WorldRenderer) (Object) this);
		cir.setReturnValue(0);
	}

	// a world change or reload drops the old sections: the frame in flight is retired first, so its held-back changes
	// apply and nothing keeps the old world's sections reachable through a menu
	@Inject(method = {"setWorld(Lnet/minecraft/client/world/ClientWorld;)V", "reload()V"}, at = @At("HEAD"))
	private void mcopt$retire(CallbackInfo ci) {
		MetalTerrain.retire();
	}
}
