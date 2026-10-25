package mcopt.metal.own;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.zip.CRC32;
import mcopt.metal.mixin.own.OwnAnimAtlasAccess;
import mcopt.metal.mixin.own.OwnAnimSpriteAccess;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * -Dmcopt.own.int.animCopy=true: texture animations drawn without a render pass over each whole atlas mip.
 *
 * Vanilla's TextureAtlas.uploadAnimationFrames opens, on every tick that has anything to draw, one render pass per mip level on the
 * whole mip of the atlas (the blocks atlas: 2048^2 down to 128^2) and draws the animated sprites into it. On a tile-based GPU each such
 * pass loads and stores the entire mip: ~25 MB in and out per tick for the blocks, GUI and particle atlases, about 0.4 ms of GPU on
 * the base M4, which the frame after each tick waits for (the mini's spin 1% lows). Here the same draws go into a small scratch
 * texture instead (the animated sprites packed on shelves, the atlas's format and mip count), and each drawn sprite's rectangle is
 * then copied into the atlas at the same mip.
 *
 * Exact by construction: the draws are vanilla's own (SpriteContents.AnimationState.drawToAtlas: its pipeline, frame textures and
 * sampler) with vanilla's uniforms except two numbers: the projection's size (the scratch's mip instead of the atlas's) and the
 * quad's position (the sprite's place in the scratch instead of the atlas; both whole pixels, aligned to 2^maxMip so every mip's
 * position stays whole). The quad's size, its texture coordinates, the padding and the mip level are vanilla's. The copy writes exactly
 * the pixels the quad covers. -Dmcopt.own.int.animHash=N: every N ticks the blocks atlas's mips are read back and their CRC logged,
 * to compare against vanilla's path tick for tick.
 */
public final class AnimCopy {
	public static final boolean ON = Boolean.getBoolean("mcopt.own.int.animCopy");
	/** -Dmcopt.own.int.animNoCopy (measurement only, NOT exact): the scratch draws without the copies into the atlas. */
	private static final boolean NO_COPY = Boolean.getBoolean("mcopt.own.int.animNoCopy");
	private static final int HASH_EVERY = Integer.getInteger("mcopt.own.int.animHash", 0);
	/** -Dmcopt.own.int.animVerify=N: every N-th drawing tick vanilla draws the atlas as usual and the same sprites go into the scratch too; both are read back and every drawn sprite's rectangle compared pixel for pixel. */
	public static final int VERIFY_EVERY = Integer.getInteger("mcopt.own.int.animVerify", 0);
	private static final Map<TextureAtlas, int[]> VERIFY_COUNTS = new WeakHashMap<>();
	private static long verifyRects, verifyPixels, verifyBad;
	private static int verifyMax;

	private static final class Layout {
		List<SpriteContents.AnimationState> states;
		List<TextureAtlasSprite> sprites;
		int maxMip, width, height;
		int[] sx, sy, pw, ph;
		GpuTexture scratch;
		GpuTextureView[] views;
		GpuBuffer ubos;
		int stride;
	}

	/**
	 * -Dmcopt.own.int.atlasWrite (measurement first): the animated atlases (blocks, gui, particles) made with shader-write usage, which
	 * a compute copy into them needs and which turns off the GPU's lossless compression of them. True while one of them is created.
	 */
	public static final boolean ATLAS_WRITE = Boolean.getBoolean("mcopt.own.int.atlasWrite") && coresOk();
	/**
	 * -Dmcopt.own.int.animMinCores=N: atlasWrite (and with it the one-pass animation's compute copy) only on a GPU with at least N cores
	 * (IORegistry gpu-core-count, as -Dmcopt.own.frag.uoccMaxMpCores reads it; unreadable: off). Measured 2026-10-06: the M4 mini (10
	 * cores) gains +12.6% spin 1% lows, the M6 (12) neither gains nor loses, the A18 Pro Neo (5) loses ~7% of its flight 1% lows to
	 * the atlases' lost lossless compression.
	 */
	public static final int MIN_CORES = Integer.getInteger("mcopt.own.int.animMinCores", 0);

