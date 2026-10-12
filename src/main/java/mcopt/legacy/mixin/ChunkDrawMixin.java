package mcopt.legacy.mixin;

import java.nio.IntBuffer;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexBuffer;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.render.world.VboChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Draws chunk sections as indexed triangles instead of quads. Chunk vertex buffers hold quads, and on macOS the OpenGL
 * driver runs on Metal, which has no quads: every glDrawArrays(GL_QUADS) made the driver build a triangle list on the
 * CPU first, for every section, every frame. Here the sections are drawn with glDrawElements from one shared index
 * buffer that splits each quad (v0 v1 v2 v3) into (v0 v1 v2) and (v2 v3 v0). The vertex data, the per-section
 * transform and the draw order are vanilla's.
 */
@Mixin(VboChunkRenderManager.class)
abstract class ChunkDrawMixin extends AbstractChunkRenderManager {
	@Shadow
	private void method_9929() {
	}

	/** The shared quad index buffer, and how many quads it covers. */
	@Unique private static int mcopt$indexBuffer;
	@Unique private static int mcopt$quads;

	@Unique
	private static void mcopt$bindIndices(int quads) {
		if (mcopt$indexBuffer == 0) {
			mcopt$indexBuffer = GL15.glGenBuffers();
		}
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mcopt$indexBuffer);
		if (quads > mcopt$quads) {
			int n = Math.max(quads, 16384);
			n = Math.max(n, mcopt$quads * 2);
			IntBuffer indices = BufferUtils.createIntBuffer(n * 6);
			for (int q = 0; q < n; q++) {
				int v = q * 4;
				indices.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
			}
			indices.flip();
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
			mcopt$quads = n;
		}
	}

	/**
	 * @author mcopt
	 * @reason Indexed triangles instead of GL_QUADS; see the class comment.
	 */
	@Overwrite
	public void render(RenderLayer layer) {
		if (this.field_10667) {
			int largest = 0;
			for (int n = 0, size = this.helpers.size(); n < size; n++) {
				int vertices = ((VertexBufferAccess) this.helpers.get(n).method_10165(layer.ordinal())).mcopt$size();
				if (vertices > largest) largest = vertices;
			}
			mcopt$bindIndices(largest / 4);

			for (int n = 0, size = this.helpers.size(); n < size; n++) {
				BuiltChunk builtChunk = this.helpers.get(n);
				VertexBuffer vertexBuffer = builtChunk.method_10165(layer.ordinal());
				int quads = ((VertexBufferAccess) vertexBuffer).mcopt$size() / 4;
				GlStateManager.pushMatrix();
				this.method_9770(builtChunk);
				builtChunk.method_10169();
				vertexBuffer.bind();
				this.method_9929();
				if (quads > 0) {
					GL11.glDrawElements(GL11.GL_TRIANGLES, quads * 6, GL11.GL_UNSIGNED_INT, 0L);
				}
				GlStateManager.popMatrix();
			}

			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
			GLX.gl15BindBuffer(GLX.arrayBuffer, 0);
			GlStateManager.clearColor();
			this.helpers.clear();
		}
	}
}
