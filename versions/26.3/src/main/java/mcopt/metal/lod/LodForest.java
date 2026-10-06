package mcopt.metal.lod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The game's own trees on terrain that was never generated: for a chunk, the vegetal decoration's tree features run exactly
 * as ChunkGenerator.applyBiomeDecoration runs them (the same decoration and feature seeds, the same placement modifiers and
 * tree shapes), against a stand-in level whose ground is the far terrain's own block-exact surface (LodNoise at level 0) and
 * which records every block the trees set. So a far forest has the trees the real chunks will have, where they will be.
 *
 * Each feature has a seed of its own (decoration seed, its index in the step, the step), so running only the tree features
 * leaves their randomness exactly the game's; within a feature the trees are placed in the game's order, which matters (each
 * consumes randomness). What differs from the real chunk: the ground ignores caves breaking the surface and earlier features
 * (lakes, rocks), and trees of neighboring chunks don't see each other. Per chunk the result is cached (tiles share border
 * chunks); a tile asks for its own chunks and the ring around them (trees reach across chunk edges).
 */
final class LodForest {
	/** A chunk's tree blocks: x, y, z (absolute) and the block state id, four ints each. */
	record Trees(int[] blocks) {
	}

	/** A chunk's ground, block exact: top face y, wet (sea over it), the surface block, the biome per column. */
	record Ground(short[] height, boolean[] wet, BlockState[] state) {
	}

	private static final int VEG = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
	private static final int CACHE_MAX = 40000;
	private final LodNoise noise;
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final long seed;
	private final long biomeSeed;
	private final FeatureSorter.@Nullable StepFeatureData step;
	/** Per global feature index of the vegetal step: whether it grows trees (big plants seen from afar). */
	private final boolean[] tree;
	/** Per global feature index: whether it grows plants (grass, ferns, flowers, bushes, cane, cacti, pumpkins). */
	private final boolean[] plants;
	/** In a chunk's block list: the block was set by a tree feature (else by a plant feature). */
	static final int FROM_TREE = 1 << 30;
	private final ConcurrentHashMap<Long, Ground> grounds = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, Trees> trees = new ConcurrentHashMap<>();
	private final ThreadLocal<Stand> stands;
	final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong(), runNanos = new java.util.concurrent.atomic.AtomicLong(),
		failures = new java.util.concurrent.atomic.AtomicLong();

	LodForest(LodNoise noise) {
		this.noise = noise;
		this.level = noise.level;
		this.generator = this.level.getChunkSource().getGenerator();
		this.seed = this.level.getSeed();
		this.biomeSeed = BiomeManager.obfuscateSeed(this.seed);
		this.step = stepData(this.generator);
		boolean[] t = new boolean[0], p = new boolean[0];
		if (this.step != null) {
			var registry = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			t = new boolean[this.step.features().size()];
			p = new boolean[t.length];
			int count = 0;
			List<String> plantIds = new ArrayList<>();
			for (int i = 0; i < t.length; i++) {
				Identifier id = registry.getKey(this.step.features().get(i));
				t[i] = id != null && isTree(id.getPath());
				p[i] = LodConfig.PLANTS && id != null && !t[i] && isPlants(id.getPath());
				if (t[i]) count++;
				if (p[i]) plantIds.add(id.getPath());
			}
			System.out.println("mcopt-lod: trees from " + count + " of the game's " + t.length + " vegetal features, plants from " + plantIds.size() + ": " + plantIds);
		}
		this.tree = t;
		this.plants = p;
		this.stands = ThreadLocal.withInitial(Stand::new);
	}

	private static boolean isTree(String path) {
		return path.startsWith("trees_") || path.equals("birch_tall") || path.equals("dark_forest_vegetation") || path.equals("pale_garden_vegetation")
			|| path.equals("mushroom_island_vegetation") || path.equals("bamboo_vegetation");
	}

