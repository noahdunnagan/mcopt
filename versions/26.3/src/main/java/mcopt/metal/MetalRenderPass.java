package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Uniforms are bound lazily at the next draw, and only when the slot's content actually changed: the frontend re-sends
 * every uniform on each pipeline switch, but Metal's argument slots survive pipeline changes, so most of that is a no-op.
 */
final class MetalRenderPass implements RenderPassBackend {
	private static final int MAX_UNIFORMS = MetalConst.PUSH_CONSTANTS_INDEX;

	private final MetalEncoder encoder;
	private final long enc;
	private final int areaX, areaY, areaWidth, areaHeight;
	private @Nullable MetalPipeline pipeline;
	private final Object[] pending = new Object[MAX_UNIFORMS];
	private final Object[] bound = new Object[MAX_UNIFORMS];
	private int dirtyUpTo;
	/** Metal draw calls issued, multi-draws counted per element, and indices drawn (terrain multi-draws only counted while tracing); for the trace. */
	int draws;
	long indices;
	/** Texel buffers need an MTLTexture view per (buffer, range); build each once per pass. */
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();
	/** Last vertex buffer in slot 0, for MetalTerrain and MetalProbe. */
	private @Nullable GpuBufferSlice vertexBuffer0;
	/** Set while the current pipeline is Sodium terrain with a pulled twin: its draws go to MetalTerrain, not the encoder. */
	private @Nullable MetalPipeline recording;
	/** The frontend's index buffer, restored after MetalTerrain draws with its own. */
	private long indexHandle;
	private int indexInt;
	/** What the frontend set in the encoder besides uniforms, for MetalTerrain.OCC to put back after it splits the pass. */
	private final int[] scissor = new int[4];
	private final @Nullable GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[4];
	private final byte[] pushConstants = new byte[128];
	private int pushConstantsLength;

	private final boolean hasDepth;
	/** Shaderpack runtime: draws this pass's pipelines with its own (see MetalHooks); null on the backend's own path. */
	private final @Nullable PassDelegate delegate;
	/** With a delegate: the current pipeline's uniform slots (index -> slot, -1 unbound); null drops its draws. */
	private int @Nullable [] slots;

	MetalRenderPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate) {
		this.encoder = encoder;
		this.enc = encoder.enc;
		this.hasDepth = hasDepth;
		this.delegate = delegate;
		this.areaX = x;
		this.areaY = y;
		this.areaWidth = width;
		this.areaHeight = height;
		this.enableScissor(x, y, width, height);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		MetalPipeline p = (MetalPipeline) pipeline;
		if (this.delegate != null) {
			this.pipeline = p;
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			Arrays.fill(this.pending, 0, p.uniforms.size(), null);
			Arrays.fill(this.bound, null);
			this.dirtyUpTo = p.uniforms.size();
			return;
		}
		boolean terrain = p.pulled != 0 && this.hasDepth, record = terrain && this.encoder.terrain.split;
		if (terrain) this.encoder.terrain.terrainPixels = (long) this.areaWidth * this.areaHeight;
		this.flushTerrain(record);
		if (this.encoder.tracing()) this.encoder.trace("  pipeline", p.name, "after draws:", this.draws, "triangles:", this.indices / 3);
		this.pipeline = p;
		this.recording = record ? p : null;
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		Arrays.fill(this.pending, 0, p.uniforms.size(), null);
		this.dirtyUpTo = p.uniforms.size();
	}

	/**
	 * Far terrain (mcopt.metal.lod, -Dmcopt.lod) encoded its own draw into this pass and left its pipeline state behind: set the
	 * current pipeline's state again (the game's next draw may come without a setPipeline). Its own resources sit in slots no
	 * pipeline here binds, so the uniforms stay as they are.
	 */
	void reapplyPipeline() {
		MetalPipeline p = this.pipeline;
		if (p == null) return;
		if (this.delegate != null) {
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			return;
		}
		Native.pipeline(this.enc, this.hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	/** Called by the encoder when the frontend ends the pass. */
	void end() {
		this.flushTerrain(false);
		if (this.delegate != null) this.delegate.end(this.enc);
	}

	/** With a delegate: false when this draw must not be encoded (pipeline not taken over, or the delegate says so). */
	private boolean delegated() {
		return this.delegate == null || this.slots != null && this.delegate.beforeDraw(this.enc);
	}

	/**
	 * Draws the Sodium terrain recorded under the current pipeline, then puts back what that draw changed. more: the next
	 * pipeline is pulled terrain too, so MetalTerrain.OCC's split can wait for its flush and serve both (Sodium's terrain
	 * pipelines only multi-draw, which is recorded, so nothing is encoded in between).
	 */
	private void flushTerrain(boolean more) {
		MetalPipeline p = this.recording;
		if (p == null) return;
		this.encoder.terrain.flush(this.enc, p, (GpuBufferSlice) Objects.requireNonNull(this.bound[uniform(p, "u_Globals")]), this.areaWidth, this.areaHeight,
			this.restorer(p));
		if (!more) this.encoder.terrain.split(this.enc, this::rebind);
		if (this.indexHandle != 0) Native.index(this.enc, this.indexHandle, this.indexInt);
		Arrays.fill(this.bound, MetalTerrain.QUADS_SLOT, MetalTerrain.ARENAS_SLOT + 1, null);
		this.recording = null;
	}

	/** After MetalTerrain split the pass: the reopened encoder has none of the frontend's state, so bind it all again. */
	private void rebind() {
		Arrays.fill(this.bound, null);
		this.dirtyUpTo = Objects.requireNonNull(this.pipeline).uniforms.size();
		this.bindUniforms(0);
		Native.scissor(this.enc, this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			GpuBufferSlice vb = this.vertexBuffers[slot];
			if (vb != null) Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vb.buffer()).handle, vb.offset());
		}
		if (this.pushConstantsLength == 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer bytes = stack.malloc(this.pushConstantsLength).put(this.pushConstants, 0, this.pushConstantsLength).flip();
			Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(bytes), this.pushConstantsLength);
		}
	}

	/** Binds again what p's draws are bound with now (its uniforms and the scissor), for MetalTerrain to draw more of them after a split. */
	private Runnable restorer(MetalPipeline p) {
		Object[] values = Arrays.copyOf(this.bound, p.uniforms.size());
		int[] scissor = this.scissor.clone();
		return () -> {
			for (int i = 0; i < values.length; i++) this.bindUniform(p.uniforms.get(i), i, values[i]);
			Native.scissor(this.enc, scissor[0], scissor[1], scissor[2], scissor[3]);
		};
	}

	private static int uniform(MetalPipeline p, String name) {
		for (int i = 0; i < p.uniforms.size(); i++) {
			if (p.uniforms.get(i).name().equals(name)) return i;
		}
		throw new IllegalStateException(p.name + " has no uniform " + name);
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		this.pending[index] = value;
		this.dirtyUpTo = Math.max(this.dirtyUpTo, index + 1);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		if (this.encoder.probe != null && value.remaining() >= 12) this.encoder.probe.pushConstants(MemoryUtil.memAddress(value));
		if (this.recording != null) {
			this.encoder.terrain.pushConstants(MemoryUtil.memAddress(value), value.remaining());
			return;
		}
		Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(value), value.remaining());
		if (MetalTerrain.OCC && value.remaining() <= this.pushConstants.length) {
			this.pushConstantsLength = value.remaining();
			value.get(value.position(), this.pushConstants, 0, this.pushConstantsLength);
		}
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		Native.scissor(this.enc, x, y, width, height);
		this.scissor[0] = x;
		this.scissor[1] = y;
		this.scissor[2] = width;
		this.scissor[3] = height;
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) return;
		if (MetalConst.VERTEX_BUFFER_BASE + slot > 30) throw new IllegalArgumentException("Metal backend supports vertex buffer slots 0-3, got " + slot);
		if (slot == 0) this.vertexBuffer0 = vertexBuffer;
		if (this.recording != null) return;
		Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vertexBuffer.buffer()).handle, vertexBuffer.offset());
		this.vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		this.indexHandle = this.encoder.use(indexBuffer).handle;
		this.indexInt = indexType == IndexType.INT ? 1 : 0;
		Native.index(this.enc, this.indexHandle, this.indexInt);
	}

	private void bindUniforms(int drawCount) {
		this.draws += drawCount;
		if (this.dirtyUpTo == 0) return;
		List<BindGroupLayout.UniformDescription> uniforms = Objects.requireNonNull(this.pipeline).uniforms;
		for (int i = 0; i < this.dirtyUpTo && i < uniforms.size(); i++) {
			Object value = this.pending[i];
			if (value == null) throw new IllegalStateException("Missing uniform " + uniforms.get(i).name());
			if (value.equals(this.bound[i])) continue;
			this.bound[i] = value;
			this.bindUniform(uniforms.get(i), i, value);
		}
		this.dirtyUpTo = 0;
	}

	private void bindUniform(BindGroupLayout.UniformDescription uniform, int index, Object value) {
		int i = index;
		if (this.delegate != null) {
			if (this.slots == null || index >= this.slots.length || this.slots[index] < 0) return;
			i = this.slots[index];
		}
		switch (uniform.type()) {
			case UNIFORM_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				Native.buffer(this.enc, i, this.encoder.use(slice.buffer()).handle, slice.offset());
			}
			case COMBINED_IMAGE_SAMPLER -> {
				TextureViewAndSampler ts = (TextureViewAndSampler) value;
				Native.texture(this.enc, i, ((MetalTexture.View) ts.view()).handle, ((MetalSampler) ts.sampler()).handle());
				if (this.delegate != null) this.delegate.texture(this.enc, i, ts.view());
			}
			case TEXEL_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				var format = Objects.requireNonNull(uniform.gpuFormat());
				long view = TexelViews.ON && TexelViews.active ? TexelViews.get(this.encoder, slice, MetalConst.pixelFormat(format), format.blockSize()) // opt-in
					: this.texelViews.computeIfAbsent(slice, s -> {
					long handle = Native.textureBuffer(this.encoder.ctx, this.encoder.use(s.buffer()).handle, MetalConst.pixelFormat(format), s.offset(),
						s.length(), format.blockSize());
					this.encoder.releaseLater(handle);
					return handle;
				});
				Native.texture(this.enc, i, view, 0);
			}
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) indexCount * instanceCount;
		String name = Objects.requireNonNull(this.pipeline).name;
		boolean clouds = name.equals("minecraft:pipeline/clouds") || name.equals("minecraft:pipeline/flat_clouds");
		if (this.encoder.terrain.split && clouds && instanceCount == 1 && firstIndex == 0 && vertexOffset == 0 && this.drawClouds(indexCount / 6)) return;
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	/**
	 * Vanilla's clouds, drawn from MetalTerrain.cullClouds' list: the same pixels, from only the faces that can be on screen.
	 * Only in frames that split, where it pays (ap5: 1080p spin 1156 -> 1175 fps); in plain ones its compute pass in the
	 * pre command buffer costs more than it saves (ap4: 3456x2234 spin 702 -> 714 fps without it).
	 */
	private boolean drawClouds(int faces) {
		MetalPipeline p = Objects.requireNonNull(this.pipeline);
		int at = uniform(p, "CloudFaces");
		MetalBuffer kept = this.encoder.terrain.cullClouds((GpuBufferSlice) this.bound[at], faces, (GpuBufferSlice) this.bound[uniform(p, "DynamicTransforms")],
			(GpuBufferSlice) this.bound[uniform(p, "Projection")], (GpuBufferSlice) this.bound[uniform(p, "CloudInfo")]);
		if (kept == null) return false;
		this.bindUniform(p.uniforms.get(at), at, kept.slice(MetalTerrain.CLOUD_FACES, MetalTerrain.cloudFacesLength(faces)));
		this.bound[at] = null; // so the next draw binds the frontend's faces again
		this.dirtyUpTo = Math.max(this.dirtyUpTo, at + 1);
		Native.drawIndexedIndirect(this.enc, kept.handle, 0, 1);
		return true;
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawIndexed(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		if (this.encoder.tracing()) for (int i = 0; i < drawCount; i++) this.indices += indexCounts.get(indexCounts.position() + i);
		if (this.encoder.probe != null) this.probe(indexCounts, vertexOffsets, drawCount);
		if (this.recording != null) {
			this.encoder.terrain.record(Objects.requireNonNull(this.vertexBuffer0), indexCounts, vertexOffsets, drawCount);
			return;
		}
		Native.multiDrawIndexedSeparate(this.enc, MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
			MemoryUtil.memAddress(vertexOffsets), drawCount);
	}

	/** Hands Sodium's terrain draws to the visibility probe; its compact vertex format is what mcprobe.m decodes. */
	private void probe(IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		MetalPipeline p = Objects.requireNonNull(this.pipeline);
		if (!p.name.startsWith("sodium:") || !p.name.contains("terrain") || this.vertexBuffer0 == null) return;
		GpuBufferSlice globals = null;
		long atlas = 0, atlasSampler = 0;
		for (int i = 0; i < p.uniforms.size(); i++) {
			String name = p.uniforms.get(i).name();
			if (name.equals("u_Globals")) globals = (GpuBufferSlice) this.bound[i];
			if (name.equals("u_BlockTex")) {
				TextureViewAndSampler ts = (TextureViewAndSampler) this.bound[i];
				atlas = ((MetalTexture.View) ts.view()).handle;
				atlasSampler = ((MetalSampler) ts.sampler()).handle();
			}
		}
		if (globals == null) return;
		this.encoder.probe.record(p, (MetalBuffer) this.vertexBuffer0.buffer(), this.vertexBuffer0.offset(), (MetalBuffer) globals.buffer(),
			globals.offset(), atlas, atlasSampler, indexCounts, vertexOffsets, drawCount, this.areaWidth, this.areaHeight);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndexedIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) vertexCount * instanceCount;
		Native.draw(this.enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDraw(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawSeparate(this.enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}
}
