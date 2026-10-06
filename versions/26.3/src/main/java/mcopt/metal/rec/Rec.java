package mcopt.metal.rec;

import com.mojang.renderpearl.api.textures.GpuTextureView;

/**
 * The opt-in in-engine recorder: -Dmcopt.rec=/path/out.mp4 records the finished frames (GUI included, so an F3 fps overlay
 * is in the video) into an H.264 mp4, sampled on the wall clock so the video plays in real time however fast the game runs.
 * Off by default: ON is a constant false without the switch, the hook in MetalSurface.blitFromTexture folds away and nothing
 * here loads. Options and the pipeline: see Recorder.
 */
public final class Rec {
	/** -Dmcopt.rec=OUT.mp4 */
	public static final boolean ON = System.getProperty("mcopt.rec") != null;
	private static Recorder recorder;
	private static boolean failed;

	private Rec() {
	}

	/** The hook: called with every frame the game hands to the window (the backend's encoder, the frame's color target). */
	public static void frame(Object encoder, GpuTextureView view) {
		if (failed) return;
		try {
			if (recorder == null) recorder = new Recorder(encoder, view);
			recorder.frame(encoder, view);
		} catch (Throwable t) {
			failed = true;
			System.out.println("mcopt-rec: recorder off after an error: " + t);
			t.printStackTrace(System.out);
			if (recorder != null) recorder.abort();
		}
	}
}
