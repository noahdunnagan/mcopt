package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.function.Supplier;
import mcopt.metal.FrameGen;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiRenderer.class)
abstract class GuiRendererFrameGenMixin {
	@WrapOperation(method = "draw", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V"))
	private void mcopt$frameGenGuiLayers(GuiRenderer self, Supplier<String> label, RenderTarget target, GpuBufferSlice transforms, int start, int end,
		Operation<Void> original) {
		original.call(self, label, target, transforms, start, end);
		if (FrameGen.enabled()) FrameGen.guiDrawn(start, layer -> original.call(self, label, layer, transforms, start, end));
	}
}
