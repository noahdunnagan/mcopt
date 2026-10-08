package mcopt.metal.lod;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * -Dmcopt.lod.nativeNoise=vector: LodNoiseKernels' volumes with the per-point float math (the corners' y terms, the
 * trilinear lerp, the sum) in the Java Vector API, 4 lanes along a column's y. The hashing and the gradients stay scalar:
 * the Vector API has no table lookup into a 256-entry table that maps to the hardware here (NEON has no gather; TBL takes 64
 * bytes at most), so a lane's 12 permutation lookups are 12 scalar loads, as in the plain Java control. Lanewise mul and add
 * are IEEE single precision without fusing, so the values are the game's bit for bit.
 *
 * Needs the JVM flag --add-modules jdk.incubator.vector (this class fails to load without it; nothing else refers to it), and
 * to keep its vectors in registers -XX:CompileCommand=dontinline,mcopt.metal.lod.LodNoiseVector::half (and ::last): C2
 * inlines those small methods into their caller, runs out of its inlining node budget there (NodeCountInliningCutoff, not a
 * product flag) and boxes every vector that crosses a call it gave up on (17-28 MB allocated per volume, 3-4x slower than
 * plain Java), measured.
 */
final class LodNoiseVector {
	private LodNoiseVector() {
	}

	private static final VectorSpecies<Float> S = FloatVector.SPECIES_128;

	/** Per worker: per corner, the cell's dotXz and gy for each y of a column (padded to whole vectors). */
	private static final class Lanes {
		float[] d = new float[0], g = new float[0];
		final float[] cd = new float[8], cg = new float[8];
		final int[] h = new int[8];
		float[] t0 = new float[0], t1 = new float[0];

		void ensure(int sy) {
			int n = 8 * ((sy + 3) & ~3);
			if (this.d.length < n) {
				this.d = new float[n];
				this.g = new float[n];
				this.t0 = new float[n / 8];
				this.t1 = new float[n / 8];
			}
		}
	}

	private static final ThreadLocal<Lanes> LANES = ThreadLocal.withInitial(Lanes::new);

	private static void column(LodNoiseKernels.Stack s, int l, int t, LodNoiseKernels.Scratch sc, Lanes ln, int x, int z, int sx, int sy, int sz, float amp,
		float[] acc, boolean[] need) {
		int[] p = s.perm;
		int o = 256 * l;
		int kx = t * sx + x, kz = t * sz + z, ky = t * sy;
		int a = sc.xa[kx], b = sc.xb[kx], zf = sc.zf[kz];
		float fx = sc.fx[kx], fx1 = sc.fx1[kx], fz = sc.fz[kz], fz1 = sc.fz1[kz];
		int stride = (sy + 3) & ~3;
		float[] d = ln.d, g = ln.g;
		int lastY = Integer.MIN_VALUE;
		float[] cd = ln.cd, cg = ln.cg;
		int[] h = ln.h;
		// pass 1 (scalar): each lane's 8 corners, cached per y floor as the game's loop does
		for (int y = 0; y < stride; y++) {
			int yy = Math.min(y, sy - 1);
			int yi = sc.yf[ky + yy];
			if (yi != lastY) {
				lastY = yi;
				int aa = p[o + (a + yi & 255)], ab = p[o + (a + yi + 1 & 255)], ba = p[o + (b + yi & 255)], bb = p[o + (b + yi + 1 & 255)];
				h[0] = p[o + (aa + zf & 255)] & 15;
				h[1] = p[o + (ba + zf & 255)] & 15;
				h[2] = p[o + (ab + zf & 255)] & 15;
				h[3] = p[o + (bb + zf & 255)] & 15;
				h[4] = p[o + (aa + zf + 1 & 255)] & 15;
				h[5] = p[o + (ba + zf + 1 & 255)] & 15;
				h[6] = p[o + (ab + zf + 1 & 255)] & 15;
				h[7] = p[o + (bb + zf + 1 & 255)] & 15;
				for (int c = 0; c < 8; c++) {
					float X = (c & 1) != 0 ? fx1 : fx, Z = (c & 4) != 0 ? fz1 : fz;
					cd[c] = LodNoiseKernels.GX[h[c]] * X + LodNoiseKernels.GZ[h[c]] * Z;
					cg[c] = LodNoiseKernels.GY[h[c]];
				}
			}
			for (int c = 0; c < 8; c++) {
				d[c * stride + y] = cd[c];
				g[c * stride + y] = cg[c];
			}
		}
		// pass 2 (vectors) in small methods: inside one method C2 ran out of inlining budget (NodeCountInliningCutoff, not a
		// product flag) for the Vector API's deep call chains and boxed the vectors that crossed un-inlined calls (17-28 MB
		// allocated a volume, 3-4x slower than plain Java); each method below compiles whole, and only arrays cross calls
		float[] t0 = ln.t0, t1 = ln.t1;
		half(d, g, stride, 0, sc.fy, sc.fy1, sc.sy, ky, sc.sx[kx], t0, need, sy);
		half(d, g, stride, 4, sc.fy, sc.fy1, sc.sy, ky, sc.sx[kx], t1, need, sy);
		last(t0, t1, sc.sz[kz], amp, acc, need, stride, sy);
	}

	private static boolean skip(boolean[] need, int y, int sy) {
		return need != null && !(need[y] || y + 1 < sy && need[y + 1] || y + 2 < sy && need[y + 2] || y + 3 < sy && need[y + 3]);
	}

