package mcopt.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Frame generation with MetalFX frame interpolation, -Dmcopt.metal.framegen=true: an experiment for how it feels, not a
 * frame rate. The game renders real frames at up to half the display's refresh; between two of them MetalFX makes the
 * frame halfway, and the display shows generated and real frames alternately, one per refresh, each at the refresh it was
 * scheduled for (presentDrawable:atTime: on the display link's grid). Generated frames are never counted as frames.
 *
 * Per real frame: once the level has drawn (before the hand pass clears the depth) the camera is recorded and, from the
 * level's depth and this and last frame's view-projection, a compute pass writes camera motion (so entities and particles
 * move with the world behind them; the camera-locked hand gets none). Just before the GUI draws, the level image is copied
 * out. The GUI's draws are replayed into two layers, over black and over white, which together reproduce the GUI's effect
 * on any background, so the newer frame's GUI goes onto the generated one unsmeared. At the present, MetalFX interpolates
 * between the previous and this level image and both images to show are drawn into a small ring; once the frame's GPU
 * work completes, a presenter queue acquires drawables and schedules them on the display link's grid: the generated one
 * on the first refresh it can make, the real one a refresh later (mcframegen.m). The render thread never waits for a
 * drawable: it sleeps until the next frame has just enough time (its measured start-to-GPU-done time) to make the
 * refresh after this one's, which caps it at half the refresh and keeps its input fresh.
 *
 * Shaders off and packs take motion from the level's depth; the native shading pipeline (-Dmcopt.shade=native) from its lit
 * scene copy's per-pixel distance (its depth lives only in tile memory). Screens (menus, inventories, chat) and anything
 * without a level present real frames only. -Dmcopt.metal.framegen=paced: the same pacing and cap without generated
 * frames, the latency baseline. Off (the default): none of this runs.
 */
public final class FrameGen {
	private static final String MODE = System.getProperty("mcopt.metal.framegen", "false");
	/** Frame generation or its paced baseline; fixed at startup, so the backend's own paths never change otherwise. */
	public static final boolean ENABLED = ("true".equals(MODE) || "paced".equals(MODE)) && MetalFx.SCALE >= 1;
	private static final boolean GENERATE = "true".equals(MODE);
	/** -Dmcopt.metal.framegen.view=generated: generated frames without the GUI; =motion: the motion vectors instead. */
	private static final String VIEW = System.getProperty("mcopt.metal.framegen.view", "");
	/**
	 * Refreshes per shown frame stay whole numbers, so every frame is held equally long (the game drops to a quarter of the
	 * refresh when it can't make half); -Dmcopt.metal.framegen.even=false lets the spacing follow the game (uneven holds).
	 */
	private static final boolean EVEN = Boolean.parseBoolean(System.getProperty("mcopt.metal.framegen.even", "true"));
	/** -Dmcopt.metal.framegen.dump=turn,move,first: save the first generated frame of each kind (and its real neighbours) as PNGs. */
	private static final Set<String> DUMP = new HashSet<>(Arrays.asList(System.getProperty("mcopt.metal.framegen.dump", "").split(",")));
	/** -Dmcopt.metal.framegen.log=true: every present (kind, target, presentedTime, handler time, frame start, frame done) into framegen/presents.csv. */
	private static final boolean LOG = Boolean.getBoolean("mcopt.metal.framegen.log");
	/**
	 * Seconds of slack past the frame's p90 start-to-done time (its images' copy, the compositor's latch, slower frames);
	 * default half a refresh. Each ms of it is a ms of latency; too little and slow frames miss their refresh (a double hold).
	 */
	private static final double MARGIN = Double.parseDouble(System.getProperty("mcopt.metal.framegen.margin", "-1"));
	/** A camera that moved further than this between two frames was teleported: nothing to interpolate. */
	private static final double TELEPORT = 8;
	/** Device depth from which a pixel is the pack runtime's hand (it draws the hand at 0.4375 to 0.5625, like Iris). */
	private static final float HAND_DEPTH = 0.43F;

	private static @Nullable FrameGen instance;
	private static boolean failed;

	private final MetalEncoder encoder;
	private final long fg;
	/** Level image size the interpolator was made for (0 until then, or when MetalFX refused). */
	private int width, height;
	private @Nullable TextureTarget black, white;

	// ---- this frame ----
	/** The level drew and its camera and motion were recorded (levelDrawn); consumed by beforeGui. */
	private boolean cameraSet;
	/** In a level with no screen open: the frame takes two refreshes (a generated one and itself). */
	private boolean inLevel;
	/** The level image was captured; the GUI layers follow it. */
	private boolean captured;
	private boolean uiDrawn, uiBroken;
	/** A generated frame goes before this one (interpolated in frame()). */
	private boolean pair;
	private boolean interpolatedLast;
	private final Matrix4f viewProjection = new Matrix4f(), previousViewProjection = new Matrix4f();
	private @Nullable Vec3 camera, previousCamera;
	private @Nullable Object level, previousLevel;
	private float yaw, previousYaw, pitch, previousPitch;
	/** Between the two frames of the pair: degrees the view turned, blocks the camera moved. */
	private float turn;
	private double move;
	/** Post effects run this frame: the hand drew into its own depth, the main one holds the level's too. */
	private boolean consistentDepth;
	private float near, far, fov, aspect;
	private boolean previousCaptured;
	private int capturedWidth, capturedHeight;
	private double frameStart, previousCaptureStart, captureStart;

	// ---- pacing ----
	/** Refreshes each shown image is held (whole numbers keep every hold equal; see EVEN). */
	private int refreshes = 1;
	private final double[] work = new double[32], gpu = new double[32], cpu = new double[32];
	private int timingSamples, cpuSamples;
	private long completedSeen;
	/** This frame's number in mcf_submit (0: nothing to show) and the refreshes its images take. */
	private long submitted;
	private int frameRefreshes;
	private @Nullable Long layerSet;

	// ---- report ----
	private final long logPosition = MemoryUtil.nmemCalloc(1, 8);
	private final long logBuffer = MemoryUtil.nmemAlloc(8 * 6 * 1024);
	private final List<double[]> window = new ArrayList<>();
	private final StringBuilder csv = new StringBuilder();
	private double reportStart;
	private int reportFrames, reportGenerated, reportLate;
	/** Generated frames between two differing real frames, and those MetalFX gave back as the newer real frame, as of the last report. */
	private long movingSeen, copiedSeen;
	private boolean presentedTimes;
	private final Set<String> dumped = new HashSet<>();
	private double firstPairAt, turningSince;
	/** -Dmcopt.metal.framegen.dump=seq: frames of the recorded sequence so far (-1: not started). */
	private int sequenceFrames = -1;

	private FrameGen(MetalEncoder encoder) {
		this.encoder = encoder;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 1024);
			this.fg = (long) N.NEW.invokeExact(encoder.ctx, err, 1024);
			if (this.fg == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
		System.out.printf("mcopt-framegen: %s (refresh %.2f Hz, %s holds)%n", GENERATE ? "MetalFX frame interpolation on" : "paced baseline, no generated frames",
			period() > 0 ? 1 / period() : 0, EVEN ? "even" : "uneven");
	}

	/** The frame generator on the Metal backend, created on first use; null otherwise. */
	static @Nullable FrameGen get(MetalEncoder encoder) {
		if (instance != null || failed || !ENABLED) return instance;
		try {
			return instance = new FrameGen(encoder);
		} catch (RuntimeException e) {
			failed = true;
			System.out.println("mcopt-framegen: disabled: " + e.getMessage());
			return null;
		}
	}

	private static @Nullable FrameGen get() {
		if (instance != null || failed || !ENABLED) return instance;
		if (!(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend() instanceof MetalEncoder encoder)) {
			failed = true;
			return null;
		}
		return get(encoder);
	}

	// ---- capture (GameRendererFrameGenMixin, GuiRendererFrameGenMixin) ----

	/**
	 * The level has drawn; the hand pass is next. projection: the one the level drew with (view bobbing in it with shaders off;
	 * with a pack it's in the camera's view rotation instead, the product is the same).
	 */
	public static void levelDrawn(RenderTarget main, Matrix4fc projection, CameraRenderState camera, boolean consistentDepth) {
		FrameGen g = get();
		if (g == null || !GENERATE) return;
		g.consistentDepth = consistentDepth;
		g.recordLevel(main, projection, camera);
	}

	private void recordLevel(RenderTarget main, Matrix4fc projection, CameraRenderState camera) {
		this.cameraSet = false;
		if (!this.ensure(main)) return;
		this.viewProjection.set(projection).mul(camera.viewRotationMatrix);
		this.camera = camera.pos;
		this.yaw = camera.yRot;
		this.pitch = camera.xRot;
		this.level = Minecraft.getInstance().level;
		// MetalFX's camera: from the unbobbed projection (a perspective with reversed or forward depth, finite or infinite far).
		Matrix4f p = camera.projectionMatrix;
		this.fov = (float) Math.toDegrees(2 * Math.atan(1 / p.m11()));
		this.aspect = p.m11() / p.m00();
		double r1 = p.m32() / (1 + p.m22()), r0 = p.m22() == 0 ? 1e6 : p.m32() / p.m22();
		this.near = (float) Math.min(r0, r1);
		this.far = (float) Math.max(r0, r1);
		this.cameraSet = true;
		MetalTexture depth = (MetalTexture) main.getDepthTexture();
		if (depth == null) {
			this.cameraSet = false;
			return;
		}
		this.encoder.flushClear(depth);
		this.motion(depth.handle, false);
	}

	/** This frame's motion from source (the level's depth, or native shading's scene copy) and the two frames' cameras. */
	private void motion(long source, boolean distance) {
		boolean history = this.previousCamera != null && this.camera != null && this.previousLevel == this.level;
		Matrix4f inverse = new Matrix4f(this.viewProjection).invert();
		Matrix4f previous = new Matrix4f();
		if (history) {
			Vec3 d = this.camera.subtract(this.previousCamera);
			previous.set(this.previousViewProjection).translate((float) d.x, (float) d.y, (float) d.z);
		} else {
			previous.set(this.viewProjection); // no history: zero motion
		}
		Matrix4f reproject = new Matrix4f(previous).mul(inverse);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long m = stack.nmalloc(16, 4 * 64);
			reproject.getToAddress(m);
			previous.getToAddress(m + 64);
			this.viewProjection.getToAddress(m + 128);
			inverse.getToAddress(m + 192);
			N.MOTION.invokeExact(this.encoder.enc, this.fg, source, distance ? 1 : 0, m, HAND_DEPTH);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** The level image is final (hand, screen effects, post effects); the GUI draws next. */
	public static void beforeGui(RenderTarget main, boolean levelRendered) {
		FrameGen g = get();
		if (g != null) g.capture(main, levelRendered);
	}

	private void capture(RenderTarget main, boolean levelRendered) {
		Minecraft mc = Minecraft.getInstance();
		this.inLevel = levelRendered && mc.gui.screen() == null && mc.gui.overlay() == null;
		this.captured = this.pair = this.uiDrawn = this.uiBroken = false;
		boolean ok = GENERATE && this.inLevel && this.cameraSet && main.width == this.width && main.height == this.height;
		this.cameraSet = false;
		if (!ok) {
			this.previousCaptured = false;
			return;
		}
		MetalTexture color = (MetalTexture) main.getColorTexture();
		MetalTexture depth = (MetalTexture) main.getDepthTexture();
		this.encoder.flushClear(color);
		long hand = 0;
		if (!this.consistentDepth && depth != null && !depth.hasPendingClear()) {
			// Shaders off: the hand pass cleared the depth and drew the hand into it, and nothing else has since.
			hand = depth.handle;
		}
		try {
			N.CAPTURE.invokeExact(this.encoder.enc, this.fg, color.handle, hand);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		this.captured = true;
		this.captureStart = this.frameStart;
		boolean sameView = this.previousCaptured && this.previousLevel == this.level && this.previousCamera != null
			&& this.camera.distanceTo(this.previousCamera) <= TELEPORT && this.capturedWidth == main.width && this.capturedHeight == main.height;
		this.pair = sameView;
		this.turn = Math.abs(((this.yaw - this.previousYaw) % 360 + 540) % 360 - 180) + Math.abs(this.pitch - this.previousPitch);
		this.move = this.previousCamera == null ? 0 : this.camera.distanceTo(this.previousCamera);
		this.previousYaw = this.yaw;
		this.previousPitch = this.pitch;
		this.previousViewProjection.set(this.viewProjection);
		this.previousCamera = this.camera;
		this.previousLevel = this.level;
		this.previousCaptured = true;
		this.capturedWidth = main.width;
		this.capturedHeight = main.height;
	}

	/**
	 * The GUI drew draws [start, ..) into the main target through draw; replays them into the two GUI layers. Draws after a
	 * blur (screens only) can't be layered: that frame gets no generated frame.
	 */
	public static void guiDrawn(int start, Consumer<RenderTarget> draw) {
		FrameGen g = instance;
		if (g == null || !g.captured) return;
		if (start != 0) {
			g.uiBroken = true;
			return;
		}
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.clearColorAndDepthTextures(g.black.getColorTexture(), new Vector4f(0, 0, 0, 0), g.black.getDepthTexture(), 0.0);
		draw.accept(g.black);
		encoder.clearColorAndDepthTextures(g.white.getColorTexture(), new Vector4f(1, 1, 1, 1), g.white.getDepthTexture(), 0.0);
		draw.accept(g.white);
		g.uiDrawn = true;
	}

	/** Sizes the interpolator and GUI layers for main; false if MetalFX can't make one (frame generation then stays off). */
	private boolean ensure(RenderTarget main) {
		if (main.width == this.width && main.height == this.height) return true;
		if (this.width < 0) return false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 1024);
			int format = MetalConst.pixelFormat(main.getColorTexture().getFormat());
			if ((int) N.RESIZE.invokeExact(this.encoder.ctx, this.fg, main.width, main.height, format, err, 1024) == 0) {
				System.out.println("mcopt-framegen: no frame interpolation: " + MemoryUtil.memUTF8(err));
				this.width = this.height = -1;
				return false;
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (this.black == null) {
			this.black = new TextureTarget("mcopt framegen GUI over black", main.width, main.height, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
			this.white = new TextureTarget("mcopt framegen GUI over white", main.width, main.height, main.getColorTexture().getFormat(), main.getDepthTexture().getFormat());
		} else {
			this.black.resize(main.width, main.height);
			this.white.resize(main.width, main.height);
		}
		this.width = main.width;
		this.height = main.height;
		this.previousCaptured = false;
		System.out.printf("mcopt-framegen: interpolating %dx%d%n", this.width, this.height);
		return true;
	}

	// ---- presentation (MetalSurface) ----

	/**
	 * The frame's image is final (blitFromTexture): the interpolation and the images to show go into the frame's command
	 * buffer; once it completes they're shown (mcf_submit), the generated one first.
	 */
	void frame(GpuTextureView view, long layer) {
		if (this.layerSet == null || this.layerSet != layer) {
			try {
				N.LAYER.invokeExact(layer);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			this.layerSet = layer;
		}
		MetalTexture src = MetalTexture.of(view);
		this.encoder.flushClear(src);
		long real = ((MetalTexture.View) view).handle;
		boolean generated = this.pair && !this.uiBroken;
		int k = this.refreshes;
		try {
			int g = -1;
			if (generated) {
				String dump = this.dumpKind();
				float dt = (float) Math.max(1e-3, this.captureStart - this.previousCaptureStart);
				int check = (int) N.INTERPOLATE.invokeExact(this.encoder.enc, this.fg, dt, this.near, this.far, this.fov, this.aspect, this.interpolatedLast ? 0 : 1);
				long early = dump == null ? 0 : this.readback((long) N.TEXTURE.invokeExact(this.fg, 2));
				int what = "motion".equals(VIEW) ? 3 : "generated".equals(VIEW) || !this.uiDrawn ? 2 : 1;
				long black = MetalBridge.textureHandle(this.black.getColorTexture()), white = MetalBridge.textureHandle(this.white.getColorTexture());
				g = (int) N.IMAGE.invokeExact(this.encoder.ctx, this.encoder.enc, this.fg, what, 0L, black, white);
				if (dump != null) this.dump(dump, src, early, check);
				if (DUMP.contains("seq")) this.recordSequence(dt, !this.interpolatedLast, check);
			}
			this.interpolatedLast = generated;
			int r = (int) N.IMAGE.invokeExact(this.encoder.ctx, this.encoder.enc, this.fg, 0, real, 0L, 0L);
			// In a level every real frame takes two slots, generated one before it or not (paced baseline, first frames).
			int hold = g < 0 && this.inLevel ? 2 * k : k;
			this.frameRefreshes = (g >= 0 ? k : 0) + hold;
			this.submitted = g >= 0 ? (long) N.SUBMIT.invokeExact(this.encoder.enc, this.fg, g, 1, k, r, 0, hold, this.frameStart)
				: (long) N.SUBMIT.invokeExact(this.encoder.enc, this.fg, r, 0, hold, -1, 0, 0, this.frameStart);
			this.reportFrames++;
			if (g >= 0) this.reportGenerated++;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * After the frame's submit (MetalSurface.present): once its presents are scheduled, sleeps until the next frame, started
	 * then, would be done just in time for the refresh after this frame's last hold.
	 */
	void afterSubmit() {
		if (this.captured) this.previousCaptureStart = this.captureStart;
		if (this.frameStart > 0) this.cpu[this.cpuSamples++ % this.cpu.length] = now() - this.frameStart;
		double period = period();
		this.updateTimings(period);
		this.report(period);
		if (this.submitted > 0 && period > 0) {
			double first;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long out = stack.nmalloc(8, 8);
				boolean scheduled = (int) N.WAIT_SCHEDULED.invokeExact(this.submitted, 0.1, out) != 0;
				first = scheduled ? MemoryUtil.memGetDouble(out) : 0;
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (first > 0) {
				double wake = first + this.frameRefreshes * period - percentile(this.work, 0.9) - margin(period);
				double now = now();
				if (wake - now > 0.2) wake = now + 0.2; // never stall long (clock trouble)
				// macOS lets a timed park overrun by about a quarter of its length: park for shrinking parts of what's
				// left, then spin the last millisecond.
				while (wake - now > 0.001) {
					LockSupport.parkNanos((long) ((wake - now) * 0.7e9));
					now = now();
				}
				while (now < wake) {
					Thread.onSpinWait();
					now = now();
				}
			}
		}
		this.submitted = 0;
		this.frameStart = now();
	}

	/** The window's surface is going away: nothing more is shown on it. */
	void close() {
		try {
			N.LAYER.invokeExact(0L);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		this.layerSet = null;
	}

	private void updateTimings(double period) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 24);
			N.FRAME_TIMES.invokeExact(out);
			long completed = (long) MemoryUtil.memGetDouble(out + 16);
			if (completed != this.completedSeen) {
				this.completedSeen = completed;
				this.work[this.timingSamples % this.work.length] = MemoryUtil.memGetDouble(out);
				this.gpu[this.timingSamples % this.gpu.length] = MemoryUtil.memGetDouble(out + 8);
				this.timingSamples++;
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (period <= 0) return;
		// Shown frames per real frame: two in a level (generated or reserved), one elsewhere. A real frame every that many
		// slots needs the render thread and the GPU each to keep up; its start-to-done time may span up to two such
		// intervals (the next frame starts while the GPU finishes this one, as without frame generation).
		int shown = this.inLevel ? 2 : 1;
		if (EVEN) {
			if (!this.fits(shown * this.refreshes * period, period, 1)) this.refreshes = Math.min(4, this.refreshes + 1);
			else if (this.refreshes > 1 && this.fits(shown * (this.refreshes - 1) * period, period, 0.9)) this.refreshes--;
		}
	}

	/** Whether a real frame every interval seconds keeps up (slack < 1: with room to spare); period: the refresh's. */
	private boolean fits(double interval, double period, double slack) {
		if (this.timingSamples == 0) return true;
		double busy = Math.max(percentile(this.cpu, Math.min(this.cpuSamples, this.cpu.length), 0.9), percentile(this.gpu, 0.9));
		return busy <= 0.85 * slack * interval && percentile(this.work, 0.9) + margin(period) <= 2 * slack * interval;
	}

	private static double margin(double period) {
		return MARGIN >= 0 ? MARGIN : period / 2;
	}

	private double percentile(double[] samples, double p) {
		return percentile(samples, Math.min(this.timingSamples, samples.length), p);
	}

	private static double percentile(double[] samples, int n, double p) {
		if (n == 0) return 0;
		double[] s = Arrays.copyOf(samples, n);
		Arrays.sort(s);
		return s[Math.min(n - 1, (int) (p * n))];
	}

	// ---- report: once a second, what the display was asked to show and when it did ----

	private void report(double period) {
		try {
			for (;;) {
				int n = (int) N.LOG_READ.invokeExact(this.logPosition, this.logBuffer, 1024);
				for (int i = 0; i < n; i++) {
					double[] e = new double[6];
					for (int k = 0; k < 6; k++) e[k] = MemoryUtil.memGetDouble(this.logBuffer + (i * 6L + k) * 8);
					if (e[2] > 0) this.presentedTimes = true;
					this.window.add(e);
					if (LOG) this.csv.append(String.format("%d,%.6f,%.6f,%.6f,%.6f,%.6f%n", (int) e[0], e[1], e[2], e[3], e[4], e[5]));
				}
				if (n < 1024) break;
			}
		} catch (Throwable t) {
			throw rethrow(t);
		}
		double now = now();
		if (this.reportStart == 0) this.reportStart = now;
		if (now - this.reportStart < 1) return;
		double seconds = now - this.reportStart;
		this.reportStart = now;
		if (LOG && !this.csv.isEmpty()) {
			try {
				Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("framegen/presents.csv");
				Files.createDirectories(file.getParent());
				if (!Files.exists(file)) Files.writeString(file, "kind,target,presented,handler,start,done\n");
				Files.writeString(file, this.csv, java.nio.file.StandardOpenOption.APPEND);
			} catch (java.io.IOException e) {
				System.out.println("mcopt-framegen: log failed: " + e);
			}
			this.csv.setLength(0);
		}
		List<double[]> shown = new ArrayList<>();
		int dropped = 0, late = 0, generated = 0;
		List<Double> latency = new ArrayList<>();
		for (double[] e : this.window) {
			double at = this.presentedTimes ? e[2] : e[3];
			if (at <= 0) {
				dropped++;
				continue;
			}
			shown.add(new double[] {at, e[0], e[1], e[4]});
			if (e[0] == 1) generated++;
			if (e[1] > 0 && period > 0 && at - e[1] > period / 2) late++;
			if (e[0] == 0 && e[4] > 0) latency.add(at - e[4]);
		}
		this.window.clear();
		shown.sort((a, b) -> Double.compare(a[0], b[0]));
		double[] gaps = new double[Math.max(0, shown.size() - 1)];
		int[] holds = new int[6];
		for (int i = 1; i < shown.size(); i++) {
			gaps[i - 1] = shown.get(i)[0] - shown.get(i - 1)[0];
			if (period > 0) holds[Math.min(5, (int) Math.round(gaps[i - 1] / period))]++;
		}
		double mean = Arrays.stream(gaps).average().orElse(0);
		double sd = Math.sqrt(Arrays.stream(gaps).map(g -> (g - mean) * (g - mean)).average().orElse(0));
		double[] sorted = gaps.clone();
		Arrays.sort(sorted);
		double[] lat = latency.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		long moving, copied;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 24);
			N.COPIES.invokeExact(out);
			moving = MemoryUtil.memGetLong(out + 8) - this.movingSeen;
			copied = MemoryUtil.memGetLong(out + 16) - this.copiedSeen;
			this.movingSeen += moving;
			this.copiedSeen += copied;
		} catch (Throwable t) {
			throw rethrow(t);
		}
		StringBuilder h = new StringBuilder();
		for (int i = 0; i < holds.length; i++) if (holds[i] > 0) h.append(' ').append(i).append("x:").append(holds[i]);
		System.out.printf("mcopt-framegen: %.1f real frames/s, %.1f shown/s (%d generated, %d dropped, %d late) | shown every %.2f ms (sd %.2f, min %.2f, max %.2f; refreshes held%s)"
				+ " | real frame start->shown %.1f ms (p90 %.1f) | frame work p90 %.1f ms, frame GPU p90 %.1f ms, %d refresh(es) per image | %s"
				+ " | %d of %d generated frames between differing real frames were the newer one given back (not interpolated)%n",
			this.reportFrames / seconds, shown.size() / seconds, generated, dropped, late, mean * 1e3, sd * 1e3,
			sorted.length > 0 ? sorted[0] * 1e3 : 0, sorted.length > 0 ? sorted[sorted.length - 1] * 1e3 : 0, h,
			lat.length > 0 ? Arrays.stream(lat).average().orElse(0) * 1e3 : 0, lat.length > 0 ? lat[(int) (0.9 * (lat.length - 1))] * 1e3 : 0,
			percentile(this.work, 0.9) * 1e3, percentile(this.gpu, 0.9) * 1e3, this.refreshes, this.presentedTimes ? "presentedTime" : "presented-handler time",
			copied, moving);
		this.reportFrames = this.reportGenerated = this.reportLate = 0;
	}

	// ---- dumps (-Dmcopt.metal.framegen.dump): a generated frame and its real neighbours as PNGs ----

	/** The kind of dump this generated frame is (marked as done), or null. */
	private @Nullable String dumpKind() {
		if (DUMP.size() <= 1 && DUMP.contains("")) return null;
		double now = now();
		if (this.firstPairAt == 0) this.firstPairAt = now;
		String kind = null;
		// Turns and moves only once the level has settled (the camera jumps around while it loads).
		boolean settled = now - this.firstPairAt > 8;
		// A turn a second in: steady turning, not its first frame.
		if (this.turn < 3) this.turningSince = 0;
		else if (this.turningSince == 0) this.turningSince = now;
		if (DUMP.contains("turn") && !this.dumped.contains("turn") && settled && this.turningSince > 0 && now - this.turningSince > 1) kind = "turn";
		else if (DUMP.contains("move") && !this.dumped.contains("move") && settled && this.move >= 0.5 && this.turn < 1) kind = "move";
		else if (DUMP.contains("first") && !this.dumped.contains("first") && now - this.firstPairAt > 3) kind = "first";
		if (kind != null) this.dumped.add(kind);
		return kind;
	}

	/**
	 * -Dmcopt.metal.framegen.dump=seq: MetalFX's inputs and output for 8 consecutive generated frames, 1.5 s into a steady
	 * turn, as raw textures (framegen/seq/NN-name.raw; RGBA8 colors, R32Float depth, RG16Float motion) and their parameters
	 * (framegen/seq/params.txt), to replay MetalFX on them offline.
	 */
	private void recordSequence(float dt, boolean reset, int check) {
		if (this.sequenceFrames >= 8) return;
		if (this.sequenceFrames < 0) {
			if (this.turningSince <= 0 || now() - this.turningSince < 1.5) return;
			this.sequenceFrames = 0;
		}
		int frame = this.sequenceFrames++;
		String[] names = frame == 0 ? new String[] {"prev-color", "color", "depth", "motion", "out"} : new String[] {"color", "depth", "motion", "out"};
		int[] which = frame == 0 ? new int[] {1, 0, 3, 4, 2} : new int[] {0, 3, 4, 2};
		long[] buffers = new long[which.length];
		try {
			for (int i = 0; i < which.length; i++) buffers[i] = this.readback((long) N.TEXTURE.invokeExact(this.fg, which[i]));
		} catch (Throwable t) {
			throw rethrow(t);
		}
		int w = this.width, h = this.height;
		String params = String.format("%d %d %d dt %.6f near %.6f far %.3f fov %.4f aspect %.6f reset %d turn %.3f move %.4f", frame, w, h, dt,
			this.near, this.far, this.fov, this.aspect, reset ? 1 : 0, this.turn, this.move);
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("framegen/seq");
		this.encoder.afterGpuFinishes(() -> {
			int size = w * h * 4;
			byte[][] data = new byte[buffers.length][size];
			for (int i = 0; i < buffers.length; i++) {
				MemoryUtil.memByteBuffer(Native.bufferContents(buffers[i]), size).get(data[i]);
				Native.release(buffers[i]);
			}
			int[] counts = this.checkCounts(check);
			String line = params + String.format(" check %d %d %d%n", counts[0], counts[1], counts[2]);
			Thread.ofPlatform().daemon().start(() -> {
				try {
					Files.createDirectories(dir);
					for (int i = 0; i < names.length; i++) Files.write(dir.resolve(String.format("%02d-%s.raw", frame, names[i])), data[i]);
					Files.writeString(dir.resolve("params.txt"), line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
				} catch (java.io.IOException e) {
					System.out.println("mcopt-framegen: sequence dump failed: " + e);
				}
			});
		});
	}

	/** A new buffer the texture (level-sized, 4 bytes a pixel) is copied into at this point of the frame. */
	private long readback(long texture) {
		long buffer = Native.bufferNew(this.encoder.ctx, (long) this.width * this.height * 4);
		Native.blitTextureToBuffer(this.encoder.enc, texture, 0, 0, 0, this.width, this.height, buffer, 0, this.width * 4);
		return buffer;
	}

	/**
	 * Saves the generated frame (read right after MetalFX made it: early) with its neighbours, and says whether it's the
	 * newer real frame given back, both by the frame's GPU check (check: its slot) and by comparing the images.
	 */
	private void dump(String kind, MetalTexture real, long early, int check) {
		int w = this.width, h = this.height;
		long size = (long) w * h * 4;
		long[] buffers;
		try {
			buffers = new long[] {this.readback((long) N.TEXTURE.invokeExact(this.fg, 1)), early, this.readback((long) N.TEXTURE.invokeExact(this.fg, 0)),
				this.readback(real.handle), this.readback(MetalBridge.textureHandle(this.black.getColorTexture())),
				this.readback(MetalBridge.textureHandle(this.white.getColorTexture())), this.readback((long) N.TEXTURE.invokeExact(this.fg, 2))};
		} catch (Throwable t) {
			throw rethrow(t);
		}
		String what = String.format("%s: turned %.2f deg, moved %.2f blocks, %.1f ms between the real frames", kind, this.turn, this.move,
			(this.captureStart - this.previousCaptureStart) * 1e3);
		Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("framegen");
		this.encoder.afterGpuFinishes(() -> {
			byte[][] pixels = new byte[buffers.length][(int) size];
			for (int i = 0; i < buffers.length; i++) {
				MemoryUtil.memByteBuffer(Native.bufferContents(buffers[i]), (int) size).get(pixels[i]);
				Native.release(buffers[i]);
			}
			int[] counts = this.checkCounts(check);
			String checks = String.format("; GPU check (of 2304 RGB samples): %d differ from the newer level, %d from the older, the two levels in %d;"
				+ " bytes: generated vs newer level %s, vs older %s, read again after the GUI composite %s",
				counts[0], counts[1], counts[2], same(pixels[1], pixels[2]), same(pixels[1], pixels[0]), same(pixels[1], pixels[6]));
			Thread.ofPlatform().daemon().start(() -> writeDump(dir, kind, what + checks, w, h, pixels));
		});
	}

	private int[] checkCounts(int slot) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(4, 12);
			N.CHECK_COUNTS.invokeExact(this.fg, slot, out);
			return new int[] {MemoryUtil.memGetInt(out), MemoryUtil.memGetInt(out + 4), MemoryUtil.memGetInt(out + 8)};
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static String same(byte[] a, byte[] b) {
		long off = 0;
		for (int i = 0; i < a.length; i++) if ((i & 3) != 3 && a[i] != b[i]) off++;
		return off == 0 ? "RGB identical" : String.format("%.2f%% of RGB bytes differ", 100.0 * off / (a.length / 4 * 3));
	}

	private static void writeDump(Path dir, String kind, String what, int w, int h, byte[][] px) {
		try {
			Files.createDirectories(dir);
			// 0 previous level, 1 generated level, 2 this level, 3 this real frame (GUI on), 4/5 GUI over black/white, 6 generated again.
			byte[] composite = new byte[px[1].length];
			for (int i = 0; i < composite.length; i++) {
				int g = px[1][i] & 255, b = px[4][i] & 255, wh = px[5][i] & 255;
				composite[i] = (byte) Math.clamp(Math.round(g * (wh - b) / 255.0 + b), 0, 255);
			}
			String[] names = {"1-previous-level", "2-generated-level", "4-level", "5-real", null, null};
			for (int k = 0; k < names.length; k++) if (names[k] != null) png(dir.resolve(kind + "-" + names[k] + ".png"), w, h, px[k]);
			png(dir.resolve(kind + "-3-generated.png"), w, h, composite);
			System.out.println("mcopt-framegen: dumped " + what + " to " + dir);
		} catch (Exception e) {
			System.out.println("mcopt-framegen: dump failed: " + e);
		}
	}

	private static void png(Path file, int w, int h, byte[] rgba) throws java.io.IOException {
		try (NativeImage image = new NativeImage(w, h, false)) {
			for (int y = 0; y < h; y++) {
				int row = (h - 1 - y) * w * 4; // texture rows are bottom-up
				for (int x = 0; x < w; x++) {
					int i = row + x * 4;
					image.setPixelABGR(x, y, 0xFF000000 | (rgba[i + 2] & 255) << 16 | (rgba[i + 1] & 255) << 8 | rgba[i] & 255);
				}
			}
			image.writeToFile(file);
		}
	}

	// ---- display timing ----

	private static double now() {
		try {
			return (double) N.NOW.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static double period() {
		try {
			return (double) N.PERIOD.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	/** The native functions (mcframegen.m), bound on first use so nothing loads with frame generation off. */
	private static final class N {
		private static final Linker LINKER = Linker.nativeLinker();
		static final MethodHandle NEW = fn("mcf_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle RESIZE = fn("mcf_resize", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
		static final MethodHandle TEXTURE = fn("mcf_texture", JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle MOTION = fn("mcf_motion", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_FLOAT);
		static final MethodHandle CAPTURE = fn("mcf_capture", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle INTERPOLATE = fn("mcf_interpolate", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT);
		static final MethodHandle LAYER = fn("mcf_layer", null, JAVA_LONG);
		static final MethodHandle IMAGE = fn("mcf_image", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
		static final MethodHandle SUBMIT = fn("mcf_submit", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE);
		static final MethodHandle WAIT_SCHEDULED = fn("mcf_wait_scheduled", JAVA_INT, JAVA_LONG, JAVA_DOUBLE, JAVA_LONG);
		static final MethodHandle NOW = fn("mcf_now", JAVA_DOUBLE);
		static final MethodHandle PERIOD = fn("mcf_period", JAVA_DOUBLE);
		static final MethodHandle LOG_READ = fn("mcf_log_read", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
		static final MethodHandle FRAME_TIMES = fn("mcf_frame_times", null, JAVA_LONG);
		static final MethodHandle COPIES = fn("mcf_copies", null, JAVA_LONG);
		static final MethodHandle CHECK_COUNTS = fn("mcf_check_counts", null, JAVA_LONG, JAVA_INT, JAVA_LONG);

		private static MethodHandle fn(String name, @Nullable MemoryLayout result, MemoryLayout... args) {
			FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
			var symbol = Native.lookup().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
			return LINKER.downcallHandle(symbol, d);
		}
	}
}
