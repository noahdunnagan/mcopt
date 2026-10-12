package mcopt.legacy.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fancy clouds are rebuilt every frame in vanilla: some 4,000 quads, sent in 128 separate draws (64 cells, each drawn
 * twice: depth first, then color). Their shape only changes when the cloud texture scrolls by a whole cell, when the
 * camera crosses the heights where the top or bottom faces appear, or when the cloud color changes by a step of the
 * 8-bit vertex color; between those, the clouds only slide by a fraction of a cell and move with the camera's height.
 *
 * So the 64 cells are compiled once into a display list, placed at a whole cell and at height 0, and each frame draws
 * that list twice, translated by the fraction and the height. The vertices, texture coordinates and colors are the
 * ones vanilla would send, except that the fraction and height are added by the matrix instead of to each vertex.
 */
@Mixin(WorldRenderer.class)
abstract class CloudCacheMixin {
	@Shadow @Final private static Identifier CLOUDS;
	@Shadow @Final private MinecraftClient client;
	@Shadow @Final private TextureManager textureManager;
	@Shadow private ClientWorld world;
	@Shadow private int ticks;

	@Unique private static final boolean OFF = Boolean.getBoolean("mcopt.vanillaClouds");  // dev: vanilla's clouds, to compare
	@Unique private int mcopt$list = -1;
	@Unique private int mcopt$cellX, mcopt$cellZ, mcopt$faces = -1;
	@Unique private final int[] mcopt$colors = new int[4];

	@Inject(method = "renderFancyClouds", at = @At("HEAD"), cancellable = true)
	private void mcopt$cachedClouds(float tickDelta, int anaglyphFilter, CallbackInfo ci) {
		if (OFF) return;
		ci.cancel();
		GlStateManager.disableCull();
		Entity cam = this.client.getCameraEntity();
		float f = (float)(cam.prevTickY + (cam.y - cam.prevTickY) * tickDelta);
		double d = this.ticks + tickDelta;
		double e = (cam.prevX + (cam.x - cam.prevX) * tickDelta + d * 0.03F) / 12.0;
		double i = (cam.prevZ + (cam.z - cam.prevZ) * tickDelta) / 12.0 + 0.33F;
		float j = this.world.dimension.getCloudHeight() - f + 0.33F;
		int k = MathHelper.floor(e / 2048.0);
		int l = MathHelper.floor(i / 2048.0);
		e -= k * 2048;
		i -= l * 2048;
		this.textureManager.bindTexture(CLOUDS);
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(770, 771, 1, 0);
		Vec3d vec3d = this.world.getCloudColor(tickDelta);
		float m = (float)vec3d.x;
		float n = (float)vec3d.y;
		float o = (float)vec3d.z;
		if (anaglyphFilter != 2) {
			float p = (m * 30.0F + n * 59.0F + o * 11.0F) / 100.0F;
			float q = (m * 30.0F + n * 70.0F) / 100.0F;
			float r = (m * 30.0F + o * 70.0F) / 100.0F;
			m = p;
			n = q;
			o = r;
		}

		int cellX = MathHelper.floor(e);
		int cellZ = MathHelper.floor(i);
		float ab = (float)(e - cellX);
		float ac = (float)(i - cellZ);
		int faces = (j > -5.0F ? 1 : 0) | (j <= 5.0F ? 2 : 0);
		// the colors as the vertex format stores them (BufferBuilder.color keeps (int)(c * 255) of each)
		int c0 = mcopt$rgb(m, n, o, 1.0F), c1 = mcopt$rgb(m, n, o, 0.9F), c2 = mcopt$rgb(m, n, o, 0.7F), c3 = mcopt$rgb(m, n, o, 0.8F);
		if (this.mcopt$list < 0 || cellX != this.mcopt$cellX || cellZ != this.mcopt$cellZ || faces != this.mcopt$faces
			|| c0 != this.mcopt$colors[0] || c1 != this.mcopt$colors[1] || c2 != this.mcopt$colors[2] || c3 != this.mcopt$colors[3]) {
			if (this.mcopt$list < 0) this.mcopt$list = GL11.glGenLists(1);
			this.mcopt$cellX = cellX;
			this.mcopt$cellZ = cellZ;
			this.mcopt$faces = faces;
			this.mcopt$colors[0] = c0;
			this.mcopt$colors[1] = c1;
			this.mcopt$colors[2] = c2;
			this.mcopt$colors[3] = c3;
			GL11.glNewList(this.mcopt$list, GL11.GL_COMPILE);
			mcopt$build(cellX * 0.00390625F, cellZ * 0.00390625F, faces, m, n, o);
			GL11.glEndList();
		}

		GlStateManager.scale(12.0F, 1.0F, 12.0F);
		GlStateManager.pushMatrix();
		GlStateManager.translate(-ab, j, -ac);
		for (int ag = 0; ag < 2; ag++) {
			if (ag == 0) {
				GlStateManager.colorMask(false, false, false, false);
			} else {
				switch (anaglyphFilter) {
					case 0:
						GlStateManager.colorMask(false, true, true, true);
						break;
					case 1:
						GlStateManager.colorMask(true, false, false, true);
						break;
					case 2:
						GlStateManager.colorMask(true, true, true, true);
				}
			}
			GL11.glCallList(this.mcopt$list);
		}
		GlStateManager.popMatrix();

		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.disableBlend();
		GlStateManager.enableCull();
	}

	@Unique
	private static int mcopt$rgb(float r, float g, float b, float shade) {
		return (int)(r * shade * 255.0F) << 16 | (int)(g * shade * 255.0F) << 8 | (int)(b * shade * 255.0F);
	}

