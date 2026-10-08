package mcopt.metal.chunkio;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * -Dmcopt.chunk.saveSkip=true|probe (C2): a chunk, entity or POI region write whose bytes equal what the region file already
 * holds, apart from the root LastUpdate stamp, is skipped ({@code true}); {@code probe} compares and counts but always writes.
 * Its own package and mixin config (mcopt-metal-chunkio.mixins.json, ChunkIoMixinPlugin) reference vanilla classes only, so a
 * build without Sodium can carry it. The counters are the chunk switches' counters (ChunkOpt reports them too).
 */
public final class ChunkIo {
	private ChunkIo() { }

	public static final String SAVE_SKIP = prop("saveSkip");
	public static final boolean SAVE_SKIP_ON = "true".equals(SAVE_SKIP);
	public static final boolean SAVE_PROBE = "probe".equals(SAVE_SKIP);
	/** Count what saveSkip does: -Dmcopt.chunk.stats=true, the probe, or any chunk switch in a verify / A-B mode. */
	public static final boolean STATS = Boolean.getBoolean("mcopt.chunk.stats") || SAVE_PROBE || anyCheckMode();

	private static String prop(String name) {
		String v = System.getProperty("mcopt.chunk." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean anyCheckMode() {
		for (String k : System.getProperties().stringPropertyNames()) {
			if (!k.startsWith("mcopt.chunk.")) continue;
			String v = System.getProperty(k, "").trim().toLowerCase(Locale.ROOT);
			if (v.equals("verify") || v.equals("ab") || k.equals("mcopt.chunk.clonesVerify") && v.equals("true")) return true;
		}
		return false;
	}

	/** Whether the mixin named (simple class name) applies: RegionFileStorageMixin belongs to saveSkip. */
	public static boolean mixinEnabled(String simpleName) {
		return ("RegionFileStorageMixin".equals(simpleName) || "RegionFileStorageAccess".equals(simpleName)) && (SAVE_SKIP_ON || SAVE_PROBE);
	}

	private static final Map<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

	public static void count(String key, long n) {
		COUNTERS.computeIfAbsent(key, k -> new LongAdder()).add(n);
	}

	public static void count(String key) {
		count(key, 1);
	}

	/** Every counter so far, sorted by name. */
	public static Map<String, Long> counters() {
		Map<String, Long> out = new LinkedHashMap<>();
		COUNTERS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.put(e.getKey(), e.getValue().sum()));
		return out;
	}
}
