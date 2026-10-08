package mcopt.metal.lod;

import java.util.Optional;
import java.util.function.Function;
import mcopt.metal.mixin.lod.LodMaterialContextAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Far terrain straight from the world generator, without generating a chunk: the game's own density functions sampled in
 * batches over a tile's columns at the tile's resolution (26.3's DensityVolume takes a step), the biome from the same
 * climate noise the game uses, the top block from the dimension's material rules (the data-driven surface rules), colors
 * from the block textures and biome tints, and an impostor canopy where the biome grows trees.
 *
 * The terrain density is the overworld's sloped_cheese (the shape before caves are carved: a far surface with cave mouths
 * punched into it only flickers) with the final density's top slide applied; its zero crossing between the 8-block samples
 * is found by the same linear interpolation the game's cells use (cell height 8), and below level 2 the columns between
 * the 4-block corners are interpolated bilinearly, also as the game does: at those levels the surface is the game's own.
 */
final class LodNoise {
	private static final int STEP_Y = 8;
	final ServerLevel level;
	final NoiseGeneratorSettings settings;
	final RandomState random;
	final DensityFunction terrain;
	/** Levels 0-1 (-Dmcopt.lod.fineDensity): the router's final density, caves and all, so cave mouths break the surface as they do. */
	final DensityFunction fine;
	/**
	 * The final density taken apart as the game evaluates it: min(f(interpolated(inner)), carvers...) with f keeping the sign
	 * (squeeze, scaling): a block is solid where inner, interpolated across the game's cells, and every carver (the noodle
	 * caves, evaluated per block) are positive. Null when the router isn't shaped so (then the corner values of the final
	 * density are interpolated, which moves steep surfaces by a block or more: squeeze saturates).
	 */
	final @Nullable Split split;
	/** The game's aquifers (levels 0-1: whether a column's ground lies under water, and the water's level), or null. */
	final Aquifer.@Nullable Config aquifers;
	final @Nullable PositionalRandomFactory aquiferRandom;
	final Aquifer.@Nullable FluidPicker fluidPicker;

	record Split(DensityFunction inner, int cellXz, int cellY, java.util.List<DensityFunction> carvers) {
	}

	/** The function's tree to a depth, for the log (record components that are functions or holders of one). */
	static String describe(Object f, int depth) {
		if (f instanceof Holder<?> h) return "holder(" + describe(h.value(), depth) + ")";
		if (!(f instanceof Record r)) return String.valueOf(f).length() > 60 ? f.getClass().getSimpleName() : String.valueOf(f);
		StringBuilder b = new StringBuilder(f.getClass().getSimpleName());
		if (depth <= 0) return b.append("(..)").toString();
		b.append('(');
		boolean first = true;
		for (var c : r.getClass().getRecordComponents()) {
			try {
				Object v = c.getAccessor().invoke(r);
				if (!first) b.append(", ");
				first = false;
				b.append(v instanceof DensityFunction || v instanceof Holder<?> ? describe(v, depth - 1) : String.valueOf(v));
			} catch (ReflectiveOperationException | RuntimeException e) {
				b.append('?');
			}
		}
		return b.append(')').toString();
	}

	private static boolean beardifier(DensityFunction f) {
		return String.valueOf(f).equals("BEARDIFIER") || f.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("beardifier");
	}

