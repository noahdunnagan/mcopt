package mcopt.metal.mixin.sodium;

import mcopt.metal.MetalBackend;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.MappedStagingBuffer;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MappedStagingBuffer.class, remap = false)
abstract class StagingFlushMixin {
	@Redirect(method = "flush", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend;BACKEND:Lnet/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend;"))
	private DrawBackend mcopt$noGlFlush() {
		return MetalBackend.isActive() ? DrawBackend.VK_MULTIDRAW : DrawBackend.BACKEND;
	}
}
