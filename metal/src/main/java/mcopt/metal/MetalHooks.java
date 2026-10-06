package mcopt.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * The backend's seams for redirecting and labelling render passes: inert unless something installs a redirector or a
 * labeler, which nothing in this build does.
 */
public final class MetalHooks {
	/**
	 * Redirected attachments for a pass, and the delegate that draws into them. clears: per color attachment, a clear
	 * color for its load action or null to load it. depth 0 keeps the pass's own depth attachment; otherwise that texture
	 * replaces it (cleared to depthClear unless NaN, else loaded) and the pass covers the whole width x height.
	 */
	public record Redirect(long @Nullable [] colors, float @Nullable [][] clears, int width, int height, long depth, float depthClear, PassDelegate delegate) {
		/** No depth clear (depthClear is only read when depth replaces the pass's own). */
		public static final float NO_CLEAR = Float.NaN;

		/** The pass keeps its own attachments (colors, depth, clears); only its pipelines go through the delegate. */
		public static Redirect keep(PassDelegate delegate) {
			return new Redirect(null, null, 0, 0, 0, NO_CLEAR, delegate);
		}

		public boolean keepsAttachments() {
			return this.colors == null;
		}
	}

	/** Decides for each render pass whether it is taken over. */
	public interface PassRedirector {
		@Nullable Redirect redirect(RenderPassDescriptor descriptor);
	}

	static volatile @Nullable PassRedirector redirector;

	private MetalHooks() {
	}

	/** Names a native render encoder (for GPU traces). */
	public interface Labeler {
		void label(long enc, String name);
	}

	/** When set, every render pass's encoder gets its descriptor's label. */
	static volatile @Nullable Labeler labeler;

	public static void setLabeler(@Nullable Labeler l) {
		labeler = l;
	}

	public static void setRedirector(@Nullable PassRedirector r) {
		redirector = r;
	}
}
