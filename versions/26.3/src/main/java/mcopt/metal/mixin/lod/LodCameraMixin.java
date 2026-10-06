package mcopt.metal.mixin.lod;

import mcopt.metal.lod.Lod;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far terrain: the far plane reaches past the far terrain (reversed Z on a float buffer: precision doesn't suffer). */
@Mixin(Camera.class)
abstract class LodCameraMixin {
	@Shadow private float depthFar;

	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void mcopt$lodFar(DeltaTracker deltaTracker, CallbackInfo ci) {
		float reach = Lod.activeReach();
		if (reach > 0) this.depthFar = Math.max(this.depthFar, reach * 1.5F + 1024.0F);
	}
}
