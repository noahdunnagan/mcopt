package mcopt.metal.lod;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import mcopt.metal.MetalBridge;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import org.jspecify.annotations.Nullable;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * -Dmcopt.lod.nativeNoise=neon|java|vector (default off): the far terrain's density sampling with the game's hottest noise
 * primitives evaluated outside the game's own classes, bit-exact. The compiled density function tree (the game's
 * DensitySampler records) is copied with two kinds of leaves replaced:
 * - NoiseFunction$Sampler over a NoiseStack of PerlinNoise or SmearedPerlinNoise layers (the cave noises, the terrain's
 *   limit and main noises): the whole stack over the whole volume in one call;
 * - the BlendedNoise as compiled, lerp(clamp(main + 0.5, 0, 1), minLimit, maxLimit): with the game's LerpFunction rule
 *   (alpha 0 gives the first exactly, 1 the second) a point evaluates only the limit stack(s) it needs.
 * Everything else (splines, the shifted 2D noises, caches, the aquifers' and surface rules' logic) stays the game's code.
 *
 * Backends: neon (lodnoise.c through FFM: hashing with scalar loads, gradients and float math 4 lanes at a time), java
 * (LodNoiseKernels: the same algorithm in plain Java, the control), vector (LodNoiseVector: the Java Vector API, needs
 * --add-modules jdk.incubator.vector). All three give the game's values bit for bit (an offline harness checks the primitives
 * over millions of random inputs; -Dmcopt.lod.genBench checks whole tiles).
 *
 * The far terrain's samplers only: the game's own chunk generation never sees these nodes.
 */
final class LodNativeNoise {
	private LodNativeNoise() {
	}

	enum Backend {
		/** The game's own samplers (the copy's leaves delegate to the originals). */
		PLAIN,
		JAVA,
		VECTOR,
		NEON
	}

	/** The backend asked for, or null (off: the samplers are not even copied). */
	static final @Nullable Backend CONFIGURED = parse(System.getProperty("mcopt.lod.nativeNoise", ""));
	/** -Dmcopt.lod.nativeNoise.verify=N: every Nth volume a leaf computes is computed again by the game's sampler and compared. */
	static final int VERIFY = Integer.getInteger("mcopt.lod.nativeNoise.verify", 0);

	/** A copy's backend, read by its leaves at every call. */
	static final class Mode {
		volatile Backend backend;

		Mode(Backend b) {
			this.backend = b;
		}
	}

	/** The far terrain's copies (LodGenBench switches their backend between its phases). */
	static final Mode LOD = new Mode(CONFIGURED != null ? CONFIGURED : Backend.PLAIN);
	/**
	 * -Dmcopt.gen.nativeNoise=neon|java: the integrated server's chunk generation gets copies too (GenNoiseChunkMixin wraps the
	 * samplers NoiseChunk takes). Off by default; behaviour-identical by construction, proven on saved worlds before any default
	 * changes.
	 */
	static final @Nullable Backend SERVER_BACKEND = parse(System.getProperty("mcopt.gen.nativeNoise", ""));
	static final Mode SERVER = new Mode(SERVER_BACKEND != null ? SERVER_BACKEND : Backend.PLAIN);

	static final AtomicLong volumes = new AtomicLong(), volumePoints = new AtomicLong(), points = new AtomicLong(), verified = new AtomicLong(),
		mismatches = new AtomicLong();
	/** Volume calls by size: 1, 2-16, 17-256, 257-4096, 4097-65536, more (and their values), logged every 2^17 calls. */
	static final java.util.concurrent.atomic.AtomicLongArray sizeCalls = new java.util.concurrent.atomic.AtomicLongArray(6), sizeValues = new java.util.concurrent.atomic.AtomicLongArray(6);

	static void counted(int size) {
		long n = volumes.incrementAndGet();
		volumePoints.addAndGet(size);
		int b = size <= 1 ? 0 : size <= 16 ? 1 : size <= 256 ? 2 : size <= 4096 ? 3 : size <= 65536 ? 4 : 5;
		sizeCalls.incrementAndGet(b);
		sizeValues.addAndGet(b, size);
		if ((n & ((1 << 17) - 1)) == 0) {
			StringBuilder sb = new StringBuilder("mcopt-lod: nativeNoise volume calls by size (1 / 2-16 / 17-256 / 257-4k / 4k-64k / more): calls");
			for (int i = 0; i < 6; i++) sb.append(' ').append(sizeCalls.get(i));
			sb.append(", values");
			for (int i = 0; i < 6; i++) sb.append(' ').append(sizeValues.get(i));
			sb.append(", point calls ").append(points.get());
			System.out.println(sb);
		}
	}

