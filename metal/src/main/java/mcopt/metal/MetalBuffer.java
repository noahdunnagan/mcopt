package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

/**
 * A shared-storage MTLBuffer. On Apple silicon the CPU mapping *is* the GPU memory, so map() is a pointer
 * offset and uploads never need a staging copy. Mapping is unsynchronized, exactly like the Vulkan backend.
 */
class MetalBuffer extends BaseGpuBuffer {
	/** The memory, which rename() can swap. */
	long handle, address;
	/** Submit index of the last command that touched this buffer on the GPU; see MetalEncoder.idle. */
	long lastUse = -1;
	/** One of Sodium's arena buffers (its terrain geometry), with MetalTerrain on: GPU copies into it go to the pre command buffer. */
	boolean arena;
	private final MetalEncoder encoder;
	private boolean closed, mapped;
	/** Memory rename() moved off and the GPU is done with, for the next rename. */
	private final ArrayDeque<Long> spares = new ArrayDeque<>();

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size) {
		this(encoder, handle, usage, size, Native.bufferContents(handle));
	}

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size, long address) {
		super(usage, size);
		this.encoder = encoder;
		this.handle = handle;
		this.address = address;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.handle);
			for (long spare : this.spares) this.encoder.releaseLater(spare);
			this.spares.clear();
		}
	}

	/**
	 * Moves this buffer onto other memory, for a write replacing all of it while the GPU may still read the old contents:
	 * commands encoded before keep reading those, and the old memory is reused once this submit is done with it. False for
	 * a buffer someone mapped, whose view would keep pointing at the old memory.
	 */
	boolean rename() {
		if (this.mapped || this.arena) return false;
		long old = this.handle;
		Long spare = this.spares.poll();
		this.handle = spare != null ? spare : Native.bufferNew(this.encoder.ctx, this.size());
		this.address = Native.bufferContents(this.handle);
		this.lastUse = -1;
		this.encoder.afterGpuFinishes(() -> {
			if (this.closed) Native.release(old);
			else this.spares.add(old);
		});
		return true;
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (this.closed) throw new IllegalStateException("Buffer already closed");
		if (offset < 0 || length < 0 || offset + length > this.size()) throw new IllegalArgumentException("Mapping out of range");
		this.mapped = true;
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, Math.toIntExact(length)), () -> {});
	}
}
