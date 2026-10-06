package mcopt.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

final class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 1L << 20;

	private final long ctx;
	private final MetalEncoder encoder;
	private final ArrayDeque<MetalBuffer> free = new ArrayDeque<>();
	private List<MetalBuffer> used = new ArrayList<>();
	private @Nullable MetalBuffer block;
	private long blockOffset;
	private long submitIndex;

	MetalTransientMemory(long ctx, MetalEncoder encoder) {
		this.ctx = ctx;
		this.encoder = encoder;
	}

	void endSubmit() {
		List<MetalBuffer> done = this.used;
		this.used = new ArrayList<>();
		this.block = null;
		this.encoder.afterGpuFinishes(() -> {
			for (MetalBuffer b : done) {
				if (b.size() == BLOCK_SIZE) this.free.add(b);
				else Native.release(b.handle);
			}
		});
		this.submitIndex++;
	}

	private long allocate(long size, long alignment) {
		if (this.block != null) {
			long offset = Mth.roundToward(this.blockOffset, Math.max(1, alignment));
			if (offset + size <= this.block.size()) {
				this.blockOffset = offset + size;
				return offset;
			}
		}
		long capacity = Math.max(BLOCK_SIZE, size);
		MetalBuffer next = capacity == BLOCK_SIZE ? this.free.poll() : null;
		this.block = next != null ? next : new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, capacity), 0, capacity);
		this.used.add(this.block);
		this.blockOffset = size;
		return 0;
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		long offset = this.allocate(size, alignment);
		return MemoryUtil.memByteBuffer(this.block.address + offset, (int) size);
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		long offset = this.allocate(size, alignment);
		Transient buffer = new Transient(this.block, usage, this.submitIndex);
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, offset, size), MemoryUtil.memByteBuffer(buffer.address + offset, (int) size), () -> {});
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
			MemoryUtil.memCopy(MemoryUtil.memAddress(b), dst + offset, b.remaining());
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

	private final class Transient extends MetalBuffer {
		private final long submit;

		Transient(MetalBuffer block, @GpuBuffer.Usage int usage, long submit) {
			super(MetalTransientMemory.this.encoder, block.handle, usage, block.size(), block.address);
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
