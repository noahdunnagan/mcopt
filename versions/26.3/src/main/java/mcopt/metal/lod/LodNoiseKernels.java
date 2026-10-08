package mcopt.metal.lod;

import java.lang.reflect.Field;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

/**
 * The game's gradient (Perlin) noise as the far terrain's density sampling evaluates it, rewritten in plain Java over flat
 * arrays (-Dmcopt.lod.nativeNoise=java: the control for the native kernels in lodnoise.c, same algorithm, same results). A
 * stack is a NoiseStack's layers (PerlinNoise or SmearedPerlinNoise each, at its frequency and amplitude); a volume is a
 * DensityVolume (y fastest, then x, then z). Every float and double operation is the game's own, in its order, so every
 * value is bit-identical to NoiseStack.addToVolume / NoiseStack.get and the BlendedNoise lerp (checked by
 * an offline harness over millions of points).
 *
 * The y-, x- and z-dependent parts of each layer (floors, fractions, smoothsteps, the first permutation) are computed once
 * per layer and volume, not per point; the volume is walked column by column so a column's sum stays in a small array.
 */
final class LodNoiseKernels {
	private LodNoiseKernels() {
	}

	/** GradientNoise.GRADIENT's components. */
	static final float[] GX = {1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0};
	static final float[] GY = {1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1};
	static final float[] GZ = {0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1};
	private static final double HALF_ROUND_OFF = Math.nextDown(16777216.0);

	/** A NoiseStack's layers as flat arrays (perm: 256 per layer, unsigned). */
	static final class Stack {
		final int n;
		final boolean smeared;
		final int[] perm;
		final double[] ox, oy, oz, fudge, freq;
		final float[] amp;
		/** lodnoise.c's copy (0 until LodNativeNoise makes one). */
		long handle;

		Stack(int n, boolean smeared) {
			this.n = n;
			this.smeared = smeared;
			this.perm = new int[256 * n];
			this.ox = new double[n];
			this.oy = new double[n];
			this.oz = new double[n];
			this.fudge = new double[n];
			this.freq = new double[n];
			this.amp = new float[n];
		}
	}

	private static final Field LAYERS, PERMS, OX, OY, OZ, FUDGE;

