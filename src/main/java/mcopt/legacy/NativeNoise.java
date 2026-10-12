package mcopt.legacy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Random;

import mcopt.legacy.mixin.ImprovedNoiseAccess;
import net.minecraft.world.gen.class_1779;

/**
 * World-gen noise in native code: class_1779.method_6577 (the improved Perlin noise every overworld, nether and end
 * density octave goes through) runs in src/main/native/noise.c instead of Java. The result is bit-exact, so a seed gives
 * the same world with it on or off. On by default; {@code nativeNoise=false} in config/mcopt.properties (or
 * {@code -Dmcopt.nativeNoise=false}) turns it off. Where the library can't load (no build for this OS) or the start-up
 * self-test finds any difference from the game's own code, the game's Java code runs as before.
 * <p>
 * Dev switches: {@code -Dmcopt.noise.verify=true} runs both for every call and counts mismatches;
 * {@code -Dmcopt.gen.bench=true} picks native or Java at random per generated chunk and logs the time of each, plus a
 * terrain hash that must not change between runs.
 */
public final class NativeNoise {
	/** Read by NoiseMixin on every call; false until the library loaded and passed the self-test. */
	public static volatile boolean active;
	public static boolean verify;
	private static boolean loaded;

	private NativeNoise() {
	}

	public static native void populate(double[] out, int[] perm, double xo, double yo, double zo, int xs, int ys, int zs,
		double xScale, double yScale, double zScale, double noiseScale, double cx, double cy, double cz);

	public static synchronized void init() {
		if (loaded) return;
		loaded = true;
		if (!Config.flag("nativeNoise", true)) {
			System.out.println("[mcopt] native world-gen noise: off (config)");
			return;
		}
		String why = load();
		if (why != null) {
			System.out.println("[mcopt] native world-gen noise: off (" + why + ")");
			return;
		}
		long t = System.nanoTime();
		int bad;
		try {
			bad = selfTest();
		} catch (Throwable e) {
			System.out.println("[mcopt] native world-gen noise: off (self-test: " + e + ")");
			return;
		}
		if (bad != 0) {
			System.out.println("[mcopt] native world-gen noise: off (self-test: " + bad + " results differ from the game's)");
			return;
		}
		verify = Boolean.getBoolean("mcopt.noise.verify");
		active = true;
		System.out.printf("[mcopt] native world-gen noise: on (self-test passed in %.1f ms)%s%n", (System.nanoTime() - t) / 1e6,
			verify ? ", verify mode" : "");
	}

	/** Extracts and loads the library; null on success, else why not. */
	private static String load() {
		String os = System.getProperty("os.name", "");
		if (!os.startsWith("Mac")) return "no native build for " + os;
		try (InputStream in = NativeNoise.class.getResourceAsStream("/mcopt/native/libmcoptnoise.dylib")) {
			if (in == null) return "library missing from the jar";
			Path tmp = Files.createTempFile("mcoptnoise", ".dylib");
			try {
				Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				System.load(tmp.toAbsolutePath().toString());
			} finally {
				Files.deleteIfExists(tmp);  // loaded and mapped: the file isn't needed any more
			}
			return null;
		} catch (Throwable e) {
			return e.toString();
		}
	}

	/**
	 * Runs the game's code and the native code on the same random inputs, both the 2D path (ySize 1) and the 3D path,
	 * with offsets and scales like the overworld's, and counts results whose bits differ. Called while {@link #active} is
	 * still false, so method_6577 is the game's own.
	 */
	private static int selfTest() {
		Random r = new Random(0x6d636f7074L);
		int bad = 0;
		for (int n = 0; n < 96; n++) {
			class_1779 noise = new class_1779(new Random(r.nextLong()));
			ImprovedNoiseAccess a = (ImprovedNoiseAccess) noise;
			boolean flat = n % 3 == 0;
			int xs = 1 + r.nextInt(16), ys = flat ? 1 : 1 + r.nextInt(33), zs = 1 + r.nextInt(16);
			double f = Math.scalb(1.0, -r.nextInt(16));
			double xo = (r.nextDouble() - 0.5) * 3.3e7, yo = (r.nextDouble() - 0.5) * 512, zo = (r.nextDouble() - 0.5) * 3.3e7;
			double xsc = 684.412 * f * (r.nextBoolean() ? 1 : 1 / 80.0), ysc = 684.412 * f / 160.0, zsc = xsc;
			double[] want = new double[xs * ys * zs];
			for (int i = 0; i < want.length; i++) want[i] = r.nextGaussian() * 100;
			double[] got = want.clone();
			noise.method_6577(want, xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, f);
			populate(got, a.mcopt$perm(), xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, f, noise.field_7548, noise.field_7549,
				noise.field_7550);
			bad += differ(want, got);
		}
		return bad;
	}

	static int differ(double[] a, double[] b) {
		int bad = 0;
		for (int i = 0; i < a.length; i++) {
			if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i])) bad++;
		}
		return bad;
	}

	// Verify mode: the native result for the call in progress (one thread generates at a time per call).
	private static final ThreadLocal<double[]> PENDING = new ThreadLocal<>();
	private static long checked, mismatched;

	public static void verifyBefore(double[] arr, int[] perm, double xo, double yo, double zo, int xs, int ys, int zs,
		double xsc, double ysc, double zsc, double scale, double cx, double cy, double cz) {
		double[] copy = Arrays.copyOf(arr, arr.length);
		populate(copy, perm, xo, yo, zo, xs, ys, zs, xsc, ysc, zsc, scale, cx, cy, cz);
		PENDING.set(copy);
	}

	public static void verifyAfter(double[] arr) {
		double[] copy = PENDING.get();
		if (copy == null) return;
		PENDING.remove();
		int bad = differ(arr, copy);
		synchronized (NativeNoise.class) {
			checked += arr.length;
			mismatched += bad;
			if (bad != 0 || (checked >> 20) != ((checked - arr.length) >> 20)) {
				System.out.println("[mcopt] noise verify: " + mismatched + " mismatches in " + checked + " values");
			}
		}
	}
}
