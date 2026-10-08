package mcopt.metal.lod;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Trees as impostor volumes on terrain that was never generated (no features run there): per biome, how much of the ground
 * a canopy covers seen from above, how high it stands and which leaves it has. Fine cells get a canopy or not by a hash of
 * their 4-block patch (canopies are blobs, not single blocks); cells of 16 blocks and up get the average, a raised layer
 * tinted by the cover. Real chunks, once seen, replace all of this with the trees the game placed.
 */
final class LodTrees {
	/** cover 0..1, canopy height above the ground (blocks), leaves block. */
	record Profile(float cover, int height, BlockState leaves) {
	}

	private static final Profile NONE = new Profile(0, 0, Blocks.AIR.defaultBlockState());
	private static final ConcurrentHashMap<Holder<Biome>, Profile> PROFILES = new ConcurrentHashMap<>();

	private LodTrees() {
	}

	static Profile profile(Holder<Biome> biome) {
		return PROFILES.computeIfAbsent(biome, LodTrees::derive);
	}

	private static Profile derive(Holder<Biome> biome) {
		String name = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		BlockState oak = Blocks.OAK_LEAVES.defaultBlockState(), spruce = Blocks.SPRUCE_LEAVES.defaultBlockState(), birch = Blocks.BIRCH_LEAVES.defaultBlockState();
		return switch (name) {
			case "forest", "flower_forest" -> new Profile(0.72F, 6, oak);
			case "birch_forest" -> new Profile(0.7F, 7, birch);
			case "old_growth_birch_forest" -> new Profile(0.75F, 11, birch);
			case "dark_forest" -> new Profile(0.95F, 8, Blocks.DARK_OAK_LEAVES.defaultBlockState());
			case "pale_garden" -> new Profile(0.9F, 8, Blocks.PALE_OAK_LEAVES.defaultBlockState());
			case "taiga", "snowy_taiga" -> new Profile(0.55F, 9, spruce);
			case "old_growth_pine_taiga", "old_growth_spruce_taiga" -> new Profile(0.65F, 16, spruce);
			case "grove" -> new Profile(0.35F, 9, spruce);
			case "windswept_forest" -> new Profile(0.45F, 8, spruce);
			case "jungle" -> new Profile(0.88F, 14, Blocks.JUNGLE_LEAVES.defaultBlockState());
			case "bamboo_jungle" -> new Profile(0.6F, 10, Blocks.JUNGLE_LEAVES.defaultBlockState());
			case "sparse_jungle" -> new Profile(0.35F, 9, Blocks.JUNGLE_LEAVES.defaultBlockState());
			case "savanna", "savanna_plateau" -> new Profile(0.12F, 6, Blocks.ACACIA_LEAVES.defaultBlockState());
			case "windswept_savanna" -> new Profile(0.1F, 6, Blocks.ACACIA_LEAVES.defaultBlockState());
			case "swamp" -> new Profile(0.35F, 6, oak);
			case "mangrove_swamp" -> new Profile(0.8F, 9, Blocks.MANGROVE_LEAVES.defaultBlockState());
			case "cherry_grove" -> new Profile(0.45F, 7, Blocks.CHERRY_LEAVES.defaultBlockState());
			case "wooded_badlands" -> new Profile(0.2F, 5, oak);
			case "plains", "sunflower_plains", "meadow", "windswept_hills" -> new Profile(0.03F, 6, oak);
			default -> NONE;
		};
	}

	/**
	 * Canopy height to add over a column whose top block is `ground` (0: no tree here). Forest density is a smooth
	 * low-frequency noise thresholded by the biome's cover, ramped over a few blocks: woods come in stands with soft edges,
	 * never as lone pillars; inside a stand the height varies a little per 4-block patch (a tree or two). Cells of 16 blocks
	 * and up get the stand's expected height.
	 */
	static int canopy(Holder<Biome> biome, BlockState ground, int x, int z, int cell) {
		Profile p = profile(biome);
		if (p.cover <= 0) return 0;
		if (!ground.is(Blocks.GRASS_BLOCK) && !ground.is(Blocks.PODZOL) && !ground.is(Blocks.DIRT) && !ground.is(Blocks.COARSE_DIRT)
			&& !ground.is(Blocks.MUD) && !ground.is(Blocks.SNOW_BLOCK) && !ground.is(Blocks.MYCELIUM) && !ground.is(Blocks.ROOTED_DIRT)
			&& !ground.is(Blocks.MOSS_BLOCK) && !ground.is(Blocks.PALE_MOSS_BLOCK)) return 0;
		if (cell >= 16) return Math.round(p.cover * p.height * 0.85F);
		float n = valueNoise(x / 22.0F, z / 22.0F);
		float density = Math.clamp((p.cover - n) * 3.0F + 0.5F, 0.0F, 1.0F);
		if (density <= 0) return 0;
		if (cell <= 2) {
			// fine cells: separate crowns, 3 x 3 blocks on a jittered 5-block grid, a stand's density of them (gaps between trees)
			int px = Math.floorDiv(x, 5), pz = Math.floorDiv(z, 5);
			int hash = mix(px * 73428767 ^ pz * 912931);
			if ((hash & 0xFFFF) / 65536.0F >= density * 0.85F + 0.1F) return 0;
			int cx = px * 5 + 1 + ((hash >>> 16) & 1) + ((hash >>> 17) & 1), cz = pz * 5 + 1 + ((hash >>> 18) & 1) + ((hash >>> 19) & 1);
			int d = Math.max(Math.abs(x - cx), Math.abs(z - cz));
			if (d > 1) return 0;
			float vary = 0.8F + 0.2F * (((hash >>> 20) & 0xFF) / 255.0F);
			return Math.max(2, Math.round(p.height * vary * (d == 0 ? 1.0F : 0.7F)));
		}
		int hash = mix(Math.floorDiv(x, 4) * 73428767 ^ Math.floorDiv(z, 4) * 912931);
		float vary = 0.8F + 0.2F * ((hash & 0xFF) / 255.0F);
		int h = Math.round(p.height * density * vary);
		return h < 2 ? 0 : h;
	}

	/** Bilinear value noise on a unit lattice, 0..1 (smoothstep weights). */
	static float valueNoise(float x, float z) {
		int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
		float fx = x - x0, fz = z - z0;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		float a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
		float ab = a + (b - a) * fx, cd = c + (d - c) * fx;
		return ab + (cd - ab) * fz;
	}

	private static float lattice(int x, int z) {
		return (mix(x * 0x27d4eb2d ^ z * 0x165667b1 ^ 0x5bd1e995) & 0xFFFF) / 65535.0F;
	}

	static int leaves(Holder<Biome> biome, Biome b, int x, int z) {
		Profile p = profile(biome);
		return LodColors.top(p.leaves, b, x, z);
	}

	static float cover(Holder<Biome> biome, int cell) {
		return profile(biome).cover;
	}

	private static int mix(int h) {
		h ^= h >>> 16;
		h *= 0x7feb352d;
		h ^= h >>> 15;
		h *= 0x846ca68b;
		h ^= h >>> 16;
		return h;
	}
}
