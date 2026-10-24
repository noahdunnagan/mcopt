#include "framepace.h"
#include <stdio.h>
#include <stdlib.h>

static int checks;

static void require(int condition, const char *message) {
	if (!condition) {
		fprintf(stderr, "%s\n", message);
		exit(1);
	}
	checks++;
}

static void runRate(double hz, double rate) {
	FramePaceState state = {0};
	double period = 1 / hz, now = 10, target = 0;
	int frames = (int)(rate * 5), presented = 0;
	for (int i = 0; i < frames; i++) {
		now += (1 + sin(i * 0.7) * 0.08) / rate;
		double anchor = (floor(now / period) + 1) * period;
		int keep = framePaceChoose(&state, now, anchor, period, 0.003, &target);
		presented += keep;
		if (rate < hz * 1.8) require(keep, "near-refresh frame was unnecessarily discarded");
		if (keep) require(target >= now && isfinite(target), "invalid present deadline");
	}
	if (rate > hz * 2.5) {
		require(presented >= hz * 4.5 && presented <= hz * 5.5, "high-FPS present rate diverged from display");
	}
}

int main(void) {
	double displays[] = {60, 120, 144, 165, 180, 240};
	for (unsigned i = 0; i < sizeof displays / sizeof displays[0]; i++) {
		runRate(displays[i], displays[i] * 0.9);
		runRate(displays[i], displays[i]);
		runRate(displays[i], displays[i] * 1.5);
		runRate(displays[i], displays[i] * 3);
		runRate(displays[i], displays[i] * 5);
	}
	FramePaceState state = {0};
	double target;
	require(framePaceChoose(&state, 10, 0, 0, 0, &target), "missing display clock froze presentation");
	require(target == 0, "missing clock retained stale deadline");
	require(framePaceChoose(&state, 10, 9, 1.0 / 180, 0, &target), "sleeping display froze presentation");
	require(framePaceChoose(&state, 10, NAN, 1.0 / 180, 0, &target), "invalid display timestamp froze presentation");
	for (int i = 0; i < 200; i++) {
		double period = i < 100 ? 1.0 / 120 : 1.0 / 180;
		double now = 20 + i * 0.006;
		double anchor = (floor(now / period) + 1) * period;
		require(framePaceChoose(&state, now, anchor, period, INFINITY, &target), "monitor transition dropped a completed frame");
	}
	framePaceReset(&state);
	for (int i = 0; i < 1000; i++) {
		double now = 30 + i * 0.001 + (i >= 500 ? 0.05 : 0);
		double period = 1.0 / 180, anchor = (floor(now / period) + 1) * period;
		int keep = framePaceChoose(&state, now, anchor, period, 0.002, &target);
		if (i == 500) require(keep, "late frame was discarded after a 50 ms hitch");
	}
	printf("PASS %d frame-pacing checks\n", checks);
	return 0;
}
