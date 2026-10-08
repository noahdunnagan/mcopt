package mcopt.metal;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MetalOptions implements ConfigEntryPoint {
	private static final Identifier RENDER_SCALE = Identifier.fromNamespaceAndPath("mcopt-metal", "render_scale");
	private static final Identifier FRAME_GENERATION = Identifier.fromNamespaceAndPath("mcopt-metal", "frame_generation");

	public enum RenderScale {
		OFF(1.0F), P85(0.85F), P75(0.75F), P67(0.67F), P50(0.5F);

		final float value;

		RenderScale(float value) {
			this.value = value;
		}

		static RenderScale of(float value) {
			RenderScale best = OFF;
			for (RenderScale s : values()) if (Math.abs(s.value - value) < Math.abs(best.value - value)) best = s;
			return best;
		}
	}

	public enum FrameGeneration {
		OFF("false"), ON("true"), PACED("paced");

		final String mode;

		FrameGeneration(String mode) {
			this.mode = mode;
		}

		static FrameGeneration of(String mode) {
			for (FrameGeneration f : values()) if (f.mode.equals(mode)) return f;
			return OFF;
		}
	}

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions()
			.setName("mcopt Metal")
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("mcopt.options.page"))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(builder.createEnumOption(RENDER_SCALE, RenderScale.class)
						.setStorageHandler(() -> {})
						.setName(Component.translatable("mcopt.options.render_scale"))
						.setTooltip(Component.translatable("mcopt.options.render_scale.tooltip"))
						.setElementNameProvider(s -> s == RenderScale.OFF ? Component.translatable("options.off") : Component.literal(Math.round(s.value * 100) + "%"))
						.setDefaultValue(RenderScale.OFF)
						.setImpact(OptionImpact.HIGH)
						.setEnabled(Versioned.metalActive())
						.setBinding(s -> {
							MetalFx.setScale(s.value);
							Profile.save("mcopt.fx.scale", Float.toString(s.value));
						}, () -> RenderScale.of(MetalFx.scale())))
					.addOption(builder.createEnumOption(FRAME_GENERATION, FrameGeneration.class)
						.setStorageHandler(() -> {})
						.setName(Component.translatable("mcopt.options.frame_generation"))
						.setTooltip(Component.translatable("mcopt.options.frame_generation.tooltip"))
						.setElementNameProvider(f -> Component.translatable("mcopt.options.frame_generation." + f.name().toLowerCase(java.util.Locale.ROOT)))
						.setDefaultValue(FrameGeneration.OFF)
						.setImpact(OptionImpact.MEDIUM)
						.setEnabledProvider(state -> Versioned.metalActive() && state.readEnumOption(RENDER_SCALE, RenderScale.class) == RenderScale.OFF, RENDER_SCALE)
						.setBinding(f -> {
							FrameGen.setMode(f.mode);
							Profile.save("mcopt.metal.framegen", f.mode);
						}, () -> FrameGeneration.of(FrameGen.mode())))));
	}
}
