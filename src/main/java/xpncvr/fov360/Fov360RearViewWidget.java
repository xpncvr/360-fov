package xpncvr.fov360;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

public final class Fov360RearViewWidget extends AbstractWidget {
	private static final int GRAB = 4;
	private static final int HANDLE = 2;
	private static final float KEY_STEP = 0.005F;
	private static final float KEY_STEP_LARGE = 0.05F;
	private static final int ACTIVE_COLOR = 0xFFFFFFFF;
	private static final int IDLE_COLOR = 0xC0E0E0E0;
	private static final int SHADOW_COLOR = 0xFF000000;
	private static final int EMPTY_FILL = 0xC0101418;
	private static final int TEXT_COLOR = 0xFFFFFFFF;

	private final Fov360Config config;
	private final Runnable onRelease;
	private int screenWidth = 1;
	private int screenHeight = 1;
	private boolean dragging = false;
	private boolean dragLeft, dragRight, dragTop, dragBottom;
	private double startMouseX, startMouseY;
	private float startX, startY, startWidth, startHeight;

	public Fov360RearViewWidget(Fov360Config config, Runnable onRelease) {
		super(0, 0, 0, 0, Component.translatable("fov360.rearView.widget"));
		this.config = config;
		this.onRelease = onRelease;
	}

	public void layout(int screenWidth, int screenHeight) {
		this.screenWidth = Math.max(1, screenWidth);
		this.screenHeight = Math.max(1, screenHeight);
		syncBounds();
	}

	private int left() {
		return Math.round(config.rearOverlayX * screenWidth);
	}

	private int top() {
		return Math.round(config.rearOverlayY * screenHeight);
	}

	private int right() {
		return Math.round((config.rearOverlayX + config.rearOverlayWidth) * screenWidth);
	}

	private int bottom() {
		return Math.round((config.rearOverlayY + config.rearOverlayHeight) * screenHeight);
	}

	private void syncBounds() {
		config.clampRearOverlay();
		setX(left() - GRAB);
		setY(top() - GRAB);
		setWidth(right() - left() + GRAB * 2);
		setHeight(bottom() - top() + GRAB * 2);
	}

	private boolean nearLeft(double x) {
		return Math.abs(x - left()) <= GRAB;
	}

	private boolean nearRight(double x) {
		return Math.abs(x - right()) <= GRAB;
	}

	private boolean nearTop(double y) {
		return Math.abs(y - top()) <= GRAB;
	}

	private boolean nearBottom(double y) {
		return Math.abs(y - bottom()) <= GRAB;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		dragLeft = nearLeft(event.x());
		dragRight = !dragLeft && nearRight(event.x());
		dragTop = nearTop(event.y());
		dragBottom = !dragTop && nearBottom(event.y());
		startMouseX = event.x();
		startMouseY = event.y();
		startX = config.rearOverlayX;
		startY = config.rearOverlayY;
		startWidth = config.rearOverlayWidth;
		startHeight = config.rearOverlayHeight;
		dragging = true;
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging) {
			return;
		}
		float dx = (float) ((event.x() - startMouseX) / screenWidth);
		float dy = (float) ((event.y() - startMouseY) / screenHeight);
		float min = Fov360Config.MIN_REAR_OVERLAY_SIZE;
		if (!dragLeft && !dragRight && !dragTop && !dragBottom) {
			config.rearOverlayX = Mth.clamp(startX + dx, 0.0F, 1.0F - startWidth);
			config.rearOverlayY = Mth.clamp(startY + dy, 0.0F, 1.0F - startHeight);
		} else {
			float x0 = startX;
			float x1 = startX + startWidth;
			float y0 = startY;
			float y1 = startY + startHeight;
			if (dragLeft) {
				x0 = Mth.clamp(x0 + dx, 0.0F, x1 - min);
			}
			if (dragRight) {
				x1 = Mth.clamp(x1 + dx, x0 + min, 1.0F);
			}
			if (dragTop) {
				y0 = Mth.clamp(y0 + dy, 0.0F, y1 - min);
			}
			if (dragBottom) {
				y1 = Mth.clamp(y1 + dy, y0 + min, 1.0F);
			}
			config.rearOverlayX = x0;
			config.rearOverlayY = y0;
			config.rearOverlayWidth = x1 - x0;
			config.rearOverlayHeight = y1 - y0;
		}
		syncBounds();
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			onRelease.run();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		float step = event.hasControlDown() ? KEY_STEP_LARGE : KEY_STEP;
		float dx = 0.0F;
		float dy = 0.0F;
		switch (event.key()) {
			case InputConstants.KEY_LEFT -> dx = -step;
			case InputConstants.KEY_RIGHT -> dx = step;
			case InputConstants.KEY_UP -> dy = -step;
			case InputConstants.KEY_DOWN -> dy = step;
			default -> {
				return false;
			}
		}
		if (event.hasShiftDown()) {
			config.rearOverlayWidth += dx;
			config.rearOverlayHeight += dy;
		} else {
			config.rearOverlayX += dx;
			config.rearOverlayY += dy;
		}
		syncBounds();
		onRelease.run();
		return true;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		int l = left();
		int t = top();
		int r = right();
		int b = bottom();

		if (client.level == null) {
			graphics.fill(l, t, r, b, EMPTY_FILL);
			Component empty = Component.translatable("fov360.rearView.noWorld");
			graphics.text(font, empty, (l + r - font.width(empty)) / 2, (t + b - font.lineHeight) / 2, TEXT_COLOR, false);
		}

		boolean active = dragging || isHovered() || isFocused();
		int color = active ? ACTIVE_COLOR : IDLE_COLOR;
		graphics.outline(l - 2, t - 2, r - l + 4, b - t + 4, SHADOW_COLOR);
		graphics.outline(l - 1, t - 1, r - l + 2, b - t + 2, color);

		int mx = (l + r) / 2;
		int my = (t + b) / 2;
		for (int[] p : new int[][] {{l, t}, {mx, t}, {r, t}, {l, my}, {r, my}, {l, b}, {mx, b}, {r, b}}) {
			graphics.fill(p[0] - HANDLE - 1, p[1] - HANDLE - 1, p[0] + HANDLE + 2, p[1] + HANDLE + 2, SHADOW_COLOR);
			graphics.fill(p[0] - HANDLE, p[1] - HANDLE, p[0] + HANDLE + 1, p[1] + HANDLE + 1, color);
		}

		Window window = client.getWindow();
		Component size = Component.translatable("fov360.rearView.size",
			Math.round(config.rearOverlayWidth * window.getWidth()),
			Math.round(config.rearOverlayHeight * window.getHeight()));
		int labelY = b + GRAB + 2 + font.lineHeight <= screenHeight ? b + GRAB + 2 : t - GRAB - 2 - font.lineHeight;
		graphics.text(font, size, l, labelY, TEXT_COLOR, true);

		if (!dragging && isHovered()) {
			graphics.setComponentTooltipForNextFrame(font, List.of(Component.translatable("fov360.rearView.hint")), mouseX, mouseY);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
		output.add(NarratedElementType.USAGE, Component.translatable("fov360.rearView.keysHint"));
	}
}
