package xpncvr.fov360;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class Fov360RearViewScreen extends Screen {
	private static final int BUTTON_WIDTH = 120;
	private static final int ROW_HEIGHT = 20;
	private static final int SPACING = 4;
	private static final int PADDING = 6;
	private static final int MARGIN = 8;
	private static final int PANEL_FILL = 0xB0101418;
	private static final int PANEL_BORDER = 0xFF5A6068;
	private static final int HINT_COLOR = 0xFFB0B4BA;
	private static final int WARNING_COLOR = 0xFFFFFF55;
	private static final Fov360Config DEFAULTS = new Fov360Config();

	private final Screen lastScreen;
	private final Fov360Config config = Fov360Renderer.currentConfig();
	private int panelX, panelY, panelWidth, panelHeight;
	private boolean panelAtTop;
	private boolean relayout = false;

	public Fov360RearViewScreen(Screen lastScreen) {
		super(Component.translatable("fov360.rearView.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		Fov360Renderer.rearOverlayPreview = true;
		config.clampRearOverlay();

		int buttonWidth = Math.min(BUTTON_WIDTH, (width - MARGIN * 2 - PADDING * 2 - SPACING * 2) / 3);
		panelWidth = buttonWidth * 3 + SPACING * 2 + PADDING * 2;
		panelHeight = PADDING + font.lineHeight + SPACING + ROW_HEIGHT * 3 + SPACING * 2 + PADDING;
		panelX = (width - panelWidth) / 2;
		panelAtTop = overlayInLowerHalf();
		panelY = panelAtTop ? MARGIN : height - panelHeight - MARGIN;

		int left = panelX + PADDING;
		int col = buttonWidth + SPACING;
		int row = panelY + PADDING + font.lineHeight + SPACING;

		addOption(OptionInstance.createBoolean("fov360.options.rearOverlay",
				Fov360OptionsScreen.tooltip("fov360.options.rearOverlay.tooltip", Fov360OptionsScreen.onOff(DEFAULTS.rearOverlay)),
				config.rearOverlay,
				value -> config.rearOverlay = value),
			left, row, buttonWidth);
		addOption(OptionInstance.createBoolean("fov360.rearView.mirror",
				Fov360OptionsScreen.tooltip("fov360.rearView.mirror.tooltip", Fov360OptionsScreen.onOff(DEFAULTS.rearOverlayMirror)),
				config.rearOverlayMirror,
				value -> config.rearOverlayMirror = value),
			left + col, row, buttonWidth);
		addOption(OptionInstance.createBoolean("fov360.rearView.border",
				Fov360OptionsScreen.tooltip("fov360.rearView.border.tooltip", Fov360OptionsScreen.onOff(DEFAULTS.rearOverlayBorder)),
				config.rearOverlayBorder,
				value -> config.rearOverlayBorder = value),
			left + col * 2, row, buttonWidth);

		row += ROW_HEIGHT + SPACING;
		addOption(new OptionInstance<>("fov360.rearView.fov",
				Fov360OptionsScreen.tooltip("fov360.rearView.fov.tooltip", degrees(DEFAULTS.rearOverlayFov)),
				(caption, value) -> Options.genericValueLabel(caption, degrees(value)),
				new OptionInstance.IntRange(Fov360Config.MIN_REAR_OVERLAY_FOV, Fov360Config.MAX_REAR_OVERLAY_FOV),
				config.rearOverlayFov,
				value -> config.rearOverlayFov = value),
			left, row, buttonWidth);
		addOption(new OptionInstance<>("fov360.rearView.renderScale",
				Fov360OptionsScreen.tooltip("fov360.rearView.renderScale.tooltip", percent(DEFAULTS.rearOverlayRenderScale)),
				(caption, value) -> Options.genericValueLabel(caption, percent(value)),
				new OptionInstance.IntRange(Fov360Config.MIN_REAR_OVERLAY_SCALE, Fov360Config.MAX_REAR_OVERLAY_SCALE),
				config.rearOverlayRenderScale,
				value -> config.rearOverlayRenderScale = value),
			left + col, row, buttonWidth);
		addOption(new OptionInstance<>("fov360.rearView.fps",
				Fov360OptionsScreen.tooltip("fov360.rearView.fps.tooltip", fps(DEFAULTS.rearOverlayFps)),
				(caption, value) -> value <= 0 ? fps(value) : Options.genericValueLabel(caption, fps(value)),
				new OptionInstance.IntRange(0, Fov360Config.MAX_REAR_OVERLAY_FPS),
				config.rearOverlayFps,
				value -> config.rearOverlayFps = value),
			left + col * 2, row, buttonWidth);

		row += ROW_HEIGHT + SPACING;
		addRenderableWidget(Button.builder(Component.translatable("fov360.rearView.reset"), button -> {
				config.copyRearOverlayFrom(DEFAULTS);
				relayout = true;
			})
			.bounds(left, row, buttonWidth, ROW_HEIGHT)
			.tooltip(Tooltip.create(Component.translatable("fov360.rearView.reset.tooltip")))
			.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(left + col, row, buttonWidth * 2 + SPACING, ROW_HEIGHT)
			.build());

		Fov360RearViewWidget overlay = addRenderableWidget(new Fov360RearViewWidget(config, this::onOverlayMoved));
		overlay.layout(width, height);
	}

	private void addOption(OptionInstance<?> option, int x, int y, int width) {
		AbstractWidget widget = option.createButton(minecraft.options, x, y, width);
		addRenderableWidget(widget);
	}

	private boolean overlayInLowerHalf() {
		return config.rearOverlayY + config.rearOverlayHeight / 2.0F > 0.5F;
	}

	private void onOverlayMoved() {
		if (overlayInLowerHalf() != panelAtTop) {
			relayout = true;
		}
	}

	@Override
	public void tick() {
		if (relayout) {
			relayout = false;
			rebuildWidgets();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level == null) {
			super.extractBackground(graphics, mouseX, mouseY, partialTick);
		}
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_FILL);
		graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);
		boolean blocked = config.splitScreen;
		Component hint = blocked
			? Component.translatable("fov360.rearView.splitWarning").withStyle(ChatFormatting.YELLOW)
			: Component.translatable("fov360.rearView.hint");
		graphics.text(font, hint, panelX + (panelWidth - font.width(hint)) / 2, panelY + PADDING,
			blocked ? WARNING_COLOR : HINT_COLOR, false);
	}

	private static Component degrees(int value) {
		return Component.translatable("fov360.rearView.degrees", value);
	}

	private static Component percent(int value) {
		return Component.translatable("fov360.rearView.percent", value);
	}

	private static Component fps(int value) {
		return value <= 0
			? Component.translatable("fov360.rearView.fps.unlimited")
			: Component.literal(String.valueOf(value));
	}

	@Override
	public void removed() {
		Fov360Renderer.rearOverlayPreview = false;
		config.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
		if (lastScreen instanceof Fov360OptionsScreen optionsScreen) {
			optionsScreen.refresh();
		}
	}
}
