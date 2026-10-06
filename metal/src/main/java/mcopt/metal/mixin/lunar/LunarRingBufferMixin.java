package mcopt.metal.mixin.lunar;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import mcopt.metal.MetalBackend;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MappableRingBuffer.class, priority = 100)
abstract class LunarRingBufferMixin {
	@Shadow @Final private GpuBuffer[] buffers;
	@Shadow @Final private GpuFence[] fences;
	@Shadow private int current;

	@Inject(method = "rotate", at = @At("HEAD"), cancellable = true, order = 100)
	private void mcopt$rotateOnMetal(CallbackInfo ci) {
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!MetalBackend.isMetal(encoder)) return;
		ci.cancel();
		if (fences[current] != null) fences[current].close();
		fences[current] = encoder.createFence();
		current = (current + 1) % buffers.length;
	}
}
