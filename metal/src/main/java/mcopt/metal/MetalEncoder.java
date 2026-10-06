package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One MTLCommandBuffer per submit (per frame). Metal tracks hazards on its own, so unlike the Vulkan backend there
 * are no barriers: copies, clears and passes are simply recorded in order.
 */
final class MetalEncoder implements CommandEncoderBackend {
	/**
	 * Same pacing as the Vulkan backend: submitting frame N waits for frame N-2. -Dmcopt.metal.inFlight=0 waits for each frame
	 * itself, so no two frames' GPU work overlaps and a GPU trace times each encoder alone (slow; don't bench it).
	 */
	private static final int MAX_IN_FLIGHT = Integer.getInteger("mcopt.metal.inFlight", 2);
	/**
	 * -Dmcopt.metal.trace=N prints every encoder operation of submit N, the tool for finding pass boundaries worth removing,
	 * and the average per-encoder GPU time of submits N to N + PROFILE_FRAMES - 1. Traced runs are a few % slower; don't bench them.
	 */
	private static final long TRACE_SUBMIT = Long.getLong("mcopt.metal.trace", -1);
	/** Above this, an upload is cheaper as an async GPU blit than as a memcpy on the render thread. */
	private static final long CPU_COPY_MAX = 256 << 10;
	private static final int PROFILE_FRAMES = 100, PROFILE_ENCODERS = 16;
	private static final boolean GPU_TIMES = Boolean.getBoolean("mcopt.metal.gpuTimes");
	/**
	 * -Dmcopt.metal.presentQueue=true (idea: ByteV0rtex, noahdunnagan/mcopt#2): present from a second command queue, so the frame's
	 * command buffer, which the render thread waits on, never touches the drawable (mc_present_queued in mcmetal.m).
	 */
	static final boolean PRESENT_QUEUE = "true".equals(System.getProperty("mcopt.metal.presentQueue")) || "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));
	/** -Dmcopt.metal.presentQueue=acquire: also acquire the drawable on the present side, so the render thread never waits in nextDrawable. */
	static final boolean PRESENT_ACQUIRE = "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));
	private static final boolean STATS = Boolean.getBoolean("mcopt.metal.stats");

	final long ctx;
	final long enc;
	final MetalTransientMemory transientMemory;
	/** Draws Sodium's solid and cutout terrain with occlusion on (-Dmcopt.metal.occ=true); see MetalRenderPass.recording. */
	final MetalTerrain terrain;
	private final ArrayDeque<Frame> inFlight = new ArrayDeque<>();
	private List<Runnable> afterThisFrame = new ArrayList<>();
	private final Set<MetalTexture> pendingClears = new HashSet<>();
	private long submitIndex;
	private long completedIndex = -1;
	/** Render encoders opened in this submit; the trace's #numbers, which the GPU profile lines refer to. */
	private int encoderIndex;
	private @Nullable MetalRenderPass currentPass;
	/** Recording the current submit's terrain draws for the visibility probe (-Dmcopt.metal.probe=N); null otherwise. */
	@Nullable MetalProbe probe;
	/** Summed GPU µs over the profiled frames: whole submit, then vertex and fragment per encoder. */
	private final double[] profileSums = new double[1 + 2 * PROFILE_ENCODERS];
	private int profiledFrames;
	private long waitNanos, statStart, statTerrainFrames;
	private int statFrames, statPresents;

	private record Frame(long index, long cmd, List<Runnable> after) {
	}

	MetalEncoder(long ctx) {
		this.ctx = ctx;
		this.enc = Native.encNew(ctx);
		if (GPU_TIMES) GpuTimes.initialize();
		this.transientMemory = new MetalTransientMemory(ctx, this);
		this.terrain = new MetalTerrain(this);
	}

	boolean tracing() {
		return this.submitIndex == TRACE_SUBMIT;
	}

	void trace(String op, Object... args) {
		if (!this.tracing()) return;
		StringBuilder line = new StringBuilder("mcopt-metal trace: ").append(op);
		for (Object a : args) line.append(' ').append(a instanceof GpuTexture t ? t.getLabel() : a instanceof GpuTextureView v ? v.texture().getLabel() : a);
		System.out.println(line);
	}

