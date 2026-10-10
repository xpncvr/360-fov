package xpncvr.fov360;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class Fov360ProjectionScreen extends Screen {
	private static final int CONTENT_WIDTH = 360;
	private static final int REMOVE_WIDTH = 60;
	private static final int BLEND_WIDTH = 130;
	private static final int FOOTER_BUTTON_WIDTH = 110;

	private static boolean preview = false;

	private final Screen lastScreen;
	private final Fov360Config config = Fov360Renderer.currentConfig();
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	private Fov360ThresholdWidget thresholds;
	private CycleButton<Fov360Projection> projectionButton;
	private CycleButton<Fov360Blend> blendButton;
	private Button removeButton;
	private MultiLineTextWidget info;

	public Fov360ProjectionScreen(Screen lastScreen) {
		super(Component.translatable("fov360.thresholds.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);

		int contentWidth = Math.min(width - 40, CONTENT_WIDTH);
		LinearLayout content = layout.addToContents(LinearLayout.vertical().spacing(6));
		content.addChild(new MultiLineTextWidget(Component.translatable("fov360.thresholds.help"), font).setMaxWidth(contentWidth));
		thresholds = content.addChild(new Fov360ThresholdWidget(contentWidth, 46, config.projectionPoints, this::updateControls));

		LinearLayout row = content.addChild(LinearLayout.horizontal().spacing(4));
		int projectionWidth = contentWidth - BLEND_WIDTH - REMOVE_WIDTH - 8;
		projectionButton = row.addChild(CycleButton.builder(Fov360Projection::displayName, Fov360Projection.RECTILINEAR)
			.withValues(Fov360Projection.values())
			.create(0, 0, projectionWidth, 20, Component.translatable("fov360.thresholds.projection"), (button, value) -> {
				thresholds.selectedPoint().projection = value;
				updateControls();
			}));
		blendButton = row.addChild(CycleButton.builder(Fov360Blend::displayName, Fov360Blend.EASE_OUT)
			.withValues(Fov360Blend.values())
			.create(0, 0, BLEND_WIDTH, 20, Component.translatable("fov360.thresholds.blend"), (button, value) -> {
				thresholds.selectedPoint().blend = value;
				updateControls();
			}));
		removeButton = row.addChild(Button.builder(Component.translatable("fov360.thresholds.remove"), button -> thresholds.removeSelected())
			.width(REMOVE_WIDTH)
			.build());

		info = content.addChild(new MultiLineTextWidget(Component.empty(), font).setMaxWidth(contentWidth));

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("fov360.thresholds.reset"), button -> confirmReset())
			.width(FOOTER_BUTTON_WIDTH)
			.tooltip(Tooltip.create(Component.translatable("fov360.thresholds.reset.tooltip")
				.append(CommonComponents.NEW_LINE)
				.append(Fov360OptionsScreen.defaultLine(defaultPointsSummary()))))
			.build());
		footer.addChild(CycleButton.onOffBuilder(preview)
			.withTooltip(value -> Tooltip.create(Component.translatable("fov360.thresholds.preview.tooltip")
				.append(CommonComponents.NEW_LINE)
				.append(Fov360OptionsScreen.defaultLine(CommonComponents.OPTION_OFF))))
			.create(0, 0, FOOTER_BUTTON_WIDTH, 20, Component.translatable("fov360.thresholds.preview"), (button, value) -> preview = value));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(FOOTER_BUTTON_WIDTH).build());

		layout.visitWidgets(this::addRenderableWidget);
		updateControls();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!preview || minecraft.level == null) {
			super.extractBackground(graphics, mouseX, mouseY, partialTick);
		}
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	private void updateControls() {
		Fov360Config.ProjectionPoint point = thresholds.selectedPoint();
		boolean first = thresholds.selectedIndex() == 0;

		projectionButton.setValue(point.projection);
		blendButton.setValue(point.blend);
		blendButton.active = !first;
		blendButton.setTooltip(Tooltip.create(first
			? Component.translatable("fov360.thresholds.blend.first")
			: Component.translatable("fov360.thresholds.blend.tooltip", config.projectionPoints.get(thresholds.selectedIndex() - 1).projection.displayName(), point.projection.displayName())
				.append(CommonComponents.NEW_LINE)
				.append(Fov360OptionsScreen.defaultLine(Fov360Blend.EASE_OUT.displayName()))));
		removeButton.active = config.projectionPoints.size() > 1;

		MutableComponent text = Component.translatable("fov360.thresholds.info", thresholds.selectedIndex() + 1, config.projectionPoints.size(), Fov360Config.MAX_POINTS);
		if (point.projection.hasMaxFov()) {
			text.append(CommonComponents.NEW_LINE).append(Component.translatable("fov360.thresholds.limit",
				point.projection.displayName(), Math.round(point.projection.maxFov())).withStyle(ChatFormatting.YELLOW));
		}
		info.setMessage(text);
		layout.arrangeElements();
	}

	private Component defaultPointsSummary() {
		MutableComponent summary = Component.empty();
		boolean first = true;
		for (Fov360Config.ProjectionPoint point : Fov360Config.defaultProjectionPoints()) {
			if (!first) {
				summary.append(", ");
			}
			summary.append(Component.translatable("fov360.thresholds.selected", point.fov, point.projection.displayName()));
			first = false;
		}
		return summary;
	}

	private void confirmReset() {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				config.projectionPoints.clear();
				config.projectionPoints.addAll(Fov360Config.defaultProjectionPoints());
				thresholds.select(0);
				updateControls();
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("fov360.thresholds.reset.confirm"), Component.translatable("fov360.reset.warning")));
	}

	@Override
	public void removed() {
		config.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(lastScreen);
	}
}
