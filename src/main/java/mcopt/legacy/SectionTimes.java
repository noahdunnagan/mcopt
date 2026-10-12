package mcopt.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dev only: wall time per profiler section on the client thread while the fps bench measures (-Dmcopt.fps.sections). */
public final class SectionTimes {
	public static volatile boolean on;
	private static Thread thread;
	private static final String[] paths = new String[64];
	private static final long[] starts = new long[64];
	private static int depth;
	private static final Map<String, long[]> totals = new HashMap<>();

	private SectionTimes() {
	}

	public static void start() {
		thread = Thread.currentThread();
		depth = 0;
		totals.clear();
		on = true;
	}

	public static void push(String name) {
		if (Thread.currentThread() != thread || depth == paths.length) return;
		paths[depth] = depth == 0 ? name : paths[depth - 1] + "." + name;
		starts[depth++] = System.nanoTime();
	}

	public static void pop() {
		if (Thread.currentThread() != thread || depth == 0) return;
		depth--;
		long[] t = totals.computeIfAbsent(paths[depth], k -> new long[1]);
		t[0] += System.nanoTime() - starts[depth];
	}

	/** Per frame; the client tick's sections also per tick, since a tick runs in only some frames. */
	public static void print(int frames, int ticks) {
		on = false;
		List<Map.Entry<String, long[]>> list = new ArrayList<>(totals.entrySet());
		list.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, long[]> e : list) {
			double ms = e.getValue()[0] / 1e6 / frames;
			if (ms >= 0.05) System.out.printf("[mcopt] section %-60s %6.2f ms%n", e.getKey(), ms);
			double perTick = e.getValue()[0] / 1e6 / Math.max(1, ticks);
			if (e.getKey().startsWith("root.tick") && perTick >= 0.02) {
				System.out.printf("[mcopt] per tick %-59s %6.2f ms%n", e.getKey(), perTick);
			}
		}
	}
}