	static @Nullable Split split(DensityFunction f, java.util.List<DensityFunction> carvers) {
		for (int guard = 0; guard < 64; guard++) {
			if (f instanceof DensityFunctions.HolderHolder h) {
				f = h.function().value();
			} else if (f instanceof InterpolatedFunction i) {
				return new Split(i.input(), i.cellSizeXz(), i.cellSizeY(), carvers);
			} else if (f instanceof UnaryFunction u && u.type() == UnaryFunction.Type.SQUEEZE) {
				f = u.input();
			} else if (f instanceof BinaryFunction b && b.type() == BinaryFunction.Type.MUL && b.left() instanceof ConstantFunction c && c.value() > 0) {
				f = b.right();
			} else if (f instanceof BinaryFunction b && b.type() == BinaryFunction.Type.MUL && b.right() instanceof ConstantFunction c && c.value() > 0) {
				f = b.left();
			} else if (f instanceof BinaryFunction b && b.type() == BinaryFunction.Type.ADD && beardifier(b.right())) {
				// structures' terrain adaptation: nothing where no structure stands (the far terrain has none)
				f = b.left();
			} else if (f instanceof BinaryFunction b && b.type() == BinaryFunction.Type.ADD && beardifier(b.left())) {
				f = b.right();
			} else if (f instanceof BinaryFunction b && b.type() == BinaryFunction.Type.MIN) {
				java.util.List<DensityFunction> withRight = new java.util.ArrayList<>(carvers);
				withRight.add(b.right());
				Split s = split(b.left(), withRight);
				if (s != null) return s;
				java.util.List<DensityFunction> withLeft = new java.util.ArrayList<>(carvers);
				withLeft.add(b.left());
				return split(b.right(), withLeft);
			} else {
				return null;
			}
		}
		return null;
	}
	final boolean slideTop;
	final BiomeSource biomes;
	final @Nullable MultiNoiseBiomeSource multi;
	final int seaLevel, minY, maxY;
	final WorldGenerationContext context;
	private final ThreadLocal<Worker> workers;
	private final BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState(), ice = Blocks.ICE.defaultBlockState(), water = Blocks.WATER.defaultBlockState();

	/** Per thread: the sampler contexts hold caches and buffers. */
	private final class Worker {
		{
			// (-Dmcopt.lod.qos: the generation workers' QoS class, set by each worker thread as it makes its sampler context)
			LodGenQos.once(LodGenQos.LOD, "far-terrain generation");
		}

		final SamplerContext ctx = SamplerContext.builder().enableCaches().build();
		// (-Dmcopt.lod.nativeNoise: copies whose noise leaves are evaluated by LodNativeNoise's backends; off: the game's own)
		final DensitySamplerSet samplers = LodNativeNoise.wrap(LodNoise.this.random.samplersWithContext(this.ctx), LodNoise.this);
		final DensitySampler.Bound terrain = this.samplers.get(LodNoise.this.terrain);
		final DensitySampler.Bound fine = this.samplers.get(LodNoise.this.fine);
		final DensitySampler.@Nullable Bound inner = LodNoise.this.split != null ? this.samplers.get(LodNoise.this.split.inner()) : null;
		final DensitySampler.Bound[] carvers = LodNoise.this.split != null
			? LodNoise.this.split.carvers().stream().map(this.samplers::get).toArray(DensitySampler.Bound[]::new) : new DensitySampler.Bound[0];
		final Climate.Sampler climate = LodNoise.this.random.createClimateSampler(this.ctx);
		final BiomeResolver resolver = LodNoise.this.biomes.createResolver(this.climate);
		float[] columns = new float[0];
	}

	private LodNoise(ServerLevel level, NoiseGeneratorSettings settings, RandomState random, DensityFunction terrain, boolean slideTop) {
		this.level = level;
		this.settings = settings;
		this.random = random;
		this.terrain = terrain;
		this.fine = LodConfig.FINE_DENSITY ? settings.noiseRouter().finalDensity() : terrain;
		Split sp = LodConfig.FINE_DENSITY ? split(settings.noiseRouter().finalDensity(), java.util.List.of()) : null;
		// the walk below assumes the game's cells of 4 x 8 x 4 blocks
		this.split = sp != null && sp.cellXz() == 4 && sp.cellY() == STEP_Y ? sp : null;
		if (this.split == null && LodConfig.FINE_DENSITY) System.out.println("mcopt-lod: final density: " + describe(settings.noiseRouter().finalDensity(), 5));
		System.out.println("mcopt-lod: final density " + (this.split != null ? "split: interpolated over " + sp.cellXz() + "x" + sp.cellY() + " cells, "
			+ this.split.carvers().size() + " carver(s) per block" : "not split (" + (sp == null ? "unexpected shape" : "cells " + sp.cellXz() + "x" + sp.cellY()) + ")"));
		this.slideTop = slideTop;
		Aquifer.Config aq = settings.aquifers().orElse(null);
		Aquifer.FluidPicker picker = null;
		if (aq != null && level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator nb) {
			try {
				java.lang.reflect.Field fp = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
				fp.setAccessible(true);
				picker = (Aquifer.FluidPicker) ((java.util.function.Supplier<?>) fp.get(nb)).get();
			} catch (ReflectiveOperationException | RuntimeException e) {
				System.out.println("mcopt-lod: no fluid picker (" + e + "): water by sea level");
			}
		}
		this.aquifers = picker != null ? aq : null;
		this.fluidPicker = picker;
		this.aquiferRandom = picker != null ? random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer")) : null;
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		this.biomes = gen.getBiomeSource();
		this.multi = this.biomes instanceof MultiNoiseBiomeSource m ? m : null;
		this.seaLevel = settings.seaLevel();
		this.minY = Math.max(level.getMinY(), settings.noiseSettings().minY());
		this.maxY = Math.min(level.getMaxY(), settings.noiseSettings().minY() + settings.noiseSettings().height() - 1);
		this.context = new WorldGenerationContext(gen, level);
		this.workers = ThreadLocal.withInitial(Worker::new);
	}