	static @Nullable Backend parse(String s) {
		return switch (s.toLowerCase(Locale.ROOT)) {
			case "", "false", "off" -> null;
			case "true", "neon", "native" -> Backend.NEON;
			case "java" -> Backend.JAVA;
			case "vector" -> Backend.VECTOR;
			case "plain" -> Backend.PLAIN;
			default -> {
				System.out.println("mcopt-lod: unknown -Dmcopt.lod.nativeNoise=" + s + " (neon, java, vector): off");
				yield null;
			}
		};
	}

	/** Whether the samplers are copied (the flag is on, or the generation bench wants the backends side by side). */
	static boolean enabled() {
		return CONFIGURED != null || LodGenBench.ENABLED;
	}

	// ---- the copied tree ----

	/**
	 * A worker's sampler set whose samplers are the copies (the same set when off). The copies are kept per owner (the
	 * dimension's LodNoise), so a world left behind takes its copies with it.
	 */
	static DensitySamplerSet wrap(DensitySamplerSet set, Object owner) {
		if (!enabled()) return set;
		return wrap(set, owner, LOD, CONFIGURED == Backend.NEON || LodGenBench.ENABLED, "the far terrain's");
	}

	/** The server's NoiseChunk samplers through copies with the -Dmcopt.gen.nativeNoise backend (GenServerNoise, the mixin's door). */
	static DensitySamplerSet wrapServer(DensitySamplerSet set, Object owner) {
		if (SERVER_BACKEND == null || SERVER_BACKEND == Backend.PLAIN) return set;
		return wrap(set, owner, SERVER, SERVER_BACKEND == Backend.NEON, "the server's chunk generation");
	}

	private static DensitySamplerSet wrap(DensitySamplerSet set, Object owner, Mode mode, boolean natives, String what) {
		Copier copier;
		synchronized (COPIERS) {
			copier = COPIERS.computeIfAbsent(owner, o -> new Copier(mode, natives, what));
		}
		return new DensitySamplerSet() {
			@Override
			public DensitySampler.Bound get(DensityFunction f) {
				DensitySampler.Bound b = set.get(f);
				return new DensitySampler.Bound(copier.rewrite(b.sampler()), b.context());
			}
		};
	}

	private static final Map<Object, Copier> COPIERS = new java.util.WeakHashMap<>();

	private static final class Copier {
		private final Mode mode;
		private final boolean natives;
		private final String what;
		private final Map<Object, DensitySampler> copies = new IdentityHashMap<>();
		private final Map<NoiseStack, LodNoiseKernels.@Nullable Stack> stacks = new IdentityHashMap<>();
		/** Roots already copied, read without the lock (replaced whole on every addition; a handful of roots per dimension). */
		private volatile Map<Object, DensitySampler> roots = new IdentityHashMap<>();
		private int leaves, blends, unknown;

		Copier(Mode mode, boolean natives, String what) {
			this.mode = mode;
			this.natives = natives;
			this.what = what;
		}

		/** The sampler with its noise leaves replaced (memoized: shared subtrees stay shared). */
		DensitySampler rewrite(DensitySampler root) {
			DensitySampler done = this.roots.get(root);
			return done != null ? done : this.rewriteLocked(root);
		}

		private synchronized DensitySampler rewriteLocked(DensitySampler root) {
			int before = this.leaves + this.blends;
			DensitySampler r = this.copy(root);
			Map<Object, DensitySampler> next = new IdentityHashMap<>(this.roots);
			next.put(root, r);
			this.roots = next;
			if (this.leaves + this.blends != before) {
				System.out.printf(Locale.ROOT, "mcopt-lod: nativeNoise %s: %d noise stacks and %d blended noises replaced in %s samplers (%d records kept)%n",
					this.mode.backend.name().toLowerCase(Locale.ROOT), this.leaves, this.blends, this.what, this.unknown);
			}
			return r;
		}

