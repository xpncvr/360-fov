package xpncvr.fov360;

import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class Fov360OptionsScreen extends OptionsSubScreen {
	private static final int FACE_SIZE_STEP = 128;
	private static final Fov360Config DEFAULTS = new Fov360Config();

	private final Fov360Config config = Fov360Renderer.currentConfig();

	public Fov360OptionsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, Component.translatable("fov360.options.title"));
	}

	@Override
	protected void addOptions() {
		list.addHeader(Component.translatable("fov360.options.general"));
		list.addSmall(
			OptionInstance.createBoolean("fov360.options.splitScreen",
				tooltip("fov360.options.splitScreen.tooltip", onOff(DEFAULTS.splitScreen)),
				config.splitScreen,
				value -> config.splitScreen = value),
			OptionInstance.createBoolean("fov360.options.invertSplitScreen",
				tooltip("fov360.options.invertSplitScreen.tooltip", onOff(DEFAULTS.invertSplitScreen)),
				config.invertSplitScreen,
				value -> config.invertSplitScreen = value),
			new OptionInstance<>("fov360.options.faceSizeCap",
				tooltip("fov360.options.faceSizeCap.tooltip", Component.literal(String.valueOf(DEFAULTS.faceSizeCap))),
				(caption, value) -> Options.genericValueLabel(caption, value * FACE_SIZE_STEP),
				new OptionInstance.IntRange(256 / FACE_SIZE_STEP, 4096 / FACE_SIZE_STEP),
				Math.clamp(Math.round(config.faceSizeCap / (float) FACE_SIZE_STEP), 256 / FACE_SIZE_STEP, 4096 / FACE_SIZE_STEP),
				value -> config.faceSizeCap = value * FACE_SIZE_STEP),
			OptionInstance.createBoolean("fov360.options.lowResTopBottomFaces",
				tooltip("fov360.options.lowResTopBottomFaces.tooltip", onOff(DEFAULTS.lowResTopBottomFaces)),
				config.lowResTopBottomFaces,
				value -> config.lowResTopBottomFaces = value),
			new OptionInstance<>("fov360.options.antialiasSamples",
				tooltip("fov360.options.antialiasSamples.tooltip", Component.literal(String.valueOf(DEFAULTS.antialiasSamples))),
				Options::genericValueLabel,
				new OptionInstance.Enum<>(List.of(1, 2, 4), Codec.INT),
				config.antialiasSamples >= 4 ? 4 : config.antialiasSamples >= 2 ? 2 : 1,
				value -> config.antialiasSamples = value)
		);

		list.addHeader(Component.translatable("fov360.options.projections"));
		list.addBig(Button.builder(Component.translatable("fov360.options.editThresholds"),
				button -> minecraft.gui.setScreen(new Fov360ProjectionScreen(this)))
			.tooltip(Tooltip.create(Component.translatable("fov360.options.editThresholds.tooltip")))
			.build());
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("fov360.options.reset"), button -> confirmReset())
			.width(150)
			.tooltip(Tooltip.create(Component.translatable("fov360.options.reset.tooltip")))
			.build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(150).build());
	}

	private void confirmReset() {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				config.copyFrom(new Fov360Config());
				minecraft.gui.setScreen(new Fov360OptionsScreen(lastScreen, options));
			} else {
				minecraft.gui.setScreen(this);
			}
		}, Component.translatable("fov360.options.reset.confirm"), Component.translatable("fov360.reset.warning")));
	}

	@Override
	public void removed() {
		super.removed();
		config.save();
	}

	static Component defaultLine(Component value) {
		return Component.translatable("fov360.options.default", value).withStyle(ChatFormatting.GRAY);
	}

	private static Component onOff(boolean value) {
		return value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
	}

	private static <T> OptionInstance.TooltipSupplier<T> tooltip(String key, Component defaultValue) {
		return OptionInstance.cachedConstantTooltip(Component.translatable(key)
			.append(CommonComponents.NEW_LINE)
			.append(defaultLine(defaultValue)));
	}
}
