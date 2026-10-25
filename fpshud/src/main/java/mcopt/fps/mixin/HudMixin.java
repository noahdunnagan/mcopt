package mcopt.fps.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "NN fps" in the top-left corner while playing. Hidden with the HUD (F1) and while F3 shows its own counter.
 * {@code mcopt.fps=false} in config/mcopt.properties (or -Dmcopt.fps=false) turns it off.
 */
@Mixin(Hud.class)
abstract class HudMixin {
	@Unique private static final boolean mcoptFps$on = Boolean.parseBoolean(System.getProperty("mcopt.fps", "true"));

	@Shadow @Final private Minecraft minecraft;
	@Shadow public abstract boolean isHidden();

	// The count changes once a second; the label is rebuilt only then, not every frame.
	@Unique private int mcoptFps$shown = -1;
	@Unique private String mcoptFps$label = "";

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void mcoptFps$draw(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!mcoptFps$on || this.isHidden() || this.minecraft.getDebugOverlay().showDebugScreen() || this.minecraft.gui.screen() instanceof LevelLoadingScreen) {
			return;
		}
		int fps = this.minecraft.getFps();
		if (fps != this.mcoptFps$shown) {
			this.mcoptFps$shown = fps;
			this.mcoptFps$label = fps + " fps";
		}
		graphics.text(this.minecraft.font, this.mcoptFps$label, 2, 2, 0xFFFFFFFF, true);
	}
}
