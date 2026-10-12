package mcopt.legacy.mixin;

import java.util.Arrays;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev fps bench, off unless {@code -Dmcopt.fps.bench=true}: opens the world "mcopt-test" from the title screen, waits
 * {@code mcopt.fps.warmup} seconds (default 25) for chunks, then spins the camera for {@code mcopt.fps.seconds} (default
 * 20) and logs the mean fps, the 1% low and the median frame time, and closes the game. With {@code mcopt.fps.shot=NAME}
 * it first faces yaw 0, pitch 0 for a second and saves screenshots/NAME.png, for comparing renders.
 */
@Mixin(MinecraftClient.class)
abstract class FpsBenchMixin {
	@Shadow public ClientWorld world;
	@Shadow public ClientPlayerEntity player;
	@Shadow public abstract void startIntegratedServer(String name, String displayName, net.minecraft.world.level.LevelInfo info);
	@Shadow public abstract void scheduleStop();

	@Unique private static final boolean BENCH = Boolean.getBoolean("mcopt.fps.bench");
	@Unique private static final long WARMUP = Long.getLong("mcopt.fps.warmup", 25) * 1_000_000_000L;
	@Unique private static final long SECONDS = Long.getLong("mcopt.fps.seconds", 20) * 1_000_000_000L;
	@Unique private static final String SHOT = System.getProperty("mcopt.fps.shot");
	@Unique private static final float SHOT_PITCH = Float.parseFloat(System.getProperty("mcopt.fps.shotPitch", "0"));  // up is negative
	@Unique private static final float SHOT_YAW = Float.parseFloat(System.getProperty("mcopt.fps.shotYaw", "0"));
	@Unique private int mcopt$state;
	@Unique private long mcopt$t0, mcopt$last;
	@Unique private long[] mcopt$frames = BENCH ? new long[1 << 16] : null;
	@Unique private int mcopt$n;
	@Unique private boolean[] mcopt$ticked = BENCH ? new boolean[1 << 16] : null;  // the frame ran a client tick
	@Unique private boolean mcopt$tick;
	@Unique private boolean[] mcopt$gced = BENCH ? new boolean[1 << 16] : null;  // a GC finished during the frame
	@Unique private int[] mcopt$uploads = BENCH ? new int[1 << 16] : null;
	@Unique private long[] mcopt$uploadNs = BENCH ? new long[1 << 16] : null;
	@Unique private long mcopt$gcCount;
	@Unique private long mcopt$logged;
	@Unique private int mcopt$loggedN;

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void mcopt$tick(CallbackInfo ci) {
		this.mcopt$tick = true;
	}

	@Inject(method = "runGameLoop", at = @At("HEAD"))
	private void mcopt$bench(CallbackInfo ci) {
		if (!BENCH) return;
		long now = System.nanoTime();
		MinecraftClient self = (MinecraftClient) (Object) this;
		try {
			this.mcopt$step(self, now);
		} finally {
			this.mcopt$tick = false;
			mcopt.legacy.metal.MetalTerrain.benchUploads = 0;
			mcopt.legacy.metal.MetalTerrain.benchUploadNanos = 0;
		}
	}

