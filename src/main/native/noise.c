// World-gen noise for Minecraft 1.8.9, bit-exact with net.minecraft.world.gen.class_1779.method_6577
// (NoiseGeneratorImproved.populateNoiseArray in MCP names). Every double operation is the game's own, in the
// game's order: IEEE round to nearest, no fused multiply-add (fp contract off below and -ffp-contract=off in the
// build), no fast-math. Java's (int) cast of a double is reproduced exactly, out-of-range and NaN values included.
//
// The 3D path keeps the game's y-cell cache as it is: the four x/z-lerped corners are only recomputed when the
// y lattice cell changes (or at the first y of a column), using the y fraction of the first sample in that cell.
// That quirk shapes vanilla terrain, so it stays.
#include <jni.h>
#include <stdint.h>

#pragma clang fp contract(off)

// class_1779 gradient tables (field_7552/7553/7554); the 2D tables (field_7555/7556) hold the same x and z values.
static const double GX[16] = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0};
static const double GY[16] = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1};
static const double GZ[16] = {0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1};

// Java's d2i: NaN is 0, out-of-range values saturate.
static inline int32_t d2i(double v) {
	if (v != v) return 0;
	if (v >= 2147483647.0) return INT32_MAX;
	if (v <= -2147483648.0) return INT32_MIN;
	return (int32_t) v;
}

// (int) v, then one less when v is below it: the game's floor. The decrement wraps like Java's iinc.
static inline int32_t floorj(double v) {
	int32_t i = d2i(v);
	if (v < (double) i) i = (int32_t) ((uint32_t) i - 1u);
	return i;
}

static inline double fade(double t) {
	return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static inline double lerp(double t, double a, double b) {
	return a + t * (b - a);
}

static inline double grad2(int h, double x, double z) {
	h &= 15;
	return GX[h] * x + GZ[h] * z;
}

static inline double grad3(int h, double x, double y, double z) {
	h &= 15;
	return GX[h] * x + GY[h] * y + GZ[h] * z;
}

static void populate(double *arr, const int32_t *p, double xo, double yo, double zo, int xs, int ys, int zs,
	double xsc, double ysc, double zsc, double scale, double cx, double cy, double cz) {
	if (ys == 1) {
		int idx = 0;
		double inv = 1.0 / scale;
		for (int i = 0; i < xs; i++) {
			double x = xo + (double) i * xsc + cx;
			int32_t xi = floorj(x);
			int X = xi & 255;
			x = x - (double) xi;
			double u = fade(x);
			for (int k = 0; k < zs; k++) {
				double z = zo + (double) k * zsc + cz;
				int32_t zi = floorj(z);
				int Z = zi & 255;
				z = z - (double) zi;
				double w = fade(z);
				int a = p[X] + 0;
				int aa = p[a] + Z;
				int b = p[X + 1] + 0;
				int ba = p[b] + Z;
				double l1 = lerp(u, grad2(p[aa], x, z), grad3(p[ba], x - 1.0, 0.0, z));
				double l2 = lerp(u, grad3(p[aa + 1], x, 0.0, z - 1.0), grad3(p[ba + 1], x - 1.0, 0.0, z - 1.0));
				double v = lerp(w, l1, l2);
				arr[idx] = arr[idx] + v * inv;
				idx++;
			}
		}
		return;
	}
	// Every column of one call sees the same y samples, so the y lattice cell, fraction, fade and the points where
	// the game refreshes its cached corners are worked out once here, not once per column. Likewise the z values are
	// the same for every x. The arithmetic per value is unchanged, so the results are too.
	double ybuf[2 * 64], zbuf[2 * 64];
	int ycell[64], zcell[64];
	unsigned char refresh[64];
	if (ys > 64 || zs > 64) return;  // NoiseMixin leaves these to the game (vanilla never asks for them)
	int last = -1;
	for (int j = 0; j < ys; j++) {
		double y = yo + (double) j * ysc + cy;
		int32_t yi = floorj(y);
		int Y = yi & 255;
		y = y - (double) yi;
		ybuf[2 * j] = y;
		ybuf[2 * j + 1] = fade(y);
		ycell[j] = Y;
		refresh[j] = j == 0 || Y != last;
		last = Y;
	}
	for (int k = 0; k < zs; k++) {
		double z = zo + (double) k * zsc + cz;
		int32_t zi = floorj(z);
		zcell[k] = zi & 255;
		z = z - (double) zi;
		zbuf[2 * k] = z;
		zbuf[2 * k + 1] = fade(z);
	}
	int idx = 0;
	double inv = 1.0 / scale;
	double c1 = 0.0, c2 = 0.0, c3 = 0.0, c4 = 0.0;
	for (int i = 0; i < xs; i++) {
		double x = xo + (double) i * xsc + cx;
		int32_t xi = floorj(x);
		int X = xi & 255;
		x = x - (double) xi;
		double u = fade(x);
		int pa = p[X], pb = p[X + 1];
		for (int k = 0; k < zs; k++) {
			int Z = zcell[k];
			double z = zbuf[2 * k], w = zbuf[2 * k + 1];
			for (int j = 0; j < ys; j++) {
				double y = ybuf[2 * j], v = ybuf[2 * j + 1];
				if (refresh[j]) {
					int Y = ycell[j];
					int a = pa + Y;
					int aa = p[a] + Z;
					int ab = p[a + 1] + Z;
					int b = pb + Y;
					int ba = p[b] + Z;
					int bb = p[b + 1] + Z;
					c1 = lerp(u, grad3(p[aa], x, y, z), grad3(p[ba], x - 1.0, y, z));
					c2 = lerp(u, grad3(p[ab], x, y - 1.0, z), grad3(p[bb], x - 1.0, y - 1.0, z));
					c3 = lerp(u, grad3(p[aa + 1], x, y, z - 1.0), grad3(p[ba + 1], x - 1.0, y, z - 1.0));
					c4 = lerp(u, grad3(p[ab + 1], x, y - 1.0, z - 1.0), grad3(p[bb + 1], x - 1.0, y - 1.0, z - 1.0));
				}
				double l1 = lerp(v, c1, c2);
				double l2 = lerp(v, c3, c4);
				double r = lerp(w, l1, l2);
				arr[idx] = arr[idx] + r * inv;
				idx++;
			}
		}
	}
}

JNIEXPORT void JNICALL Java_mcopt_legacy_NativeNoise_populate(JNIEnv *env, jclass cls, jdoubleArray out, jintArray perm,
	jdouble xo, jdouble yo, jdouble zo, jint xs, jint ys, jint zs, jdouble xsc, jdouble ysc, jdouble zsc, jdouble scale,
	jdouble cx, jdouble cy, jdouble cz) {
	(void) cls;
	double *arr = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
	if (arr == NULL) return;
	int32_t *p = (*env)->GetPrimitiveArrayCritical(env, perm, NULL);
	if (p == NULL) {
		(*env)->ReleasePrimitiveArrayCritical(env, out, arr, JNI_ABORT);
		return;
	}
	populate(arr, p, xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, scale, cx, cy, cz);
	(*env)->ReleasePrimitiveArrayCritical(env, perm, p, JNI_ABORT);
	(*env)->ReleasePrimitiveArrayCritical(env, out, arr, 0);
}
