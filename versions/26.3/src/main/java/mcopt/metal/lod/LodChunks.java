package mcopt.metal.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The game's real chunks as far terrain: when the client loads a chunk (and when it unloads it, with the player's changes),
 * its 16 x 16 columns become far-terrain columns with the real surface, trees, water and builds, replacing the generated
 * estimate in every tile that covers them. The render thread only copies block states and biomes out of the chunk (a few
 * microseconds); colors are worked out on a worker and the tiles patched back on the render thread.
 */
final class LodChunks {
	/**
	 * What the render thread copies out of a chunk, per column: the ground (its top block or the floor under water, what is
	 * under it, its top face, the water's), and a tree crown over air if one floats there (its top block, lowest and top y).
	 * For the solid runs (-Dmcopt.lod.realOcc only): the y each column's run starts at (its ground's top block; Short.MIN_VALUE:
	 * none, under water), and copies of the chunk's sections it can reach (sections[i]: section secFirst + i; null: only air).
	 */
	record Snapshot(int chunkX, int chunkZ, BlockState[] top, BlockState[] under, short[] height, short[] water, Holder<Biome>[] biome,
		BlockState[] crown, BlockState[] crownLeaf, short[] crownLo, short[] crownHi, int[] crownRuns, BlockState[] trunk, BlockState[] above,
		short[] runFrom, PalettedContainer<BlockState>[] sections, int secFirst, int minY) {
	}

	/**
	 * A chunk's columns as clipmap columns: the ground (top face, water, colors) and, where a crown floats over air, its lowest
	 * block y (crownLo > the ground's surface), its top face y and colors (top / side then hold the crown's, groundColor the
	 * ground's top). Without a crown crownLo is Short.MIN_VALUE and crownHi the column's top.
	 */
	/**
	 * clear: the column's top doesn't hide what's behind it (water, glass, a fence, a slab...): it occludes nothing. depth
	 * (-Dmcopt.lod.realOcc only, else 0): how far the ground's top solid run reaches down, in blocks (1-127, 127: that deep or
	 * more; 0: none): under it may be air, a cave or the space under an overhang.
	 */
	record Summary(int chunkX, int chunkZ, short[] height, short[] water, int[] top, int[] side, int[] below, boolean[] fringe, short[] crownLo,
		short[] crownHi, int[] groundColor, int[] tex, int[] runs, int[] plantA, int[] plantB, boolean[] clear, byte[] depth) {
	}

	private LodChunks() {
	}

	/** A plant, torch or flower over the ground: seen from far away the ground shows. Snow layers count as ground. */
	private static boolean decoration(BlockState s) {
		// (powder snow has no collision shape for a query without an entity, but it is a block of snow to the eye)
		return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.SNOW) && !s.is(Blocks.POWDER_SNOW)
			&& s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	private static boolean crownBlock(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(Blocks.SNOW);
	}

	/** The deepest solid run counted (Snapshot.depth). */
	static final int DEPTH_MAX = 127;
	/** -Dmcopt.lod.realOccRuns=false: no solid runs (every real column solid all the way down), for the cave fixtures' A/B. */
	private static final boolean RUNS = LodMesh.REAL_OCC && !"false".equals(System.getProperty("mcopt.lod.realOccRuns"));

	/**
	 * How many blocks down from y (the ground's top block, x / z in the chunk) are solid (hide what's behind them), up to
	 * DEPTH_MAX, in the snapshot's section copies. A section that holds only such blocks counts whole (solid: per section, 0
	 * not looked at yet, 1 solid, 2 not). Runs on a worker.
	 */
	private static int solidRun(Snapshot s, byte[] solid, int x, int y, int z) {
		int d = 0;
		while (d < DEPTH_MAX) {
			if (y < s.minY) return DEPTH_MAX;
			int si = ((y - s.minY) >> 4) - s.secFirst;
			if (si < 0 || si >= s.sections.length) return d;
			PalettedContainer<BlockState> sec = s.sections[si];
			if (sec == null) return d;
			if (solid[si] == 0) solid[si] = (byte) (!sec.maybeHas(st -> !st.canOcclude()) ? 1 : 2);
			if (solid[si] == 1) {
				int n = ((y - s.minY) & 15) + 1;
				d += n;
				y -= n;
				continue;
			}
			if (!sec.get(x, (y - s.minY) & 15, z).canOcclude()) return d;
			d++;
			y--;
		}
		return DEPTH_MAX;
	}