	// the slowest 1% of frames: how many ran a client tick, against the share of all frames that did
	@Unique
	private void mcopt$printSlow(long cut) {
		int slow = 0, slowTicked = 0, ticked = 0, gced = 0, slowGced = 0;
		long tickNs = 0, plainNs = 0, uploads = 0, slowUploads = 0, upNs = 0, slowUpNs = 0;
		for (int k = 0; k < this.mcopt$n; k++) {
			boolean t = this.mcopt$ticked[k];
			uploads += this.mcopt$uploads[k];
			upNs += this.mcopt$uploadNs[k];
			if (this.mcopt$gced[k]) gced++;
			if (this.mcopt$frames[k] >= cut) {
				slowUploads += this.mcopt$uploads[k];
				slowUpNs += this.mcopt$uploadNs[k];
				if (this.mcopt$gced[k]) slowGced++;
			}
			if (t) {
				ticked++;
				tickNs += this.mcopt$frames[k];
			} else {
				plainNs += this.mcopt$frames[k];
			}
			if (this.mcopt$frames[k] >= cut) {
				slow++;
				if (t) slowTicked++;
			}
		}
		System.out.printf("[mcopt] fps bench: slowest 1%% (>= %.2f ms): %d frames, %d with a tick; ticks in %d of %d frames;"
			+ " mean %.2f ms with a tick, %.2f ms without%n", cut / 1e6, slow, slowTicked, ticked, this.mcopt$n,
			tickNs / 1e6 / Math.max(1, ticked), plainNs / 1e6 / Math.max(1, this.mcopt$n - ticked));
		System.out.printf("[mcopt] fps bench: GC in %d of the slowest, %d of all; uploads per frame %.2f (%.3f ms) in the slowest,"
			+ " %.2f (%.3f ms) overall%n", slowGced, gced, slowUploads / (double) Math.max(1, slow), slowUpNs / 1e6 / Math.max(1, slow),
			uploads / (double) Math.max(1, this.mcopt$n), upNs / 1e6 / Math.max(1, this.mcopt$n));
	}