	/** Features that grow small plants on the surface (not under water, not in caves, not on trees). */
	private static boolean isPlants(String path) {
		for (String no : new String[] {"sea", "kelp", "lily", "mushroom", "vine", "lichen", "spring", "bamboo", "cave", "dripleaf", "spore", "moss", "lush"}) {
			if (path.contains(no)) return false;
		}
		for (String yes : new String[] {"patch_", "flower", "grass", "fern", "bush", "cane", "cactus", "pumpkin", "melon", "berr", "leaf_litter", "petal"}) {
			if (path.contains(yes)) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static FeatureSorter.@Nullable StepFeatureData stepData(ChunkGenerator generator) {
		try {
			java.lang.reflect.Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
			f.setAccessible(true);
			List<FeatureSorter.StepFeatureData> steps = ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
			return VEG < steps.size() ? steps.get(VEG) : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.out.println("mcopt-lod: no feature list (" + e + "): far trees are impostors");
			return null;
		}
	}

	boolean available() {
		return this.step != null;
	}

	/** The chunk's ground at block resolution (cached). */
	Ground ground(int chunkX, int chunkZ) {
		long key = chunkKey(chunkX, chunkZ);
		Ground g = this.grounds.get(key);
		if (g != null) return g;
		LodTile t = new LodTile(0, chunkX * 16, chunkZ * 16, 16);
		t.impostorTrees = false;
		this.noise.generate(t);
		g = groundOf(t, 0, 0, 16);
		if (this.grounds.size() > CACHE_MAX) this.grounds.clear();
		this.grounds.put(key, g);
		return g;
	}

	/** A level-0 tile's chunks go into the ground cache as it is generated (tile cells from (cx, cz), 16 x 16). */
	void offer(LodTile t) {
		if (t.level != 0) return;
		for (int cz = 0; cz < t.size / 16; cz++) {
			for (int cx = 0; cx < t.size / 16; cx++) {
				long key = chunkKey((t.x0 >> 4) + cx, (t.z0 >> 4) + cz);
				if (!this.grounds.containsKey(key)) this.grounds.put(key, groundOf(t, cx * 16, cz * 16, t.size));
			}
		}
	}

	private Ground groundOf(LodTile t, int ox, int oz, int stride) {
		short[] h = new short[256];
		boolean[] wet = new boolean[256];
		BlockState[] st = new BlockState[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = (oz + z) * stride + ox + x;
				h[z * 16 + x] = t.ground[i];
				wet[z * 16 + x] = t.water[i] != LodTile.DRY && t.water[i] > t.ground[i];
				st[z * 16 + x] = t.state[i] != null ? t.state[i] : Blocks.STONE.defaultBlockState();
			}
		}
		return new Ground(h, wet, st);
	}

	static long chunkKey(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	/** The chunk's trees (cached): every block the game's tree features set when decorating it. */
	Trees trees(int chunkX, int chunkZ) {
		long key = chunkKey(chunkX, chunkZ);
		Trees t = this.trees.get(key);
		if (t != null) return t;
		t = this.run(chunkX, chunkZ);
		if (this.trees.size() > CACHE_MAX) this.trees.clear();
		this.trees.put(key, t);
		return t;
	}

	private Trees run(int chunkX, int chunkZ) {
		FeatureSorter.StepFeatureData step = this.step;
		if (step == null) return new Trees(new int[0]);
		long start = System.nanoTime();
		Stand s = this.stands.get();
		s.reset();
		// the features of the biomes at the chunk's surface (a feature whose biome isn't there places nothing: its biome check fails)
		Ground g = this.ground(chunkX, chunkZ);
		java.util.BitSet wanted = new java.util.BitSet();
		java.util.Set<Holder<Biome>> seen = new java.util.HashSet<>();
		// a 6 x 6 grid over the chunk and 4 blocks around it (the game's biome lookup jitters between neighboring 4-block cells)
		for (int gz = 0; gz < 6; gz++) {
			for (int gx = 0; gx < 6; gx++) {
				int lx = -4 + gx * 24 / 5, lz = -4 + gz * 24 / 5;
				int i = Math.clamp(lz, 0, 15) * 16 + Math.clamp(lx, 0, 15);
				Holder<Biome> b = s.biomes.getBiome(chunkX * 16 + lx, g.height()[i], chunkZ * 16 + lz);
				if (!seen.add(b)) continue;
				List<net.minecraft.core.HolderSet<PlacedFeature>> steps = this.generator.getBiomeGenerationSettings(b).features();
				if (steps.size() <= VEG) continue;
				for (Holder<PlacedFeature> pf : steps.get(VEG)) {
					int idx = step.indexMapping().applyAsInt(pf.value());
					if (idx >= 0 && idx < this.tree.length && (this.tree[idx] || this.plants[idx])) wanted.set(idx);
				}
			}
		}
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		BlockPos origin = new BlockPos(chunkX * 16, this.level.getMinY(), chunkZ * 16);
		long decoration = random.setDecorationSeed(this.seed, origin.getX(), origin.getZ());
		FeaturePlacer placer = new FeaturePlacer(s.proxy, this.generator);
		for (int idx = wanted.nextSetBit(0); idx >= 0; idx = wanted.nextSetBit(idx + 1)) {
			random.setFeatureSeed(decoration, idx, VEG);
			s.treeFeature = this.tree[idx];
			try {
				placer.placeWithBiomeCheck(step.features().get(idx), random, origin);
			} catch (RuntimeException e) {
				if (this.failures.incrementAndGet() <= 5) {
					var id = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(step.features().get(idx));
					System.out.println("mcopt-lod: tree feature " + id + " failed in chunk " + chunkX + "," + chunkZ + ": " + e);
				}
			}
		}
		int[] out = new int[s.placed.size() * 4];
		int n = 0;
		for (var e : s.placed.entrySet()) {
			long p = e.getKey();
			out[n++] = BlockPos.getX(p);
			out[n++] = BlockPos.getY(p);
			out[n++] = BlockPos.getZ(p);
			out[n++] = Block.getId(e.getValue()) | (s.fromTree.contains(p) ? FROM_TREE : 0);
		}
		this.runs.incrementAndGet();
		this.runNanos.addAndGet(System.nanoTime() - start);
		return new Trees(out);
	}

	/**
	 * Adds the trees of the tile's chunks and of the ring of chunks around them to a tile of level 0 or 1 (-Dmcopt.lod.treeLevels):
	 * per column (per cell: the blocks of its 2 x 2 columns at level 1, where at least two of them must hold a tree), a trunk
	 * or low growth standing on the ground raises the column; a crown over air is a floating canopy (canopyLo..canopyHi).
	 * The ground a tree changed (podzol, dirt under trunks) becomes the column's surface at level 0.
	 */
	void plant(LodTile t) {
		if (t.level >= LodConfig.TREE_LEVELS || t.level > 1 || this.step == null) return;
		int L = t.level, span = t.size << L;
		int c0x = (t.x0 >> 4) - 1, c0z = (t.z0 >> 4) - 1, c1x = ((t.x0 + span) >> 4), c1z = ((t.z0 + span) >> 4);
		int n = t.cells();
		int[] lo = new int[n], hi = new int[n], cols = new int[n];
		// the blocks a tree fills, up from the ground (bit k: ground + k), for the crown's runs (a spruce's tiers)
		long[] occ = new long[n];
		BlockState[] topState = new BlockState[n];
		boolean[] standing = new boolean[n];
		// level 0: plants standing on the ground, up to 3 blocks (k: ground + k)
		BlockState[] plantAt = L == 0 && LodConfig.PLANTS ? new BlockState[n * 3] : null;
		// level 1: which of a cell's 4 columns hold a tree block (bit per column)
		java.util.Arrays.fill(lo, Integer.MAX_VALUE);
		java.util.Arrays.fill(hi, Integer.MIN_VALUE);
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				int[] b = this.trees(cx, cz).blocks();
				for (int k = 0; k < b.length; k += 4) {
					int bx = b[k] - t.x0, y = b[k + 1], bz = b[k + 2] - t.z0;
					if (bx < 0 || bz < 0 || bx >= span || bz >= span) continue;
					int i = (bz >> L) * t.size + (bx >> L);
					BlockState st = Block.stateById(b[k + 3] & ~FROM_TREE);
					boolean fromTree = (b[k + 3] & FROM_TREE) != 0;
					if (st.isAir()) continue;
					int g = t.ground[i];
					if (L == 0 && y < g) {
						// the ground itself changed (podzol, rooted dirt, mud under mangroves)
						if (y == g - 1 && fromTree) t.state[i] = st;
						continue;
					}
					if (y < g) continue;
					if (!fromTree) {
						// a plant (crossed quads) on the ground; solid growth (cacti, pumpkins, melons) stands like a tree; flat
						// growth (petals, leaf litter) isn't seen from afar
						if (LodColors.look(st).cross() && st.getFluidState().isEmpty()) {
							if (plantAt != null && y - g < 3) plantAt[i * 3 + (y - g)] = st;
							continue;
						}
						var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
						if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
					}
					cols[i] |= 1 << (((bz & L) << 1) | (bx & L));
					if (y - g < 64) occ[i] |= 1L << (y - g);
					if (st.is(BlockTags.LOGS)) t.trunk[i] = st;
					if (y == g) standing[i] = true;
					if (y < lo[i]) lo[i] = y;
					if (y > hi[i]) {
						hi[i] = y;
						topState[i] = st;
					}
				}
			}
		}
		if (plantAt != null) {
			for (int i = 0; i < n; i++) {
				if (plantAt[i * 3] == null || standing[i] || t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
				int blocks = 1;
				while (blocks < 3 && plantAt[i * 3 + blocks] != null) blocks++;
				t.plantLower[i] = plantAt[i * 3];
				t.plantUpper[i] = blocks > 1 ? plantAt[i * 3 + blocks - 1] : null;
				t.plantBlocks[i] = (byte) blocks;
			}
		}
		for (int i = 0; i < n; i++) {
			if (hi[i] == Integer.MIN_VALUE || L == 1 && Integer.bitCount(cols[i]) < 2) continue;
			t.canopyLo[i] = (short) (standing[i] ? t.ground[i] : lo[i]);
			t.canopyHi[i] = (short) hi[i];
			t.canopyState[i] = topState[i];
			t.standing[i] = standing[i] || lo[i] <= t.ground[i];
			if (!t.standing[i] && occ[i] != 0) t.crownRuns[i] = LodTile.runs(occ[i] >>> Long.numberOfTrailingZeros(occ[i]));
		}
	}

	/** The stand-in level a chunk's trees grow in: its ground from the far terrain, what the trees set in a map. */
	private final class Stand implements InvocationHandler {
		final HashMap<Long, BlockState> placed = new HashMap<>();
		/** Blocks the tree features set (the rest: plant features). */
		final java.util.HashSet<Long> fromTree = new java.util.HashSet<>();
		boolean treeFeature;
		/** Per column (x, z packed), per heightmap type: the highest block set that the heightmap counts (heightmaps rise with them). */
		final HashMap<Long, int[]> tops = new HashMap<>();
		final WorldGenLevel proxy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, this);
		final BiomeManager biomes = new BiomeManager(LodForest.this.noise.resolver(), LodForest.this.biomeSeed);
		final RandomSource random = RandomSource.create(0L);
		private final java.util.Set<String> unknown = new java.util.HashSet<>();

		void reset() {
			this.placed.clear();
			this.fromTree.clear();
			this.tops.clear();
			this.scratch = null;
		}

		private net.minecraft.world.level.chunk.@Nullable ProtoChunk scratch;

		net.minecraft.world.level.chunk.ProtoChunk scratch() {
			if (this.scratch == null) {
				this.scratch = new net.minecraft.world.level.chunk.ProtoChunk(net.minecraft.world.level.ChunkPos.ZERO, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
					LodForest.this.level, LodForest.this.level.palettedContainerFactory(), null);
			}
			return this.scratch;
		}

		BlockState state(int x, int y, int z) {
			BlockState p = this.placed.get(BlockPos.asLong(x, y, z));
			if (p != null) return p;
			Ground g = LodForest.this.ground(x >> 4, z >> 4);
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			if (y < h) {
				if (y == h - 1) return g.state()[i];
				if (y >= h - 4) {
					BlockState s = g.state()[i];
					return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : s;
				}
				return Blocks.STONE.defaultBlockState();
			}
			if (g.wet()[i] && y < LodForest.this.noise.seaLevel) return Blocks.WATER.defaultBlockState();
			return Blocks.AIR.defaultBlockState();
		}

		int height(Heightmap.Types type, int x, int z) {
			Ground g = LodForest.this.ground(x >> 4, z >> 4);
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			int[] top = this.tops.get(BlockPos.asLong(x, 0, z));
			int placedTop = top == null || top[type.ordinal()] == Integer.MIN_VALUE ? Integer.MIN_VALUE : top[type.ordinal()] + 1;
			int water = g.wet()[i] ? Math.max(h, LodForest.this.noise.seaLevel) : h;
			return switch (type) {
				case OCEAN_FLOOR, OCEAN_FLOOR_WG -> Math.max(h, placedTop);
				default -> Math.max(water, placedTop);
			};
		}

		@Override
		public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
			String name = m.getName();
			int n = a == null ? 0 : a.length;
			switch (name) {
				case "getBlockState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ());
					}
					break;
				case "getFluidState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ()).getFluidState();
					}
					break;
				case "isStateAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<BlockState> pred = (Predicate<BlockState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()));
					}
					break;
				case "isFluidAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<FluidState> pred = (Predicate<FluidState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()).getFluidState());
					}
					break;
				case "setBlock":
					if (n >= 2) {
						BlockPos p = (BlockPos) a[0];
						BlockState st = (BlockState) a[1];
						this.placed.put(p.asLong(), st);
						if (this.treeFeature) this.fromTree.add(p.asLong());
						else this.fromTree.remove(p.asLong());
						if (!st.isAir()) {
							int[] top = this.tops.computeIfAbsent(BlockPos.asLong(p.getX(), 0, p.getZ()), k -> {
								int[] none = new int[Heightmap.Types.values().length];
								java.util.Arrays.fill(none, Integer.MIN_VALUE);
								return none;
							});
							for (Heightmap.Types t : Heightmap.Types.values()) if (t.isOpaque().test(st)) top[t.ordinal()] = Math.max(top[t.ordinal()], p.getY());
						}
						return true;
					}
					break;
				case "removeBlock":
				case "destroyBlock":
					if (n >= 1) {
						BlockPos p = (BlockPos) a[0];
						this.placed.put(p.asLong(), Blocks.AIR.defaultBlockState());
						return true;
					}
					break;
				case "getHeight":
					if (n == 3) return this.height((Heightmap.Types) a[0], (Integer) a[1], (Integer) a[2]);
					if (n == 0) return LodForest.this.level.getHeight();
					break;
				case "getMinY":
					return LodForest.this.level.getMinY();
				case "getBiome":
					if (n == 1) return this.biomes.getBiome((BlockPos) a[0]);
					break;
				case "getSeed":
					return LodForest.this.seed;
				case "getLevel":
					return LodForest.this.level;
				case "registryAccess":
					return LodForest.this.level.registryAccess();
				case "getRandom":
					return this.random;
				case "getBlockEntity":
					if (n == 1) return null;
					break;
				case "getChunk":
					// a feature marking blocks for post-processing (fallen trees, multiface growth): a scratch chunk takes the marks
					return this.scratch();
				case "getLightEngine":
					return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
				case "ensureCanWrite":
				case "hasChunk":
				case "isAreaLoaded":
					return true;
				case "setCurrentlyGenerating":
				case "scheduleTick":
				case "blockUpdated":
				case "updateNeighborsAt":
				case "playSound":
				case "levelEvent":
				case "gameEvent":
				case "addParticle":
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == a[0];
				case "toString":
					return "LodForest.Stand";
				default:
					break;
			}
			if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
			if (this.unknown.add(name + "/" + n)) System.out.println("mcopt-lod: tree stand-in level asked " + name + "/" + n + " (answering a default)");
			Class<?> r = m.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			if (r == float.class) return 0.0F;
			if (r == double.class) return 0.0;
			if (r == java.util.Optional.class) return java.util.Optional.empty();
			if (r == List.class) return new ArrayList<>();
			return null;
		}
	}
}
