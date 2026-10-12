package mcopt.legacy.mixin;

import mcopt.legacy.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "NN fps" in the top-left corner while playing. Hidden with the HUD (F1) and while F3 shows its own counter. */
@Mixin(InGameHud.class)
abstract class HudMixin {
	@Shadow @Final private MinecraftClient client;

	@Unique private static final boolean ENABLED = Config.flag("fpsHud", true);
	// The count changes once a second; the label is rebuilt only then, not every frame.
	@Unique private int mcopt$shown = -1;
	@Unique private String mcopt$label = "";

	@Inject(method = "render", at = @At("TAIL"))
	private void mcopt$drawFps(float tickDelta, CallbackInfo ci) {
		if (!ENABLED || this.client.options.hudHidden || this.client.options.debugEnabled) return;
		int fps = MinecraftClient.getCurrentFps();
		if (fps != this.mcopt$shown) {
			this.mcopt$shown = fps;
			this.mcopt$label = fps + " fps";
		}
		this.client.textRenderer.drawWithShadow(this.mcopt$label, 2, 2, 0xFFFFFFFF);
	}
}