	static {
		try {
			LAYERS = NoiseStack.class.getDeclaredField("layers");
			PERMS = GradientNoise.class.getDeclaredField("perms");
			OX = GradientNoise.class.getDeclaredField("offsetX");
			OY = GradientNoise.class.getDeclaredField("offsetY");
			OZ = GradientNoise.class.getDeclaredField("offsetZ");
			FUDGE = SmearedPerlinNoise.class.getDeclaredField("fudgeYScale");
			for (Field f : new Field[] {LAYERS, PERMS, OX, OY, OZ, FUDGE}) f.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/** The stack's layers, or null when they aren't all PerlinNoise or all SmearedPerlinNoise (exact classes). */
	static Stack of(NoiseStack s) {
		try {
			Object[] layers = (Object[]) LAYERS.get(s);
			int n = layers.length;
			if (n == 0) return null;
			Boolean smeared = null;
			Noise[] noises = new Noise[n];
			double[] freq = new double[n];
			float[] amp = new float[n];
			for (int i = 0; i < n; i++) {
				// NoiseStack.Layer (noise, frequency, amplitude) is a protected record: by its components
				java.lang.reflect.RecordComponent[] comps = layers[i].getClass().getRecordComponents();
				for (var comp : comps) comp.getAccessor().setAccessible(true);
				noises[i] = (Noise) comps[0].getAccessor().invoke(layers[i]);
				freq[i] = (double) comps[1].getAccessor().invoke(layers[i]);
				amp[i] = (float) comps[2].getAccessor().invoke(layers[i]);
				boolean sm = noises[i].getClass() == SmearedPerlinNoise.class;
				if (!sm && noises[i].getClass() != PerlinNoise.class) return null;
				if (smeared != null && smeared != sm) return null;
				smeared = sm;
			}
			Stack st = new Stack(n, smeared);
			for (int i = 0; i < n; i++) {
				byte[] p = (byte[]) PERMS.get(noises[i]);
				for (int k = 0; k < 256; k++) st.perm[256 * i + k] = p[k] & 255;
				st.ox[i] = OX.getDouble(noises[i]);
				st.oy[i] = OY.getDouble(noises[i]);
				st.oz[i] = OZ.getDouble(noises[i]);
				st.fudge[i] = smeared ? FUDGE.getDouble(noises[i]) : 0.0;
				st.freq[i] = freq[i];
				st.amp[i] = amp[i];
			}
			return st;
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	// ---- the game's helpers, op for op ----

	static double wrap(double v) {
		return v >= -HALF_ROUND_OFF && v < HALF_ROUND_OFF ? v : v - Math.floor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
	}

	static int floor(double v) {
		return (int) Math.floor(v);
	}

	static float smooth(float t) {
		return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
	}

	static float lerp(float t, float a, float b) {
		return a + t * (b - a);
	}

	static double fudgeY(double fudge, double y, double fyd) {
		double v = y >= 0.0 && y < fyd ? y : fyd;
		return (double) floor(v / fudge + 1.0E-7F) * fudge;
	}

	/** Mth.clamp(main + 0.5, 0, 1): the BlendedNoise's alpha. */
	static float alpha(float main) {
		float a = main + 0.5F;
		return a < 0.0F ? 0.0F : Math.min(a, 1.0F);
	}

	// ---- points ----

	private static float sampleAndLerp(int[] p, int o, int x, int y, int z, float fx, float fy, float fz, float fys) {
		int a = p[o + (x & 255)], b = p[o + (x + 1 & 255)];
		int aa = p[o + (a + y & 255)], ab = p[o + (a + y + 1 & 255)], ba = p[o + (b + y & 255)], bb = p[o + (b + y + 1 & 255)];
		float v000 = dot(p[o + (aa + z & 255)], fx, fy, fz);
		float v100 = dot(p[o + (ba + z & 255)], fx - 1.0F, fy, fz);
		float v010 = dot(p[o + (ab + z & 255)], fx, fy - 1.0F, fz);
		float v110 = dot(p[o + (bb + z & 255)], fx - 1.0F, fy - 1.0F, fz);
		float v001 = dot(p[o + (aa + z + 1 & 255)], fx, fy, fz - 1.0F);
		float v101 = dot(p[o + (ba + z + 1 & 255)], fx - 1.0F, fy, fz - 1.0F);
		float v011 = dot(p[o + (ab + z + 1 & 255)], fx, fy - 1.0F, fz - 1.0F);
		float v111 = dot(p[o + (bb + z + 1 & 255)], fx - 1.0F, fy - 1.0F, fz - 1.0F);
		float sx = smooth(fx), sy = smooth(fys), sz = smooth(fz);
		return lerp(sz, lerp(sy, lerp(sx, v000, v100), lerp(sx, v010, v110)), lerp(sy, lerp(sx, v001, v101), lerp(sx, v011, v111)));
	}

	/** GradientNoise.gradDot: x * gx + y * gy + z * gz. */
	private static float dot(int h, float x, float y, float z) {
		h &= 15;
		return GX[h] * x + GY[h] * y + GZ[h] * z;
	}

	static float layerGet(Stack s, int l, double x, double y, double z) {
		double X = wrap(x) + s.ox[l], Y = wrap(y) + s.oy[l], Z = wrap(z) + s.oz[l];
		int xi = floor(X), yi = floor(Y), zi = floor(Z);
		float fx = (float) (X - xi), fz = (float) (Z - zi);
		if (!s.smeared) {
			float fy = (float) (Y - yi);
			return sampleAndLerp(s.perm, 256 * l, xi, yi, zi, fx, fy, fz, fy);
		}
		double fyd = Y - yi;
		float fy = (float) (fyd - fudgeY(s.fudge[l], y, fyd));
		return sampleAndLerp(s.perm, 256 * l, xi, yi, zi, fx, fy, fz, (float) fyd);
	}

	/** NoiseStack.get(x, y, z). */
	static float stackPoint(Stack s, double x, double y, double z) {
		float sum = 0.0F;
		for (int l = 0; l < s.n; l++) {
			double f = s.freq[l];
			sum = sum + s.amp[l] * layerGet(s, l, x * f, y * f, z * f);
		}
		return sum;
	}

	/** The BlendedNoise's LerpFunction.sampleValue: alpha first, then only the side(s) it needs. */
	static float blendedPoint(Stack m, Stack lo, Stack hi, int x, int y, int z, double mxz, double my, double lxz, double ly) {
		float t = alpha(stackPoint(m, x * mxz, y * my, z * mxz));
		if (t == 0.0F) return stackPoint(lo, x * lxz, y * ly, z * lxz);
		if (t == 1.0F) return stackPoint(hi, x * lxz, y * ly, z * lxz);
		return lerp(t, stackPoint(lo, x * lxz, y * ly, z * lxz), stackPoint(hi, x * lxz, y * ly, z * lxz));
	}

	// ---- volumes ----

	/** Per worker: the per-layer tables and a column's sums. */
	static final class Scratch {
		int[] yf = new int[0], xf = new int[0], xa = new int[0], xb = new int[0], zf = new int[0];
		float[] fy = new float[0], fy1 = new float[0], sy = new float[0], fx = new float[0], fx1 = new float[0], sx = new float[0];
		float[] fz = new float[0], fz1 = new float[0], sz = new float[0];
		float[] acc = new float[0], alo = new float[0], ahi = new float[0];
		boolean[] needLo = new boolean[0], needHi = new boolean[0];

		void ensure(int layers, int sx, int sy, int sz) {
			int ny = layers * sy, nx = layers * sx, nz = layers * sz;
			if (this.yf.length < ny) {
				this.yf = new int[ny];
				this.fy = new float[ny];
				this.fy1 = new float[ny];
				this.sy = new float[ny];
			}
			if (this.xf.length < nx) {
				this.xf = new int[nx];
				this.xa = new int[nx];
				this.xb = new int[nx];
				this.fx = new float[nx];
				this.fx1 = new float[nx];
				this.sx = new float[nx];
			}
			if (this.zf.length < nz) {
				this.zf = new int[nz];
				this.fz = new float[nz];
				this.fz1 = new float[nz];
				this.sz = new float[nz];
			}
			if (this.acc.length < sy) {
				this.acc = new float[sy];
				this.alo = new float[sy];
				this.ahi = new float[sy];
				this.needLo = new boolean[sy];
				this.needHi = new boolean[sy];
			}
		}
	}

	static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	/** Layer l's tables into slot t of the scratch (y over the whole volume). */
	static void prepare(Stack s, int l, int t, Scratch sc, int sx, int sy, int sz, int x0, int y0, int z0, int dx, int dy, int dz, double xzScale, double yScale) {
		double xs = xzScale * s.freq[l], ys = yScale * s.freq[l];
		int[] p = s.perm;
		int o = 256 * l;
		for (int y = 0; y < sy; y++) {
			double yv = (double) (y0 + y * dy) * ys;
			double Y = wrap(yv) + s.oy[l];
			int yi = floor(Y);
			double fyd = Y - yi;
			float fy = s.smeared ? (float) (fyd - fudgeY(s.fudge[l], yv, fyd)) : (float) fyd;
			int k = t * sy + y;
			sc.yf[k] = yi;
			sc.fy[k] = fy;
			sc.fy1[k] = fy - 1.0F;
			sc.sy[k] = smooth((float) fyd);
		}
		for (int x = 0; x < sx; x++) {
			double X = wrap((double) (x0 + x * dx) * xs) + s.ox[l];
			int xi = floor(X);
			int k = t * sx + x;
			sc.xf[k] = xi;
			sc.fx[k] = (float) (X - xi);
			sc.fx1[k] = sc.fx[k] - 1.0F;
			sc.sx[k] = smooth(sc.fx[k]);
			sc.xa[k] = p[o + (xi & 255)];
			sc.xb[k] = p[o + (xi + 1 & 255)];
		}
		for (int z = 0; z < sz; z++) {
			double Z = wrap((double) (z0 + z * dz) * xs) + s.oz[l];
			int zi = floor(Z);
			int k = t * sz + z;
			sc.zf[k] = zi;
			sc.fz[k] = (float) (Z - zi);
			sc.fz1[k] = sc.fz[k] - 1.0F;
			sc.sz[k] = smooth(sc.fz[k]);
		}
	}

	/** One layer (scratch slot t) over one column, added into acc (rows where need is false skipped; need null: all). */
	static void column(Stack s, int l, int t, Scratch sc, int x, int z, int sx, int sy, int sz, float amp, float[] acc, boolean[] need) {
		int[] p = s.perm;
		int o = 256 * l;
		int kx = t * sx + x, kz = t * sz + z, ky = t * sy;
		int a = sc.xa[kx], b = sc.xb[kx], zf = sc.zf[kz];
		float fx = sc.fx[kx], fx1 = sc.fx1[kx], vsx = sc.sx[kx], fz = sc.fz[kz], fz1 = sc.fz1[kz], vsz = sc.sz[kz];
		int lastY = Integer.MIN_VALUE;
		float d000 = 0, d100 = 0, d010 = 0, d110 = 0, d001 = 0, d101 = 0, d011 = 0, d111 = 0;
		float g000 = 0, g100 = 0, g010 = 0, g110 = 0, g001 = 0, g101 = 0, g011 = 0, g111 = 0;
		for (int y = 0; y < sy; y++) {
			if (need != null && !need[y]) continue;
			int yi = sc.yf[ky + y];
			if (yi != lastY) {
				lastY = yi;
				int aa = p[o + (a + yi & 255)], ab = p[o + (a + yi + 1 & 255)], ba = p[o + (b + yi & 255)], bb = p[o + (b + yi + 1 & 255)];
				int h;
				// Gradient.dotXz (x * gx + z * gz), and gy
				h = p[o + (aa + zf & 255)] & 15;
				d000 = GX[h] * fx + GZ[h] * fz;
				g000 = GY[h];
				h = p[o + (ba + zf & 255)] & 15;
				d100 = GX[h] * fx1 + GZ[h] * fz;
				g100 = GY[h];
				h = p[o + (ab + zf & 255)] & 15;
				d010 = GX[h] * fx + GZ[h] * fz;
				g010 = GY[h];
				h = p[o + (bb + zf & 255)] & 15;
				d110 = GX[h] * fx1 + GZ[h] * fz;
				g110 = GY[h];
				h = p[o + (aa + zf + 1 & 255)] & 15;
				d001 = GX[h] * fx + GZ[h] * fz1;
				g001 = GY[h];
				h = p[o + (ba + zf + 1 & 255)] & 15;
				d101 = GX[h] * fx1 + GZ[h] * fz1;
				g101 = GY[h];
				h = p[o + (ab + zf + 1 & 255)] & 15;
				d011 = GX[h] * fx + GZ[h] * fz1;
				g011 = GY[h];
				h = p[o + (bb + zf + 1 & 255)] & 15;
				d111 = GX[h] * fx1 + GZ[h] * fz1;
				g111 = GY[h];
			}
			float fy = sc.fy[ky + y], fy1 = sc.fy1[ky + y], vsy = sc.sy[ky + y];
			float l0 = lerp(vsy, lerp(vsx, d000 + g000 * fy, d100 + g100 * fy), lerp(vsx, d010 + g010 * fy1, d110 + g110 * fy1));
			float l1 = lerp(vsy, lerp(vsx, d001 + g001 * fy, d101 + g101 * fy), lerp(vsx, d011 + g011 * fy1, d111 + g111 * fy1));
			acc[y] = acc[y] + amp * lerp(vsz, l0, l1);
		}
	}

	/** NoiseFunction$Sampler.sampleVolume over a stack: out[i] = 0 + the layers' sum, in the volume's order. */
	static void stackVolume(Stack s, float[] out, int sx, int sy, int sz, int x0, int y0, int z0, int dx, int dy, int dz, double xzScale, double yScale) {
		Scratch sc = SCRATCH.get();
		sc.ensure(s.n, sx, sy, sz);
		for (int l = 0; l < s.n; l++) prepare(s, l, l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, xzScale, yScale);
		float[] acc = sc.acc;
		for (int z = 0; z < sz; z++) {
			for (int x = 0; x < sx; x++) {
				java.util.Arrays.fill(acc, 0, sy, 0.0F);
				for (int l = 0; l < s.n; l++) column(s, l, l, sc, x, z, sx, sy, sz, s.amp[l], acc, null);
				System.arraycopy(acc, 0, out, (z * sx + x) * sy, sy);
			}
		}
	}

	/** The BlendedNoise lerp node over a volume: the main stack first, then per point only the limit stack(s) it needs. */
	static void blendedVolume(Stack m, Stack lo, Stack hi, float[] out, int sx, int sy, int sz, int x0, int y0, int z0, int dx, int dy, int dz, double mxz,
		double my, double lxz, double ly) {
		Scratch sc = SCRATCH.get();
		int tm = 0, tlo = m.n, thi = m.n + lo.n;
		sc.ensure(m.n + lo.n + hi.n, sx, sy, sz);
		for (int l = 0; l < m.n; l++) prepare(m, l, tm + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, mxz, my);
		for (int l = 0; l < lo.n; l++) prepare(lo, l, tlo + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, lxz, ly);
		for (int l = 0; l < hi.n; l++) prepare(hi, l, thi + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, lxz, ly);
		float[] am = sc.acc, alo = sc.alo, ahi = sc.ahi;
		boolean[] needLo = sc.needLo, needHi = sc.needHi;
		for (int z = 0; z < sz; z++) {
			for (int x = 0; x < sx; x++) {
				java.util.Arrays.fill(am, 0, sy, 0.0F);
				java.util.Arrays.fill(alo, 0, sy, 0.0F);
				java.util.Arrays.fill(ahi, 0, sy, 0.0F);
				for (int l = 0; l < m.n; l++) column(m, l, tm + l, sc, x, z, sx, sy, sz, m.amp[l], am, null);
				boolean anyLo = false, anyHi = false;
				for (int y = 0; y < sy; y++) {
					float t = alpha(am[y]);
					needLo[y] = t != 1.0F;
					needHi[y] = t != 0.0F;
					anyLo |= needLo[y];
					anyHi |= needHi[y];
				}
				if (anyLo) for (int l = 0; l < lo.n; l++) column(lo, l, tlo + l, sc, x, z, sx, sy, sz, lo.amp[l], alo, needLo);
				if (anyHi) for (int l = 0; l < hi.n; l++) column(hi, l, thi + l, sc, x, z, sx, sy, sz, hi.amp[l], ahi, needHi);
				int at = (z * sx + x) * sy;
				for (int y = 0; y < sy; y++) {
					float t = alpha(am[y]);
					out[at + y] = t == 0.0F ? alo[y] : t == 1.0F ? ahi[y] : lerp(t, alo[y], ahi[y]);
				}
			}
		}
	}
}