	private static boolean coresOk() {
		// (reads the property itself: ATLAS_WRITE, which calls this, is initialised before MIN_CORES)
		int min = Integer.getInteger("mcopt.own.int.animMinCores", 0);
		if (min <= 0 || !Boolean.getBoolean("mcopt.own.int.atlasWrite")) return true;
		int cores = mcopt.metal.Profile.gpuCores();
		boolean ok = cores >= min;
		System.out.println("mcopt-own: atlasWrite / one-pass animation " + (ok ? "on" : "off") + " (gpu cores " + (cores < 0 ? "unknown" : cores)
			+ (ok ? " >= " : " < ") + min + ")");
		return ok;
	}
	public static volatile boolean creatingAnimatedAtlas;

	private static boolean isAnimatedAtlas(TextureAtlas atlas) {
		String path = atlas.location().getPath();
		return path.endsWith("atlas/blocks.png") || path.endsWith("atlas/gui.png") || path.endsWith("atlas/particles.png");
	}

	public static void creatingAtlas(TextureAtlas atlas, boolean on) {
		String path = atlas.location().getPath();
		creatingAnimatedAtlas = on && ATLAS_WRITE && isAnimatedAtlas(atlas);
		if (on && creatingAnimatedAtlas) System.out.println("mcopt-own: shader-write usage on " + atlas.location());
	}

	private static final Map<TextureAtlas, Layout> LAYOUTS = new WeakHashMap<>();
	private static final Map<TextureAtlas, int[]> TICKS = new WeakHashMap<>();
	private static boolean warned;

	private AnimCopy() {
	}

	/** In place of TextureAtlas.uploadAnimationFrames: true when done here (vanilla's is skipped), false to let vanilla's run. */
	public static boolean upload(TextureAtlas atlas) {
		if (!ON && VERIFY_EVERY <= 0) return false;
		OwnAnimAtlasAccess a = (OwnAnimAtlasAccess) atlas;
		List<SpriteContents.AnimationState> states = a.mcopt$animationStates();
		boolean any = false;
		for (SpriteContents.AnimationState s : states) any |= s.needsToDraw();
		if (!any) return true;  // vanilla does nothing either
		Layout l = layout(atlas, a, states);
		if (l == null) return false;
		boolean verify = VERIFY_EVERY > 0 && ++VERIFY_COUNTS.computeIfAbsent(atlas, k -> new int[1])[0] % VERIFY_EVERY == 0;  // (per atlas: they draw on different ticks)
		if (!ON && !verify) return false;
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		int n = states.size();
		boolean[] drawn = new boolean[n];
		for (int i = 0; i < n; i++) drawn[i] = states.get(i).needsToDraw();
		GpuTexture target = atlas.getTexture();
		for (int mip = 0; mip <= l.maxMip; mip++) {
			try (RenderPass pass = encoder.createRenderPass(() -> "mcopt animation copy", l.views[mip], Optional.of(new Vector4f(0, 0, 0, 0)))) {
				RenderSystem.bindDefaultUniforms(pass);
				for (int i = 0; i < n; i++) {
					if (drawn[i]) states.get(i).drawToAtlas(pass, l.ubos.slice((long) (i * (l.maxMip + 1) + mip) * l.stride, SpriteContents.UBO_SIZE));
				}
			}
		}
		// then the copies: with shader-write atlases (-Dmcopt.own.int.atlasWrite), one compute dispatch a mip; else blits, all mips in one run
		OwnTerrain own = OwnTerrain.get();
		if (!verify && !NO_COPY && ATLAS_WRITE && own != null && isAnimatedAtlas(atlas)) {
			long src = mcopt.metal.MetalBridge.textureHandle(l.scratch), dst = mcopt.metal.MetalBridge.textureHandle(target);
			// (on the heap: a resource pack's atlas can animate thousands of sprites, more than LWJGL's 64 KB stack holds)
			long rects = MemoryUtil.nmemAllocChecked(n * 24L);
			try {
				boolean ok = true;
				for (int mip = 0; mip <= l.maxMip && ok; mip++) {
					int count = 0, maxW = 0, maxH = 0;
					for (int i = 0; i < n; i++) {
						int w = l.pw[i] >> mip, h = l.ph[i] >> mip;
						if (!drawn[i] || w == 0 || h == 0) continue;
						TextureAtlasSprite s = l.sprites.get(i);
						long r = rects + count * 24L;
						MemoryUtil.memPutInt(r, l.sx[i] >> mip);
						MemoryUtil.memPutInt(r + 4, l.sy[i] >> mip);
						MemoryUtil.memPutInt(r + 8, s.getX() >> mip);
						MemoryUtil.memPutInt(r + 12, s.getY() >> mip);
						MemoryUtil.memPutInt(r + 16, w);
						MemoryUtil.memPutInt(r + 20, h);
						maxW = Math.max(maxW, w);
						maxH = Math.max(maxH, h);
						count++;
					}
					ok = OwnNative.animCopy(own.ownHandle(), own.encHandle(), src, dst, mip, rects, count, maxW, maxH);
				}
				if (ok) {
					if (VERIFY_COPY_EVERY > 0 && ++VERIFY_COPY_COUNTS.computeIfAbsent(atlas, k -> new int[1])[0] % VERIFY_COPY_EVERY == 0) compare(atlas, l, drawn);
					return true;
				}
			} finally {
				MemoryUtil.nmemFree(rects);
			}
		}
		// (blits: all mips in one run, one render-to-blit switch a tick instead of one a mip)
		for (int mip = 0; mip <= l.maxMip && !verify && !NO_COPY; mip++) {
			for (int i = 0; i < n; i++) {
				int w = l.pw[i] >> mip, h = l.ph[i] >> mip;
				if (!drawn[i] || w == 0 || h == 0) continue;
				TextureAtlasSprite s = l.sprites.get(i);
				encoder.copyTextureToTexture(l.scratch, target, mip, s.getX() >> mip, s.getY() >> mip, l.sx[i] >> mip, l.sy[i] >> mip, w, h);
			}
		}
		if (verify) {
			pending.put(atlas, drawn);
			return false;  // vanilla's draws follow; afterVanilla reads both back
		}
		if (VERIFY_COPY_EVERY > 0 && !NO_COPY && ++VERIFY_COPY_COUNTS.computeIfAbsent(atlas, k -> new int[1])[0] % VERIFY_COPY_EVERY == 0) compare(atlas, l, drawn);
		return true;
	}