		private DensitySampler copy(DensitySampler s) {
			DensitySampler done = this.copies.get(s);
			if (done != null) return done;
			DensitySampler r = replace(s);
			this.copies.put(s, r);
			return r;
		}

		private DensitySampler replace(DensitySampler s) {
			Blend b = blend(s);
			if (b != null) {
				this.blends++;
				return b;
			}
			if (s instanceof NoiseFunction.Sampler ns && ns.noise() instanceof NoiseStack st) {
				LodNoiseKernels.Stack k = stack(st);
				if (k != null) {
					this.leaves++;
					return new Leaf(ns, k, this.mode);
				}
			}
			if (!(s instanceof Record r)) return s;
			RecordComponent[] comps = r.getClass().getRecordComponents();
			Object[] args = new Object[comps.length];
			boolean changed = false;
			try {
				for (int i = 0; i < comps.length; i++) {
					var acc = comps[i].getAccessor();
					acc.setAccessible(true);
					Object v = acc.invoke(r);
					Object n = v;
					if (v instanceof DensitySampler d) {
						n = copy(d);
					} else if (v instanceof DensitySampler[] arr) {
						DensitySampler[] c = arr.clone();
						for (int k = 0; k < c.length; k++) c[k] = copy(arr[k]);
						n = c;
						for (int k = 0; k < c.length; k++) if (c[k] != arr[k]) changed = true;
					} else if (v instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(x -> x instanceof DensitySampler)) {
						n = list.stream().map(x -> copy((DensitySampler) x)).toList();
						if (!n.equals(v)) changed = true;
					}
					if (n != v && !(v instanceof DensitySampler[]) && !(v instanceof List<?>)) changed = true;
					args[i] = n;
				}
				if (!changed) return s;
				Class<?>[] types = new Class<?>[comps.length];
				for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
				Constructor<?> c = r.getClass().getDeclaredConstructor(types);
				c.setAccessible(true);
				return (DensitySampler) c.newInstance(args);
			} catch (ReflectiveOperationException | RuntimeException e) {
				this.unknown++;
				return s;
			}
		}

		private LodNoiseKernels.@Nullable Stack stack(NoiseStack st) {
			LodNoiseKernels.Stack k = this.stacks.get(st);
			if (k == null && !this.stacks.containsKey(st)) {
				k = LodNoiseKernels.of(st);
				if (k != null && this.natives) Natives.attach(k);
				this.stacks.put(st, k);
			}
			return k;
		}

		private @Nullable Blend blend(DensitySampler s) {
			if (!(s instanceof LerpFunction.Sampler l)) return null;
			if (!(l.alpha() instanceof ClampFunction.Sampler c) || c.min() != 0.0F || c.max() != 1.0F) return null;
			if (!(c.input() instanceof BinaryFunction.ConstAddSampler add) || add.right() != 0.5F) return null;
			if (!(add.left() instanceof NoiseFunction.Sampler m && m.noise() instanceof NoiseStack ms)) return null;
			if (!(l.first() instanceof NoiseFunction.Sampler lo && lo.noise() instanceof NoiseStack los)) return null;
			if (!(l.second() instanceof NoiseFunction.Sampler hi && hi.noise() instanceof NoiseStack his)) return null;
			if (lo.xzScale() != hi.xzScale() || lo.yScale() != hi.yScale()) return null;
			LodNoiseKernels.Stack km = stack(ms), klo = stack(los), khi = stack(his);
			if (km == null || klo == null || khi == null) return null;
			return new Blend(l, m.xzScale(), m.yScale(), lo.xzScale(), lo.yScale(), km, klo, khi, this.mode);
		}
	}

	// ---- the leaves ----

	private static final VarHandle VALUES;

