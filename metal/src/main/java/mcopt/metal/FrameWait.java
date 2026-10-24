package mcopt.metal;

/** The frame limiter's wait, on a timer macOS can't defer; see mc_sleep_precise and FramerateLimiterMixin. */
public final class FrameWait {
	/** -Dmcopt.preciseLimiter=false keeps vanilla's park-then-spin wait. */
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.preciseLimiter", "true"));

	private FrameWait() {
	}

	public static void sleep(long nanos) {
		Native.sleepPrecise(nanos);
	}

	public static void sleepUntil(long deadline) {
		for (long remaining; (remaining = deadline - System.nanoTime()) > 0; ) Native.sleepPrecise(remaining);
	}
}
