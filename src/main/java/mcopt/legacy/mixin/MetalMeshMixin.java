package mcopt.legacy.mixin;

import mcopt.legacy.metal.MetalMesh;
import mcopt.legacy.metal.MetalTerrain;
import net.minecraft.client.render.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where a chunk vertex buffer's mesh lives in MetalTerrain's pages; the range is freed with the buffer. */
@Mixin(VertexBuffer.class)
abstract class MetalMeshMixin implements MetalMesh {
	@Unique private int mcopt$range = -1;
	@Unique private int mcopt$class;
	@Unique private int mcopt$count;
	@Unique private final int[] mcopt$groups = new int[13];
	@Unique private int[] mcopt$units = new int[0];
	@Unique private int mcopt$slot = -1;
	@Unique private int mcopt$slotClass;
	@Unique private int mcopt$unitCount;
	@Unique private int mcopt$layer;
	@Unique private boolean mcopt$active;

	@Override
	public int mcopt$slot() {
		return this.mcopt$slot;
	}

	@Override
	public int mcopt$slotClass() {
		return this.mcopt$slotClass;
	}

	@Override
	public int mcopt$unitCount() {
		return this.mcopt$unitCount;
	}

	@Override
	public int mcopt$layer() {
		return this.mcopt$layer;
	}

	@Override
	public boolean mcopt$active() {
		return this.mcopt$active;
	}

	@Override
	public void mcopt$slots(int layer, int slot, int slotClass, int unitCount, boolean active) {
		this.mcopt$layer = layer;
		this.mcopt$slot = slot;
		this.mcopt$slotClass = slotClass;
		this.mcopt$unitCount = unitCount;
		this.mcopt$active = active;
	}

	@Override
	public int[] mcopt$units() {
		return this.mcopt$units;
	}

	@Override
	public void mcopt$units(int[] units) {
		this.mcopt$units = units;
	}

	@Override
	public int[] mcopt$groups() {
		return this.mcopt$groups;
	}

	@Override
	public int mcopt$range() {
		return this.mcopt$range;
	}

	@Override
	public int mcopt$class() {
		return this.mcopt$class;
	}

	@Override
	public int mcopt$count() {
		return this.mcopt$count;
	}

	@Override
	public void mcopt$set(int range, int sizeClass, int count) {
		this.mcopt$range = range;
		this.mcopt$class = sizeClass;
		this.mcopt$count = count;
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void mcopt$release(CallbackInfo ci) {
		MetalTerrain.release(this);
	}
}
