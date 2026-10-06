package mcopt.metal.lod;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A square of far-terrain columns being generated: size x size cells of 1 << level blocks from block (x0, z0). A clipmap
 * tile is 64 x 64 (tx, tz index tiles of 64 cells); the tree runs use single chunks (16 x 16 at level 0). A column is its
 * surface (the y of its top face), the water surface above it if any, the color seen from above and the color of its
 * sides; while generating, also the block at its surface and its biome (trees need both).
 */
final class LodTile {
	static final int SIZE = 64, CELLS = SIZE * SIZE;
	/** No water over the column. */
	static final short DRY = Short.MIN_VALUE;
	/** Where this tile's data came from: the world generator's noise, or the game's real chunks (some or all). */
	static final int SOURCE_NOISE = 1, SOURCE_CHUNKS = 2;

	final int level, x0, z0, size;
	/** World y of each column's top face (one above its highest block). */
	final short[] height;
	/** World y of the water surface's top face, or DRY. */
	final short[] water;
	/** RGB seen from above (water's own color, already mixed with the floor by depth, where wet). */
	final int[] top;
	/**
	 * RGB of the column's sides: at level 0 the top block's own side, deeper walls take `below`; coarser levels a mix of the
	 * two (a cell's walls are mostly below its top block).
	 */
	final int[] side;
	/** RGB of what is under the top block (the walls of a cliff below its top block), and whether the top block's side has a
	 * band of its top's color along its upper edge (grass, podzol, mycelium, and snow on grass). */
	final int[] below;
	final boolean[] fringe;
	/** Level 0: the column's top, side and under-the-top blocks as palette numbers (LodPalette.word), for the real textures. */
	final int[] tex;
	/** The block at the surface (under the water where wet) and the column's biome. */
	final BlockState[] state;
	final Holder<Biome>[] biome;
	/** The ground's own top face (the surface before trees were added; == height where no tree stands). */
	final short[] ground;
	/**
	 * Trees over the ground (LodForest): the lowest and highest tree block y in the column (canopyHi < canopyLo: none), the
	 * block at the top, and whether the tree stands on the ground there (a trunk or low growth) or floats over air (a crown).
	 */
	final short[] canopyLo, canopyHi;
	final BlockState[] canopyState;
	final boolean[] standing;
	/** A standing tree's trunk block (its log) where the column holds one, else null. */
	final BlockState[] trunk;
	/** The crown's colors (top, side) where a tree floats over the ground (filled by LodNoise.dressTrees). */
	final int[] crownTop, crownSide;
	/** The crown's runs of leaves (a spruce's tiers) as LodTile.runs encodes them; 0: one run from canopyLo to canopyHi. */
	final int[] crownRuns;
	/**
	 * A plant standing on the column (level 0: grass, ferns, flowers, from the game's own features or the real chunks): its
	 * lowest and top blocks (top null: one block), its height in blocks, its color (the lowest block's texture under its tint).
	 */
	final BlockState[] plantLower, plantUpper;
	final byte[] plantBlocks;
	final int[] plantColor;
	/** The water is known (the game's aquifers asked, levels 0-1): `water` holds it; else the sea fills what lies under its level. */
	boolean waterKnown;
	/** Impostor canopies from the biome (LodTrees) where no exact trees are planted. */
	boolean impostorTrees = true;
	int source;

	/** A clipmap tile: tile (tx, tz) of level `level`. */
	LodTile(int level, int tx, int tz) {
		this(level, tx * (SIZE << level), tz * (SIZE << level), SIZE);
	}

	@SuppressWarnings("unchecked")
	LodTile(int level, int x0, int z0, int size) {
		this.level = level;
		this.x0 = x0;
		this.z0 = z0;
		this.size = size;
		int n = size * size;
		this.height = new short[n];
		this.water = new short[n];
		this.top = new int[n];
		this.side = new int[n];
		this.below = new int[n];
		this.fringe = new boolean[n];
		this.tex = new int[n];
		this.state = new BlockState[n];
		this.biome = new Holder[n];
		this.ground = new short[n];
		this.canopyLo = new short[n];
		this.canopyHi = new short[n];
		java.util.Arrays.fill(this.canopyHi, Short.MIN_VALUE);
		this.canopyState = new BlockState[n];
		this.standing = new boolean[n];
		this.trunk = new BlockState[n];
		this.crownTop = new int[n];
		this.crownSide = new int[n];
		this.crownRuns = new int[n];
		this.plantLower = new BlockState[n];
		this.plantUpper = new BlockState[n];
		this.plantBlocks = new byte[n];
		this.plantColor = new int[n];
	}

	/**
	 * A crown's runs of leaves as a word: bits of `occupied` are blocks up from the crown's lowest one (bit 0 is set); at most
	 * 4 runs, each a gap (3 bits, at most 7) then a length (5 bits, at most 31), packed a byte each from the low end. More runs,
	 * or wider gaps, are closed by filling the narrowest gaps (the crown looks a little fuller).
	 */
	static int runs(long occupied) {
		if (occupied == 0) return 0;
		int[] start = new int[64], len = new int[64];
		int n = 0;
		for (int b = 0; b < 64; ) {
			if ((occupied >>> b & 1) == 0) {
				b++;
				continue;
			}
			int e = b;
			while (e < 64 && (occupied >>> e & 1) != 0) e++;
			start[n] = b;
			len[n] = e - b;
			n++;
			b = e;
		}
		while (n > 1) {
			// too many runs: close the narrowest gap; a gap too wide to encode: close it
			int merge = -1;
			if (n > 4) {
				int best = Integer.MAX_VALUE;
				for (int k = 1; k < n; k++) {
					int gap = start[k] - (start[k - 1] + len[k - 1]);
					if (gap < best) {
						best = gap;
						merge = k;
					}
				}
			} else {
				for (int k = 1; k < n && merge < 0; k++) if (start[k] - (start[k - 1] + len[k - 1]) > 7) merge = k;
			}
			if (merge < 0) break;
			len[merge - 1] = start[merge] + len[merge] - start[merge - 1];
			System.arraycopy(start, merge + 1, start, merge, n - merge - 1);
			System.arraycopy(len, merge + 1, len, merge, n - merge - 1);
			n--;
		}
		for (int k = 0; k < n; k++) if (len[k] > 31) return 0;
		if (n == 1) return 0;
		int word = 0, at = 0;
		for (int k = 0; k < n; k++) {
			int gap = start[k] - at, l = Math.min(31, len[k]);
			word |= (gap & 7 | l << 3) << (8 * k);
			at = start[k] + l;
		}
		return word;
	}

	static long key(int level, int tx, int tz) {
		return (long) level << 58 | (long) (tx & 0x1FFFFFFF) << 29 | (tz & 0x1FFFFFFF);
	}

	static int levelOf(long key) {
		return (int) (key >>> 58);
	}

	static int txOf(long key) {
		return (int) (key << 6 >> 35);
	}

	static int tzOf(long key) {
		return (int) (key << 35 >> 35);
	}

	int cells() {
		return this.size * this.size;
	}

	int cell() {
		return 1 << this.level;
	}

	int minX() {
		return this.x0;
	}

	int minZ() {
		return this.z0;
	}

	/** Effective surface: the water's when wet. */
	int surface(int i) {
		return this.water[i] != DRY ? Math.max(this.water[i], this.height[i]) : this.height[i];
	}
}