	@Unique
	private void mcopt$step(MinecraftClient self, long now) {
		switch (this.mcopt$state) {
		case 0:
			if (this.mcopt$t0 == 0) this.mcopt$t0 = now;
			if (now - this.mcopt$t0 > 3_000_000_000L && this.world == null) {
				self.options.pauseOnLostFocus = false;
				this.mcopt$state = 1;
				this.startIntegratedServer("mcopt-test", "mcopt test", null);
			}
			break;
		case 1:
			if (this.world != null && this.player != null) {
				this.mcopt$state = 2;
				this.mcopt$t0 = now;
			}
			break;
		case 2:
			this.mcopt$spin(now);
			if (Boolean.getBoolean("mcopt.fps.progress") && now - this.mcopt$logged > 2_000_000_000L) {
				this.mcopt$logged = now;
				WorldRendererAccess wr = (WorldRendererAccess) self.worldRenderer;
				System.out.printf("[mcopt] warmup %.0fs: fps %d, visible %d, to rebuild %d%s%n", (now - this.mcopt$t0) / 1e9,
					MinecraftClient.getCurrentFps(), wr.mcopt$visible().size(), wr.mcopt$rebuild().size(),
					mcopt.legacy.metal.MetalTerrain.active ? ", metal " + mcopt.legacy.metal.MetalTerrain.memory() : "");
			}
			if (now - this.mcopt$t0 > WARMUP) {
				this.mcopt$state = SHOT != null ? 5 : 3;
				this.mcopt$t0 = now;
				this.mcopt$n = 0;
			}
			break;
		case 3:
			this.mcopt$spin(now);
			if (this.mcopt$n == 0 && mcopt.legacy.metal.MetalTerrain.active) {
				mcopt.legacy.metal.MetalTerrain.memory();
				String dump = System.getProperty("mcopt.metal.dump");
				if (dump != null) mcopt.legacy.metal.MetalTerrain.dump(dump);
			}
			if (this.mcopt$n == 0 && Boolean.getBoolean("mcopt.fps.sections")) mcopt.legacy.SectionTimes.start();
			if (this.mcopt$n < this.mcopt$frames.length) {
				this.mcopt$ticked[this.mcopt$n] = this.mcopt$tick;
				long gcs = 0;
				for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
					gcs += gc.getCollectionCount();
				}
				this.mcopt$gced[this.mcopt$n] = this.mcopt$n > 0 && gcs != this.mcopt$gcCount;
				this.mcopt$gcCount = gcs;
				this.mcopt$uploads[this.mcopt$n] = mcopt.legacy.metal.MetalTerrain.benchUploads;
				this.mcopt$uploadNs[this.mcopt$n] = mcopt.legacy.metal.MetalTerrain.benchUploadNanos;
				this.mcopt$frames[this.mcopt$n++] = now - this.mcopt$last;
			}
			if (Boolean.getBoolean("mcopt.fps.progress") && now - this.mcopt$logged > 2_000_000_000L) {
				// windowed: fps over the last 2 s, plus what might track a slowdown (sections, memory, GC)
				long gcMs = 0, gcN = 0;
				for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
					gcMs += gc.getCollectionTime();
					gcN += gc.getCollectionCount();
				}
				Runtime rt = Runtime.getRuntime();
				WorldRendererAccess wr = (WorldRendererAccess) self.worldRenderer;
				System.out.printf("[mcopt] measure %.0fs: fps %.1f, visible %d, rebuild %d, heap %d MiB, gc %d/%d ms%s%n",
					(now - this.mcopt$t0) / 1e9, (this.mcopt$n - this.mcopt$loggedN) / ((now - this.mcopt$logged) / 1e9),
					wr.mcopt$visible().size(), wr.mcopt$rebuild().size(), (rt.totalMemory() - rt.freeMemory()) >> 20, gcN, gcMs,
					mcopt.legacy.metal.MetalTerrain.active ? ", metal " + mcopt.legacy.metal.MetalTerrain.memory() : "");
				this.mcopt$logged = now;
				this.mcopt$loggedN = this.mcopt$n;
			}
			if (now - this.mcopt$t0 > SECONDS) {
				long[] f = Arrays.copyOf(this.mcopt$frames, this.mcopt$n);
				Arrays.sort(f);
				double mean = this.mcopt$n / ((now - this.mcopt$t0) / 1e9);
				double low = 1e9 / f[Math.max(0, f.length - 1 - f.length / 100)];
				if (mcopt.legacy.SectionTimes.on) {
					int ticks = 0;
					for (int k = 0; k < this.mcopt$n; k++) if (this.mcopt$ticked[k]) ticks++;
					mcopt.legacy.SectionTimes.print(this.mcopt$n, ticks);
				}
				WorldRendererAccess wr = (WorldRendererAccess) self.worldRenderer;
				System.out.println("[mcopt] fps bench: visible " + wr.mcopt$visible().size() + ", to rebuild " + wr.mcopt$rebuild().size()
					+ (mcopt.legacy.metal.MetalTerrain.active ? ", metal " + mcopt.legacy.metal.MetalTerrain.memory() : ""));
				System.out.printf("[mcopt] fps bench: mean %.1f fps, 1%% low %.1f fps, median %.2f ms, %d frames%n", mean, low,
					f[f.length / 2] / 1e6, this.mcopt$n);
				this.mcopt$printSlow(f[Math.max(0, f.length - 1 - f.length / 100)]);
				this.mcopt$state = 4;
				this.scheduleStop();
			}
			break;
		case 5:
			this.player.yaw = SHOT_YAW;
			this.player.prevYaw = SHOT_YAW;
			this.player.pitch = SHOT_PITCH;
			this.player.prevPitch = SHOT_PITCH;
			if (now - this.mcopt$t0 > 1_000_000_000L) {
				net.minecraft.client.util.ScreenshotUtils.saveScreenshot(self.runDirectory, SHOT + ".png", self.width, self.height,
					self.getFramebuffer());
				this.mcopt$state = 3;
				this.mcopt$t0 = now;
				this.mcopt$n = 0;
			}
			break;
		default:
		}
		this.mcopt$last = now;
	}

	/** A full turn every 8 seconds, level gaze. */
	@Unique
	private void mcopt$spin(long now) {
		if (this.player == null) return;
		if (Boolean.getBoolean("mcopt.fps.still") && this.mcopt$state == 3) {
			this.player.yaw = this.player.prevYaw = 0;  // a still camera: the visibility walk doesn't rerun
			this.player.pitch = this.player.prevPitch = 0;
			return;
		}
		this.player.yaw += (float) ((now - this.mcopt$last) / 1e9 * 45.0);
		this.player.pitch = 0;
	}
}