	static {
		VarHandle v;
		try {
			v = MethodHandles.privateLookupIn(DensityBuffer.class, MethodHandles.lookup()).findVarHandle(DensityBuffer.class, "values", float[].class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
		VALUES = v;
	}

	static float[] values(DensityBuffer b) {
		return (float[]) VALUES.get(b);
	}

	/** NoiseFunction$Sampler over a stack of Perlin or SmearedPerlin layers. */
	record Leaf(NoiseFunction.Sampler original, LodNoiseKernels.Stack stack, Mode mode) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume v) {
			Backend b = this.mode.backend;
			// (a buffer whose size isn't the volume's: the game's own loop, which fills by the buffer's size)
			if (b == Backend.PLAIN || buffer.size() != v.size()) {
				this.original.sampleVolume(ctx, buffer, v);
				return;
			}
			float[] out = values(buffer);
			double xz = this.original.xzScale(), y = this.original.yScale();
			switch (b) {
				case NEON -> Natives.stackVolume(this.stack, out, v, xz, y);
				case VECTOR -> LodNoiseVector.stackVolume(this.stack, out, v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(), v.minBlockZ(),
					v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), xz, y);
				default -> LodNoiseKernels.stackVolume(this.stack, out, v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(), v.minBlockZ(),
					v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), xz, y);
			}
			counted(v.size());
			if (VERIFY > 0) verify(this.original, ctx, buffer, v);
		}

		@Override
		public float sampleValue(SamplerContext ctx, int x, int y, int z) {
			Backend b = this.mode.backend;
			if (b == Backend.PLAIN) return this.original.sampleValue(ctx, x, y, z);
			points.incrementAndGet();
			double xz = this.original.xzScale(), ys = this.original.yScale();
			return b == Backend.NEON ? Natives.stackPoint(this.stack, x * xz, y * ys, z * xz) : LodNoiseKernels.stackPoint(this.stack, x * xz, y * ys, z * xz);
		}
	}

	/** The BlendedNoise's lerp node. */
	record Blend(LerpFunction.Sampler original, double mainXz, double mainY, double limitXz, double limitY, LodNoiseKernels.Stack main,
		LodNoiseKernels.Stack lo, LodNoiseKernels.Stack hi, Mode mode) implements DensitySampler {
		@Override
		public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume v) {
			Backend b = this.mode.backend;
			if (b == Backend.PLAIN || buffer.size() != v.size()) {
				this.original.sampleVolume(ctx, buffer, v);
				return;
			}
			float[] out = values(buffer);
			switch (b) {
				case NEON -> Natives.blendedVolume(this, out, v);
				case VECTOR -> LodNoiseVector.blendedVolume(this.main, this.lo, this.hi, out, v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(),
					v.minBlockZ(), v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), this.mainXz, this.mainY, this.limitXz, this.limitY);
				default -> LodNoiseKernels.blendedVolume(this.main, this.lo, this.hi, out, v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(),
					v.minBlockZ(), v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), this.mainXz, this.mainY, this.limitXz, this.limitY);
			}
			counted(v.size());
			if (VERIFY > 0) verify(this.original, ctx, buffer, v);
		}

		@Override
		public float sampleValue(SamplerContext ctx, int x, int y, int z) {
			Backend b = this.mode.backend;
			if (b == Backend.PLAIN) return this.original.sampleValue(ctx, x, y, z);
			points.incrementAndGet();
			return b == Backend.NEON ? Natives.blendedPoint(this, x, y, z)
				: LodNoiseKernels.blendedPoint(this.main, this.lo, this.hi, x, y, z, this.mainXz, this.mainY, this.limitXz, this.limitY);
		}
	}

	private static final ThreadLocal<int[]> VERIFY_COUNT = ThreadLocal.withInitial(() -> new int[1]);

	private static void verify(DensitySampler original, SamplerContext ctx, DensityBuffer got, DensityVolume v) {
		int[] n = VERIFY_COUNT.get();
		if (++n[0] % VERIFY != 0) return;
		DensityBuffer want = DensityBuffer.createUnpooled(v.size());
		original.sampleVolume(ctx, want, v);
		long bad = 0;
		int first = -1;
		for (int i = 0; i < v.size(); i++) {
			if (Float.floatToRawIntBits(want.get(i)) != Float.floatToRawIntBits(got.get(i))) {
				if (bad++ == 0) first = i;
			}
		}
		long done = verified.incrementAndGet();
		if (done % 200 == 0) System.out.printf(Locale.ROOT, "mcopt-lod: nativeNoise verify: %d volumes compared with the game's samplers, %d values differed%n", done, mismatches.get());
		if (bad > 0) {
			long all = mismatches.addAndGet(bad);
			System.out.printf(Locale.ROOT, "mcopt-lod: nativeNoise verify MISMATCH: %d of %d values (%s, first at %d: game %s, %s %s; %d so far)%n", bad, v.size(), v, first,
				want.get(first), "copy", got.get(first), all);
		}
	}

	// ---- lodnoise.c ----

	static final class Natives {
		private static final Linker LINKER = Linker.nativeLinker();
		private static final MethodHandle NEW = fn("mcn_stack_new", false, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		private static final MethodHandle VOLUME = fn("mcn_stack_volume", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
			JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_FLOAT, JAVA_INT, JAVA_INT);
		private static final MethodHandle BLENDED = fn("mcn_blended_volume", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
			JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT);
		// points take well under a microsecond: linked critical (no thread-state transition)
		private static final MethodHandle POINT = fn("mcn_stack_point", true, JAVA_FLOAT, JAVA_LONG, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
		private static final MethodHandle BLENDED_POINT = fn("mcn_blended_point", true, JAVA_FLOAT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
			JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);

		private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
			FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
			var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
			return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
		}

		private static RuntimeException rethrow(Throwable t) {
			if (t instanceof RuntimeException r) return r;
			if (t instanceof Error e) throw e;
			return new IllegalStateException(t);
		}

		/** lodnoise.c's copy of the stack (kept for the session: a few hundred KB for the overworld's stacks). */
		static void attach(LodNoiseKernels.Stack s) {
			try (Arena a = Arena.ofConfined()) {
				MemorySegment perms = a.allocate(256L * s.n), params = a.allocate(8L * 5 * s.n, 8), amps = a.allocate(4L * s.n, 4);
				for (int i = 0; i < s.n; i++) {
					for (int k = 0; k < 256; k++) perms.set(ValueLayout.JAVA_BYTE, 256L * i + k, (byte) s.perm[256 * i + k]);
					params.setAtIndex(JAVA_DOUBLE, 5L * i, s.ox[i]);
					params.setAtIndex(JAVA_DOUBLE, 5L * i + 1, s.oy[i]);
					params.setAtIndex(JAVA_DOUBLE, 5L * i + 2, s.oz[i]);
					params.setAtIndex(JAVA_DOUBLE, 5L * i + 3, s.fudge[i]);
					params.setAtIndex(JAVA_DOUBLE, 5L * i + 4, s.freq[i]);
					amps.setAtIndex(JAVA_FLOAT, i, s.amp[i]);
				}
				s.handle = (long) NEW.invokeExact(s.n, s.smeared ? 1 : 0, perms.address(), params.address(), amps.address());
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (s.handle == 0) throw new IllegalStateException("mcn_stack_new failed");
		}

		/** Per thread: the native side writes here, then the values are copied into the buffer's array. */
		private static final ThreadLocal<MemorySegment[]> OUT = ThreadLocal.withInitial(() -> new MemorySegment[1]);

		private static MemorySegment out(int n) {
			MemorySegment[] h = OUT.get();
			if (h[0] == null || h[0].byteSize() < 4L * n) h[0] = Arena.ofAuto().allocate(4L * Math.max(n, 65536), 16);
			return h[0];
		}

		static void stackVolume(LodNoiseKernels.Stack s, float[] dst, DensityVolume v, double xz, double y) {
			int n = v.size();
			MemorySegment o = out(n);
			try {
				VOLUME.invokeExact(s.handle, o.address(), v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(), v.minBlockZ(), v.stepBlockX(),
					v.stepBlockY(), v.stepBlockZ(), xz, y, 1.0F, 1, 1);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			MemorySegment.copy(o, JAVA_FLOAT, 0, dst, 0, n);
		}

		static void blendedVolume(Blend b, float[] dst, DensityVolume v) {
			int n = v.size();
			MemorySegment o = out(n);
			try {
				BLENDED.invokeExact(b.main.handle, b.lo.handle, b.hi.handle, o.address(), v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(),
					v.minBlockZ(), v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), b.mainXz, b.mainY, b.limitXz, b.limitY, 1);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			MemorySegment.copy(o, JAVA_FLOAT, 0, dst, 0, n);
		}

		static float stackPoint(LodNoiseKernels.Stack s, double x, double y, double z) {
			try {
				return (float) POINT.invokeExact(s.handle, x, y, z);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}

		static float blendedPoint(Blend b, int x, int y, int z) {
			try {
				return (float) BLENDED_POINT.invokeExact(b.main.handle, b.lo.handle, b.hi.handle, x, y, z, b.mainXz, b.mainY, b.limitXz, b.limitY);
			} catch (Throwable t) {
				throw rethrow(t);
			}
		}
	}
}