	/** A generator for this level, or null when it isn't noise-based (flat, debug) or has a ceiling. */
	static @Nullable LodNoise of(ServerLevel level) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator gen)) return null;
		if (level.dimensionType().hasCeiling()) return null;
		NoiseGeneratorSettings settings = gen.generatorSettings().value();
		RandomState random = level.getChunkSource().randomState();
		DensityFunction terrain = settings.noiseRouter().finalDensity();
		boolean slide = false;
		Optional<? extends Holder<DensityFunction>> cheese = level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION)
			.get(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace("overworld/sloped_cheese")));
		if (cheese.isPresent() && gen.stable(NoiseGeneratorSettings.OVERWORLD)) {
			terrain = cheese.get().value();
			slide = true;
		}
		System.out.println("mcopt-lod: terrain from " + (slide ? "overworld/sloped_cheese (+ top slide)" : "the router's final density") + " in " + level.dimension().identifier());
		return new LodNoise(level, settings, random, terrain, slide);
	}

	/** This thread's biome resolver (the climate noise, as the game's chunks are filled). */
	BiomeResolver resolver() {
		return this.workers.get().resolver;
	}

	/** Nanoseconds spent per level in each stage (density, biomes, materials), and tiles, for the stats line. */
	final java.util.concurrent.atomic.AtomicLongArray stageNanos = new java.util.concurrent.atomic.AtomicLongArray(16 * 4);

	/** Fills t's columns. Runs on a worker thread (with -Dmcopt.lod.yield, after the server's chunk work: LodYield). */
	void generate(LodTile t) {
		if (!LodYield.ON) {
			this.generate0(t);
			return;
		}
		LodYield.enter(t.level, LodYield.top);
		try {
			this.generate0(t);
		} finally {
			LodYield.exit();
		}
	}

	private void generate0(LodTile t) {
		long t0 = System.nanoTime();
		Worker w = this.workers.get();
		int c = t.cell(), x0 = t.minX(), z0 = t.minZ(), size = t.size;
		// Sample grid: every s blocks (s >= 4, the game's cell width), corners included, so finer levels interpolate.
		int s = Math.max(c, 4), n = size * c / s, g = n + 1;
		if (s == c && COARSE) {
			this.coarseSurface(t, w);
			this.finish(t, w, t0);
			return;
		}
		int ny = (this.maxY - this.minY) / STEP_Y + 1, y0 = Math.floorDiv(this.minY, STEP_Y) * STEP_Y;
		DensityVolume volume = new DensityVolume(g, ny, g, x0, y0, z0, s, STEP_Y, s);
		int vsize = volume.size();
		if (w.columns.length < vsize) w.columns = new float[vsize];
		DensityBuffer buffer = DensityBuffer.createUnpooled(vsize);
		boolean fine = LodConfig.FINE_DENSITY && t.level < LodConfig.FINE_LEVELS;
		// with the final density split, the interpolated part at the cell corners (the game interpolates it, then squeezes)
		boolean exact = fine && w.inner != null && s == 4;
		(exact ? w.inner : fine ? w.fine : w.terrain).sampleVolume(buffer, volume);
		float[] d = w.columns;
		for (int i = 0; i < vsize; i++) d[i] = buffer.get(i);
		if (this.slideTop && !fine) {
			// the final density's top slide: toward -0.078125 from y 240 to 256
			for (int k = 0; k < ny; k++) {
				int y = y0 + k * STEP_Y;
				float a = Math.clamp((256 - y) / 16.0F, 0.0F, 1.0F);
				if (a >= 1.0F) continue;
				for (int col = 0; col < g * g; col++) {
					int i = k + col * ny;
					d[i] = -0.078125F + a * (d[i] + 0.078125F);
				}
			}
		}
		// Surface per cell.
		short[] h = t.height;
		if (s == c) {
			for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) h[z * size + x] = (short) surface(d, (x + z * g) * ny, ny, y0);
		} else {
			// bilinear between the 4-block corners (levels 0 and 1), then the crossing of the interpolated column
			float[] col = new float[ny];
			// the game's aquifers decide what fills the air over ground under sea level (the sea, a local level, or nothing)
			Aquifer aquifer = exact && this.aquifers != null && this.aquiferRandom != null && this.fluidPicker != null
				? this.aquifers.create(w.samplers, this.aquiferRandom, volume, this.fluidPicker) : null;
			if (aquifer != null) t.waterKnown = true;
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					float bx = (x * c + c * 0.5F - 0.5F) / s, bz = (z * c + c * 0.5F - 0.5F) / s;
					int ix = Math.min(n - 1, (int) bx), iz = Math.min(n - 1, (int) bz);
					float fx = bx - ix, fz = bz - iz;
					int a00 = (ix + iz * g) * ny, a10 = (ix + 1 + iz * g) * ny, a01 = (ix + (iz + 1) * g) * ny, a11 = (ix + 1 + (iz + 1) * g) * ny;
					for (int k = 0; k < ny; k++) {
						float v0 = d[a00 + k] + (d[a10 + k] - d[a00 + k]) * fx;
						float v1 = d[a01 + k] + (d[a11 + k] - d[a01 + k]) * fx;
						col[k] = v0 + (v1 - v0) * fz;
					}
					int top = surface(col, 0, ny, y0);
					// the carvers (noodle caves) take blocks out per block: down to the first block they leave
					if (exact && c == 1 && w.carvers.length > 0 && top > this.minY) {
						int bxw = x0 + x, bzw = z0 + z;
						for (int y = top - 1, guard = 0; y > this.minY && guard < 24; y--, guard++) {
							if (!(interpolatedAt(col, y, y0) > 0)) continue;
							boolean carved = false;
							for (DensitySampler.Bound cv : w.carvers) {
								if (!(cv.sampleValue(bxw, y, bzw) > 0)) {
									carved = true;
									break;
								}
							}
							if (!carved) {
								top = y + 1;
								break;
							}
						}
					}
					h[z * size + x] = (short) top;
					if (aquifer != null) t.water[z * size + x] = this.waterOver(aquifer, col, x0 + x * c + c / 2, top, z0 + z * c + c / 2, y0);
				}
			}
		}
		this.finish(t, w, t0);
	}

	/** The top face of the water the game's aquifer puts over a column's ground (DRY: none; under sea level only). */
	private short waterOver(Aquifer aquifer, float[] col, int x, int top, int z, int y0) {
		if (top >= this.seaLevel) return LodTile.DRY;
		try {
			BlockState over = aquifer.computeSubstance(x, top, z, Math.min(-1e-4F, interpolatedAt(col, top, y0)));
			if (over == null || !over.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return LodTile.DRY;
			int y = top;
			while (y < this.seaLevel + 16) {
				BlockState next = aquifer.computeSubstance(x, y + 1, z, Math.min(-1e-4F, interpolatedAt(col, y + 1, y0)));
				if (next == null || !next.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) break;
				y++;
			}
			return (short) (y + 1);
		} catch (RuntimeException e) {
			return top < this.seaLevel ? (short) this.seaLevel : LodTile.DRY;
		}
	}

	/** The column's interpolated value at block y (linear between the 8-block samples, as the game's cells). */
	private static float interpolatedAt(float[] col, int y, int y0) {
		int k = Math.floorDiv(y - y0, STEP_Y);
		if (k < 0) return col[0];
		if (k + 1 >= col.length) return col[col.length - 1];
		float f = (y - y0 - k * STEP_Y) / (float) STEP_Y;
		return col[k] + (col[k + 1] - col[k]) * f;
	}

	/** -Dmcopt.lod.coarse=false: sample every 8 blocks of every column at every level (the slow reference). */
	private static final boolean COARSE = Boolean.parseBoolean(System.getProperty("mcopt.lod.coarse", "true"));
	private static final int COARSE_Y = 32;

	private float slide(int y, float d) {
		if (!this.slideTop || y < 240) return d;
		float a = Math.clamp((256 - y) / 16.0F, 0.0F, 1.0F);
		return -0.078125F + a * (d + 0.078125F);
	}

	/**
	 * Levels 2 and up (cells of 4 blocks or more, one column per cell): the density every 32 blocks over the whole column (one
	 * batched volume) brackets the top crossing; two point samples inside the bracket narrow it to the game's 8-block cell,
	 * interpolated linearly as the game does. The point samples find the 2D parts of the density (splines over continents,
	 * erosion, ridges) in the sampler's caches from the volume, so only the 3D noise is evaluated again: a quarter of the work.
	 */
	private void coarseSurface(LodTile t, Worker w) {
		int c = t.cell(), x0 = t.minX(), z0 = t.minZ(), n = t.size;
		int y0 = Math.floorDiv(this.minY, COARSE_Y) * COARSE_Y, ny = (this.maxY - y0) / COARSE_Y + 1;
		DensityVolume volume = new DensityVolume(n, ny, n, x0, y0, z0, c, COARSE_Y, c);
		int size = volume.size();
		if (w.columns.length < size) w.columns = new float[size];
		DensityBuffer buffer = DensityBuffer.createUnpooled(size);
		w.terrain.sampleVolume(buffer, volume);
		float[] d = w.columns;
		for (int k = 0; k < ny; k++) {
			int y = y0 + k * COARSE_Y;
			for (int col = 0; col < n * n; col++) d[k + col * ny] = this.slide(y, buffer.get(k + col * ny));
		}
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int at = (x + z * n) * ny, k = ny - 1;
				while (k >= 0 && d[at + k] <= 0) k--;
				// a single solid sample with air under it is a thin floating pocket (an overhang's lip): a heightfield would
				// stand it on a pillar to the ground, so look under it for the ground
				while (k >= 1 && d[at + k - 1] <= 0) {
					k--;
					while (k >= 0 && d[at + k] <= 0) k--;
				}
				int h;
				if (k < 0) h = this.minY;
				else if (k == ny - 1) h = y0 + k * COARSE_Y + 1;
				else {
					int bx = x0 + x * c, bz = z0 + z * c;
					int lo = y0 + k * COARSE_Y, hi = lo + COARSE_Y;
					float vlo = d[at + k], vhi = d[at + k + 1];
					// halve the 32-block bracket twice: to the game's 8-block cell
					for (int step = COARSE_Y / 2; step >= STEP_Y; step /= 2) {
						int mid = lo + step;
						float v = this.slide(mid, w.terrain.sampleValue(bx, mid, bz));
						if (v > 0) {
							lo = mid;
							vlo = v;
						} else {
							hi = mid;
							vhi = v;
						}
					}
					float f = vlo / (vlo - vhi);
					h = (int) Math.floor(lo + f * (hi - lo)) + 1;
				}
				t.height[z * n + x] = (short) h;
			}
		}
	}

	private void finish(LodTile t, Worker w, long t0) {
		if (LodYield.ON) LodYield.checkpoint();
		long t1 = System.nanoTime();
		this.biomes(t, w);
		if (LodYield.ON) LodYield.checkpoint();
		long t2 = System.nanoTime();
		this.surfaceMaterials(t, w);
		long t3 = System.nanoTime();
		int l = Math.min(15, t.level);
		this.stageNanos.addAndGet(l * 4, t1 - t0);
		this.stageNanos.addAndGet(l * 4 + 1, t2 - t1);
		this.stageNanos.addAndGet(l * 4 + 2, t3 - t2);
		this.stageNanos.incrementAndGet(l * 4 + 3);
		t.source = LodTile.SOURCE_NOISE;
	}

	/**
	 * After LodForest planted the game's trees in a level-0 tile: their colors (the top block's own texture and tint, snow
	 * on crowns where it snows at their height), and ground the trees changed (podzol under big spruces). A tree standing on
	 * the ground (a trunk, low growth) raises its column to its top; a crown over air keeps the ground under it and gets its
	 * own colors (the walk draws it floating, -Dmcopt.lod.crownLevels); its sides are its leaves in their own shade.
	 */
	void dressTrees(LodTile t) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int c = t.cell();
		for (int z = 0; z < t.size; z++) {
			for (int x = 0; x < t.size; x++) {
				int i = z * t.size + x;
				int bx = t.x0 + x * c, bz = t.z0 + z * c;
				Biome b = t.biome[i].value();
				if (t.canopyHi[i] < t.canopyLo[i]) continue;
				BlockState top = t.canopyState[i];
				boolean leaves = top.is(net.minecraft.tags.BlockTags.LEAVES);
				int topColor = LodColors.top(top, b, bx, bz);
				pos.set(bx, t.canopyHi[i] + 1, bz);
				if (leaves && b.coldEnoughToSnow(pos, this.seaLevel)) topColor = LodColors.top(this.snow, b, bx, bz);
				int sideColor = LodColors.side(top, b, bx, bz);
				t.crownTop[i] = topColor;
				t.crownSide[i] = sideColor;
				if (LodConfig.TEXTURES) {
					// a crown's word: its top's and its sides' blocks, and the ground's under it (its walls in the crown's shade)
					boolean snowy = leaves && b.coldEnoughToSnow(pos, this.seaLevel);
					BlockState ground = t.state[i] != null ? t.state[i] : top;
					t.tex[i] = LodPalette.word(LodPalette.id(snowy ? this.snow : top), LodPalette.id(top), LodPalette.id(ground));
				}
				if (t.standing[i] || t.level >= LodConfig.CROWN_LEVELS) {
					// a column the tree stands in: its top block's leaves on top and along its upper block, its trunk below
					BlockState trunk = t.trunk[i];
					int trunkColor = trunk != null ? LodColors.side(trunk, b, bx, bz) : sideColor;
					t.height[i] = (short) Math.max(t.height[i], t.canopyHi[i] + 1);
					t.top[i] = topColor;
					t.side[i] = sideColor;
					t.below[i] = trunkColor;
					t.fringe[i] = false;
					t.standing[i] = true;
					if (LodConfig.TEXTURES) {
						boolean snowy = leaves && b.coldEnoughToSnow(pos, this.seaLevel);
						t.tex[i] = LodPalette.word(LodPalette.id(snowy ? this.snow : top), LodPalette.id(top), LodPalette.id(trunk != null ? trunk : top));
					}
					continue;
				}
			}
		}
	}

	/**
	 * After LodForest on a level-0 tile: plants' colors (their lowest block's texture under its tint), and the ground where the
	 * game's snow can't fall: it settles on the first block that stops motion (a crown's leaves, not the ground under them) and
	 * only into air (not where a plant stands).
	 */
	void dressGround(LodTile t) {
		if (t.level != 0) return;
		for (int z = 0; z < t.size; z++) {
			for (int x = 0; x < t.size; x++) {
				int i = z * t.size + x;
				int bx = t.x0 + x, bz = t.z0 + z;
				Biome b = t.biome[i].value();
				boolean crown = t.canopyHi[i] >= t.canopyLo[i] && !t.standing[i];
				BlockState p = t.plantLower[i];
				if (p != null) t.plantColor[i] = LodColors.side(p, b, bx, bz);
				if (p == null && !crown || t.standing[i] || t.state[i] == null) continue;
				if (t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
				BlockState ground = t.state[i];
				t.top[i] = LodColors.top(ground, b, bx, bz);
				if (!crown) {
					t.side[i] = LodColors.side(ground, b, bx, bz);
					t.fringe[i] = LodColors.fringed(ground);
					if (LodConfig.TEXTURES) t.tex[i] = LodPalette.word(LodPalette.id(ground), LodPalette.id(ground), (t.tex[i] >> 20) & 1023);
				}
			}
		}
	}

	/** The y of the top face over the highest solid sample: the zero crossing below the first positive density from the top. */
	private int surface(float[] d, int at, int ny, int y0) {
		for (int k = ny - 1; k >= 0; k--) {
			float v = d[at + k];
			if (v > 0) {
				if (k == ny - 1) return y0 + k * STEP_Y + 1;
				float above = d[at + k + 1];
				float f = v / (v - above);
				return (int) Math.floor(y0 + (k + f) * STEP_Y) + 1;
			}
		}
		return this.minY;
	}

	/** Biomes per column from the climate noise, sampled as a volume (two planes: depth is linear in y). */
	private void biomes(LodTile t, Worker w) {
		int c = t.cell(), x0 = t.minX(), z0 = t.minZ(), size = t.size;
		int bs = Math.max(c, 4), n = size * c / bs;
		if (this.multi != null) {
			DensityVolume v = new DensityVolume(n, 2, n, x0 + bs / 2, 0, z0 + bs / 2, bs, 256, bs);
			int vsize = v.size();
			DensityBuffer te = DensityBuffer.createUnpooled(vsize), hu = DensityBuffer.createUnpooled(vsize), co = DensityBuffer.createUnpooled(vsize),
				er = DensityBuffer.createUnpooled(vsize), de = DensityBuffer.createUnpooled(vsize), we = DensityBuffer.createUnpooled(vsize);
			w.climate.temperature().sampleVolume(te, v);
			w.climate.humidity().sampleVolume(hu, v);
			w.climate.continentalness().sampleVolume(co, v);
			w.climate.erosion().sampleVolume(er, v);
			w.climate.depth().sampleVolume(de, v);
			w.climate.weirdness().sampleVolume(we, v);
			for (int bz = 0; bz < n; bz++) {
				for (int bx = 0; bx < n; bx++) {
					int i0 = v.indexUnchecked(bx, 0, bz), i1 = v.indexUnchecked(bx, 1, bz);
					// the cell's surface (finer levels: the first of the 4-block group)
					int cx = bx * bs / c, cz = bz * bs / c;
					int y = t.height[cz * size + cx];
					float f = y / 256.0F;
					float depth = de.get(i0) + (de.get(i1) - de.get(i0)) * f;
					Holder<Biome> b = this.multi.getNoiseBiome(Climate.target(te.get(i0), hu.get(i0), co.get(i0), er.get(i0), depth, we.get(i0)));
					int per = bs / c;
					for (int dz = 0; dz < per; dz++) for (int dx = 0; dx < per; dx++) t.biome[(cz + dz) * size + cx + dx] = b;
				}
			}
		} else {
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					int bx = x0 + x * c + c / 2, bz = z0 + z * c + c / 2, y = t.height[z * size + x];
					t.biome[z * size + x] = w.resolver.getNoiseBiome(bx >> 2, y >> 2, bz >> 2);
				}
			}
		}
	}

	/** Top and side colors from the material rules, snow and ice, water over the sea floor, and the canopy. */
	private void surfaceMaterials(LodTile t, Worker w) {
		int c = t.cell(), x0 = t.minX(), z0 = t.minZ(), size = t.size;
		Holder<Biome>[] biomes = t.biome;
		Function<BlockPos, Holder<Biome>> biomeAt = pos -> {
			int x = Math.clamp((pos.getX() - x0) / c, 0, size - 1), z = Math.clamp((pos.getZ() - z0) / c, 0, size - 1);
			return biomes[z * size + x];
		};
		// The context's own volume only serves its prefilled lookups (ore veins, deep down, never at a surface) and the preliminary
		// surface (set per column below): one block. A strided volume here makes interpolated functions expand it to every block.
		DensityVolume expected = new DensityVolume(1, 1, 1, x0, 0, z0);
		MaterialRuleContext ctx = LodMaterialContextAccess.create(this.random.surfaceSystem(), this.random, expected, w.samplers, biomeAt, this.context, null);
		LodMaterialContextAccess access = (LodMaterialContextAccess) (Object) ctx;
		RuleEvaluator rule = this.settings.materialRule().value().compile(ctx);
		BlockState stone = this.settings.defaultBlock();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		short[] h = t.height;
		short[] ground = t.ground;
		System.arraycopy(h, 0, ground, 0, t.cells());
		int last = size - 1;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int i = z * size + x;
				int bx = x0 + x * c + c / 2, bz = z0 + z * c + c / 2;
				int top = ground[i] - 1;
				// water: what the game's aquifers put over the ground (levels 0-1: the sea, a local level, or nothing in cave
				// mouths and pits); coarser levels: the sea over anything under its level
				boolean wet = t.waterKnown ? t.water[i] != LodTile.DRY && t.water[i] > ground[i] : ground[i] < this.seaLevel;
				int waterTop = t.waterKnown && wet ? t.water[i] : this.seaLevel;
				// the game's gradient is over two blocks (x - 1 to x + 1); a cell is c blocks, so scale to that baseline
				int gx = ground[z * size + Math.min(x + 1, last)] - ground[z * size + Math.max(x - 1, 0)];
				int gz = ground[Math.min(z + 1, last) * size + x] - ground[Math.max(z - 1, 0) * size + x];
				if (c > 1) {
					gx /= c;
					gz /= c;
				}
				access.mcopt$updateXZ(bx, bz, gx, gz);
				// what getMinSurfaceLevel would compute from the preliminary surface (an estimate of this very surface)
				access.mcopt$setMinSurfaceLevel(top + ctx.surfaceDepth() - 8);
				access.mcopt$setLastMinSurfaceLevelUpdate(access.mcopt$lastUpdateXZ());
				int waterHeight = wet ? waterTop : Integer.MIN_VALUE;
				access.mcopt$updateY(1, 64, waterHeight, top);
				BlockState surface = rule.tryApply(bx, top, bz);
				if (surface == null) surface = stone;
				t.state[i] = surface;
				// the block under the top block (a wall's second block down shows it)
				access.mcopt$updateY(2, 64, waterHeight, top - 1);
				BlockState below = rule.tryApply(bx, top - 1, bz);
				if (below == null) below = stone;
				Biome b = biomes[i].value();
				int topColor = LodColors.top(surface, b, bx, bz);
				int belowColor = LodColors.top(below, b, bx, bz);
				int sideColor = c == 1 ? LodColors.side(surface, b, bx, bz) : LodColors.mix(LodColors.side(surface, b, bx, bz), belowColor, 0.6F);
				t.below[i] = belowColor;
				t.fringe[i] = LodColors.fringed(surface);
				BlockState texTop = surface, texSide = surface;
				if (wet) {
					int depth = waterTop - ground[i];
					pos.set(bx, waterTop, bz);
					if (b.coldEnoughToSnow(pos, this.seaLevel)) {
						topColor = LodColors.top(this.ice, b, bx, bz);
						texTop = this.ice;
					} else {
						texTop = null;
						int waterColor = LodColors.top(this.water, b, bx, bz);
						float f = Math.min(1.0F, 0.55F + depth / 24.0F);
						topColor = LodColors.mix(topColor, waterColor, f);
					}
					t.water[i] = (short) waterTop;
				} else {
					t.water[i] = LodTile.DRY;
					pos.set(bx, ground[i], bz);
					if (b.coldEnoughToSnow(pos, this.seaLevel) && surface.isSolidRender()) {
						topColor = LodColors.top(this.snow, b, bx, bz);
						texTop = this.snow;
						// a snow layer on a grass block makes it snowy: its side shows the snowy side texture
						// (its own white band along the top: no fringe), and its color is that texture's
						if (surface.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
							texSide = surface.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, true);
							int snowySide = LodColors.side(texSide, b, bx, bz);
							sideColor = c == 1 ? snowySide : LodColors.mix(snowySide, belowColor, 0.6F);
							t.fringe[i] = false;
						}
					}
					if (LodConfig.TREES && t.impostorTrees) {
						int canopy = LodTrees.canopy(biomes[i], surface, bx, bz, c);
						if (canopy > 0) {
							h[i] = (short) Math.min(this.maxY, h[i] + canopy);
							int raw = LodTrees.leaves(biomes[i], b, bx, bz), leaves = raw;
							float cover = LodTrees.cover(biomes[i], c);
							// snow lies on the canopy where it falls at the canopy's height (a dusting: needles show through)
							pos.set(bx, h[i], bz);
							if (b.coldEnoughToSnow(pos, this.seaLevel)) leaves = LodColors.mix(leaves, LodColors.top(this.snow, b, bx, bz), 0.45F);
							topColor = c >= 16 ? LodColors.mix(topColor, leaves, cover) : leaves;
							// a stand's side is foliage in the shade of the trunks, under the snow
							int shade = LodColors.multiply(raw, 0x8C8C8C);
							sideColor = LodColors.mix(sideColor, shade, c >= 16 ? cover : 0.9F);
						}
					}
				}
				t.top[i] = topColor;
				t.side[i] = sideColor;
				if (c == 1 && LodConfig.TEXTURES) t.tex[i] = LodPalette.word(texTop == null ? 0 : LodPalette.id(texTop), LodPalette.id(texSide), LodPalette.id(below));
			}
		}
	}
}
