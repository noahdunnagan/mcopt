package mcopt.metal;

public final class FrameDeadlineTest {
	private static int checks;

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	public static void main(String[] args) {
		for (int fps : new int[] {30, 60, 120, 144, 165, 180, 240, 260}) {
			FrameDeadline deadlines = new FrameDeadline();
			long period = 1_000_000_000L / fps;
			long beginning = 1_000_000_000L, last = beginning;
			for (int frame = 0; frame < 10_000; frame++) {
				long now = last + period / 4;
				long deadline = deadlines.next(now, last, fps);
				require(deadline == beginning + (frame + 1L) * period, "late timer wake accumulated deadline drift");
				last = deadline + 30_000 + frame % 17 * 1000;
			}
			long stalled = last + 50_000_000;
			require(deadlines.next(stalled, last, fps) <= stalled, "limiter added a delay after a 50 ms hitch");
			long recovered = deadlines.next(stalled + period / 3, stalled, fps);
			require(recovered > stalled && recovered <= stalled + period, "limiter did not recover its cadence after a hitch");
			long changed = deadlines.next(recovered + 1000, recovered, 90);
			require(changed == recovered + 1_000_000_000L / 90, "changing the FPS cap retained the old deadline");
			require(deadlines.next(changed, changed, 0) == changed, "uncapped mode failed");
			require(deadlines.next(changed, changed, -1) == changed, "invalid cap failed");
			long resumed = changed + 10_000_000_000L;
			require(deadlines.next(resumed, changed, fps) == resumed, "resuming after inactivity stalled");
		}
		System.out.println("PASS " + checks + " frame-deadline checks");
	}
}
