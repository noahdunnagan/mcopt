package mcopt.metal;

/** Lets mcopt.metal.cpu.AbShots switch the texel-view cache per frame (it lives in this package). */
public final class TexelViewsSwitch {
	private TexelViewsSwitch() {
	}

	public static void set(boolean on) {
		TexelViews.active = on;
	}

	/** The native pass-begin lever (mc_cpu_flags), for AbShots. */
	public static void pass(boolean on) {
		Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags(on));
	}
}
