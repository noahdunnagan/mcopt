package mcopt.metal;

public final class FrameDeadline {
	private long deadline;
	private long interval;

	public long next(long now, long lastFrame, int framesPerSecond) {
		if (framesPerSecond <= 0) {
			this.deadline = 0;
			this.interval = 0;
			return now;
		}
		long period = Math.max(1, 1_000_000_000L / framesPerSecond);
		if (period != this.interval || this.deadline == 0 || now < lastFrame || now - this.deadline > period * 4) {
			this.deadline = lastFrame > 0 && now >= lastFrame && now - lastFrame <= period * 4 ? lastFrame + period : now;
			this.interval = period;
		} else {
			this.deadline += period;
		}
		if (now - this.deadline >= period) this.deadline = now;
		return this.deadline;
	}
}
