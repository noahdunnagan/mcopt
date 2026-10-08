package mcopt.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import mcopt.metal.mixin.sodium.RenderPassAccess;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class MetalDrawContext extends DrawContext {
	private MetalRenderPass backend;

	@Override
	public void setContext(RenderPass pass, RenderPipeline pipeline) {
		this.pass = pass;
		this.backend = (MetalRenderPass) ((RenderPassAccess) pass).mcopt$backend();
	}

	@Override
	public void updateData(RenderRegion region, CameraTransform camera) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long memory = stack.nmalloc(PUSH_CONSTANT_RANGE);
			MemoryUtil.memPutFloat(memory, getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX));
			MemoryUtil.memPutFloat(memory + 4, getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY));
			MemoryUtil.memPutFloat(memory + 8, getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ));
			MemoryUtil.memPutInt(memory + 12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
			MemoryUtil.memPutInt(memory + 16, region.getId());
			this.backend.pushConstants(memory, PUSH_CONSTANT_RANGE);
		}
	}

	@Override
	public void rotate() {
	}

	@Override
	public void delete() {
	}

	@Override
	public void endDraw() {
	}
}
