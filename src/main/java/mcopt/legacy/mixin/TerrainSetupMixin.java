package mcopt.legacy.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;
import mcopt.legacy.TerrainState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BaseFrustum;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.CullingCameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.util.math.Vector3d;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkAssemblyHelper;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.util.vector.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * setupTerrain with the same result, faster. While the camera turns, vanilla redoes its visibility search (a breadth-first
 * walk over the render-chunk grid, through the faces each chunk lets light pass) every frame, and at render distance 32
 * that walk covers some 25,000 chunk sections. Per section it allocated an entry with an EnumSet, a LinkedList node and
 * looked neighbours up through an EnumMap and a BlockPos. Here the walk runs over int arrays: neighbours come from grid
 * arithmetic, the faces walked so far are a bit mask, the queue is an array, and the list entries are made once per grid
 * slot and reused. The order of visits, every test, and the list the renderer gets are vanilla's. The rebuild bookkeeping
 * after the walk is vanilla's too, minus a set lookup per visible section when the rebuild set is empty.
 */
@Mixin(WorldRenderer.class)
abstract class TerrainSetupMixin {
	@Shadow @Final private MinecraftClient client;
	@Shadow private ClientWorld world;
	@Shadow private Set<BuiltChunk> chunksToRebuild;
	@Shadow private List<Object> visibleChunks;
	@Shadow private BuiltChunkStorage chunks;
	@Shadow private double lastCameraChunkUpdateX;
	@Shadow private double lastCameraChunkUpdateY;
	@Shadow private double lastCameraChunkUpdateZ;
	@Shadow private int cameraChunkX;
	@Shadow private int cameraChunkY;
	@Shadow private int cameraChunkZ;
	@Shadow private double lastCameraX;
	@Shadow private double lastCameraY;
	@Shadow private double lastCameraZ;
	@Shadow private double lastCameraPitch;
	@Shadow private double lastCameraYaw;
	@Shadow @Final private ChunkBuilder chunkBuilder;
	@Shadow private AbstractChunkRenderManager chunkRenderManager;
	@Shadow private int renderDistance;
	@Shadow private boolean field_10813;
	@Shadow private BaseFrustum capturedFrustum;
	@Shadow @Final private Vector3d capturedFrustumPosition;
	@Shadow private boolean needsTerrainUpdate;

	@Shadow public abstract void reload();
	@Shadow private boolean isInChunk(BlockPos pos, BuiltChunk chunk) { return false; }
	@Shadow private Set<Direction> getOpenChunkFaces(BlockPos pos) { return null; }
	@Shadow private void captureFrustum(double x, double y, double z) { }
	@Shadow protected abstract Vector3f getFacing(Entity entity, double tickDelta);

	@Unique private static final Direction[] DIRS = Direction.values();
	@Unique private static final int[] OPPOSITE = new int[6];
	@Unique private static final int[] OFF_X = new int[6];
	@Unique private static final int[] OFF_Y = new int[6];
	@Unique private static final int[] OFF_Z = new int[6];
	@Unique private static final MethodHandle NEW_INFO;

