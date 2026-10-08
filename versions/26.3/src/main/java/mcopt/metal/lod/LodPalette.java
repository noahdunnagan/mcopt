package mcopt.metal.lod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * Block states seen at the surface, numbered for the GPU (1..MAX-1; 0: none): per number, where its top and side sprites
 * are in the block atlas and their textures' average colors. Level-0 cells name their top, side and under-the-top blocks
 * by these numbers, and the composite draws them with the real textures at the mip the distance calls for, tinted so their
 * average is the cell's own color (the cell's color already holds the biome tint).
 *
 * The table lives in a shared buffer written as numbers are handed out (by whichever worker meets a block first); a
 * number is written before any cell names it, so the GPU never reads an entry that isn't there.
 */
final class LodPalette {
	static final int MAX = 1024;
	/** Floats per entry: top u0 v0 u1 v1, side u0 v0 u1 v1, top average rgb, side average rgb (0..1, sRGB), a plant's profile (4 ints). */
	static final int STRIDE = 20;
	private static final ConcurrentHashMap<BlockState, Integer> IDS = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT = new AtomicInteger(1);
	private static long table;

	private LodPalette() {
	}

	/** The shared buffer the table is written into (set once by Lod; entries assigned before it exist are written then). */
	static synchronized void bind(long address) {
		table = address;
		MemoryUtil.memSet(address, 0, (long) MAX * STRIDE * 4);
		IDS.forEach(LodPalette::write);
	}

	/** The block's number (0 when the table is full, or for air and fluids: they keep the flat color). */
	static int id(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return 0;
		Integer id = IDS.get(state);
		if (id != null) return id;
		synchronized (LodPalette.class) {
			id = IDS.get(state);
			if (id != null) return id;
			int n = NEXT.get();
			if (n >= MAX) return 0;
			NEXT.incrementAndGet();
			write(state, n);
			STATES.put(n, state);
			IDS.put(state, n);
			return n;
		}
	}

	private static void write(BlockState state, int n) {
		if (table == 0) return;
		LodColors.Look l = LodColors.look(state);
		long at = table + (long) n * STRIDE * 4;
		for (int i = 0; i < 4; i++) {
			MemoryUtil.memPutFloat(at + i * 4L, l.topUv()[i]);
			MemoryUtil.memPutFloat(at + 16 + i * 4L, l.sideUv()[i]);
		}
		putRgb(at + 32, l.top());
		putRgb(at + 48, l.side());
		for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(at + 64 + i * 4L, l.profile()[i]);
	}

	private static void putRgb(long at, int rgb) {
		MemoryUtil.memPutFloat(at, ((rgb >> 16) & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 4, ((rgb >> 8) & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 8, (rgb & 255) / 255.0F);
		MemoryUtil.memPutFloat(at + 12, 0);
	}

	private static final ConcurrentHashMap<Integer, BlockState> STATES = new ConcurrentHashMap<>();

	/** The block state of a number (null for 0 or unknown). */
	static @org.jspecify.annotations.Nullable BlockState stateOf(int id) {
		return STATES.get(id);
	}

	/** The block state's name for the disk cache ("" for 0). */
	static String nameOf(int id) {
		BlockState s = STATES.get(id);
		return s == null ? "" : net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).result()
			.map(Object::toString).orElse("");
	}

	/** A cached block state name back to this session's number (0 when unknown). */
	static int idOf(String name) {
		if (name.isEmpty()) return 0;
		Integer cached = BY_NAME.get(name);
		if (cached != null) return cached;
		int id = net.minecraft.world.level.block.state.BlockState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(name))
			.result().map(LodPalette::id).orElse(0);
		BY_NAME.put(name, id);
		return id;
	}

	private static final ConcurrentHashMap<String, Integer> BY_NAME = new ConcurrentHashMap<>();

	/** A level-0 cell's texture word: its top's, its side's and its under-the-top block's numbers. */
	static int word(int top, int side, int below) {
		return (top & 1023) | (side & 1023) << 10 | (below & 1023) << 20;
	}
}