	/** Vanilla's 64 cells at a fraction and height of 0, in vanilla's order, as one draw. */
	@Unique
	private static void mcopt$build(float z, float aa, int faces, float m, float n, float o) {
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder b = tessellator.getBuffer();
		float p = m * 0.9F, q = n * 0.9F, r = o * 0.9F;
		float s = m * 0.7F, t = n * 0.7F, u = o * 0.7F;
		float v = m * 0.8F, w = n * 0.8F, x = o * 0.8F;
		float y = 0.00390625F;
		float eps = 9.765625E-4F;
		b.begin(7, VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
		for (int ah = -3; ah <= 4; ah++) {
			for (int ai = -3; ai <= 4; ai++) {
				float aj = ah * 8;
				float ak = ai * 8;
				if ((faces & 1) != 0) {
					b.vertex(aj, 0.0F, ak + 8.0F).texture(aj * y + z, (ak + 8.0F) * y + aa).color(s, t, u, 0.8F).normal(0.0F, -1.0F, 0.0F).next();
					b.vertex(aj + 8.0F, 0.0F, ak + 8.0F).texture((aj + 8.0F) * y + z, (ak + 8.0F) * y + aa).color(s, t, u, 0.8F).normal(0.0F, -1.0F, 0.0F).next();
					b.vertex(aj + 8.0F, 0.0F, ak).texture((aj + 8.0F) * y + z, ak * y + aa).color(s, t, u, 0.8F).normal(0.0F, -1.0F, 0.0F).next();
					b.vertex(aj, 0.0F, ak).texture(aj * y + z, ak * y + aa).color(s, t, u, 0.8F).normal(0.0F, -1.0F, 0.0F).next();
				}
				if ((faces & 2) != 0) {
					float top = 4.0F - eps;
					b.vertex(aj, top, ak + 8.0F).texture(aj * y + z, (ak + 8.0F) * y + aa).color(m, n, o, 0.8F).normal(0.0F, 1.0F, 0.0F).next();
					b.vertex(aj + 8.0F, top, ak + 8.0F).texture((aj + 8.0F) * y + z, (ak + 8.0F) * y + aa).color(m, n, o, 0.8F).normal(0.0F, 1.0F, 0.0F).next();
					b.vertex(aj + 8.0F, top, ak).texture((aj + 8.0F) * y + z, ak * y + aa).color(m, n, o, 0.8F).normal(0.0F, 1.0F, 0.0F).next();
					b.vertex(aj, top, ak).texture(aj * y + z, ak * y + aa).color(m, n, o, 0.8F).normal(0.0F, 1.0F, 0.0F).next();
				}
				if (ah > -1) {
					for (int an = 0; an < 8; an++) {
						float px = aj + an, tu = (aj + an + 0.5F) * y + z;
						b.vertex(px, 0.0F, ak + 8.0F).texture(tu, (ak + 8.0F) * y + aa).color(p, q, r, 0.8F).normal(-1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 4.0F, ak + 8.0F).texture(tu, (ak + 8.0F) * y + aa).color(p, q, r, 0.8F).normal(-1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 4.0F, ak).texture(tu, ak * y + aa).color(p, q, r, 0.8F).normal(-1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 0.0F, ak).texture(tu, ak * y + aa).color(p, q, r, 0.8F).normal(-1.0F, 0.0F, 0.0F).next();
					}
				}
				if (ah <= 1) {
					for (int an = 0; an < 8; an++) {
						float px = aj + an + 1.0F - eps, tu = (aj + an + 0.5F) * y + z;
						b.vertex(px, 0.0F, ak + 8.0F).texture(tu, (ak + 8.0F) * y + aa).color(p, q, r, 0.8F).normal(1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 4.0F, ak + 8.0F).texture(tu, (ak + 8.0F) * y + aa).color(p, q, r, 0.8F).normal(1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 4.0F, ak).texture(tu, ak * y + aa).color(p, q, r, 0.8F).normal(1.0F, 0.0F, 0.0F).next();
						b.vertex(px, 0.0F, ak).texture(tu, ak * y + aa).color(p, q, r, 0.8F).normal(1.0F, 0.0F, 0.0F).next();
					}
				}
				if (ai > -1) {
					for (int an = 0; an < 8; an++) {
						float pz = ak + an, tv = (ak + an + 0.5F) * y + aa;
						b.vertex(aj, 4.0F, pz).texture(aj * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, -1.0F).next();
						b.vertex(aj + 8.0F, 4.0F, pz).texture((aj + 8.0F) * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, -1.0F).next();
						b.vertex(aj + 8.0F, 0.0F, pz).texture((aj + 8.0F) * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, -1.0F).next();
						b.vertex(aj, 0.0F, pz).texture(aj * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, -1.0F).next();
					}
				}
				if (ai <= 1) {
					for (int an = 0; an < 8; an++) {
						float pz = ak + an + 1.0F - eps, tv = (ak + an + 0.5F) * y + aa;
						b.vertex(aj, 4.0F, pz).texture(aj * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, 1.0F).next();
						b.vertex(aj + 8.0F, 4.0F, pz).texture((aj + 8.0F) * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, 1.0F).next();
						b.vertex(aj + 8.0F, 0.0F, pz).texture((aj + 8.0F) * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, 1.0F).next();
						b.vertex(aj, 0.0F, pz).texture(aj * y + z, tv).color(v, w, x, 0.8F).normal(0.0F, 0.0F, 1.0F).next();
					}
				}
			}
		}
		tessellator.draw();
	}
}
