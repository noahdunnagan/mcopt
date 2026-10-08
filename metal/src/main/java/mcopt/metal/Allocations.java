package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class Allocations {
	/**
	 * Sodium grows its terrain geometry in arenas of up to 256 MiB, created the moment a region no longer fits (flight 5.34 s:
	 * 179 MB). A fresh buffer's memory is found and wired in the submission of the first command buffer using it, the whole
	 * buffer at once, and when free memory is short the kernel compresses other memory first: 36-45 ms of one frame on the mini
	 * with a 4 GiB heap. So one arena of this size (and the two culling caches MetalTerrain adds per arena) is kept provisioned
	 * ahead of need, off the render thread (Native.poolAdd), and handed to the next arena request it can hold, with its full
	 * size: Sodium sizes the arena from buffer.size(). Costs its size in memory while it waits. 0 turns it off.
	 */
	private static final long ARENA_RESERVE = Long.getLong("mcopt.metal.arenaReserve", 256L << 20);
	/** Wiring costs about 0.03 ms per MiB with memory free and 0.2-0.25 ms per MiB when short: only big arenas stall a frame. */
	private static final long ARENA_RESERVE_MIN = 64L << 20;

	private final long ctx;
	private final MetalEncoder encoder;
	/** A provisioned arena waits in the pool (at most one at a time). */
	private boolean arenaReserved;

	Allocations(long ctx, MetalEncoder encoder) {
		this.ctx = ctx;
		this.encoder = encoder;
	}

	/** Queues an ARENA_RESERVE arena for provisioning, and with culling on the two caches MetalTerrain.ArenaCache adds for it. */
	private boolean reserveArena(int delayMs) {
		if (!Native.poolAdd(this.ctx, ARENA_RESERVE, delayMs)) return false;
		if (MetalTerrain.OCC) {
			// ArenaCache's bounds and boxes: 8 bytes per quad slot, a slot being 4 vertices of 20 bytes (+2 slots). If this drifts
			// from MetalTerrain the pool just doesn't match them (it hands out buffers at most a quarter bigger than asked for).
			long cache = (ARENA_RESERVE / 80 + 2) * 8;
			for (int i = 0; i < 2; i++) Native.poolAdd(this.ctx, cache, delayMs);
		}
		return true;
	}

	MetalTexture texture(@Nullable String label, int usage, GpuFormat format, int width, int height, int layers, int mips) {
		// MTLTextureUsage: ShaderRead 1, RenderTarget 4. Copies need no usage bit in Metal.
		int mtlUsage = ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? 1 : 0) | ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? 4 : 0);
		// Split-pass occlusion builds its hi-Z from the main pass's depth.
		// Shaderpacks sample the game's depth (depthtex0) too.
		if (MetalTerrain.OCC && format == GpuFormat.D32_FLOAT) mtlUsage |= 1;
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long handle = Native.textureNew(this.ctx, MetalConst.pixelFormat(format), width, height, layers, mips, Math.max(1, mtlUsage), cube ? 1 : 0);
		if (handle == 0) throw new IllegalStateException("Couldn't create " + width + "x" + height + " " + format + " texture " + label);
		return new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mips);
	}

	MetalBuffer buffer(@Nullable Supplier<String> label, int usage, long size) {
		MetalEvents.Operation event = MetalEvents.begin("allocate", -1, size);
		// Sodium's ArenaAggregator names every geometry and index arena this way.
		boolean arena = label != null && "Arena buffer".equals(label.get());
		MetalBuffer buffer;
		try {
			long handle = 0, actual = size;
			if (arena && ARENA_RESERVE > 0 && size >= ARENA_RESERVE_MIN) {
				// At most twice what was asked for: Sodium fills an arena over time, a much bigger one would mostly sit empty.
				if (size <= ARENA_RESERVE && size * 2 >= ARENA_RESERVE) handle = Native.poolTake(size, ARENA_RESERVE);
				if (handle != 0) {
					actual = Native.bufferLength(handle);
					this.arenaReserved = false;
				}
				// The next one a second later: provisioning takes memory, and this frame is already finding some for the arena's use.
				if (!this.arenaReserved) this.arenaReserved = this.reserveArena(handle != 0 ? 1000 : 0);
			}
			buffer = new MetalBuffer(this.encoder, handle != 0 ? handle : Native.bufferNew(this.ctx, size), usage, actual);
		} finally {
			MetalEvents.end(event);
		}
		buffer.arena = MetalTerrain.OCC && arena;
		if (buffer.arena) MetalEvents.arenaTransfer("allocate", size, 0, size);
		return buffer;
	}
}
