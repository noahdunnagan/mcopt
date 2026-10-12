package mcopt.legacy.mixin;

import net.minecraft.client.render.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A chunk vertex buffer's vertex count, for ChunkDrawMixin. */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccess {
	@Accessor("size")
	int mcopt$size();
}