	/** Marks a buffer as referenced by the submit being recorded. Every GPU reference to a buffer must go through here. */
	MetalBuffer use(GpuBuffer buffer) {
		MetalBuffer b = (MetalBuffer) buffer;
		b.lastUse = this.submitIndex;
		return b;
	}

	/** True when no recorded or in-flight GPU work touches b, so the CPU may read and write it right now. */
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
		this.trace("submit");
		this.transientMemory.endSubmit();
		long samples = 0;
		int sampleCount = 0;
		if (profiled(this.submitIndex)) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long count = stack.ncalloc(4, 1, 4);
				samples = Native.profileEnd(this.enc, count);
				sampleCount = MemoryUtil.memGetInt(count);
			}
		}
		this.terrain.releaseHeld(true);
		MetalEvents.Operation commitEvent = MetalEvents.begin("commit", this.submitIndex, 0);
		long cmd;
		try {
			if (GPU_TIMES) GpuTimes.submit(this.submitIndex);
			cmd = Native.encCommit(this.enc);
		} finally {
			MetalEvents.end(commitEvent);
		}
		if (this.probe != null) {
			Native.cmdWait(cmd); // the probe reads back what this frame's terrain culling kept
			this.probe.run(this.ctx, this.terrain.drawnForProbe);
			this.terrain.drawnForProbe.clear();
			this.probe = null;
		}
		this.terrain.endFrame();
		if (samples != 0) {
			long s = samples;
			int n = sampleCount;
			this.afterThisFrame.add(() -> this.accumulateProfile(cmd, s, n));
		}
		this.inFlight.add(new Frame(this.submitIndex++, cmd, this.afterThisFrame));
		if (mcopt.metal.cpu.Cpu.FENCE_STATS) mcopt.metal.cpu.Cpu.fenceTick(this.submitIndex); // opt-in
		this.afterThisFrame = new ArrayList<>();
		this.encoderIndex = 0;
		if (this.submitIndex == MetalProbe.SUBMIT) this.probe = new MetalProbe();
		if (profiled(this.submitIndex) && !Native.profileBegin(this.enc, 512)) System.out.println("mcopt-metal trace: no GPU timestamps on this device");
		while (this.inFlight.size() > MAX_IN_FLIGHT) this.retire(this.inFlight.poll());
		if (STATS) this.stats();
	}

	/** Once a second: how much of each frame the render thread spent blocked on the GPU. Near zero means the CPU is the limit. */
	private void stats() {
		long now = System.nanoTime();
		this.statFrames++;
		if (this.statStart == 0) this.statStart = now;
		if (now - this.statStart < 1_000_000_000L) return;
		double frameMs = (now - this.statStart) / 1e6 / this.statFrames;
		System.out.printf("mcopt-metal stats: %d fps, %.3f ms/frame, %.3f ms of it waiting on the GPU, %d presents%s%n", this.statFrames,
			frameMs, this.waitNanos / 1e6 / this.statFrames, this.statPresents, (PRESENT_QUEUE ? " (queued" + (PRESENT_ACQUIRE ? ", acquired on the present side" : "") + "; skipped so far " + Native.presentSkipped() + ", dropped " + Native.presentDropped() + ")" : "")
			+ (MetalSurface.PACE_ADAPT ? String.format(" (pace margin +%.2f ms learned)", Native.paceExtraMs()) : ""));
		if (MetalTerrain.OCC) {
			long[] t = this.terrain.lastCompletedCounts();
			System.out.printf("mcopt-metal stats: terrain drew %d of %d quads (%.1f%%), %d before the split, %d chunks (%.1f quads each), %d frames drew terrain%n", t[0], t[1],
				100.0 * t[0] / Math.max(1, t[1]), t[2], t[3], (double) t[1] / Math.max(1, t[3]), this.terrain.terrainFrames - this.statTerrainFrames);
			this.statTerrainFrames = this.terrain.terrainFrames;
		}
		this.statStart = now;
		this.statFrames = 0;
		this.statPresents = 0;
		this.waitNanos = 0;
	}

	/** GPU timestamps cover the traced submit and the PROFILE_FRAMES - 1 after it; one frame alone is too noisy to compare shader variants. */
	private static boolean profiled(long submit) {
		return TRACE_SUBMIT >= 0 && submit >= TRACE_SUBMIT && submit < TRACE_SUBMIT + PROFILE_FRAMES;
	}

	private void accumulateProfile(long cmd, long samples, int count) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, Math.max(1, count) * 8);
			Native.profileRead(this.ctx, samples, count, out);
			this.profileSums[0] += Native.cmdGpuMicros(cmd);
			for (int e = 0; e < Math.min(count / 4, PROFILE_ENCODERS); e++) {
				double vs = MemoryUtil.memGetDouble(out + e * 32L), ve = MemoryUtil.memGetDouble(out + e * 32L + 8);
				double fs = MemoryUtil.memGetDouble(out + e * 32L + 16), fe = MemoryUtil.memGetDouble(out + e * 32L + 24);
				if (vs >= 0) this.profileSums[1 + 2 * e] += ve - vs;
				if (fs >= 0) this.profileSums[2 + 2 * e] += fe - fs;
			}
		}
		if (++this.profiledFrames < PROFILE_FRAMES) return;
		double[] avg = Arrays.stream(this.profileSums).map(v -> v / PROFILE_FRAMES).toArray();
		System.out.printf("mcopt-metal gpu: submits %d-%d took %.0f us on the GPU on average%n", TRACE_SUBMIT, TRACE_SUBMIT + PROFILE_FRAMES - 1, avg[0]);
		for (int e = 0; e < PROFILE_ENCODERS && avg[1 + 2 * e] + avg[2 + 2 * e] > 0; e++) {
			System.out.printf("mcopt-metal gpu: #%d vertex %6.0f us, fragment %6.0f us%n", e, avg[1 + 2 * e], avg[2 + 2 * e]);
		}
	}

	private void retire(Frame frame) {
		long waitStart = System.nanoTime();
		MetalEvents.Operation event = MetalEvents.begin("wait", frame.index, 0);
		try {
			Native.cmdWait(frame.cmd);
			if (GPU_TIMES) GpuTimes.retire(frame.index, frame.cmd);
			if (event != null) event.gpuMicros = Native.cmdGpuMicros(frame.cmd);
		} finally {
			MetalEvents.end(event);
		}
		this.waitNanos += System.nanoTime() - waitStart;
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
		// Shaderpack runtime (-Dmcopt.pack): the pass may draw into the pack's targets instead of its color attachments.
		MetalHooks.PassRedirector redirector = MetalHooks.redirector;
		MetalHooks.Redirect redirect = redirector == null ? null : redirector.redirect(descriptor);
		// A redirect that keeps the pass's attachments only hands its pipelines to the delegate (native shading's lite tier).
		PassDelegate keptDelegate = redirect != null && redirect.keepsAttachments() ? redirect.delegate() : null;
		if (keptDelegate != null) redirect = null;
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = redirect != null ? List.of() : descriptor.colorAttachments();
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = redirect != null && redirect.depth() != 0 ? null : descriptor.depthAttachment();
		// Anything sampled inside this pass must have its deferred clear in memory before the pass starts.
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
			this.forgetSettledClears();
			int colorCount = colors.size();
			if (redirect != null) {
				colorHandles = stack.nmalloc(8, Math.max(1, redirect.colors().length) * 8);
				clears = stack.ncalloc(4, Math.max(1, redirect.colors().length) * 5, 4);
				for (int i = 0; i < redirect.colors().length; i++) {
					MemoryUtil.memPutAddress(colorHandles + i * 8L, redirect.colors()[i]);
					float[] c = redirect.clears() == null ? null : redirect.clears()[i];
					if (c != null && c.length == 0) MemoryUtil.memPutFloat(clears + i * 20L, 2); // MetalBridge.DONT_CARE: not loaded
					else if (c != null) putClear(clears + i * 20L, new org.joml.Vector4f(c[0], c[1], c[2], c[3]));
				}
				colorCount = redirect.colors().length;
				width = redirect.width();
				height = redirect.height();
				if (redirect.depth() != 0) {
					depthHandle = redirect.depth();
					clearDepth = !Float.isNaN(redirect.depthClear());
					depthValue = clearDepth ? redirect.depthClear() : 0;
				}
			}
			if (mcopt.metal.cpu.Cpu.PASS_AB) { // opt-in A/B: lever on odd frames
				Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags((mcopt.metal.cpu.Ab.frame & 1) == 1));
				mcopt.metal.cpu.Cpu.PASS_TIMER.begin();
			}
			int continued = Native.renderBegin(this.enc, colorCount, colorHandles, clears, depthHandle, clearDepth ? 1 : 0, (float) depthValue, width, height);
			if (mcopt.metal.cpu.Cpu.PASS_AB) mcopt.metal.cpu.Cpu.PASS_TIMER.end();
			if (redirect != null) redirect.delegate().begin(this.enc, depthHandle != 0);
			if (keptDelegate != null) keptDelegate.begin(this.enc, depthHandle != 0);
			MetalHooks.Labeler labeler = MetalHooks.labeler;
			if (labeler != null && continued == 0) labeler.label(this.enc, "game " + descriptor.label().get());
			if (this.submitIndex == TRACE_SUBMIT) {
				StringBuilder targets = new StringBuilder();
				for (int i = 0; i < colors.size(); i++) {
					var c = colors.get(i);
					targets.append(c == null ? "-" : c.textureView().texture().getLabel() + (MemoryUtil.memGetFloat(clears + i * 20L) != 0 ? "(clear)" : "")).append(' ');
				}
				if (depth != null) targets.append("depth=").append(depth.textureView().texture().getLabel()).append(clearDepth ? "(clear)" : "");
				String how = switch (continued) { case 1 -> "pass (merged)"; case 2 -> "pass (merged, depth cleared in place)"; default -> "pass #" + this.encoderIndex; };
				this.trace(how, "'" + descriptor.label().get() + "'", targets, width + "x" + height);
			}
			if (continued == 0) this.encoderIndex++;
		}
		var area = descriptor.renderArea();
		if (redirect != null && redirect.depth() != 0) {
			this.currentPass = new MetalRenderPass(this, true, 0, 0, redirect.width(), redirect.height(), redirect.delegate());
			return this.currentPass;
		}
		this.currentPass = new MetalRenderPass(this, depth != null, area.x(), area.y(), area.width(), area.height(),
			redirect != null ? redirect.delegate() : keptDelegate);
		return this.currentPass;
	}

	private boolean isAttachment(MetalTexture t, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors,
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth) {
		for (var c : colors) {
			if (c != null && c.textureView().texture() == t && c.textureView().baseMipLevel() == 0) return true;
		}
		return depth != null && depth.textureView().texture() == t;
	}

	private void forgetSettledClears() {
		this.pendingClears.removeIf(t -> !t.hasPendingClear());
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
		this.currentPass.end();
		this.trace("  end, draws:", this.currentPass.draws, "triangles:", this.currentPass.indices / 3);
		this.currentPass = null;
	}

	boolean inRenderPass() {
		return this.currentPass != null;
	}

	/** Far terrain (mcopt.metal.lod): the open pass's pipeline state again after its own draw; false when no pass is open. */
	boolean reapplyPipeline() {
		if (this.currentPass == null) return false;
		this.currentPass.reapplyPipeline();
		return true;
	}

	/** Forgets a pending clear of t: something is about to overwrite all of it (the shaderpack's final pass). */
	void dropPendingClear(MetalTexture t) {
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	/** Writes a deferred clear to memory with an empty pass: needed when the texture is read before it is drawn to. */
	void flushClear(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean depthFormat = t.getFormat().hasDepthAspect();
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, depthFormat ? 0 : t.handle);
			if (t.pendingColorClear != null) putClear(clears, t.pendingColorClear);
			boolean clearDepth = !Double.isNaN(t.pendingDepthClear);
			int continued = Native.renderBegin(this.enc, depthFormat ? 0 : 1, colors, clears, depthFormat ? t.handle : 0, clearDepth ? 1 : 0,
				clearDepth ? (float) t.pendingDepthClear : 0, t.getWidth(0), t.getHeight(0));
			this.trace(continued == 0 ? "flushClear #" + this.encoderIndex : "flushClear (in place)", t);
			if (continued == 0) this.encoderIndex++;
		}
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	void flushClear(GpuTexture t) {
		this.flushClear((MetalTexture) t);
	}

	private void deferClear(GpuTexture texture, @Nullable Vector4fc color, double depth) {
		MetalTexture t = (MetalTexture) texture;
		if (t.getMipLevels() != 1) {
			// Rare (only mip chains); clear every level for real rather than tracking per-level state.
			for (int mip = 0; mip < t.getMipLevels(); mip++) this.clearRegion(t, color, null, depth, 0, 0, t.getWidth(mip), t.getHeight(mip), mip);
			return;
		}
		if (color != null) t.pendingColorClear = color;
		if (!Double.isNaN(depth)) t.pendingDepthClear = depth;
		this.pendingClears.add(t);
		Native.discard(this.enc, t.handle); // old contents are dead: if the open encoder renders to t, skip writing it back
		this.trace("deferClear", t);
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
		int x, int y, int width, int height, int mip) {
		if (mip == 0 && x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		} else {
			this.clearRegion((MetalTexture) colorTexture, clearColor, (MetalTexture) depthTexture, clearDepth, x, y, width, height, mip);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		this.deferClear(depthTexture, null, clearDepth);
	}

	private void clearRegion(@Nullable MetalTexture color, @Nullable Vector4fc clearColor, @Nullable MetalTexture depth, double clearDepth,
		int x, int y, int width, int height, int mip) {
		this.trace("clearRect", color, depth, x, y, width, height, mip);
		if (color != null) this.flushClear(color);
		if (depth != null) this.flushClear(depth);
		Vector4fc c = clearColor != null ? clearColor : new org.joml.Vector4f();
		Native.clearRect(this.enc, color != null ? color.handle : 0, depth != null ? depth.handle : 0, c.x(), c.y(), c.z(), c.w(),
			Double.isNaN(clearDepth) ? -1 : (float) clearDepth, x, y, width, height, mip);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalEvents.Operation event = MetalEvents.begin("upload", this.submitIndex, data.remaining());
		try {
			MetalBuffer dst = (MetalBuffer) destination.buffer();
			if (event != null) event.arena = dst.arena;
			// A blit would end the open render encoder, and wait for every command before it that reads dst. A buffer the GPU isn't
			// using can just be written, it is the same memory; one it is using can move to fresh memory if the write replaces all of it.
			boolean whole = destination.offset() == 0 && data.remaining() == dst.size();
			if (data.remaining() <= CPU_COPY_MAX && (this.idle(dst) || whole && dst.rename())) {
				this.trace("writeToBuffer (cpu)", data.remaining());
				MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address + destination.offset(), data.remaining());
				if (dst.arena) this.terrain.invalidate(dst, destination.offset(), data.remaining());
				return;
			}
			this.trace("writeToBuffer", data.remaining());
			GpuBufferSlice staging = this.transientMemory.uploadGpu(data, 1L, GpuBuffer.USAGE_COPY_SRC);
			this.blitBuffer(this.use(staging.buffer()), staging.offset(), this.use(dst), destination.offset(), data.remaining());
		} finally {
			MetalEvents.end(event);
		}
	}

	/** In a frame that splits, Sodium geometry copied before its culling waits for the split (see MetalTerrain.hold); the rest goes in order. */
	private void blitBuffer(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		if (dst.arena && this.terrain.split && !this.terrain.culledThisFrame()) {
			this.terrain.hold(src, srcOffset, dst, dstOffset, size);
		} else {
			if (src.arena || dst.arena) this.terrain.releaseHeld(false); // earlier arena copies land first
			Native.blitBuffer(this.enc, src.handle, srcOffset, dst.handle, dstOffset, size);
		}
		if (dst.arena) this.terrain.invalidate(dst, dstOffset, size);
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		MetalEvents.Operation event = MetalEvents.begin("copy", this.submitIndex, source.length());
		try {
			MetalBuffer src = (MetalBuffer) source.buffer(), dst = (MetalBuffer) target.buffer();
			if (event != null) event.arena = src.arena || dst.arena;
			if (src.arena || dst.arena) MetalEvents.arenaTransfer("copy", source.length(), src.size(), dst.size());
			if (source.length() <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
				this.trace("copyToBuffer (cpu)", source.length());
				MemoryUtil.memCopy(src.address + source.offset(), dst.address + target.offset(), source.length());
				if (dst.arena) this.terrain.invalidate(dst, target.offset(), source.length());
				return;
			}
			this.trace("copyToBuffer", source.length());
			this.blitBuffer(this.use(src), source.offset(), this.use(dst), target.offset(), source.length());
		} finally {
			MetalEvents.end(event);
		}
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mip, int layer, int x, int y, int width, int height) {
		this.trace("writeToTexture", destination, width + "x" + height);
		this.flushClear(destination);
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		Native.blitBufferToTexture(this.enc, this.use(staging.buffer()).handle, staging.offset(), bytesPerRow, bytesPerRow * height,
			((MetalTexture) destination).handle, layer, mip, x, y, width, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destX, int destY, int copyWidth, int copyHeight, int mip, int layer) {
		this.trace("copyBufferToTexture", destination, copyWidth + "x" + copyHeight);
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
		this.trace("copyTextureToBuffer", source, width + "x" + height);
		this.flushClear(source);
		Native.blitTextureToBuffer(this.enc, ((MetalTexture) source).handle, mip, x, y, width, height, this.use(destination).handle, offset,
			width * source.getFormat().blockSize());
		this.afterGpuFinishes(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mip, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		this.trace("copyTextureToTexture", source, destination, width + "x" + height);
		this.flushClear(source);
		this.flushClear(destination);
		Native.blitTextureToTexture(this.enc, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mip, destX, destY, sourceX, sourceY,
			width, height);
	}

	/** MetalFX: rebuilds dst (full size) from color and depth (render scale); see MetalFx. dst's old contents don't matter. */
	void upscale(long fx, MetalTexture color, MetalTexture depth, MetalTexture dst, long reproject, float jitterX, float jitterY, float motionSign,
		boolean reset) {
		if (this.currentPass != null) throw new IllegalStateException("Cannot upscale inside a render pass");
		this.trace("upscale", color, "->", dst, reset ? "(reset)" : "");
		this.flushClear(color);
		this.flushClear(depth);
		dst.pendingColorClear = null;
		this.pendingClears.remove(dst);
		Native.fxUpscale(this.enc, fx, color.handle, depth.handle, dst.handle, reproject, jitterX, jitterY, motionSign, reset ? 1 : 0);
	}

	@Override
	public GpuFence createFence() {
		long current = this.submitIndex;
		// opt-in (-Dmcopt.cpu.fence): a fence made right after submit() with nothing recorded since (vanilla's uniform
		// ring rotates its slot there) covers exactly the work already committed, so it names the last submit instead of the
		// next, still empty one, and the ring's slot is free a frame sooner.
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
				long t0 = mcopt.metal.cpu.Cpu.FENCE_STATS && timeoutNs != 0 ? System.nanoTime() : 0;
				while (MetalEncoder.this.completedIndex < index) {
					Frame oldest = MetalEncoder.this.inFlight.peek();
					if (timeoutNs == 0 && !Native.cmdDone(oldest.cmd)) return false;
					MetalEncoder.this.retire(MetalEncoder.this.inFlight.poll());
				}
				if (t0 != 0) mcopt.metal.cpu.Cpu.fenceWaited(System.nanoTime() - t0);
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

	/** -Dmcopt.metal.presentQueue=acquire: this frame's image goes to a staging slot; the present side acquires the drawable and presents it. */
	void presentAcquire(long layer, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.flushClear(view.texture());
			if (Native.presentQueuedAcquire(this.enc, layer, ((MetalTexture.View) view).handle, GPU_TIMES && this.submitIndex < Integer.MAX_VALUE ? this.submitIndex : -1) == 0) return;
			if (GPU_TIMES) GpuTimes.presentQueued(this.submitIndex);
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}

	void presentTexture(long drawable, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.flushClear(view.texture());
			if (PRESENT_QUEUE) {
				// the frame's command buffer only fills a staging slot; the present goes out on a second queue after the commit
				if (Native.presentQueued(this.enc, drawable, ((MetalTexture.View) view).handle) == 0) return; // slot still being read: skip
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
			} else {
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
				Native.present(this.enc, drawable, ((MetalTexture.View) view).handle);
			}
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}
}
