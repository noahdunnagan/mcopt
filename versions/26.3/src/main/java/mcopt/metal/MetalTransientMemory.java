package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit scratch memory. Vulkan keeps separate staging, device and mapped-device pools and copies between them;
 * with unified memory all three are the same shared MTLBuffer blocks, so every "upload" is a single memcpy.
 */
final class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 1L << 20;

	private final MetalEncoder encoder;
	private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu =
		new TransientBlockAllocator<>(BLOCK_SIZE, 16L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
	private final TransientBlockAllocator<Block> gpu;
	private long submitIndex;

	MetalTransientMemory(long ctx, MetalEncoder encoder) {
		this.encoder = encoder;
		this.gpu = new TransientBlockAllocator<>(BLOCK_SIZE, 1L << 62,
			TransientBlockAllocator.Allocator.create(size -> new Block(encoder, Native.bufferNew(ctx, size), size), block -> Native.release(block.buffer.handle)));
	}

	/** Called at submit: this submit's blocks become reusable once the GPU has finished with it. */
	void endSubmit() {
		this.cpu.rotate().run();
		this.encoder.afterGpuFinishes(this.gpu.rotate());
		this.submitIndex++;
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> a = this.cpu.allocate(size, alignment, minimumAllocation, elementSize);
		return MemoryUtil.memByteBuffer(a.block().address() + a.offset(), (int) a.size());
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		TransientBlockAllocator.Allocation<Block> a = this.gpu.allocate(size, alignment, minimumAllocation, elementSize);
		Transient buffer = new Transient(a.block(), usage, this.submitIndex);
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, a.offset(), a.size()),
			MemoryUtil.memByteBuffer(buffer.address + a.offset(), (int) a.size()), () -> {});
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize).slice();
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		long total = 0;
		for (ByteBuffer b : data) total = Mth.roundToward(total + b.remaining(), alignment);
		GpuBufferSlice.MappedView mapped = this.allocateStaging(total, alignment, usage, minimumAllocation, elementSize);
		long dst = MemoryUtil.memAddress(mapped.data());
		long offset = 0;
		for (ByteBuffer b : data) {
			if (offset >= mapped.slice().length()) break;
			MemoryUtil.memCopy(MemoryUtil.memAddress(b), dst + offset, Math.min(mapped.slice().length() - offset, b.remaining()));
			offset = Mth.roundToward(offset + b.remaining(), alignment);
		}
		return mapped.slice();
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		return this.multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> out = new ArrayList<>(data.size());
		for (ByteBuffer b : data) out.add(this.uploadGpu(List.of(b), alignment, usage, b.remaining(), 1L));
		return out;
	}

	private record Block(MetalBuffer buffer, long size) implements TransientBlockAllocator.Allocator.Block {
		Block(MetalEncoder encoder, long handle, long size) {
			this(new MetalBuffer(encoder, handle, 0, size), size);
		}

		@Override
		public boolean suboptimal() {
			return false;
		}
	}

	/** An API-facing view of a block that stops being valid once its submit is over; the block itself is pooled. */
	private final class Transient extends MetalBuffer {
		private final long submit;

		Transient(Block block, @GpuBuffer.Usage int usage, long submit) {
			super(MetalTransientMemory.this.encoder, block.buffer.handle, usage, block.size, block.buffer.address);
			this.submit = submit;
		}

		@Override
		public boolean isClosed() {
			return this.submit < MetalTransientMemory.this.submitIndex;
		}

		@Override
		public void close() {
		}

		@Override
		boolean rename() {
			return false;
		}

		@Override
		public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}
	}
}
