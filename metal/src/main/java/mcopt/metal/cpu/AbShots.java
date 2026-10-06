package mcopt.metal.cpu;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * -Dmcopt.cpu.abShots=texel|model (with the lever's own flag on): a same-run picture check. In the bench's settle hold (camera and
 * world frozen, terrain settled; use -Dmcopt.bench.settleHold=6) four consecutive frames are grabbed with the lever off, on,
 * off, on (screenshots/ab-off1.png, ab-on1.png, ab-off2.png, ab-on2.png). off1 vs off2 is the frame-to-frame floor of the frozen
 * scene; on vs off is the lever. The lever goes back on afterwards.
 */
public final class AbShots {
	public static final String WHAT = System.getProperty("mcopt.cpu.abShots", "");
	public static final boolean ON = !WHAT.isEmpty();
	private static final String[] NAMES = {"ab-off1.png", "ab-on1.png", "ab-off2.png", "ab-on2.png"};
	private static Field settledAt;
	private static boolean looked, done;
	private static long start = -1;
	private static int step = -1;

	private AbShots() {
	}

	private static long settledAt() {
		if (!looked) {
			looked = true;
			try {
				settledAt = Class.forName("mcopt.bench.Bench").getDeclaredField("settledAt");
				settledAt.setAccessible(true);
			} catch (ReflectiveOperationException e) {
				settledAt = null;
			}
		}
		try {
			return settledAt == null ? 0 : settledAt.getLong(null);
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static void lever(boolean on) {
		switch (WHAT) {
			case "texel" -> mcopt.metal.TexelViewsSwitch.set(on);
			case "model" -> Cpu.modelActive = on;
			case "leash" -> Cpu.leashActive = on;
			case "fence" -> Cpu.fenceActive = on;
			case "pass" -> mcopt.metal.TexelViewsSwitch.pass(on);
			default -> { }
		}
	}

	/** Frame start: in the hold, set the lever for this frame. */
	public static void frameStart() {
		if (done || !"SETTLE".equals(Cpu.phase())) return;
		long s = settledAt();
		if (s == 0 || System.nanoTime() - s < 1_000_000_000L) return;
		if (start < 0) start = 0;
		start++;
		step = start >= 10 && start < 14 ? (int) (start - 10) : -1; // four consecutive frames after a 10-frame lead-in
		if (step >= 0 && step < 4) lever(step % 2 == 1);
	}

	/** Frame end: grab the frame this step drew. */
	public static void frameEnd() {
		if (done || step < 0 || step >= 4) return;
		Minecraft mc = Minecraft.getInstance();
		Screenshot.grab(mc.gameDirectory, NAMES[step], mc.gameRenderer.mainRenderTarget(), 1, message -> { });
		System.out.println("mcopt-cpu: abShots " + WHAT + " grabbed " + NAMES[step]);
		if (step == 3) {
			done = true;
			lever(true);
		}
		step = -1;
	}
}
