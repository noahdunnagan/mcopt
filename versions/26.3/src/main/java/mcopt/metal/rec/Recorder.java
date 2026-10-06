package mcopt.metal.rec;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The recorder behind Rec (only built with -Dmcopt.rec=OUT.mp4). Options:
 * <ul>
 * <li>-Dmcopt.rec.size=WxH: video size, default 1920x1080. The frame is fitted inside (black bars where the aspects differ);
 * W or H 0 takes the other from the first frame's aspect (1920x0 at 3456x2234: 1920x1242). Rounded to even sizes. The GPU
 * reads one bilinear tap per 2 x 2 frame pixels, so half the frame's size (1728x1116 at 3456x2234) is an exact box filter
 * and the cheapest.</li>
 * <li>-Dmcopt.rec.crop=X,Y,W,H: record only this window of the frame (frame pixels, from the top left), fitted into the
 * video size like a whole frame (W x H = the video size: native pixels, 1 tap each). Default: the whole frame.</li>
 * <li>-Dmcopt.rec.fps=N: frames per second of video, default 60.</li>
 * <li>-Dmcopt.rec.start=WHEN: when recording starts, default 0. Seconds after the first frame; {@code world} (in a world, no
 * screen open); or a bench phase (mcopt.bench: settle, spin, flight, capture...), optionally {@code +S} seconds into it.</li>
 * <li>-Dmcopt.rec.stop=WHEN: a bench phase (+S) that stops it; -Dmcopt.rec.duration=S: seconds after the start. Else the
 * recording runs until the game exits.</li>
 * <li>-Dmcopt.rec.bitrate=12M, -Dmcopt.rec.codec=h264_videotoolbox, -Dmcopt.rec.ffmpeg=/opt/homebrew/bin/ffmpeg,
 * -Dmcopt.rec.args="..." (more encoder options, space separated), -Dmcopt.rec.slots=6 (readback buffers, 3-16),
 * -Dmcopt.rec.speed=true (VideoToolbox's speed-over-quality and real-time hints).</li>
 * <li>-Dmcopt.rec.probe=MS: measurement only: recording switches on and off every MS ms and the mean frame time of each state
 * is logged every 5 s (the recorder's own cost, in the same run; the video repeats frames while it is off).
 * -Dmcopt.rec.null=true: measurement only, frames are converted and read back but not encoded.</li>
 * </ul>
 * Pipeline: the wall clock is cut into ticks of 1/fps from the start; the first frame the game finishes in a new tick is that
 * tick's video frame (the newest frame at that moment, as a display would show it). It is downscaled and converted to 4:2:0
 * on the GPU (NV12, BT.709 limited range), in one dispatch at the end of the frame's command buffer, into a ring of shared buffers (native/rec.m,
 * mcopt/rec/rec.metal). The render thread never waits: when every buffer is still busy it skips the tick. A writer thread
 * takes finished buffers in order and pipes them into ffmpeg (H.264 on VideoToolbox), repeating the last frame for ticks
 * without one, so the video plays in real time and a stall in the game shows as a stall. Next to the video: OUT.csv (one
 * line per recorded frame: video frame, wall clock, bench phase, camera) and OUT.log (ffmpeg's messages).
 */
final class Recorder {
	private static final String OUT = System.getProperty("mcopt.rec");
	private static final String SIZE = System.getProperty("mcopt.rec.size", "1920x1080");
	private static final String CROP = System.getProperty("mcopt.rec.crop", "").trim();
	private static final double FPS = Double.parseDouble(System.getProperty("mcopt.rec.fps", "60"));
	private static final String START = System.getProperty("mcopt.rec.start", "0").trim();
	private static final String STOP = System.getProperty("mcopt.rec.stop", "").trim();
	private static final double DURATION = Double.parseDouble(System.getProperty("mcopt.rec.duration", "0"));
	private static final String BITRATE = System.getProperty("mcopt.rec.bitrate", "12M");
	/** vt (default): VideoToolbox in this process, encoding the GPU's pixel buffers in place; else an ffmpeg encoder fed raw frames. */
	private static final String CODEC = System.getProperty("mcopt.rec.codec", "vt");
	private static final String FFMPEG = System.getProperty("mcopt.rec.ffmpeg", "/opt/homebrew/bin/ffmpeg");
	private static final String ARGS = System.getProperty("mcopt.rec.args", "").trim();
	private static final int SLOTS = Math.clamp(Integer.getInteger("mcopt.rec.slots", 6), 3, 16);
	private static final long PROBE_NS = Long.getLong("mcopt.rec.probe", 0) * 1_000_000L;
	/** -Dmcopt.rec.speed=true: VideoToolbox's speed-over-quality and real-time hints (less encoder work per frame). */
	private static final boolean SPEED = Boolean.getBoolean("mcopt.rec.speed");
	/** -Dmcopt.rec.encode=false: measurement only: ffmpeg reads the frames from the pipe and drops them (no encoding, no file). */
	private static final boolean ENCODE = Boolean.parseBoolean(System.getProperty("mcopt.rec.encode", "true"));
	/** -Dmcopt.rec.null=true: measurement only: frames are converted and read back but not encoded (no ffmpeg, no file). */
	private static final boolean NULL = Boolean.getBoolean("mcopt.rec.null");
	private static final boolean VT = CODEC.equals("vt") && !NULL && ENCODE;
	private static final int PARAMS_BYTES = 64;
	private static final int SLOT_READY = 2;

	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mcr_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle TAKE = fn("mcr_take", JAVA_INT, JAVA_LONG);
	private static final MethodHandle UNTAKE = fn("mcr_untake", null, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CAPTURE = fn("mcr_capture", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
		JAVA_INT);
	private static final MethodHandle WAIT = fn("mcr_wait", JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle STATE = fn("mcr_state", JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle FRAME = fn("mcr_frame", JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CONTENTS = fn("mcr_contents", JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle RELEASE = fn("mcr_release", null, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BUSY = fn("mcr_busy", JAVA_INT, JAVA_LONG);
	private static final MethodHandle VT_OPEN = fn("mcr_vt_open", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle VT_CAPTURE = fn("mcr_vt_capture", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
		JAVA_INT);
	private static final MethodHandle VT_STATS = fn("mcr_vt_stats", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle VT_CLOSE = fn("mcr_vt_close", JAVA_INT, JAVA_LONG);

	private enum State { WAIT, REC, DONE }

	/** The ring path's native state (codec other than vt), or the in-process encoder's (vt); the other is 0. */
	private final long rec, vt, params = MemoryUtil.nmemCalloc(1, PARAMS_BYTES);
	private final int outW, outH, slotBytes;
	private final double periodNs = 1e9 / FPS;
	private volatile State state = State.WAIT;
	private long firstNs, t0, lastK = -1, skippedK = -1;
	private int srcW, srcH, gridW, gridH;
	/** Per slot: its CSV line, handed from the render thread to the writer with the slot. */
	private final AtomicReferenceArray<String> meta = new AtomicReferenceArray<>(SLOTS);
	private volatile long skipped, captured;
	private volatile boolean closing;
	private volatile long drainUntil;
	private final Process ffmpeg;
	/** The ring path's writer thread; with vt, the thread that finishes the file (started by finish()). */
	private Thread writer;
	private final Path out;
	/** vt: the CSV, written on the render thread (one short line per recorded frame) and closed by the finishing thread. */
	private BufferedWriter csv;
	private long vtFailed;
	// bench phases (mcopt.bench.Bench, when it's there): when each was first seen, for a start or stop by phase
	private final Field benchPhase = benchPhase();
	private final Map<String, Long> phaseSeen = new HashMap<>();
	private String phase = "";
	// -Dmcopt.rec.probe: frame time with recording on and off
	private long probeLast, probeOnNs, probeOffNs, probeOnFrames, probeOffFrames, probeLogAt;

	Recorder(Object encoder, GpuTextureView view) {
		if (!OUT.endsWith(".mp4") && !OUT.endsWith(".mov")) throw new IllegalArgumentException("-Dmcopt.rec=" + OUT + ": the output must be a .mp4 or .mov");
		if (!(FPS > 0 && FPS <= 240)) throw new IllegalArgumentException("-Dmcopt.rec.fps=" + FPS);
		String[] p = SIZE.toLowerCase(Locale.ROOT).split("x");
		if (p.length != 2) throw new IllegalArgumentException("-Dmcopt.rec.size=" + SIZE + ": expected WxH");
		int w = Integer.parseInt(p[0].trim()), h = Integer.parseInt(p[1].trim());
		int sw = view.getWidth(0), sh = view.getHeight(0);
		if (w == 0 && h > 0) w = (int) Math.round((double) h * sw / sh);
		if (h == 0 && w > 0) h = (int) Math.round((double) w * sh / sw);
		if (w < 16 || h < 16 || w > 8192 || h > 8192) throw new IllegalArgumentException("-Dmcopt.rec.size=" + SIZE + ": " + w + "x" + h);
		this.outW = even(w);
		this.outH = even(h);
		this.slotBytes = this.outW * this.outH * 3 / 2;
		String source;
		try (var in = Recorder.class.getResourceAsStream("/mcopt/rec/rec.metal")) {
			if (in == null) throw new IllegalStateException("rec.metal missing");
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Path out = Path.of(OUT).toAbsolutePath();
		this.out = out;
		try {
			Files.createDirectories(out.getParent());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		if (VT) {
			if (FPS != Math.rint(FPS)) throw new IllegalArgumentException("-Dmcopt.rec.fps=" + FPS + ": whole numbers only with codec vt");
			this.rec = 0;
			this.ffmpeg = null;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.nmalloc(1, 4096);
				MemoryUtil.memPutByte(err, (byte) 0);
				long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source)), ff = MemoryUtil.memAddress(MemoryUtil.memUTF8(FFMPEG)),
					o = MemoryUtil.memAddress(MemoryUtil.memUTF8(out.toString())), log = MemoryUtil.memAddress(MemoryUtil.memUTF8(out + ".log"));
				try {
					this.vt = (long) VT_OPEN.invokeExact(MetalBridge.ctx(encoder), src, this.outW, this.outH, (int) FPS, bits(BITRATE), SPEED ? 1 : 0, ff, o, log, err, 4096);
				} finally {
					MemoryUtil.nmemFree(src);
					MemoryUtil.nmemFree(ff);
					MemoryUtil.nmemFree(o);
					MemoryUtil.nmemFree(log);
				}
				if (this.vt == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				this.csv = Files.newBufferedWriter(Path.of(out + ".csv"));
				this.csv.write("frame,epochMs,ms,phase,x,y,z,yaw,pitch,srcW,srcH\n");
			} catch (Throwable e) {
				throw rethrow(e);
			}
			Runtime.getRuntime().addShutdownHook(new Thread(this::exit, "mcopt-rec exit"));
			System.out.printf("mcopt-rec: recorder ready, %s %dx%d at %s fps (VideoToolbox H.264 in process, %s), start %s, stop %s%s%n", out, this.outW, this.outH,
				fpsArg(), BITRATE, START, STOP.isEmpty() ? (DURATION > 0 ? DURATION + " s" : "at exit") : STOP + (DURATION > 0 ? " or after " + DURATION + " s" : ""),
				PROBE_NS > 0 ? ", PROBE every " + PROBE_NS / 1_000_000 + " ms (measurement only)" : "");
			return;
		}
		this.vt = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			try {
				this.rec = (long) NEW.invokeExact(MetalBridge.ctx(encoder), src, SLOTS, (long) this.slotBytes, err, 4096);
			} finally {
				MemoryUtil.nmemFree(src);
			}
			if (this.rec == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
		} catch (Throwable e) {
			throw rethrow(e);
		}
		List<String> cmd = new ArrayList<>(List.of(FFMPEG, "-hide_banner", "-nostats", "-loglevel", "warning", "-y",
			"-f", "rawvideo", "-pix_fmt", "nv12", "-video_size", this.outW + "x" + this.outH, "-framerate", fpsArg(),
			"-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-i", "pipe:0",
			"-c:v", CODEC, "-b:v", BITRATE, "-g", String.valueOf(Math.max(1, Math.round(FPS * 2)))));
		if (CODEC.contains("264")) cmd.addAll(List.of("-profile:v", "high"));
		if (SPEED && CODEC.contains("videotoolbox")) cmd.addAll(List.of("-prio_speed", "1", "-realtime", "1"));
		if (!ARGS.isEmpty()) cmd.addAll(List.of(ARGS.split("\\s+")));
		// (NV12 goes into VideoToolbox as it is, no conversion on the way; the stream is 4:2:0, yuv420p to every decoder)
		if (!CODEC.contains("videotoolbox")) cmd.addAll(List.of("-pix_fmt", "yuv420p"));
		cmd.addAll(List.of("-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-movflags", "+faststart",
			out.toString()));
		if (!ENCODE) cmd = new ArrayList<>(List.of(FFMPEG, "-hide_banner", "-nostats", "-loglevel", "warning", "-f", "rawvideo", "-pix_fmt", "nv12", "-video_size",
			this.outW + "x" + this.outH, "-framerate", fpsArg(), "-i", "pipe:0", "-c:v", "rawvideo", "-f", "null", "-"));
		try {
			this.ffmpeg = NULL ? null : new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(new File(out + ".log")).start();
		} catch (IOException e) {
			throw new IllegalStateException("can't start " + FFMPEG + ": " + e.getMessage(), e);
		}
		this.writer = new Thread(() -> this.write(out), "mcopt-rec writer");
		this.writer.setDaemon(true);
		this.writer.start();
		Runtime.getRuntime().addShutdownHook(new Thread(this::exit, "mcopt-rec exit"));
		System.out.printf("mcopt-rec: recorder ready, %s %dx%d at %s fps (%s %s), start %s, stop %s%s%n", out, this.outW, this.outH, fpsArg(), NULL ? "NOT ENCODED" : CODEC, BITRATE,
			START, STOP.isEmpty() ? (DURATION > 0 ? DURATION + " s" : "at exit") : STOP + (DURATION > 0 ? " or after " + DURATION + " s" : ""),
			PROBE_NS > 0 ? ", PROBE every " + PROBE_NS / 1_000_000 + " ms (measurement only)" : "");
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	/** 12M, 12000k, 12000000: bits per second. */
	private static long bits(String rate) {
		String r = rate.trim().toLowerCase(Locale.ROOT);
		double scale = r.endsWith("m") ? 1e6 : r.endsWith("k") ? 1e3 : 1;
		return Math.round(Double.parseDouble(scale == 1 ? r : r.substring(0, r.length() - 1)) * scale);
	}

	private static String fpsArg() {
		return FPS == Math.rint(FPS) ? String.valueOf((long) FPS) : String.valueOf(FPS);
	}

	private static int even(double v) {
		return (int) Math.round(v / 2) * 2;
	}

	private static Field benchPhase() {
		try {
			Field f = Class.forName("mcopt.bench.Bench", false, Recorder.class.getClassLoader()).getDeclaredField("phase");
			f.setAccessible(true);
			return f;
		} catch (Throwable absent) {
			return null;
		}
	}

	/** The bench's phase this frame ("" without the bench); remembers when each phase was first seen. */
	private String readPhase(long now) {
		if (this.benchPhase == null) return "";
		try {
			Object v = this.benchPhase.get(null);
			String name = v == null ? "" : v.toString().toLowerCase(Locale.ROOT);
			if (!name.equals(this.phase)) {
				this.phase = name;
				this.phaseSeen.putIfAbsent(name, now);
			}
			return name;
		} catch (Throwable e) {
			return "";
		}
	}

	/** Whether WHEN (seconds since the first frame, world, or a bench phase with an optional +S) has come; since: its zero. */
	private boolean reached(String when, long now, long since) {
		if (when.isEmpty()) return false;
		int plus = when.indexOf('+');
		String what = (plus >= 0 ? when.substring(0, plus) : when).trim().toLowerCase(Locale.ROOT);
		double after = plus >= 0 ? Double.parseDouble(when.substring(plus + 1).trim()) : 0;
		if (what.isEmpty() || Character.isDigit(what.charAt(0)) || what.charAt(0) == '.') {
			return now - since >= (Double.parseDouble(what) + after) * 1e9;
		}
		if (what.equals("world")) {
			Minecraft mc = Minecraft.getInstance();
			boolean in = mc.level != null && mc.player != null && mc.gui.screen() == null;
			if (in) this.phaseSeen.putIfAbsent("world", now);
			Long seen = this.phaseSeen.get("world");
			return seen != null && now - seen >= after * 1e9;
		}
		Long seen = this.phaseSeen.get(what);
		return seen != null && now - seen >= after * 1e9;
	}

	/** The hook's work, on the render thread, every frame. Never waits on the GPU or the writer. */
	void frame(Object encoder, GpuTextureView view) {
		if (this.state == State.DONE) return;
		long now = System.nanoTime();
		if (this.firstNs == 0) this.firstNs = now;
		String phase = this.readPhase(now);
		if (this.state == State.WAIT) {
			if (!this.reached(START, now, this.firstNs)) return;
			this.t0 = now;
			this.state = State.REC;
			System.out.printf("mcopt-rec: recording from %d (epoch ms), phase '%s'%n", System.currentTimeMillis(), phase);
		}
		if (this.reached(STOP, now, this.t0) || DURATION > 0 && now - this.t0 >= DURATION * 1e9) {
			this.finish("stop condition " + (STOP.isEmpty() ? DURATION + " s" : STOP));
			return;
		}
		if (PROBE_NS > 0) {
			boolean on = this.probe(now);
			if (!on) return;
		}
		long k = (long) Math.floor((now - this.t0) / this.periodNs);
		if (k <= this.lastK) return;
		int w = view.getWidth(0), h = view.getHeight(0);
		if (w != this.srcW || h != this.srcH) this.layout(w, h);
		if (this.vt != 0) {
			this.captureVt(encoder, view, k, now, phase, w, h);
			return;
		}
		try {
			int slot = (int) TAKE.invokeExact(this.rec);
			if (slot < 0) {
				if (k != this.skippedK) this.skipped++;   // (later frames in the same tick try again)
				this.skippedK = k;
				return;
			}
			MetalBridge.flushClear(encoder, view.texture());
			// (its line goes with the slot; the writer sees the slot only once this frame's command buffer is done)
			this.meta.set(slot, this.csvLine(k, now, phase, w, h));
			int r = (int) CAPTURE.invokeExact(this.rec, MetalBridge.enc(encoder), MetalBridge.viewHandle(view), slot, k, this.params, PARAMS_BYTES, this.gridW,
				this.gridH);
			if (r != 0) {
				this.meta.set(slot, null);
				UNTAKE.invokeExact(this.rec, slot);
				return;
			}
		} catch (Throwable e) {
			throw rethrow(e);
		}
		this.lastK = k;
		this.captured++;
	}

	/** vt: tick k's frame into a fresh pixel buffer from the encoder's pool; a full pool skips the tick (the encoder repeats the last one). */
	private void captureVt(Object encoder, GpuTextureView view, long k, long now, String phase, int w, int h) {
		int r;
		try {
			MetalBridge.flushClear(encoder, view.texture());
			r = (int) VT_CAPTURE.invokeExact(this.vt, MetalBridge.enc(encoder), MetalBridge.viewHandle(view), k, this.params, PARAMS_BYTES, this.gridW, this.gridH);
		} catch (Throwable e) {
			throw rethrow(e);
		}
		if (r == -2) {
			if (k != this.skippedK) this.skipped++;
			this.skippedK = k;
			return;
		}
		if (r != 0) {
			if (++this.vtFailed == 100) this.finish("100 failed captures");
			return;
		}
		String line = this.csvLine(k, now, phase, w, h);
		synchronized (this) {   // (uncontended; the finishing thread closes the CSV under the same lock, after finish())
			try {
				if (this.state != State.DONE) this.csv.write(line + "\n");
			} catch (IOException e) {
				// the CSV is a side note: the video goes on
			}
		}
		this.lastK = k;
		this.captured++;
	}

	/** -Dmcopt.rec.probe: whether recording is on in this window; sums frame times per state and logs them every 5 s. */
	private boolean probe(long now) {
		boolean on = (now - this.t0) / PROBE_NS % 2 == 0;
		if (this.probeLast != 0) {
			// a frame's time counts for the state it was rendered in (the previous frame's call decided it)
			long dt = now - this.probeLast;
			boolean wasOn = (this.probeLast - this.t0) / PROBE_NS % 2 == 0;
			if (wasOn) {
				this.probeOnNs += dt;
				this.probeOnFrames++;
			} else {
				this.probeOffNs += dt;
				this.probeOffFrames++;
			}
		}
		this.probeLast = now;
		if (this.probeLogAt == 0) this.probeLogAt = now;
		if (now - this.probeLogAt >= 5_000_000_000L && this.probeOnFrames > 0 && this.probeOffFrames > 0) {
			double onMs = this.probeOnNs / 1e6 / this.probeOnFrames, offMs = this.probeOffNs / 1e6 / this.probeOffFrames;
			System.out.printf(Locale.ROOT, "mcopt-rec probe: on %.4f ms/frame (%d frames), off %.4f ms/frame (%d frames): recording costs %+.4f ms/frame, %+.2f%% fps, phase %s%n",
				onMs, this.probeOnFrames, offMs, this.probeOffFrames, onMs - offMs, (offMs / onMs - 1) * 100, this.phase);
			this.probeOnNs = this.probeOffNs = this.probeOnFrames = this.probeOffFrames = 0;
			this.probeLogAt = now;
		}
		return on;
	}

	/** Where a w x h frame goes in the video: fitted (filled when the aspects differ by under 1%), centered, even; and the bilinear taps per pixel that cover it. */
	private void layout(int w, int h) {
		this.srcW = w;
		this.srcH = h;
		// the source window (-Dmcopt.rec.crop): fitted and sampled like a whole frame of that size
		float[] crop = {0, 0, 1, 1};
		if (!CROP.isEmpty()) {
			String[] c = CROP.split(",");
			int cx = Integer.parseInt(c[0].trim()), cy = Integer.parseInt(c[1].trim()), cw = Integer.parseInt(c[2].trim()), ch = Integer.parseInt(c[3].trim());
			cx = Math.clamp(cx, 0, w - 16);
			cy = Math.clamp(cy, 0, h - 16);
			cw = Math.clamp(cw, 16, w - cx);
			ch = Math.clamp(ch, 16, h - cy);
			crop = new float[] {(float) cx / w, (float) cy / h, (float) cw / w, (float) ch / h};
			System.out.printf("mcopt-rec: crop %d,%d %dx%d of %dx%d%n", cx, cy, cw, ch, w, h);
			w = cw;
			h = ch;
		}
		int rw, rh;
		double mismatch = (double) w * this.outH / ((double) this.outW * h);
		if (Math.abs(mismatch - 1) < 0.01) {
			// the same aspect but for rounding to even sizes (3456x2234 into 1728x1116): fill it, no 1-2 px bars
			rw = this.outW;
			rh = this.outH;
		} else if ((long) w * this.outH >= (long) this.outW * h) {
			rw = this.outW;
			rh = Math.min(this.outH, even((double) this.outW * h / w));
		} else {
			rh = this.outH;
			rw = Math.min(this.outW, even((double) this.outH * w / h));
		}
		int rx = even((this.outW - rw) / 2.0), ry = even((this.outH - rh) / 2.0);
		rx = Math.min(rx, this.outW - rw);
		ry = Math.min(ry, this.outH - rh);
		// one bilinear tap per 2 x 2 source pixels: at an exact 2x (3456x2234 into 1728x1116) one tap is an exact box filter
		int tapsX = Math.clamp((int) Math.ceil((double) w / rw / 2 - 0.01), 1, 4), tapsY = Math.clamp((int) Math.ceil((double) h / rh / 2 - 0.01), 1, 4);
		long a = this.params;
		int[] v = {this.outW, this.outH, rx, ry, rw, rh, tapsX, tapsY, this.outW * this.outH, 0, 0, 0};
		for (int i = 0; i < v.length; i++) MemoryUtil.memPutInt(a + 4L * i, v[i]);
		for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(a + 48 + 4L * i, crop[i]);
		this.gridW = this.outW / 2;
		this.gridH = this.outH / 2;
		System.out.printf("mcopt-rec: frame %dx%d -> %dx%d at %d,%d in %dx%d, %dx%d taps%n", w, h, rw, rh, rx, ry, this.outW, this.outH, tapsX, tapsY);
	}

	private String csvLine(long k, long now, String phase, int w, int h) {
		StringBuilder b = new StringBuilder(96);
		b.append(k).append(',').append(System.currentTimeMillis()).append(',').append(String.format(Locale.ROOT, "%.3f", (now - this.t0) / 1e6)).append(',').append(phase);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			b.append(String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%.2f,%.2f", p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
		} else {
			b.append(",,,,,");
		}
		return b.append(',').append(w).append(',').append(h).toString();
	}

	/** Stops recording (render thread): the writer drains what's on the GPU, then closes the file. */
	private synchronized void finish(String why) {
		if (this.state == State.DONE) return;
		this.state = State.DONE;
		this.drainUntil = System.nanoTime() + 3_000_000_000L;
		this.closing = true;
		System.out.printf("mcopt-rec: stopping (%s) after %d recorded frames, %d ticks skipped (no free buffer)%n", why, this.captured, this.skipped);
		if (this.vt != 0) {
			this.writer = new Thread(this::closeVt, "mcopt-rec finish");
			this.writer.setDaemon(true);
			this.writer.start();
		}
	}

	/** vt: waits for the last captures, the encoder and ffmpeg, then reports (not on the render thread). */
	private void closeVt() {
		int code;
		long[] st = new long[7];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			code = (int) VT_CLOSE.invokeExact(this.vt);
			long a = stack.nmalloc(8, 7 * 8);
			VT_STATS.invokeExact(this.vt, a);
			for (int i = 0; i < 7; i++) st[i] = MemoryUtil.memGetLong(a + 8L * i);
		} catch (Throwable e) {
			System.out.println("mcopt-rec: closing failed: " + e);
			return;
		}
		try {
			synchronized (this) {
				this.csv.close();
			}
		} catch (IOException e) {
			// ignore
		}
		System.out.printf(Locale.ROOT, "mcopt-rec: wrote %s: %d video frames (%.2f s at %s fps), %d recorded, %d repeated, %d ticks skipped (pool full %d), %d failed, %d late, %d capture errors; H.264 %.1f MB; ffmpeg (mux) exit %d, %.1f MB%n",
			this.out, st[0], st[0] / FPS, fpsArg(), this.captured, st[1], this.skipped, st[4], st[2], st[3], this.vtFailed, st[5] / 1e6, code, this.out.toFile().length() / 1e6);
	}

	void abort() {
		this.finish("error");
	}

	/** JVM exit: stop if still recording, and give ffmpeg time to finish the file. */
	private void exit() {
		this.finish("exit");
		try {
			Thread w = this.writer;
			if (w != null) w.join(60_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The writer thread: finished slots in order into ffmpeg, the last frame repeated for ticks without one. */
	private void write(Path out) {
		long written = 0, repeated = 0, failed = 0, late = 0, next = 0;
		long logAt = System.nanoTime();
		byte[] cur = new byte[this.slotBytes], prev = null;
		OutputStream pipe = NULL ? OutputStream.nullOutputStream() : this.ffmpeg.getOutputStream();
		boolean broken = false;
		try (BufferedWriter csv = Files.newBufferedWriter(Path.of(out + ".csv"))) {
			csv.write("frame,epochMs,ms,phase,x,y,z,yaw,pitch,srcW,srcH\n");
			while (true) {
				int slot = (int) WAIT.invokeExact(this.rec, 50);
				if (slot < 0) {
					if (this.closing && ((int) BUSY.invokeExact(this.rec) == 0 || System.nanoTime() > this.drainUntil)) break;
					continue;
				}
				long k = (long) FRAME.invokeExact(this.rec, slot);
				boolean ok = (int) STATE.invokeExact(this.rec, slot) == SLOT_READY;
				String line = this.meta.getAndSet(slot, null);
				if (ok) {
					long addr = (long) CONTENTS.invokeExact(this.rec, slot);
					MemorySegment.copy(MemorySegment.ofAddress(addr).reinterpret(this.slotBytes), ValueLayout.JAVA_BYTE, 0, cur, 0, this.slotBytes);
				}
				RELEASE.invokeExact(this.rec, slot);
				if (!ok) {
					failed++;
					continue;
				}
				if (k < next) {
					late++;
					continue;
				}
				if (!broken) {
					try {
						for (byte[] fill = prev == null ? cur : prev; next < k; next++, repeated++) pipe.write(fill);
						pipe.write(cur);
					} catch (IOException e) {
						broken = true;
						System.out.println("mcopt-rec: ffmpeg stopped taking frames (" + e.getMessage() + "), see " + out + ".log");
					}
				}
				next = k + 1;
				written++;
				if (line != null) csv.write(line + "\n");
				byte[] t = prev == null ? new byte[this.slotBytes] : prev;
				prev = cur;
				cur = t;
				long now = System.nanoTime();
				if (now - logAt > 5_000_000_000L) {
					logAt = now;
					System.out.printf("mcopt-rec: %d frames (%.1f s of video), %d repeated, %d ticks skipped%n", next, next / FPS, repeated, this.skipped);
				}
			}
		} catch (Throwable e) {
			System.out.println("mcopt-rec: writer failed: " + e);
			e.printStackTrace(System.out);
		}
		// CPU the recording took outside the render thread: ffmpeg's (while it still runs) and this thread's
		double ffCpu = this.ffmpeg == null ? 0 : this.ffmpeg.info().totalCpuDuration().map(d -> d.toNanos() / 1e9).orElse(-1.0);
		double writerCpu = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1e9;
		try {
			pipe.close();
		} catch (IOException e) {
			// ffmpeg already gone
		}
		int code = -1;
		try {
			if (this.ffmpeg != null && this.ffmpeg.waitFor(60, TimeUnit.SECONDS)) code = this.ffmpeg.exitValue();
			else if (this.ffmpeg != null) this.ffmpeg.destroy();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long bytes = out.toFile().length();
		System.out.printf(Locale.ROOT, "mcopt-rec: wrote %s: %d video frames (%.2f s at %s fps), %d recorded, %d repeated, %d ticks skipped, %d failed, %d late; ffmpeg exit %d, %.1f MB; CPU s: ffmpeg %.1f, writer %.1f%n",
			out, next, next / FPS, fpsArg(), written, repeated, this.skipped, failed, late, code, bytes / 1e6, ffCpu, writerCpu);
	}
}