	/** Corners c0..c0+3 (x0y0, x1y0, x0y1, x1y1 at one z): their x lerps, then the y lerp, into t. */
	private static void half(float[] d, float[] g, int stride, int c0, float[] fy, float[] fy1, float[] syt, int ky, float sx, float[] t, boolean[] need, int sy) {
		FloatVector vsx = FloatVector.broadcast(S, sx);
		for (int y = 0; y < stride; y += 4) {
			if (skip(need, y, sy)) continue;
			FloatVector vfy = FloatVector.fromArray(S, fy, ky + y), vfy1 = FloatVector.fromArray(S, fy1, ky + y), vsy = FloatVector.fromArray(S, syt, ky + y);
			FloatVector a = corner(d, g, c0 * stride + y, vfy), b = corner(d, g, (c0 + 1) * stride + y, vfy);
			FloatVector c = corner(d, g, (c0 + 2) * stride + y, vfy1), e = corner(d, g, (c0 + 3) * stride + y, vfy1);
			lerp(vsy, lerp(vsx, a, b), lerp(vsx, c, e)).intoArray(t, y);
		}
	}

	/** acc += amp * lerp(sz, t0, t1). */
	private static void last(float[] t0, float[] t1, float sz, float amp, float[] acc, boolean[] need, int stride, int sy) {
		FloatVector vsz = FloatVector.broadcast(S, sz), va = FloatVector.broadcast(S, amp);
		for (int y = 0; y < stride; y += 4) {
			if (skip(need, y, sy)) continue;
			FloatVector val = lerp(vsz, FloatVector.fromArray(S, t0, y), FloatVector.fromArray(S, t1, y));
			FloatVector.fromArray(S, acc, y).add(va.mul(val)).intoArray(acc, y);
		}
	}

	private static FloatVector corner(float[] d, float[] g, int at, FloatVector y) {
		return FloatVector.fromArray(S, d, at).add(FloatVector.fromArray(S, g, at).mul(y));
	}

	private static FloatVector lerp(FloatVector t, FloatVector a, FloatVector b) {
		return a.add(t.mul(b.sub(a)));
	}

	/** As LodNoiseKernels.stackVolume. */
	static void stackVolume(LodNoiseKernels.Stack s, float[] out, int sx, int sy, int sz, int x0, int y0, int z0, int dx, int dy, int dz, double xzScale,
		double yScale) {
		LodNoiseKernels.Scratch sc = LodNoiseKernels.SCRATCH.get();
		sc.ensure(s.n, sx, sy + 4, sz);
		Lanes ln = LANES.get();
		ln.ensure(sy);
		for (int l = 0; l < s.n; l++) LodNoiseKernels.prepare(s, l, l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, xzScale, yScale);
		float[] acc = sc.acc;
		for (int z = 0; z < sz; z++) {
			for (int x = 0; x < sx; x++) {
				java.util.Arrays.fill(acc, 0, sy + 4, 0.0F);
				for (int l = 0; l < s.n; l++) column(s, l, l, sc, ln, x, z, sx, sy, sz, s.amp[l], acc, null);
				System.arraycopy(acc, 0, out, (z * sx + x) * sy, sy);
			}
		}
	}

	/** As LodNoiseKernels.blendedVolume. */
	static void blendedVolume(LodNoiseKernels.Stack m, LodNoiseKernels.Stack lo, LodNoiseKernels.Stack hi, float[] out, int sx, int sy, int sz, int x0, int y0,
		int z0, int dx, int dy, int dz, double mxz, double my, double lxz, double ly) {
		LodNoiseKernels.Scratch sc = LodNoiseKernels.SCRATCH.get();
		int tm = 0, tlo = m.n, thi = m.n + lo.n;
		sc.ensure(m.n + lo.n + hi.n, sx, sy + 4, sz);
		Lanes ln = LANES.get();
		ln.ensure(sy);
		for (int l = 0; l < m.n; l++) LodNoiseKernels.prepare(m, l, tm + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, mxz, my);
		for (int l = 0; l < lo.n; l++) LodNoiseKernels.prepare(lo, l, tlo + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, lxz, ly);
		for (int l = 0; l < hi.n; l++) LodNoiseKernels.prepare(hi, l, thi + l, sc, sx, sy, sz, x0, y0, z0, dx, dy, dz, lxz, ly);
		float[] am = sc.acc, alo = sc.alo, ahi = sc.ahi;
		boolean[] needLo = sc.needLo, needHi = sc.needHi;
		for (int z = 0; z < sz; z++) {
			for (int x = 0; x < sx; x++) {
				java.util.Arrays.fill(am, 0, sy + 4, 0.0F);
				java.util.Arrays.fill(alo, 0, sy + 4, 0.0F);
				java.util.Arrays.fill(ahi, 0, sy + 4, 0.0F);
				for (int l = 0; l < m.n; l++) column(m, l, tm + l, sc, ln, x, z, sx, sy, sz, m.amp[l], am, null);
				boolean anyLo = false, anyHi = false;
				for (int y = 0; y < sy; y++) {
					float t = LodNoiseKernels.alpha(am[y]);
					needLo[y] = t != 1.0F;
					needHi[y] = t != 0.0F;
					anyLo |= needLo[y];
					anyHi |= needHi[y];
				}
				if (anyLo) for (int l = 0; l < lo.n; l++) column(lo, l, tlo + l, sc, ln, x, z, sx, sy, sz, lo.amp[l], alo, needLo);
				if (anyHi) for (int l = 0; l < hi.n; l++) column(hi, l, thi + l, sc, ln, x, z, sx, sy, sz, hi.amp[l], ahi, needHi);
				int at = (z * sx + x) * sy;
				for (int y = 0; y < sy; y++) {
					float t = LodNoiseKernels.alpha(am[y]);
					out[at + y] = t == 0.0F ? alo[y] : t == 1.0F ? ahi[y] : LodNoiseKernels.lerp(t, alo[y], ahi[y]);
				}
			}
		}
	}
}
