package mcopt.legacy.mixin;

import net.minecraft.client.render.BaseFrustum;
import net.minecraft.client.render.CullingCameraView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The frustum and camera offset behind CullingCameraView, so TerrainSetupMixin can run the same box test inline. */
@Mixin(CullingCameraView.class)
public interface CullingCameraViewAccess {
	@Accessor("clipper")
	BaseFrustum mcopt$clipper();

	@Accessor("x")
	double mcopt$x();

	@Accessor("y")
	double mcopt$y();

	@Accessor("z")
	double mcopt$z();
}
