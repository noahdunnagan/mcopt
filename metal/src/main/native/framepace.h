#ifndef MESHNET_FRAMEPACE_H
#define MESHNET_FRAMEPACE_H

#include <math.h>
#include <string.h>

typedef struct {
	double lastCall, interval, period, lastTarget;
} FramePaceState;

static inline void framePaceReset(FramePaceState *state) {
	memset(state, 0, sizeof *state);
}

static inline int framePaceChoose(FramePaceState *state, double now, double anchor, double period, double lead, double *target) {
	*target = 0;
	if (!isfinite(now) || !isfinite(anchor) || !isfinite(period) || period < 0.001 || period > 0.1 ||
		anchor <= 0 || now - anchor > 0.25 || anchor - now > 2 * period) {
		framePaceReset(state);
		return 1;
	}
	if (state->period <= 0 || fabs(state->period - period) > period * 0.05 || now < state->lastCall || now - state->lastCall > 0.25) {
		framePaceReset(state);
		state->period = period;
	}
	if (state->lastCall > 0) {
		double interval = now - state->lastCall;
		state->interval = state->interval > 0 ? state->interval + (interval - state->interval) * 0.1 : interval;
	}
	state->lastCall = now;
	lead = isfinite(lead) ? fmin(fmax(lead, 0), 2 * period) : 0;
	double candidate = anchor + ceil((now + lead - anchor) / period) * period;
	if (state->interval > 0 && state->interval < period * 0.5 &&
		(candidate < state->lastTarget + period * 0.5 || now + 2 * state->interval + lead < candidate)) return 0;
	state->lastTarget = *target = candidate;
	return 1;
}

#endif