	static {
		for (Direction d : DIRS) {
			OPPOSITE[d.ordinal()] = d.getOpposite().ordinal();
			OFF_X[d.ordinal()] = d.getOffsetX();
			OFF_Y[d.ordinal()] = d.getOffsetY();
			OFF_Z[d.ordinal()] = d.getOffsetZ();
		}
		MethodHandle h = null;
		try {
			for (Class<?> c : WorldRenderer.class.getDeclaredClasses()) {
				for (Constructor<?> k : c.getDeclaredConstructors()) {
					Class<?>[] p = k.getParameterTypes();
					if (p.length == 4 && p[0] == WorldRenderer.class && p[1] == BuiltChunk.class && p[2] == Direction.class && p[3] == int.class) {
						k.setAccessible(true);
						h = MethodHandles.lookup().unreflectConstructor(k).asType(
							java.lang.invoke.MethodType.methodType(Object.class, WorldRenderer.class, BuiltChunk.class, Direction.class, int.class));
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("mcopt: WorldRenderer.ChunkInfo constructor", e);
		}
		if (h == null) throw new IllegalStateException("mcopt: WorldRenderer.ChunkInfo constructor not found");
		NEW_INFO = h;
	}

	// Per grid slot: the reusable list entry, and the walk's state (entry direction, faces walked so far).
	@Unique private BuiltChunk[] mcopt$grid;
	@Unique private Object[] mcopt$infos;
	@Unique private int[] mcopt$queue;
	@Unique private byte[] mcopt$dir;
	@Unique private byte[] mcopt$faces;
	@Unique private int[] mcopt$stamp;
	@Unique private int mcopt$mark;
	@Unique private Object[] mcopt$helper;
	@Unique private long[] mcopt$mask;
	// Section positions by slot, refreshed whenever a section moves.
	@Unique private int[] mcopt$x;
	@Unique private int[] mcopt$y;
	@Unique private int[] mcopt$z;
	@Unique private int mcopt$moves;
	@Unique private boolean mcopt$posValid;

	@Unique
	private Object mcopt$info(int slot, BuiltChunk chunk) {
		Object info = this.mcopt$infos[slot];
		if (info == null) {
			try {
				info = NEW_INFO.invokeExact((WorldRenderer) (Object) this, chunk, (Direction) null, 0);
			} catch (Throwable t) {
				throw new IllegalStateException(t);
			}
			this.mcopt$infos[slot] = info;
		}
		return info;
	}

	@Unique
	private void mcopt$ensureGrid() {
		BuiltChunk[] grid = this.chunks.chunks;
		if (this.mcopt$grid != grid) {
			this.mcopt$grid = grid;
			this.mcopt$infos = new Object[grid.length];
			this.mcopt$queue = new int[grid.length];
			this.mcopt$dir = new byte[grid.length];
			this.mcopt$faces = new byte[grid.length];
			this.mcopt$stamp = new int[grid.length];
			this.mcopt$helper = new Object[grid.length];
			this.mcopt$mask = new long[grid.length];
			this.mcopt$x = new int[grid.length];
			this.mcopt$y = new int[grid.length];
			this.mcopt$z = new int[grid.length];
			this.mcopt$posValid = false;
		}
	}

	/** The grid slot holding the section at block position (x, y, z), or -1; BuiltChunkStorage.getRenderedChunk's math. */
	@Unique
	private static int mcopt$slot(int x, int y, int z, int sx, int sy, int sz) {
		int i = MathHelper.floorDiv(x, 16);
		int j = MathHelper.floorDiv(y, 16);
		int k = MathHelper.floorDiv(z, 16);
		if (j < 0 || j >= sy) return -1;
		i %= sx;
		if (i < 0) i += sx;
		k %= sz;
		if (k < 0) k += sz;
		return (k * sy + j) * sx + i;
	}

	/**
	 * @author mcopt
	 * @reason Same visibility result without per-section allocation; see the class comment.
	 */
	@Overwrite
	public void setupTerrain(Entity entity, double tickDelta, CameraView cameraView, int frame, boolean spectator) {
		if (this.client.options.viewDistance != this.renderDistance) {
			this.reload();
		}

		this.world.profiler.push("camera");
		double d = entity.x - this.lastCameraChunkUpdateX;
		double e = entity.y - this.lastCameraChunkUpdateY;
		double f = entity.z - this.lastCameraChunkUpdateZ;
		if (this.cameraChunkX != entity.chunkX || this.cameraChunkY != entity.chunkY || this.cameraChunkZ != entity.chunkZ || d * d + e * e + f * f > 16.0) {
			this.lastCameraChunkUpdateX = entity.x;
			this.lastCameraChunkUpdateY = entity.y;
			this.lastCameraChunkUpdateZ = entity.z;
			this.cameraChunkX = entity.chunkX;
			this.cameraChunkY = entity.chunkY;
			this.cameraChunkZ = entity.chunkZ;
			this.chunks.updateCameraPosition(entity.x, entity.z);
		}

		this.world.profiler.swap("renderlistcamera");
		double g = entity.prevTickX + (entity.x - entity.prevTickX) * tickDelta;
		double h = entity.prevTickY + (entity.y - entity.prevTickY) * tickDelta;
		double i = entity.prevTickZ + (entity.z - entity.prevTickZ) * tickDelta;
		this.chunkRenderManager.setViewPos(g, h, i);
		this.world.profiler.swap("cull");
		if (this.capturedFrustum != null) {
			CullingCameraView culling = new CullingCameraView(this.capturedFrustum);
			culling.setPos(this.capturedFrustumPosition.x, this.capturedFrustumPosition.y, this.capturedFrustumPosition.z);
			cameraView = culling;
		}

		this.client.profiler.swap("culling");
		BlockPos blockPos = new BlockPos(g, h + entity.getEyeHeight(), i);
		BlockPos blockPos2 = new BlockPos(MathHelper.floor(g / 16.0) * 16, MathHelper.floor(h / 16.0) * 16, MathHelper.floor(i / 16.0) * 16);
		this.needsTerrainUpdate = this.needsTerrainUpdate
			|| !this.chunksToRebuild.isEmpty()
			|| entity.x != this.lastCameraX
			|| entity.y != this.lastCameraY
			|| entity.z != this.lastCameraZ
			|| entity.pitch != this.lastCameraPitch
			|| entity.yaw != this.lastCameraYaw;
		this.lastCameraX = entity.x;
		this.lastCameraY = entity.y;
		this.lastCameraZ = entity.z;
		this.lastCameraPitch = entity.pitch;
		this.lastCameraYaw = entity.yaw;
		boolean captured = this.capturedFrustum != null;
		if (!captured && this.needsTerrainUpdate) {
			this.needsTerrainUpdate = false;
			this.mcopt$walk(entity, tickDelta, cameraView, frame, spectator, blockPos, blockPos2);
		}

		if (this.field_10813) {
			this.captureFrustum(g, h, i);
			this.field_10813 = false;
		}

		this.chunkBuilder.clear();
		Set<BuiltChunk> set2 = this.chunksToRebuild;
		this.chunksToRebuild = Sets.<BuiltChunk>newLinkedHashSet();
		boolean pending = !set2.isEmpty();
		List<Object> visible = this.visibleChunks;
		for (int n = 0, size = visible.size(); n < size; n++) {
			BuiltChunk chunk = ((ChunkInfoAccess) visible.get(n)).mcopt$chunk();
			if (chunk.method_10173() || pending && set2.contains(chunk)) {
				this.needsTerrainUpdate = true;
				if (this.isInChunk(blockPos2, chunk)) {
					this.client.profiler.push("build near");
					this.chunkBuilder.upload(chunk);
					chunk.method_10162(false);
					this.client.profiler.pop();
				} else {
					this.chunksToRebuild.add(chunk);
				}
			}
		}

		if (pending) this.chunksToRebuild.addAll(set2);
		this.client.profiler.pop();
	}

	@Unique
	private void mcopt$walk(Entity entity, double tickDelta, CameraView cameraView, int frame, boolean spectator, BlockPos blockPos,
		BlockPos blockPos2) {
		this.mcopt$ensureGrid();
		BuiltChunkStorageAccess grid = (BuiltChunkStorageAccess) this.chunks;
		int sx = grid.mcopt$sizeX(), sy = grid.mcopt$sizeY(), sz = grid.mcopt$sizeZ();
		BuiltChunk[] chunks = this.mcopt$grid;
		int[] queue = this.mcopt$queue;
		byte[] dirs = this.mcopt$dir;
		byte[] faces = this.mcopt$faces;
		int[] stamp = this.mcopt$stamp;
		int[] xs = this.mcopt$x, ys = this.mcopt$y, zs = this.mcopt$z;
		if (this.mcopt$moves != TerrainState.sectionMoves || !this.mcopt$posValid) {
			for (int s = 0; s < chunks.length; s++) {
				BlockPos pos = chunks[s].getPos();
				xs[s] = pos.getX();
				ys[s] = pos.getY();
				zs[s] = pos.getZ();
			}
			this.mcopt$moves = TerrainState.sectionMoves;
			this.mcopt$posValid = true;
		}
		// Each walk gets a fresh stamp; a slot is visited when its stamp matches. Vanilla stamps the section itself with
		// the frame number for the same purpose, and reads that stamp nowhere else.
		int mark = ++this.mcopt$mark;
		if (mark == 0) {
			java.util.Arrays.fill(stamp, 0);
			mark = this.mcopt$mark = 1;
		}

		// The box test, inline for the usual CullingCameraView; the arithmetic is BaseFrustum.isInFrustum's.
		float[][] planes = null;
		double camX = 0, camY = 0, camZ = 0;
		if (cameraView.getClass() == CullingCameraView.class) {
			CullingCameraViewAccess view = (CullingCameraViewAccess) cameraView;
			planes = view.mcopt$clipper().homogeneousCoordinates;
			camX = view.mcopt$x();
			camY = view.mcopt$y();
			camZ = view.mcopt$z();
		}

		List<Object> visible = this.visibleChunks instanceof ArrayList ? this.visibleChunks : new ArrayList<>(69696);
		visible.clear();
		this.visibleChunks = visible;
		int head = 0, tail = 0;
		boolean culling = this.client.chunkCullingEnabled;
		int start = mcopt$slot(blockPos.getX(), blockPos.getY(), blockPos.getZ(), sx, sy, sz);
		BuiltChunk builtChunk = start < 0 ? null : chunks[start];
		if (builtChunk != null) {
			boolean closed = false;
			Set<Direction> set = this.getOpenChunkFaces(blockPos);
			if (set.size() == 1) {
				Vector3f facing = this.getFacing(entity, tickDelta);
				Direction direction = Direction.getFacing(facing.x, facing.y, facing.z).getOpposite();
				set.remove(direction);
			}

			if (set.isEmpty()) {
				closed = true;
			}

			if (closed && !spectator) {
				visible.add(this.mcopt$info(start, builtChunk));
				return;
			}
			if (spectator && this.world.getBlockState(blockPos).getBlock().hasTransparency()) {
				culling = false;
			}

			stamp[start] = mark;
			dirs[start] = -1;
			faces[start] = 0;
			queue[tail++] = start;
		} else {
			int j = blockPos.getY() > 0 ? 248 : 8;
			outer:
			for (int k = -this.renderDistance; k <= this.renderDistance; k++) {
				for (int l = -this.renderDistance; l <= this.renderDistance; l++) {
					int slot = mcopt$slot((k << 4) + 8, j, (l << 4) + 8, sx, sy, sz);
					BuiltChunk chunk = slot < 0 ? null : chunks[slot];
					if (chunk != null && cameraView.isBoxInFrustum(chunk.field_11071)) {
						stamp[slot] = mark;
						// A slot can come up twice here only if the render distance outgrew the grid; vanilla would
						// queue it twice, and so does this (the entry object is shared, which nothing reads twice),
						// stopping when the queue is full.
						dirs[slot] = -1;
						faces[slot] = 0;
						queue[tail++] = slot;
						if (tail == queue.length) break outer;
					}
				}
			}
		}

		Object[] helpers = this.mcopt$helper;
		long[] masks = this.mcopt$mask;
		int range = this.renderDistance * 16;
		int cx = blockPos2.getX(), cz = blockPos2.getZ();
		int strideY = sx, strideZ = sx * sy;
		while (head < tail) {
			int slot = queue[head++];
			int dir = dirs[slot];
			int walked = faces[slot];
			visible.add(this.mcopt$info(slot, chunks[slot]));
			// Which faces can be seen out of, given the face the walk came in through. Bit n is set when the walk may go
			// on toward DIRS[n]; it's ChunkAssemblyHelper.isVisibleThrough(entry face, DIRS[n]) for every n, cached per
			// compiled section.
			int open = 0x3F;
			if (culling) {
				open &= ~mcopt$opposites(walked);
				if (dir >= 0) {
					ChunkAssemblyHelper data = chunks[slot].method_10170();
					long mask;
					if (helpers[slot] == data) {
						mask = masks[slot];
					} else {
						mask = mcopt$mask(data);
						helpers[slot] = data;
						masks[slot] = mask;
					}
					open &= (int) (mask >>> (OPPOSITE[dir] * 6)) & 0x3F;
				}
			}
			if (open == 0) continue;
			int px = xs[slot], py = ys[slot], pz = zs[slot];
			int gi = slot % sx, gj = slot / sx % sy;
			for (int n = 0; n < 6; n++) {
				if ((open & 1 << n) == 0) continue;
				int ox = OFF_X[n], oy = OFF_Y[n], oz = OFF_Z[n];
				int nx = px + ox * 16, ny = py + oy * 16, nz = pz + oz * 16;
				if (Math.abs(cx - nx) > range || ny < 0 || ny >= 256 || Math.abs(cz - nz) > range) continue;
				// The neighbour's slot by grid arithmetic: one step along the axis, wrapping like getRenderedChunk.
				int ns;
				if (ox != 0) {
					int ni = gi + ox;
					ns = slot + (ni < 0 ? sx - 1 : ni >= sx ? 1 - sx : ox);
				} else if (oy != 0) {
					int nj = gj + oy;
					if (nj < 0 || nj >= sy) continue;
					ns = slot + oy * strideY;
				} else {
					int nk = slot / strideZ + oz;
					ns = slot + (nk < 0 ? (sz - 1) * strideZ : nk >= sz ? (1 - sz) * strideZ : oz * strideZ);
				}
				if (stamp[ns] == mark) continue;
				stamp[ns] = mark;
				boolean inside;
				if (planes != null) {
					inside = mcopt$inFrustum(planes, xs[ns] - camX, ys[ns] - camY, zs[ns] - camZ, xs[ns] + 16 - camX, ys[ns] + 16 - camY,
						zs[ns] + 16 - camZ);
				} else {
					inside = cameraView.isBoxInFrustum(chunks[ns].field_11071);
				}
				if (!inside) continue;
				dirs[ns] = (byte) n;
				faces[ns] = (byte) (walked | 1 << n);
				queue[tail++] = ns;
			}
		}
	}

	/** Bits of the faces opposite the walked ones: vanilla skips a neighbour when the walk already went the other way. */
	@Unique
	private static int mcopt$opposites(int walked) {
		int r = 0;
		for (int n = 0; n < 6; n++) {
			if ((walked & 1 << n) != 0) r |= 1 << OPPOSITE[n];
		}
		return r;
	}

	/** isVisibleThrough(DIRS[a], DIRS[b]) for all a, b, as bit a * 6 + b. */
	@Unique
	private static long mcopt$mask(ChunkAssemblyHelper data) {
		long mask = 0;
		for (int a = 0; a < 6; a++) {
			for (int b = 0; b < 6; b++) {
				if (data.isVisibleThrough(DIRS[a], DIRS[b])) mask |= 1L << (a * 6 + b);
			}
		}
		return mask;
	}

	/**
	 * BaseFrustum.isInFrustum: the box is out when all eight corners are behind one plane. The corner furthest in front of
	 * a plane decides that, so only it is tested, with vanilla's arithmetic for that corner.
	 */
	@Unique
	private static boolean mcopt$inFrustum(float[][] planes, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		for (int i = 0; i < 6; i++) {
			float[] fs = planes[i];
			double x = fs[0] > 0 ? maxX : minX, y = fs[1] > 0 ? maxY : minY, z = fs[2] > 0 ? maxZ : minZ;
			if (!(fs[0] * x + fs[1] * y + fs[2] * z + fs[3] > 0.0)) return false;
		}
		return true;
	}
}
