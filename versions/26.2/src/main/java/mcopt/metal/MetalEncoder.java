package mcopt.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalEncoder implements CommandEncoderBackend {
	private static final int MAX_IN_FLIGHT = Integer.getInteger("mcopt.metal.inFlight", 2);
	private static final long CPU_COPY_MAX = 256 << 10;
	static final boolean PRESENT_QUEUE = "true".equals(System.getProperty("mcopt.metal.presentQueue")) || "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));
	static final boolean PRESENT_ACQUIRE = "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));

	final long ctx;
	final long enc;
	final MetalTransientMemory transientMemory;
	MetalDevice device;
	private final ArrayDeque<Frame> inFlight = new ArrayDeque<>();
	private List<Runnable> afterThisFrame = new ArrayList<>();
	private final Set<MetalTexture> pendingClears = new HashSet<>();
	private long submitIndex;
	private long completedIndex = -1;
	private @Nullable MetalRenderPass currentPass;

	private record Frame(long index, long cmd, List<Runnable> after) {
	}

	MetalEncoder(long ctx) {
		this.ctx = ctx;
		this.enc = Native.encNew(ctx);
		this.transientMemory = new MetalTransientMemory(ctx, this);
	}

	MetalBuffer use(GpuBuffer buffer) {
		MetalBuffer b = (MetalBuffer) buffer;
		b.lastUse = this.submitIndex;
		return b;
	}

	private boolean idle(MetalBuffer b) {
		return b.lastUse <= this.completedIndex;
	}

	void releaseLater(long handle) {
		this.afterThisFrame.add(() -> Native.release(handle));
	}

	void afterGpuFinishes(Runnable r) {
		this.afterThisFrame.add(r);
	}

	@Override
	public void submit() {
		if (this.currentPass != null) throw new IllegalStateException("Cannot submit inside a render pass");
		this.transientMemory.endSubmit();
		long cmd = Native.encCommit(this.enc);
		this.inFlight.add(new Frame(this.submitIndex++, cmd, this.afterThisFrame));
		if (mcopt.metal.cpu.Cpu.FENCE_STATS) mcopt.metal.cpu.Cpu.fenceTick(this.submitIndex);
		this.afterThisFrame = new ArrayList<>();
		while (this.inFlight.size() > MAX_IN_FLIGHT) this.retire(this.inFlight.poll());
	}

	private void retire(Frame frame) {
		Native.cmdWait(frame.cmd);
		frame.after.forEach(Runnable::run);
		Native.release(frame.cmd);
		this.completedIndex = frame.index;
	}

	void waitIdle() {
		while (!this.inFlight.isEmpty()) this.retire(this.inFlight.poll());
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		for (MetalTexture t : List.copyOf(this.pendingClears)) {
			if (t.isClosed()) this.pendingClears.remove(t);
			else if (!this.isAttachment(t, colors, depth)) this.flushClear(t);
		}
		int width = 0, height = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colorHandles = stack.nmalloc(8, Math.max(1, colors.size()) * 8);
			long clears = stack.ncalloc(4, Math.max(1, colors.size()) * 5, 4);
			for (int i = 0; i < colors.size(); i++) {
				var attachment = colors.get(i);
				long handle = 0;
				if (attachment != null) {
					MetalTexture.View view = (MetalTexture.View) attachment.textureView();
					handle = view.handle;
					width = view.getWidth(0);
					height = view.getHeight(0);
					Vector4fc clear = attachment.clearValue().orElse(view.baseMipLevel() == 0 ? view.metalTexture().pendingColorClear : null);
					view.metalTexture().pendingColorClear = null;
					if (clear != null) putClear(clears + i * 20L, clear);
				}
				MemoryUtil.memPutAddress(colorHandles + i * 8L, handle);
			}
			long depthHandle = 0;
			boolean clearDepth = false;
			double depthValue = 0;
			if (depth != null) {
				MetalTexture.View view = (MetalTexture.View) depth.textureView();
				MetalTexture texture = view.metalTexture();
				depthHandle = view.handle;
				if (colors.isEmpty()) {
					width = view.getWidth(0);
					height = view.getHeight(0);
				}
				OptionalDouble explicit = depth.clearValue();
				clearDepth = explicit.isPresent() || !Double.isNaN(texture.pendingDepthClear);
				depthValue = explicit.orElse(texture.pendingDepthClear);
				texture.pendingDepthClear = Double.NaN;
			}
			this.pendingClears.removeIf(t -> !t.hasPendingClear());
			Native.renderBegin(this.enc, colors.size(), colorHandles, clears, depthHandle, clearDepth ? 1 : 0, (float) depthValue, width, height);
		}
		var area = descriptor.renderArea;
		this.currentPass = new MetalRenderPass(this, depth != null, area.x(), area.y(), area.width(), area.height());
		return this.currentPass;
	}

	private boolean isAttachment(MetalTexture t, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors,
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth) {
		for (var c : colors) {
			if (c != null && c.textureView().texture() == t && c.textureView().baseMipLevel() == 0) return true;
		}
		return depth != null && depth.textureView().texture() == t;
	}

	private static void putClear(long at, Vector4fc c) {
		MemoryUtil.memPutFloat(at, 1);
		MemoryUtil.memPutFloat(at + 4, c.x());
		MemoryUtil.memPutFloat(at + 8, c.y());
		MemoryUtil.memPutFloat(at + 12, c.z());
		MemoryUtil.memPutFloat(at + 16, c.w());
	}

	@Override
	public void submitRenderPass() {
		if (this.currentPass == null) throw new IllegalStateException("No render pass to submit");
		this.currentPass = null;
	}

	void flushClear(GpuTexture texture) {
		MetalTexture t = (MetalTexture) texture;
		if (!t.hasPendingClear()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean depthFormat = t.getFormat().hasDepthAspect();
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, depthFormat ? 0 : t.handle);
			if (t.pendingColorClear != null) putClear(clears, t.pendingColorClear);
			boolean clearDepth = !Double.isNaN(t.pendingDepthClear);
			Native.renderBegin(this.enc, depthFormat ? 0 : 1, colors, clears, depthFormat ? t.handle : 0, clearDepth ? 1 : 0,
				clearDepth ? (float) t.pendingDepthClear : 0, t.getWidth(0), t.getHeight(0));
		}
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	private void deferClear(GpuTexture texture, @Nullable Vector4fc color, double depth) {
		MetalTexture t = (MetalTexture) texture;
		if (t.getMipLevels() != 1) {
			for (int mip = 0; mip < t.getMipLevels(); mip++) this.clearRegion(t, color, null, depth, 0, 0, t.getWidth(mip), t.getHeight(mip), mip);
			return;
		}
		if (color != null) t.pendingColorClear = color;
		if (!Double.isNaN(depth)) t.pendingDepthClear = depth;
		this.pendingClears.add(t);
		Native.discard(this.enc, t.handle);
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
		this.deferClear(depthTexture, null, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
		int x, int y, int width, int height) {
		if (x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		} else {
			this.clearRegion((MetalTexture) colorTexture, clearColor, (MetalTexture) depthTexture, clearDepth, x, y, width, height, 0);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		this.deferClear(depthTexture, null, clearDepth);
	}

	private void clearRegion(@Nullable MetalTexture color, @Nullable Vector4fc clearColor, @Nullable MetalTexture depth, double clearDepth,
		int x, int y, int width, int height, int mip) {
		if (color != null) this.flushClear(color);
		if (depth != null) this.flushClear(depth);
		Vector4fc c = clearColor != null ? clearColor : new org.joml.Vector4f();
		Native.clearRect(this.enc, color != null ? color.handle : 0, depth != null ? depth.handle : 0, c.x(), c.y(), c.z(), c.w(),
			Double.isNaN(clearDepth) ? -1 : (float) clearDepth, x, y, width, height, mip);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalBuffer dst = (MetalBuffer) destination.buffer();
		boolean whole = destination.offset() == 0 && data.remaining() == dst.size();
		if (data.remaining() <= CPU_COPY_MAX && (this.idle(dst) || whole && dst.rename())) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address + destination.offset(), data.remaining());
			return;
		}
		GpuBufferSlice staging = this.transientMemory.uploadGpu(List.of(data), 1L, GpuBuffer.USAGE_COPY_SRC, data.remaining(), 1L);
		Native.blitBuffer(this.enc, this.use(staging.buffer()).handle, staging.offset(), this.use(dst).handle, destination.offset(), data.remaining());
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		MetalBuffer src = (MetalBuffer) source.buffer(), dst = (MetalBuffer) target.buffer();
		if (source.length() <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
			MemoryUtil.memCopy(src.address + source.offset(), dst.address + target.offset(), source.length());
			return;
		}
		Native.blitBuffer(this.enc, this.use(src).handle, source.offset(), this.use(dst).handle, target.offset(), source.length());
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mip, int layer, int x, int y, int width, int height) {
		this.flushClear(destination);
		GpuBufferSlice staging = this.transientMemory.uploadGpu(List.of(source), 16L, GpuBuffer.USAGE_COPY_SRC, source.remaining(), 1L);
		int bytesPerRow = width * destination.getFormat().blockSize();
		Native.blitBufferToTexture(this.enc, this.use(staging.buffer()).handle, staging.offset(), bytesPerRow, bytesPerRow * height,
			((MetalTexture) destination).handle, layer, mip, x, y, width, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destX, int destY, int copyWidth, int copyHeight, int mip, int layer) {
		this.flushClear(destination);
		int texel = destination.getFormat().blockSize();
		long skip = (sourceX + (long) sourceY * sourceWidth) * texel;
		Native.blitBufferToTexture(this.enc, this.use(source.buffer()).handle, source.offset() + skip, sourceWidth * texel,
			sourceWidth * texel * sourceHeight, ((MetalTexture) destination).handle, layer, mip, destX, destY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip) {
		this.copyTextureToBuffer(source, destination, offset, callback, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip, int x, int y, int width, int height) {
		this.flushClear(source);
		Native.blitTextureToBuffer(this.enc, ((MetalTexture) source).handle, mip, x, y, width, height, this.use(destination).handle, offset,
			width * source.getFormat().blockSize());
		this.afterGpuFinishes(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mip, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		this.flushClear(source);
		this.flushClear(destination);
		Native.blitTextureToTexture(this.enc, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mip, destX, destY, sourceX, sourceY,
			width, height);
	}

	@Override
	public GpuFence createFence() {
		long current = this.submitIndex;
		long index = mcopt.metal.cpu.Cpu.FENCE && mcopt.metal.cpu.Cpu.fenceActive && current > 0 && Native.encEmpty(this.enc) ? current - 1 : current;
		if (mcopt.metal.cpu.Cpu.FENCE_STATS) mcopt.metal.cpu.Cpu.fenceMade(index != current);
		return new GpuFence() {
			@Override
			public boolean awaitCompletion(long timeoutNs) {
				if (MetalEncoder.this.completedIndex >= index) return true;
				if (index == MetalEncoder.this.submitIndex) {
					if (timeoutNs == 0) return false;
					throw new IllegalStateException("Cannot wait on a fence for the current submit");
				}
				while (MetalEncoder.this.completedIndex < index) {
					Frame oldest = MetalEncoder.this.inFlight.peek();
					if (timeoutNs == 0 && !Native.cmdDone(oldest.cmd)) return false;
					MetalEncoder.this.retire(MetalEncoder.this.inFlight.poll());
				}
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	void upscale(long fx, MetalTexture color, MetalTexture depth, MetalTexture dst, long reproject, float jitterX, float jitterY, float motionSign,
		boolean reset) {
		if (this.currentPass != null) throw new IllegalStateException("Cannot upscale inside a render pass");
		this.flushClear(color);
		this.flushClear(depth);
		dst.pendingColorClear = null;
		this.pendingClears.remove(dst);
		Native.fxUpscale(this.enc, fx, color.handle, depth.handle, dst.handle, reproject, jitterX, jitterY, motionSign, reset ? 1 : 0);
	}

	void presentAcquire(long layer, GpuTextureView view) {
		this.flushClear(view.texture());
		Native.presentQueuedAcquire(this.enc, layer, ((MetalTexture.View) view).handle, -1);
	}

	void presentTexture(long drawable, GpuTextureView view) {
		this.flushClear(view.texture());
		if (PRESENT_QUEUE) Native.presentQueued(this.enc, drawable, ((MetalTexture.View) view).handle);
		else Native.present(this.enc, drawable, ((MetalTexture.View) view).handle);
	}
}
