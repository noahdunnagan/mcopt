package mcopt.metal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.function.Supplier;
import mcopt.metal.FrameGen;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frame generation (FrameGen): the frame's GUI draws are replayed into two layers, one over black and one over white, so
 * the GUI can be put over a generated frame exactly as it went over the real one. The real frame's GUI is untouched.
 */
@Mixin(GuiRenderer.class)
abstract class GuiRendererFrameGenMixin {
	@WrapOperation(method = "draw", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V"))
	private void mcopt$frameGenGuiLayers(GuiRenderer self, Supplier<String> label, RenderTarget target, GpuBufferSlice transforms, int start, int end,
		Operation<Void> original) {
		original.call(self, label, target, transforms, start, end);
		if (FrameGen.enabled()) FrameGen.guiDrawn(start, layer -> original.call(self, label, layer, transforms, start, end));
	}
}
