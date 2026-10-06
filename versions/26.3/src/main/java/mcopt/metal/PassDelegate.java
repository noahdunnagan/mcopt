package mcopt.metal;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Takes over a render pass's pipelines for the shaderpack runtime (mcopt.metal.pack): the pass's attachments were
 * redirected (see MetalHooks), so the game's pipelines can't draw into it as they are; the delegate binds its own
 * pipeline state for each one and says where the game's uniforms go.
 */
public interface PassDelegate {
	/**
	 * The game set pipeline (a backend pipeline) on the pass whose native encoder is enc. The delegate binds its own
	 * pipeline state and resources, and returns, per entry of uniforms (the pipeline's own layout), the argument-table slot
	 * to bind it at (-1: don't bind); null makes the pass drop this pipeline's draws.
	 */
	int @Nullable [] setPipeline(long enc, Object pipeline, List<BindGroupLayout.UniformDescription> uniforms);

	/** The pass began on the native encoder enc, with or without a depth attachment. */
	default void begin(long enc, boolean hasDepth) {
	}

	/** A texture (a GpuTextureView) was bound at slot for the current pipeline, before the draw that uses it. */
	default void texture(long enc, int slot, Object view) {
	}

	/** Right before each draw, for per-draw state. false drops the draw. */
	boolean beforeDraw(long enc);

	/** The pass ended. */
	void end(long enc);
}