	private static final Map<TextureAtlas, boolean[]> pending = new WeakHashMap<>();

	/** After TextureAtlas.uploadAnimationFrames: on a verify tick, the scratch and the atlas read back and every drawn rectangle compared. */
	public static void afterVanilla(TextureAtlas atlas) {
		boolean[] drawn = pending.remove(atlas);
		if (drawn == null) return;
		Layout l = LAYOUTS.get(atlas);
		if (l == null) return;
		compare(atlas, l, drawn);
	}

	/** -Dmcopt.own.int.animVerifyCopy=N: every N-th drawing tick, after the full path (scratch draws + copies), the atlas and the scratch read back and every copied rectangle compared. */
	private static final int VERIFY_COPY_EVERY = Integer.getInteger("mcopt.own.int.animVerifyCopy", 0);
	private static final Map<TextureAtlas, int[]> VERIFY_COPY_COUNTS = new WeakHashMap<>();

	private static void compare(TextureAtlas atlas, Layout l, boolean[] drawn) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuTexture atlasTex = atlas.getTexture();
		int bpp = atlasTex.getFormat().blockSize();
		for (int mip = 0; mip <= l.maxMip; mip++) {
			int m = mip, aw = l.width >> mip, ah = l.height >> mip, sw = l.scratch.getWidth(mip), sh = l.scratch.getHeight(mip);
			ByteBuffer[] got = new ByteBuffer[2];
			GpuBuffer ab = device.createBuffer(() -> "mcopt animation verify atlas", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) aw * ah * bpp);
			GpuBuffer sb = device.createBuffer(() -> "mcopt animation verify scratch", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) sw * sh * bpp);
			Runnable compare = () -> {
				if (got[0] == null || got[1] == null) return;
				for (int i = 0; i < drawn.length; i++) {
					int w = l.pw[i] >> m, h = l.ph[i] >> m;
					if (!drawn[i] || w == 0 || h == 0) continue;
					TextureAtlasSprite s = l.sprites.get(i);
					int ax = s.getX() >> m, ay = s.getY() >> m, sx = l.sx[i] >> m, sy = l.sy[i] >> m;
					verifyRects++;
					for (int yy = 0; yy < h; yy++) {
						for (int xx = 0; xx < w; xx++) {
							int pa = ((ay + yy) * aw + ax + xx) * bpp, ps = ((sy + yy) * sw + sx + xx) * bpp, d = 0;
							for (int c = 0; c < bpp; c++) d = Math.max(d, Math.abs((got[0].get(pa + c) & 255) - (got[1].get(ps + c) & 255)));
							verifyPixels++;
							if (d != 0) verifyBad++;
							verifyMax = Math.max(verifyMax, d);
						}
					}
				}
				System.out.println((VERIFY_COPY_EVERY > 0 ? "mcopt-animverifycopy " : "mcopt-animverify ") + atlas.location() + " mip " + m + ": rects " + verifyRects + ", pixels " + verifyPixels + ", different " + verifyBad + ", max diff " + verifyMax);
				MemoryUtil.memFree(got[0]);
				MemoryUtil.memFree(got[1]);
				got[0] = got[1] = null;
			};
			encoder.copyTextureToBuffer(atlasTex, ab, 0, () -> {
				try (GpuBufferSlice.MappedView v = ab.map(true, false)) {
					got[0] = MemoryUtil.memAlloc(v.data().remaining()).put(v.data()).flip();
				}
				ab.close();
				compare.run();
			}, mip);
			encoder.copyTextureToBuffer(l.scratch, sb, 0, () -> {
				try (GpuBufferSlice.MappedView v = sb.map(true, false)) {
					got[1] = MemoryUtil.memAlloc(v.data().remaining()).put(v.data()).flip();
				}
				sb.close();
				compare.run();
			}, mip);
		}
	}

	private static @Nullable Layout layout(TextureAtlas atlas, OwnAnimAtlasAccess a, List<SpriteContents.AnimationState> states) {
		Layout l = LAYOUTS.get(atlas);
		if (l != null && l.states == states) return l;
		if (l != null) close(l);
		LAYOUTS.remove(atlas);
		List<TextureAtlasSprite> sprites = new ArrayList<>();
		for (TextureAtlasSprite s : a.mcopt$sprites()) if (s.isAnimated()) sprites.add(s);
		if (sprites.size() != states.size()) {
			if (!warned) System.out.println("mcopt-own: animation copy off for " + atlas.location() + " (sprites " + sprites.size() + ", states " + states.size() + ")");
			warned = true;
			return null;
		}
		l = new Layout();
		l.states = states;
		l.sprites = sprites;
		l.maxMip = a.mcopt$maxMipLevel();
		l.width = a.mcopt$width();
		l.height = a.mcopt$height();
		int n = sprites.size(), align = 1 << l.maxMip;
		l.sx = new int[n];
		l.sy = new int[n];
		l.pw = new int[n];
		l.ph = new int[n];
		int widest = align;
		for (int i = 0; i < n; i++) {
			TextureAtlasSprite s = sprites.get(i);
			int p = ((OwnAnimSpriteAccess) s).mcopt$padding();
			l.pw[i] = s.contents().width() + 2 * p;
			l.ph[i] = s.contents().height() + 2 * p;
			widest = Math.max(widest, roundUp(l.pw[i], align));
		}
		int sw = Math.max(256, Integer.highestOneBit(widest - 1) << 1), x = 0, y = 0, shelf = 0;
		for (int i = 0; i < n; i++) {
			int w = roundUp(l.pw[i], align), h = roundUp(l.ph[i], align);
			if (x + w > sw) {
				x = 0;
				y += shelf;
				shelf = 0;
			}
			l.sx[i] = x;
			l.sy[i] = y;
			x += w;
			shelf = Math.max(shelf, h);
		}
		int sh = Math.max(align, Integer.highestOneBit(Math.max(1, y + shelf) - 1) << 1);
		if (sw > l.width || sh > l.height) {
			if (!warned) System.out.println("mcopt-own: animation copy off for " + atlas.location() + " (scratch " + sw + "x" + sh + " larger than the atlas)");
			warned = true;
			return null;
		}
		GpuDevice device = RenderSystem.getDevice();
		GpuTexture t = atlas.getTexture();
		l.scratch = device.createTexture(() -> "mcopt animation scratch " + atlas.location(),
			GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, t.getFormat(), sw, sh, 1, l.maxMip + 1);
		l.views = new GpuTextureView[l.maxMip + 1];
		for (int mip = 0; mip <= l.maxMip; mip++) l.views[mip] = device.createTextureView(l.scratch, mip, 1);
		l.stride = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		ByteBuffer data = MemoryUtil.memAlloc(l.stride * n * (l.maxMip + 1));
		try {
			for (int i = 0; i < n; i++) {
				TextureAtlasSprite s = sprites.get(i);
				int p = ((OwnAnimSpriteAccess) s).mcopt$padding(), w = s.contents().width(), h = s.contents().height();
				for (int mip = 0; mip <= l.maxMip; mip++) {
					// vanilla's TextureAtlasSprite.uploadSpriteUbo, with the scratch's size and the sprite's place in it
					Std140Builder.intoBuffer(MemoryUtil.memSlice(data, (i * (l.maxMip + 1) + mip) * l.stride, l.stride))
						.putMat4f(new Matrix4f().ortho2D(0.0F, (float) (sw >> mip), 0.0F, (float) (sh >> mip)))
						.putMat4f(new Matrix4f().translate((float) (l.sx[i] >> mip), (float) (l.sy[i] >> mip), 0.0F)
							.scale((float) (w + p * 2 >> mip), (float) (h + p * 2 >> mip), 1.0F))
						.putFloat((float) p / (float) w)
						.putFloat((float) p / (float) h)
						.putInt(mip);
				}
			}
			l.ubos = device.createBuffer(() -> "mcopt animation copy UBOs " + atlas.location(), GpuBuffer.USAGE_UNIFORM, data);
		} finally {
			MemoryUtil.memFree(data);
		}
		LAYOUTS.put(atlas, l);
		System.out.println("mcopt-own: animation copy for " + atlas.location() + ": " + n + " sprites, scratch " + sw + "x" + sh + ", " + (l.maxMip + 1) + " mips");
		if (Boolean.getBoolean("mcopt.own.int.animLayout")) {
			StringBuilder b = new StringBuilder("mcopt-own: animation sprites of " + atlas.location() + " (name x y w h):");
			for (int i = 0; i < n; i++) b.append("\n  ").append(sprites.get(i).contents().name()).append(' ').append(sprites.get(i).getX()).append(' ')
				.append(sprites.get(i).getY()).append(' ').append(l.pw[i]).append(' ').append(l.ph[i]);
			System.out.println(b);
		}
		return l;
	}

	private static int roundUp(int v, int a) {
		return (v + a - 1) / a * a;
	}

	private static void close(Layout l) {
		for (GpuTextureView v : l.views) v.close();
		l.scratch.close();
		l.ubos.close();
	}

	/** After TextureAtlas.tick: with -Dmcopt.own.int.animHash=N, the blocks atlas's mips read back and CRC'd every N ticks. */
	public static void ticked(TextureAtlas atlas) {
		if (HASH_EVERY <= 0 || !atlas.location().getPath().endsWith("blocks.png")) return;
		int tick = ++TICKS.computeIfAbsent(atlas, k -> new int[1])[0];
		if (tick % HASH_EVERY != 0) return;
		OwnAnimAtlasAccess a = (OwnAnimAtlasAccess) atlas;
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuTexture t = atlas.getTexture();
		int bpp = t.getFormat().blockSize();
		for (int mip = 0; mip <= a.mcopt$maxMipLevel(); mip++) {
			int m = mip, w = a.mcopt$width() >> mip, h = a.mcopt$height() >> mip;
			GpuBuffer b = device.createBuffer(() -> "mcopt animation hash", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
			encoder.copyTextureToBuffer(t, b, 0, () -> {
				try (GpuBufferSlice.MappedView v = b.map(true, false)) {
					CRC32 crc = new CRC32();
					crc.update(v.data());
					System.out.println("mcopt-animhash tick=" + tick + " mip=" + m + " crc=" + Long.toHexString(crc.getValue()));
				}
				b.close();
			}, mip);
		}
	}
}
