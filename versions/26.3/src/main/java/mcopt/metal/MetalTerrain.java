package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Split-pass occlusion of Sodium's solid and cutout terrain, which it then draws itself. -Dmcopt.metal.occ=true splits every
 * frame, false never, and by default MetalOccPick measures which of the two is faster here (shader packs: never).
 * MetalRenderPass records Sodium's per-region multi-draws instead of encoding them; when the pass moves on, flush() splits the
 * recorded ranges into 64-quad chunks and draws the quads that showed last frame (solid's, then cutout's) with one indirect
 * instanced draw of the pipeline's pulled twin (sodium_terrain.vs.metal with PULLED), listed in the pre command buffer so the
 * list is ready when the frame starts. Then the render pass is split once, its depth becomes hi-Z, every quad is tested
 * against it (what passes shows next frame), and of the rest only those not wholly behind that depth are drawn (solid's,
 * then cutout's). Exact: what's skipped is behind depth already in the frame, and what's drawn keeps Sodium's order and
 * shading, so the image is the same; Sodium never knows, and ~2000 draw calls a frame become a few.
 */
final class MetalTerrain {
	private static final String MODE = System.getProperty("mcopt.metal.occ", "auto");
	/** The split's machinery is built: its pipelines, arena tracking and pre command buffer. */
	static final boolean OCC = !MODE.equals("false");
	/** Buffer slots of the pulled vertex shader, above any pipeline's uniforms. */
	static final int QUADS_SLOT = 20, REGIONS_SLOT = 21, ARENAS_SLOT = 22;
	private static final int CHUNK_QUADS = 64, MAX_ARENAS = 8, REGION_BYTES = 20, CHUNK_BYTES = 8, VERTEX_BYTES = 20;
	/** A flush's arena tables (see arenaTables) and MSL Frame, back to back. */
	private static final int TABLES_BYTES = 2 * 5 * MAX_ARENAS * 8, FRAME_BYTES = 96;
	/** Bounds and box cache entry per quad slot: the quad's box, or the box of the chunk starting there; see mcterrain.m. */
	private static final int BOUNDS_BYTES = 8;
	/** Frames whose work buffers may still be in use by the GPU: the one being recorded plus MetalEncoder's two in flight. */
	private static final int FRAMES = 3, MAX_FLUSHES = 4;

	private final MetalEncoder encoder;
	private final long handle;
	private final MetalOccPick pick = new MetalOccPick();
	/** This frame's terrain goes through the split. */
	boolean split = OCC;
	/** Pixels of this frame's terrain pass, 0 until MetalRenderPass sees Sodium terrain. */
	long terrainPixels;
	/** 64 quads of {0, 1, 2, 2, 3, 0} + 4q as uint16: within an instance, vertex_id = quad * 4 + corner. Private: see mc_buffer_private. */
	private final MetalBuffer indices;
	private final List<MetalBuffer> arenas = new ArrayList<>(MAX_ARENAS);
	private final Map<MetalBuffer, ArenaCache> caches = new HashMap<>();
	/** Arenas whose visibility bits for this frame are cleared already. */
	private final Set<MetalBuffer> cleared = new HashSet<>();
	/** Sodium's latest push constants: the region the next multi-draw belongs to. */
	private final long pushConstants = MemoryUtil.nmemCalloc(1, REGION_BYTES);
	private long chunks = MemoryUtil.nmemAlloc(CHUNK_BYTES * 8192L), regions = MemoryUtil.nmemAlloc(REGION_BYTES * 1024L);
	private int chunkCapacity = 8192, regionCapacity = 1024, chunkCount, regionCount;
	/** Per frame slot and flush, the second draw's arguments in its work buffer (args holds the first's). */
	private final long[][] secondArgs = new long[FRAMES][MAX_FLUSHES];
	/** GPU-written culling output per frame slot and flush (solid, cutout); see Work. */
	private final MetalBuffer[][] work = new MetalBuffer[FRAMES][MAX_FLUSHES];
	/** Per frame slot, for stats: each flush's first draw-argument offset, and quads asked for. */
	private final long[][] args = new long[FRAMES][MAX_FLUSHES];
	private final int[] slotFlushes = new int[FRAMES];
	private final long[] requested = new long[FRAMES], requestedChunks = new long[FRAMES];
	private int frame, flushes;
	/** Frames submitted. */
	private long frames;
	/** Frames that drew terrain: visibility bits flip with these, since a submit without terrain would otherwise drop a frame's bits. */
	long terrainFrames;
	/** While MetalProbe records a frame: each drawing flush's survivor list, for it to check against what's really visible. */
	final List<Drawn> drawnForProbe = new ArrayList<>();
	/** Flushes whose first list is drawn, in order, waiting for the pass to be split; see split(). */
	private final List<Waiting> waiting = new ArrayList<>(MAX_FLUSHES);
	/** Per waiting flush, its arena tables and Frame, still read at the split. */
	private final long waitingTables = MemoryUtil.nmemAlloc((long) MAX_FLUSHES * (TABLES_BYTES + FRAME_BYTES));
	/** Arena copies held for the split, see hold(). */
	private final List<Copy> held = new ArrayList<>();
	/** Per frame slot: the clouds faces that may be on screen, from CLOUD_FACES on, and their draw's arguments at 0; see cullClouds. */
	private final MetalBuffer[] clouds = new MetalBuffer[FRAMES];
	static final long CLOUD_FACES = 256;
	private long cloudsFrame = -1;

	private record Copy(long src, long srcOffset, long dst, long dstOffset, long size) {
	}

	/** Survivors {first vertex, arena index << 6 | region << 9} at quads in work, 64 per instance counted at args; arenas by index. */
	record Drawn(MetalBuffer work, long quads, long args, MetalBuffer[] arenas) {
	}

	/** A flush of p waiting for the split: its chunks and regions (uploaded), tables (arena tables, then Frame), second list, and restore, which binds what its draws were recorded with. */
	private record Waiting(MetalPipeline p, Runnable restore, MetalBuffer chunks, long chunksOffset, int chunkCount, MetalBuffer regions, long regionsOffset,
		List<MetalBuffer> arenas, long tables, MetalBuffer work, Work second) {
	}

	/** What culling keeps per Sodium geometry arena, indexed by quad slot (first vertex / 4). */
	private static final class ArenaCache {
		/** BOUNDS_BYTES a slot, see mcterrain.m; zero means recompute from the vertices. */
		final MetalBuffer bounds;
		/** BOUNDS_BYTES a slot: the box of the chunk starting there, see mcterrain.m; zero means rebuild from the bounds. */
		final MetalBuffer boxes;
		/** One bit a slot: kept by the split's test, in the frame of each parity. */
		final MetalBuffer[] visible = new MetalBuffer[2];
		/** Written in a frame that didn't split: the bounds and boxes are zeroed whole before the next split reads them. */
		boolean stale;

		ArenaCache(MetalEncoder encoder, MetalBuffer arena) {
			long slots = arena.size() / (4L * VERTEX_BYTES) + 2;
			this.bounds = zeroed(encoder, slots * BOUNDS_BYTES);
			this.boxes = zeroed(encoder, slots * BOUNDS_BYTES);
			for (int i = 0; i < 2; i++) this.visible[i] = zeroed(encoder, (slots / 32 + 1) * 4);
		}

		private static MetalBuffer zeroed(MetalEncoder encoder, long size) {
			MetalBuffer b = new MetalBuffer(encoder, Native.bufferNew(encoder.ctx, size), 0, size);
			MemoryUtil.memSet(b.address, 0, size);
			return b;
		}

		void close() {
			this.bounds.close();
			this.boxes.close();
			for (MetalBuffer v : this.visible) v.close();
		}
	}

	MetalTerrain(MetalEncoder encoder) {
		this.encoder = encoder;
		int size = CHUNK_QUADS * 6 * 2;
		long shared = Native.bufferNew(encoder.ctx, size), address = Native.bufferContents(shared);
		for (int q = 0, i = 0; q < CHUNK_QUADS; q++) {
			for (int corner : new int[] {0, 1, 2, 2, 3, 0}) MemoryUtil.memPutShort(address + 2L * i++, (short) (q * 4 + corner));
		}
		this.indices = new MetalBuffer(encoder, Native.bufferPrivate(encoder.ctx, shared, size), GpuBuffer.USAGE_INDEX, size);
		Native.release(shared);
		if (!OCC) {
			this.handle = 0;
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 1024);
			this.handle = Native.terrainNew(encoder.ctx, err, 1024);
			if (this.handle == 0) throw new IllegalStateException("terrain culling: " + MemoryUtil.memUTF8(err));
		}
	}

	/**
	 * Something wrote bytes [offset, offset + length) of an arena: zero the bounds of every quad that could hold one of those
	 * vertices, and the box of every chunk that could hold one of those quads, in the same command buffer as the write, after
	 * it. A quad starting up to 3 vertices earlier still overlaps, and a chunk starting up to 63 quads earlier. In a frame
	 * that doesn't split nothing reads the cache, so it's only marked stale: zeroing per write there cost auto 1.7% in
	 * flight at 3456x2234 once a trial had built the caches (ap4).
	 */
	void invalidate(MetalBuffer arena, long offset, long length) {
		ArenaCache cache = this.caches.get(arena);
		if (cache == null) return; // not a geometry arena, or not drawn from yet: its cache starts out all invalid
		if (!this.split) {
			cache.stale = true;
			return;
		}
		long first = firstSlot(offset), last = Math.min(cache.bounds.size() / BOUNDS_BYTES - 1, lastSlot(offset, length));
		if (last < first) return;
		this.zero(cache.bounds, first, last);
		this.zero(cache.boxes, Math.max(0, first - (CHUNK_QUADS - 1)), last);
	}

	private void zero(MetalBuffer cache, long first, long last) {
		long at = first * BOUNDS_BYTES, size = (last - first + 1) * BOUNDS_BYTES;
		if (this.culledThisFrame()) Native.fillBuffer(this.encoder.enc, this.encoder.use(cache).handle, at, size, 0);
		else Native.preFill(this.encoder.enc, this.encoder.use(cache).handle, at, size, 0);
	}

	private static long firstSlot(long offset) {
		return Math.max(0, offset / VERTEX_BYTES - 3) >> 2;
	}

	private static long lastSlot(long offset, long length) {
		return ((offset + length + VERTEX_BYTES - 1) / VERTEX_BYTES - 1) >> 2;
	}

	/**
	 * Before this frame's first terrain flush: a GPU copy into an arena is held for the split. In the pre command buffer it
	 * would wait for last frame's draws to finish reading the arena, and this frame's first draws would wait for it. At the
	 * split it waits only for this frame's first draws' vertex stage and runs beside their fragments. Those draws list quads
	 * from last frame's visibility, so the quads it may overwrite leave that: they're tested at the split like any not drawn.
	 */
	void hold(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		this.held.add(new Copy(src.handle, srcOffset, dst.handle, dstOffset, size));
		ArenaCache cache = this.caches.get(dst);
		if (cache == null) return; // not drawn from yet: nothing visible in it
		MetalBuffer last = cache.visible[(int) (this.terrainFrames & 1) ^ 1];
		long from = firstSlot(dstOffset) >> 3, to = Math.min(last.size() - 1, lastSlot(dstOffset, size) >> 3); // a bit per slot
		if (to >= from) Native.preFill(this.encoder.enc, this.encoder.use(last).handle, from, to - from + 1, 0);
	}

	/**
	 * Records the held copies, in order: into the frame's own command buffer at the split (or ahead of a later arena copy), or
	 * at submit, when no split came, into the pre command buffer, ahead of everything that could read them.
	 */
	void releaseHeld(boolean pre) {
		for (Copy c : this.held) {
			if (pre) Native.preBlitBuffer(this.encoder.enc, c.src, c.srcOffset, c.dst, c.dstOffset, c.size);
			else Native.blitBuffer(this.encoder.enc, c.src, c.srcOffset, c.dst, c.dstOffset, c.size);
		}
		this.held.clear();
	}

	/**
	 * Whether this frame's culling is already recorded. Geometry written after that must not land in the pre command buffer
	 * (ahead of the culled draws, which were culled against the old data) but after those draws, in the frame's own.
	 */
	boolean culledThisFrame() {
		return this.flushes > 0;
	}

	void pushConstants(long address, int length) {
		MemoryUtil.memCopy(address, this.pushConstants, Math.min(length, REGION_BYTES));
	}

	/** One Sodium region multi-draw: range i is indexCounts[i] / 6 quads from vertex vertexOffsets[i] of the region's arena. */
	void record(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (mcopt.metal.cpu.Cpu.RECORD && indexCounts.isDirect() && vertexOffsets.isDirect()) {
			boolean fast = !mcopt.metal.cpu.Cpu.RECORD_AB || (this.frames & 1) == 1;
			long t0 = mcopt.metal.cpu.Cpu.RECORD_AB ? System.nanoTime() : 0;
			if (fast) this.recordFast(vertexBuffer, indexCounts, vertexOffsets, drawCount);
			else this.recordPlain(vertexBuffer, indexCounts, vertexOffsets, drawCount);
			if (mcopt.metal.cpu.Cpu.RECORD_AB) mcopt.metal.cpu.Cpu.recordAb(fast, System.nanoTime() - t0, this.frames);
			return;
		}
		this.recordPlain(vertexBuffer, indexCounts, vertexOffsets, drawCount);
	}

	/**
	 * -Dmcopt.cpu.record: record() reading the ranges by address, the arena found by the last lookup first, the chunk
	 * list grown once per call. Writes the same bytes in the same order.
	 */
	private void recordFast(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		MetalBuffer arena = (MetalBuffer) vertexBuffer.buffer();
		int arenaIndex = this.lastArenaIndex;
		if (arenaIndex >= this.arenas.size() || this.arenas.get(arenaIndex) != arena) {
			arenaIndex = this.arenas.indexOf(arena);
			if (arenaIndex < 0) {
				if (this.arenas.size() == MAX_ARENAS) throw new IllegalStateException("more than " + MAX_ARENAS + " Sodium geometry arenas");
				arenaIndex = this.arenas.size();
				this.arenas.add(arena);
			}
			this.lastArenaIndex = arenaIndex;
		}
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		MemoryUtil.memCopy(this.pushConstants, this.regions + (long) this.regionCount * REGION_BYTES, REGION_BYTES);
		int region = this.regionCount++;
		long base = vertexBuffer.offset() / VERTEX_BYTES;
		long counts = MemoryUtil.memAddress(indexCounts), offsets = MemoryUtil.memAddress(vertexOffsets);
		int needed = 0;
		for (int i = 0; i < drawCount; i++) needed += (MemoryUtil.memGetInt(counts + 4L * i) / 6 + CHUNK_QUADS - 1) / CHUNK_QUADS;
		while (this.chunkCount + needed > this.chunkCapacity) this.chunks = MemoryUtil.nmemRealloc(this.chunks, (long) (this.chunkCapacity *= 2) * CHUNK_BYTES);
		int tag = arenaIndex << 6 | region << 9;
		long at = this.chunks + (long) this.chunkCount * CHUNK_BYTES;
		for (int i = 0; i < drawCount; i++) {
			int quads = MemoryUtil.memGetInt(counts + 4L * i) / 6;
			long first = base + MemoryUtil.memGetInt(offsets + 4L * i);
			for (int q = 0; q < quads; q += CHUNK_QUADS) {
				MemoryUtil.memPutInt(at, (int) (first + q * 4L));
				MemoryUtil.memPutInt(at + 4, (Math.min(CHUNK_QUADS, quads - q) - 1) | tag);
				at += CHUNK_BYTES;
			}
		}
		this.chunkCount += needed;
	}

	private int lastArenaIndex;

	private void recordPlain(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		MetalBuffer arena = (MetalBuffer) vertexBuffer.buffer();
		int arenaIndex = this.arenas.indexOf(arena);
		if (arenaIndex < 0) {
			if (this.arenas.size() == MAX_ARENAS) throw new IllegalStateException("more than " + MAX_ARENAS + " Sodium geometry arenas");
			arenaIndex = this.arenas.size();
			this.arenas.add(arena);
		}
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		MemoryUtil.memCopy(this.pushConstants, this.regions + (long) this.regionCount * REGION_BYTES, REGION_BYTES);
		int region = this.regionCount++;
		long base = vertexBuffer.offset() / VERTEX_BYTES;
		for (int i = 0; i < drawCount; i++) {
			int quads = indexCounts.get(indexCounts.position() + i) / 6;
			long first = base + vertexOffsets.get(vertexOffsets.position() + i);
			for (int q = 0; q < quads; q += CHUNK_QUADS) {
				if (this.chunkCount == this.chunkCapacity) this.chunks = MemoryUtil.nmemRealloc(this.chunks, (long) (this.chunkCapacity *= 2) * CHUNK_BYTES);
				long at = this.chunks + (long) this.chunkCount++ * CHUNK_BYTES;
				MemoryUtil.memPutInt(at, (int) (first + q * 4L));
				MemoryUtil.memPutInt(at + 4, (Math.min(CHUNK_QUADS, quads - q) - 1) | arenaIndex << 6 | region << 9);
			}
		}
	}

	/**
	 * Draws the first list of everything recorded since the last flush with p's pulled twin; the rest waits for split(), where
	 * restore binds these draws' uniforms again. The frontend's uniforms for p are still bound; globals is its u_Globals
	 * (persistently mapped, so already final on the CPU) and width x height the pass's size.
	 */
	void flush(long enc, MetalPipeline p, GpuBufferSlice globals, int width, int height, Runnable restore) {
		if (this.chunkCount > 0) this.occlude(enc, p, globals, width, height, restore);
		this.chunkCount = 0;
		this.regionCount = 0;
		this.arenas.clear();
	}

	/** Called at submit: the next frame's work buffers are the ones the GPU finished with FRAMES - 1 submits ago. */
	void endFrame() {
		this.slotFlushes[this.frame] = this.flushes;
		if (this.flushes > 0) this.terrainFrames++;
		this.cleared.clear();
		this.frame = (this.frame + 1) % FRAMES;
		this.flushes = 0;
		this.frames++;
		if (OCC && !MODE.equals("true")) this.split = this.pick.next(this.terrainPixels);
		mcopt.metal.cpu.Cpu.terrainSplitting = this.split;
		this.terrainPixels = 0;
		this.caches.entrySet().removeIf(e -> {
			if (!e.getKey().isClosed()) return false;
			e.getValue().close();
			return true;
		});
	}

	/**
	 * {quads drawn, quads Sodium asked for, quads drawn before the split, chunks culled} in the frame whose slot the next one
	 * reuses, draws rounded up to whole instances. Only valid between submit (once MetalEncoder retired down to MAX_IN_FLIGHT,
	 * which completes that frame) and the next flush.
	 */
	long[] lastCompletedCounts() {
		long drawn = 0, first = 0;
		for (int i = 0; i < this.slotFlushes[this.frame]; i++) {
			long work = this.work[this.frame][i].address, quads = 64L * MemoryUtil.memGetInt(work + this.args[this.frame][i] + 4);
			first += quads;
			drawn += quads + 64L * MemoryUtil.memGetInt(work + this.secondArgs[this.frame][i] + 4);
		}
		return new long[] {drawn, this.requested[this.frame], first, this.requestedChunks[this.frame]};
	}

	/**
	 * Draws the quads that showed last frame (listed in the pre command buffer, so the list is ready when the frame starts)
	 * and leaves the rest waiting for split().
	 */
	private void occlude(long enc, MetalPipeline p, GpuBufferSlice globals, int width, int height, Runnable restore) {
		this.countRequested();
		MetalTransientMemory memory = this.encoder.transientMemory;
		GpuBufferSlice chunkSlice = memory.uploadGpu(MemoryUtil.memByteBuffer(this.chunks, this.chunkCount * CHUNK_BYTES), 16L, GpuBuffer.USAGE_UNIFORM);
		GpuBufferSlice regionSlice = memory.uploadGpu(MemoryUtil.memByteBuffer(this.regions, this.regionCount * REGION_BYTES), 16L, GpuBuffer.USAGE_UNIFORM);
		MetalBuffer chunkBuffer = this.encoder.use(chunkSlice.buffer()), regionBuffer = this.encoder.use(regionSlice.buffer());
		int parity = (int) (this.terrainFrames & 1);
		long addresses = this.waitingTables + (long) this.waiting.size() * (TABLES_BYTES + FRAME_BYTES), handles = addresses + TABLES_BYTES / 2,
			frame = addresses + TABLES_BYTES;
		this.arenaTables(addresses, parity);
		frameData(frame, globals, width, height, this.chunkCount, this.frames);
		this.clearVisibility(enc, parity);
		Work first = new Work(this.chunkCount), second = first.at(first.size);
		MetalBuffer work = this.workBuffer(second.size, first.args);
		this.secondArgs[this.frame][this.flushes - 1] = second.args;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Native.occLast(enc, this.handle, chunkBuffer.handle, chunkSlice.offset(), this.chunkCount, regionBuffer.handle, regionSlice.offset(), addresses, handles,
				this.arenas.size(), frame, work.handle, first.offsets(stack));
		}
		List<MetalBuffer> arenas = List.copyOf(this.arenas);
		this.drawPulled(enc, p, arenas, addresses, work, first.quads, first.args, regionBuffer, regionSlice.offset());
		if (this.encoder.probe != null) this.drawnForProbe.add(new Drawn(work, first.quads, first.args, arenas.toArray(MetalBuffer[]::new)));
		this.waiting.add(new Waiting(p, restore, chunkBuffer, chunkSlice.offset(), this.chunkCount, regionBuffer, regionSlice.offset(), arenas, addresses, work,
			second));
	}

	/**
	 * Splits the pass once for every flush waiting (mc_occ_suspend: hi-Z from its depth), tests each one's quads against
	 * it, and in the reopened pass draws what each test newly let through, in flush order, bound as that flush's draws were
	 * (its restore). rebind then puts the frontend's current state back. MetalRenderPass calls this before anything else can
	 * draw in the pass: when it ends, or moves on to a pipeline that isn't pulled terrain.
	 */
	void split(long enc, Runnable rebind) {
		if (this.waiting.isEmpty()) return;
		this.releaseHeld(false);
		long compute = Native.occSuspend(enc, this.handle);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int step = 0; step < 4; step++) {
				for (Waiting w : this.waiting) {
					Native.occCull(compute, this.handle, step, w == this.waiting.getFirst(), w.chunks.handle, w.chunksOffset, w.chunkCount, w.regions.handle,
						w.regionsOffset, w.tables, w.tables + TABLES_BYTES / 2, w.arenas.size(), w.tables + TABLES_BYTES, w.work.handle, w.second.offsets(stack));
				}
			}
		}
		Native.renderResume(enc, compute);
		for (Waiting w : this.waiting) {
			w.restore.run();
			this.drawPulled(enc, w.p, w.arenas, w.tables, w.work, w.second.quads, w.second.args, w.regions, w.regionsOffset);
			if (this.encoder.probe != null) this.drawnForProbe.add(new Drawn(w.work, w.second.quads, w.second.args, w.arenas.toArray(MetalBuffer[]::new)));
		}
		this.waiting.clear();
		rebind.run();
	}

	private void countRequested() {
		if (this.flushes == 0) this.requested[this.frame] = this.requestedChunks[this.frame] = 0;
		this.requestedChunks[this.frame] += this.chunkCount;
		for (int i = 0; i < this.chunkCount; i++) this.requested[this.frame] += (MemoryUtil.memGetInt(this.chunks + i * (long) CHUNK_BYTES + 4) & 63) + 1;
	}

	/**
	 * Writes TABLES_BYTES at addresses: GPU addresses of each arena's vertices at [i], bounds at [8 + i], last frame's
	 * visibility at [16 + i], this frame's at [24 + i] and boxes at [32 + i], then the same buffers' handles from 320 bytes on.
	 */
	private void arenaTables(long addresses, int parity) {
		long handles = addresses + TABLES_BYTES / 2;
		MemoryUtil.memSet(addresses, 0, TABLES_BYTES);
		for (int i = 0; i < this.arenas.size(); i++) {
			MetalBuffer arena = this.arenas.get(i);
			ArenaCache cache = this.caches.computeIfAbsent(arena, a -> new ArenaCache(this.encoder, a));
			if (cache.stale) { // in the pre command buffer, ahead of this frame's culling and of any write in its own
				Native.preFill(this.encoder.enc, this.encoder.use(cache.bounds).handle, 0, cache.bounds.size(), 0);
				Native.preFill(this.encoder.enc, this.encoder.use(cache.boxes).handle, 0, cache.boxes.size(), 0);
				cache.stale = false;
			}
			MetalBuffer[] tables = {arena, cache.bounds, cache.visible[parity ^ 1], cache.visible[parity], cache.boxes};
			for (int t = 0; t < tables.length; t++) {
				MetalBuffer b = this.encoder.use(tables[t]);
				MemoryUtil.memPutLong(addresses + (t * MAX_ARENAS + i) * 8L, Native.gpuAddress(b.handle));
				MemoryUtil.memPutLong(handles + (t * MAX_ARENAS + i) * 8L, b.handle);
			}
		}
	}

	/**
	 * Vanilla's clouds draw of count faces (bound as clouds.vsh reads them) minus those whose cell is wholly outside the
	 * view, which put no pixel on screen but cost six vertex invocations each. The GPU lists the rest, in order, in the pre
	 * command buffer. Returns the list's buffer (see clouds), or null for a second clouds draw in a frame, which draws as is.
	 */
	@Nullable MetalBuffer cullClouds(GpuBufferSlice faces, int count, GpuBufferSlice modelView, GpuBufferSlice projection, GpuBufferSlice info) {
		if (this.cloudsFrame == this.frames) return null;
		this.cloudsFrame = this.frames;
		long size = CLOUD_FACES + cloudFacesLength(count);
		MetalBuffer out = this.clouds[this.frame];
		if (out == null || out.size() < size) {
			if (out != null) out.close();
			out = new MetalBuffer(this.encoder, Native.bufferNew(this.encoder.ctx, size + size / 2), 0, size + size / 2);
			this.clouds[this.frame] = out;
		}
		Native.cloudsCull(this.encoder.enc, this.handle, this.encoder.use(faces.buffer()).handle, faces.offset(), count, this.encoder.use(modelView.buffer()).handle,
			modelView.offset(), this.encoder.use(projection.buffer()).handle, projection.offset(), this.encoder.use(info.buffer()).handle, info.offset(),
			this.encoder.use(out).handle, CLOUD_FACES);
		return out;
	}

	/** Bytes of the clouds list's texel view for count faces: 3 each, rounded up to a safe texture row alignment. */
	static long cloudFacesLength(int count) {
		return (3L * count + 255) & ~255L;
	}

	/** This frame's visibility bits start from scratch, in the pre command buffer: each arena's at the first flush that uses it. */
	private void clearVisibility(long enc, int parity) {
		for (MetalBuffer arena : this.arenas) {
			if (!this.cleared.add(arena)) continue;
			MetalBuffer next = this.encoder.use(this.caches.get(arena).visible[parity]);
			Native.preFill(enc, next.handle, 0, next.size(), 0);
		}
	}

	/** Writes the MSL Frame at frame: view-projection, pass size, chunk count and frame number. */
	private static void frameData(long frame, GpuBufferSlice globals, int width, int height, int count, long number) {
		MemoryUtil.memSet(frame, 0, FRAME_BYTES);
		long g = ((MetalBuffer) globals.buffer()).address + globals.offset();
		Matrix4f viewProj = new Matrix4f().set(MemoryUtil.memFloatBuffer(g, 16)).mul(new Matrix4f().set(MemoryUtil.memFloatBuffer(g + 64, 16)));
		viewProj.get(MemoryUtil.memFloatBuffer(frame, 16));
		MemoryUtil.memPutFloat(frame + 64, width);
		MemoryUtil.memPutFloat(frame + 68, height);
		MemoryUtil.memPutInt(frame + 72, count);
		MemoryUtil.memPutInt(frame + 84, (int) number);
	}

	/** The pulled twin of p over the quad list in work, with the draw's arguments at args. */
	private void drawPulled(long enc, MetalPipeline p, List<MetalBuffer> arenas, long addresses, MetalBuffer work, long list, long args, MetalBuffer regions,
		long regionsOffset) {
		Native.pipeline(enc, p.pulled, p.depthState, p.cull ? 1 : 0, 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		for (MetalBuffer arena : arenas) Native.useResource(enc, arena.handle);
		Native.bytes(enc, ARENAS_SLOT, addresses, MAX_ARENAS * 8);
		Native.buffer(enc, QUADS_SLOT, work.handle, list);
		Native.buffer(enc, REGIONS_SLOT, regions.handle, regionsOffset);
		Native.index(enc, this.encoder.use(this.indices).handle, 0);
		Native.drawIndexedIndirect(enc, work.handle, args, 1);
	}

	/** This flush's work buffer, grown to fit size; args: its first draw's arguments. */
	private MetalBuffer workBuffer(long size, long args) {
		if (this.flushes == MAX_FLUSHES) throw new IllegalStateException("more than " + MAX_FLUSHES + " terrain flushes in a frame");
		MetalBuffer b = this.work[this.frame][this.flushes];
		if (b == null || b.size() < size) {
			if (b != null) b.close();
			long capacity = size + size / 2;
			b = new MetalBuffer(this.encoder, Native.bufferNew(this.encoder.ctx, capacity), 0, capacity);
			this.work[this.frame][this.flushes] = b;
		}
		this.args[this.frame][this.flushes++] = args;
		return this.encoder.use(b);
	}

	/** Byte offsets in a work buffer for n chunks: chunk masks (uint2), counts, offsets, the draw's arguments, the surviving quads (uint2 each). */
	private record Work(long masks, long counts, long offsets, long args, long quads, long size) {
		Work(int n) {
			this(0, align(8L * n), align(8L * n) + align(4L * n), align(8L * n) + 2 * align(4L * n), align(8L * n) + 2 * align(4L * n) + 32,
				align(8L * n) + 2 * align(4L * n) + 32 + 8L * CHUNK_QUADS * n);
		}

		/** The same layout from base on, e.g. a second list after a first's. */
		Work at(long base) {
			return new Work(base + this.masks, base + this.counts, base + this.offsets, base + this.args, base + this.quads, base + this.size);
		}

		/** {masks, counts, offsets, quads, args} for mc_terrain_cull and mc_occ_cull, on the stack. */
		long offsets(MemoryStack stack) {
			long o = stack.nmalloc(8, 5 * 8);
			long[] v = {this.masks, this.counts, this.offsets, this.quads, this.args};
			for (int i = 0; i < v.length; i++) MemoryUtil.memPutLong(o + i * 8L, v[i]);
			return o;
		}

		private static long align(long x) {
			return (x + 15) & ~15L;
		}
	}
}
