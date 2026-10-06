package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * -Dmcopt.cpu.texelCache=true: texel-buffer views (MTLTextures over a buffer range) kept across frames. MetalRenderPass
 * keeps them per pass object, so a uniform bound every frame (Sodium's u_SectionTimeInfo) made a new driver texture each frame.
 * Keyed by the MetalBuffer, the MTLBuffer it currently sits on (rename() moves it), offset, length and format, so a hit is a view
 * over exactly the same storage; a view no submit has used for 3 submits is released (by then no frame in flight uses it).
 */
final class TexelViews {
	static final boolean ON = Boolean.getBoolean("mcopt.cpu.texelCache");
	/** Per-frame switch for the same-run picture check (AbShots); always true otherwise. */
	static volatile boolean active = true;
	private static final int KEEP_SUBMITS = 3;

	private record Key(MetalBuffer buffer, long memory, long offset, long length, int format) {
	}

	private static final class View {
		final long handle;
		long lastSubmit;

		View(long handle) {
			this.handle = handle;
		}
	}

	private static final Map<Key, View> views = new HashMap<>();
	private static long lastSweep = -1;
	static long created, hits;

	private TexelViews() {
	}

	static long get(MetalEncoder encoder, GpuBufferSlice slice, int pixelFormat, int blockSize) {
		MetalBuffer buffer = encoder.use(slice.buffer());
		long submit = buffer.lastUse; // use() just stamped it with the current submit index
		if (submit != lastSweep) {
			lastSweep = submit;
			for (Iterator<View> it = views.values().iterator(); it.hasNext(); ) {
				View v = it.next();
				if (submit - v.lastSubmit > KEEP_SUBMITS) {
					encoder.releaseLater(v.handle);
					it.remove();
				}
			}
		}
		Key key = new Key(buffer, buffer.handle, slice.offset(), slice.length(), pixelFormat);
		View v = views.get(key);
		if (v == null) {
			v = new View(Native.textureBuffer(encoder.ctx, buffer.handle, pixelFormat, slice.offset(), slice.length(), blockSize));
			views.put(key, v);
			created++;
		} else {
			hits++;
		}
		v.lastSubmit = submit;
		return v.handle;
	}
}