	@SuppressWarnings("unchecked")
	static Snapshot snapshot(LevelChunk chunk) {
		int minY = chunk.getMinY();
		BlockState[] top = new BlockState[256], under = new BlockState[256], crown = new BlockState[256], crownLeaf = new BlockState[256];
		short[] height = new short[256], water = new short[256], crownLo = new short[256], crownHi = new short[256];
		int[] crownRuns = new int[256];
		BlockState[] trunk = new BlockState[256];
		// up to 3 blocks over the ground where a decoration stands on it (a plant, for the worker to tell)
		BlockState[] above = new BlockState[256 * 3];
		Holder<Biome>[] biome = new Holder[256];
		short[] runFrom = RUNS ? new short[256] : null;
		int runLo = Integer.MAX_VALUE, runHi = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx0 = chunk.getPos().getMinBlockX(), bz0 = chunk.getPos().getMinBlockZ();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int px = bx0 + x, pz = bz0 + z;
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				BlockState s = chunk.getBlockState(pos.set(px, y, pz));
				for (int guard = 0; guard < 8 && y > minY && (s.isAir() || decoration(s)); guard++) s = chunk.getBlockState(pos.set(px, --y, pz));
				crownLo[i] = Short.MIN_VALUE;
				crown[i] = null;
				if (crownBlock(s)) {
					// a crown: down through leaves (and the air between a spruce's layers) to whatever holds it up. Air right over the
					// ground means it floats; a log or the ground right under the leaves means the tree stands there.
					int topY = y;
					BlockState topState = s;
					int k = y - 1, lowest = y;
					BlockState b = chunk.getBlockState(pos.set(px, k, pz));
					boolean air = false;
					// leaf blocks down from the top (bit d: topY - d), for the crown's runs (a spruce's tiers)
					long below = topState.is(BlockTags.LEAVES) ? 1L : 0L;
					for (int guard = 0; guard < 48 && k > minY; guard++) {
						if (b.is(BlockTags.LEAVES)) {
							lowest = k;
							air = false;
							if (topY - k < 64) below |= 1L << (topY - k);
						} else if (b.isAir() || decoration(b) || b.is(Blocks.SNOW)) {
							// air between tiers (snow lies on each tier of a spruce in the cold)
							air = true;
						} else {
							break;
						}
						b = chunk.getBlockState(pos.set(px, --k, pz));
					}
					if (b.is(BlockTags.LOGS)) trunk[i] = b;
					if (air && !b.is(BlockTags.LOGS)) {
						crown[i] = topState;
						crownLeaf[i] = topState.is(Blocks.SNOW) ? chunk.getBlockState(pos.set(px, topY - 1, pz)) : topState;
						crownLo[i] = (short) lowest;
						// as bits up from the lowest leaf: the leaf at topY - d is lowest + (topY - lowest - d)
						long up = 0;
						for (int d = 0; d < 64; d++) if ((below >>> d & 1) != 0 && topY - lowest - d >= 0 && topY - lowest - d < 64) up |= 1L << (topY - lowest - d);
						crownRuns[i] = LodTile.runs(up);
						// a snow layer on the crown is a covering: the crown's top face is its top leaf's
						crownHi[i] = (short) (topState.is(Blocks.SNOW) ? topY : topY + 1);
						y = k;
						s = b;
					}
				}
				short wet = LodTile.DRY;
				if (s.getFluidState().is(FluidTags.WATER)) {
					wet = (short) (y + 1);
					for (int guard = 0; guard < 96 && y > minY && (s.getFluidState().is(FluidTags.WATER) || s.isAir()); guard++) {
						s = chunk.getBlockState(pos.set(px, --y, pz));
					}
				}
				top[i] = s;
				// a snow layer is a covering (an eighth of a block), not a block: the column's top stays the block under it
				height[i] = (short) (s.is(Blocks.SNOW) ? y : y + 1);
				water[i] = wet;
				under[i] = chunk.getBlockState(pos.set(px, y - 1, pz));
				// (a snow layer: the run starts at the block under it)
				if (runFrom != null) {
					int from = s.is(Blocks.SNOW) ? y - 1 : y;
					runFrom[i] = wet == LodTile.DRY ? (short) from : Short.MIN_VALUE;
					if (wet == LodTile.DRY) {
						runLo = Math.min(runLo, from);
						runHi = Math.max(runHi, from);
					}
				}
				if (crown[i] == null && wet == LodTile.DRY && !s.is(Blocks.SNOW)) {
					BlockState a = chunk.getBlockState(pos.set(px, y + 1, pz));
					for (int k = 0; k < 3 && decoration(a); k++) {
						above[i * 3 + k] = a;
						a = chunk.getBlockState(pos.set(px, y + 2 + k, pz));
					}
				}
				biome[i] = chunk.getNoiseBiome(QuartPos.fromBlock(px), QuartPos.fromBlock(y), QuartPos.fromBlock(pz));
			}
		}
		// the sections the runs can reach, copied (the walk runs on a worker)
		PalettedContainer<BlockState>[] sections = null;
		int secFirst = 0;
		if (runFrom != null && runLo <= runHi) {
			secFirst = Math.max(0, (Math.max(minY, runLo - DEPTH_MAX) - minY) >> 4);
			int secLast = Math.min(chunk.getSectionsCount() - 1, (runHi - minY) >> 4);
			sections = new PalettedContainer[Math.max(0, secLast - secFirst + 1)];
			for (int k = 0; k < sections.length; k++) {
				LevelChunkSection sec = chunk.getSection(secFirst + k);
				sections[k] = sec.hasOnlyAir() ? null : sec.getStates().copy();
			}
		}
		return new Snapshot(chunk.getPos().x(), chunk.getPos().z(), top, under, height, water, biome, crown, crownLeaf, crownLo, crownHi, crownRuns, trunk, above,
			runFrom, sections, secFirst, minY);
	}

	private static final BlockState WATER = Blocks.WATER.defaultBlockState();

	/** Colors, as the generator makes them (water over its floor by depth; crowns as LodNoise.dressTrees dresses them). Runs on a worker. */
	static boolean snowy(BlockState s) {
		return s.hasProperty(BlockStateProperties.SNOWY) && s.getValue(BlockStateProperties.SNOWY);
	}

	static Summary summarize(Snapshot s) {
		int[] top = new int[256], side = new int[256], groundColor = new int[256], below = new int[256], tex = new int[256];
		int[] plantA = new int[256], plantB = new int[256];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean[] fringe = new boolean[256], clear = new boolean[256];
		short[] crownHi = new short[256];
		byte[] solidDepth = new byte[256];
		if (s.sections != null) {
			byte[] solid = new byte[s.sections.length];
			for (int i = 0; i < 256; i++) if (s.runFrom[i] != Short.MIN_VALUE) solidDepth[i] = (byte) solidRun(s, solid, i & 15, s.runFrom[i], i >> 4);
		}
		// (a snow layer is a covering: the block under it hides or not)
		for (int i = 0; i < 256; i++) {
			clear[i] = s.water[i] != LodTile.DRY || s.top[i] == null || !(s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i]).canOcclude();
		}
		int bx0 = s.chunkX * 16, bz0 = s.chunkZ * 16;
		for (int i = 0; i < 256; i++) {
			int x = bx0 + (i & 15), z = bz0 + (i >> 4);
			Biome b = s.biome[i].value();
			int color = LodColors.top(s.top[i], b, x, z);
			// a snow layer covers the block under it: the column's side is that (snowy) block's, whose texture has its own
			// white band along the top (no fringe)
			boolean layer = s.top[i].is(Blocks.SNOW);
			int sideColor = LodColors.side(layer ? s.under[i] : s.top[i], b, x, z);
			below[i] = LodColors.top(s.under[i], b, x, z);
			fringe[i] = LodColors.fringed(s.top[i]) && !snowy(s.top[i]);
			boolean leavesTop = s.top[i].is(BlockTags.LEAVES), snowOnLeaves = s.top[i].is(Blocks.SNOW) && s.under[i].is(BlockTags.LEAVES);
			if (leavesTop || snowOnLeaves) {
				// a tree standing here, seen from afar: its crown on top, its leaves on its sides
				BlockState leaf = leavesTop ? s.top[i] : s.under[i];
				if (!snowOnLeaves) color = LodColors.top(leaf, b, x, z);
				sideColor = LodColors.side(leaf, b, x, z);
				// a trunk under the leaves: its logs below the top block
				below[i] = s.trunk[i] != null ? LodColors.side(s.trunk[i], b, x, z) : sideColor;
				fringe[i] = false;
			}
			if (s.water[i] != LodTile.DRY) {
				int depth = s.water[i] - s.height[i];
				int waterColor = LodColors.top(WATER, b, x, z);
				color = LodColors.mix(color, waterColor, Math.min(1.0F, 0.55F + depth / 24.0F));
				sideColor = color;
			}
			int surface = s.water[i] != LodTile.DRY ? Math.max(s.water[i], s.height[i]) : s.height[i];
			crownHi[i] = (short) surface;
			if (LodConfig.TEXTURES) {
				BlockState sideState = layer ? s.under[i] : s.top[i];
				BlockState belowState = s.trunk[i] != null && s.crown[i] == null ? s.trunk[i] : layer ? sideState : s.under[i];
				tex[i] = s.water[i] != LodTile.DRY ? 0 : LodPalette.word(LodPalette.id(s.top[i]), LodPalette.id(sideState), LodPalette.id(belowState));
			}
			if (s.crown[i] != null) {
				// a crown over air: its own colors; the ground keeps its own under it
				groundColor[i] = color;
				BlockState leaf = s.crownLeaf[i];
				BlockState topLeaf = leaf != null && leaf.is(BlockTags.LEAVES) ? leaf : Blocks.OAK_LEAVES.defaultBlockState();
				color = s.crown[i].is(Blocks.SNOW) ? LodColors.top(s.crown[i], b, x, z) : LodColors.top(topLeaf, b, x, z);
				sideColor = LodColors.side(topLeaf, b, x, z);
				crownHi[i] = s.crownHi[i];
				// the crown's top and sides, and the ground's block under it (its walls in the crown's shade)
				BlockState ground = s.top[i].is(Blocks.SNOW) ? s.under[i] : s.top[i];
				if (LodConfig.TEXTURES) tex[i] = LodPalette.word(LodPalette.id(s.crown[i]), LodPalette.id(topLeaf), LodPalette.id(ground));
			}
			top[i] = color;
			side[i] = sideColor;
			// a plant on the ground: its blocks (crossed quads, up to 3), color and the game's offset
			BlockState p0 = s.above[i * 3];
			if (LodConfig.PLANTS && p0 != null && s.crown[i] == null && LodColors.look(p0).cross() && p0.getFluidState().isEmpty()) {
				int blocks = 1;
				while (blocks < 3 && s.above[i * 3 + blocks] != null && LodColors.look(s.above[i * 3 + blocks]).cross()) blocks++;
				BlockState upper = blocks > 1 ? s.above[i * 3 + blocks - 1] : null;
				var off = p0.getOffset(pos.set(x, s.height[i], z));
				plantA[i] = LodClip.plantWordA(LodPalette.id(p0), upper != null ? LodPalette.id(upper) : 0, blocks);
				plantB[i] = LodClip.plantWordB(LodColors.side(p0, b, x, z), off.x(), off.y(), off.z());
			}
		}
		return new Summary(s.chunkX, s.chunkZ, s.height, s.water, top, side, below, fringe, s.crownLo, crownHi, groundColor, tex, s.crownRuns, plantA, plantB,
			clear, solidDepth);
	}
}
