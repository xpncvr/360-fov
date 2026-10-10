package xpncvr.fov360;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public final class Fov360ThresholdWidget extends AbstractWidget {
	private static final int PADDING = 8;
	private static final int BAND_TOP = 16;
	private static final int BAND_HEIGHT = 14;
	private static final int HANDLE_HALF_WIDTH = 3;
	private static final int HANDLE_OVERHANG = 4;
	private static final int GRAB_DISTANCE = 5;
	private static final int DASH = 2;
	private static final int[] TICKS = {30, 90, 180, 270, 360, 400};

	private final List<Fov360Config.ProjectionPoint> points;
	private final Runnable onChange;
	private int selected = 0;
	private boolean dragging = false;

	public Fov360ThresholdWidget(int width, int height, List<Fov360Config.ProjectionPoint> points, Runnable onChange) {
		super(0, 0, width, height, Component.translatable("fov360.thresholds.widget"));
		this.points = points;
		this.onChange = onChange;
	}

	public Fov360Config.ProjectionPoint selectedPoint() {
		selected = Mth.clamp(selected, 0, points.size() - 1);
		return points.get(selected);
	}

	public int selectedIndex() {
		selectedPoint();
		return selected;
	}

	public void select(int index) {
		selected = Mth.clamp(index, 0, points.size() - 1);
	}

	public boolean removeSelected() {
		if (points.size() <= 1) {
			return false;
		}
		points.remove(selectedIndex());
		select(selected - 1);
		onChange.run();
		return true;
	}

	private int trackLeft() {
		return getX() + PADDING;
	}

	private int trackRight() {
		return getRight() - PADDING;
	}

	private int bandTop() {
		return getY() + BAND_TOP;
	}

	private int bandBottom() {
		return bandTop() + BAND_HEIGHT;
	}

	private int fovToX(float fov) {
		float t = (fov - Fov360Config.MIN_POINT_FOV) / (Fov360Config.MAX_POINT_FOV - Fov360Config.MIN_POINT_FOV);
		return trackLeft() + Math.round(t * (trackRight() - trackLeft()));
	}

	private int xToFov(double x) {
		double t = (x - trackLeft()) / (double) (trackRight() - trackLeft());
		int fov = (int) Math.round(Fov360Config.MIN_POINT_FOV + t * (Fov360Config.MAX_POINT_FOV - Fov360Config.MIN_POINT_FOV));
		return Mth.clamp(fov, Fov360Config.MIN_POINT_FOV, Fov360Config.MAX_POINT_FOV);
	}

	private int pointAt(double mouseX, double mouseY) {
		if (mouseY < bandTop() - HANDLE_OVERHANG - 2 || mouseY > bandBottom() + HANDLE_OVERHANG + 2) {
			return -1;
		}
		int best = -1;
		double bestDistance = GRAB_DISTANCE + 1;
		for (int i = 0; i < points.size(); i++) {
			double distance = Math.abs(mouseX - fovToX(points.get(i).fov));
			if (distance <= GRAB_DISTANCE && distance < bestDistance) {
				best = i;
				bestDistance = distance;
			}
		}
		return best;
	}

	private boolean overTrack(double mouseX, double mouseY) {
		return mouseX >= trackLeft() && mouseX <= trackRight()
			&& mouseY >= bandTop() - HANDLE_OVERHANG && mouseY <= bandBottom() + HANDLE_OVERHANG;
	}

	private int minFovFor(int index) {
		return index == 0 ? Fov360Config.MIN_POINT_FOV : points.get(index - 1).fov + 1;
	}

	private int maxFovFor(int index) {
		return index == points.size() - 1 ? Fov360Config.MAX_POINT_FOV : points.get(index + 1).fov - 1;
	}

	private void moveSelected(int fov) {
		int index = selectedIndex();
		int clamped = Mth.clamp(fov, minFovFor(index), maxFovFor(index));
		if (points.get(index).fov != clamped) {
			points.get(index).fov = clamped;
			onChange.run();
		}
	}

	private int insertPoint(int fov) {
		if (points.size() >= Fov360Config.MAX_POINTS) {
			return -1;
		}
		int index = 0;
		while (index < points.size() && points.get(index).fov < fov) {
			index++;
		}
		if (index < points.size() && points.get(index).fov == fov) {
			return -1;
		}
		Fov360Config.ProjectionBlend blend = Fov360Config.sample(points, fov);
		Fov360Projection projection = blend.t() < 0.5F ? blend.from() : blend.to();
		points.add(index, new Fov360Config.ProjectionPoint(fov, projection, Fov360Blend.EASE_OUT));
		return index;
	}

	private void setPlayerFov(int horizontalFov) {
		Minecraft client = Minecraft.getInstance();
		client.options.fov().set(Fov360Renderer.sliderFovFor(client, horizontalFov));
		client.options.save();
	}

	@Override
	protected boolean isValidClickButton(MouseButtonInfo info) {
		return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		int hit = pointAt(event.x(), event.y());
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (hit >= 0) {
				select(hit);
				removeSelected();
			} else if (overTrack(event.x(), event.y())) {
				setPlayerFov(xToFov(event.x()));
			}
			return;
		}
		if (hit < 0 && overTrack(event.x(), event.y())) {
			hit = insertPoint(xToFov(event.x()));
		}
		if (hit >= 0) {
			select(hit);
			dragging = true;
			onChange.run();
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			moveSelected(xToFov(event.x()));
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		dragging = false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int step = event.hasShiftDown() ? 10 : 1;
		if (event.isLeft()) {
			moveSelected(selectedPoint().fov - step);
			return true;
		}
		if (event.isRight()) {
			moveSelected(selectedPoint().fov + step);
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
			return removeSelected();
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int left = trackLeft();
		int right = trackRight();
		int top = bandTop();
		int bottom = bandBottom();

		graphics.fill(getX(), getY(), getRight(), getBottom(), 0xA0101418);
		graphics.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? 0xFFFFFFFF : 0xFF5A6068);

		for (int x = left; x < right; x++) {
			Fov360Config.ProjectionBlend blend = Fov360Config.sample(points, xToFov(x + 0.5));
			int color = ARGB.srgbLerp(blend.t(), blend.from().color(), blend.to().color());
			graphics.fill(x, top, x + 1, bottom, color);
		}
		graphics.outline(left - 1, top - 1, right - left + 2, bottom - top + 2, 0xFF000000);

		for (int tick : TICKS) {
			int x = fovToX(tick);
			graphics.fill(x, bottom + 1, x + 1, bottom + 4, 0xFFB0B4BA);
			String label = tick + "°";
			int labelX = Mth.clamp(x - font.width(label) / 2, getX() + 2, getRight() - 2 - font.width(label));
			graphics.text(font, label, labelX, bottom + 6, 0xFFB0B4BA, false);
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			float current = Fov360Renderer.effectiveFov(client);
			if (current >= Fov360Config.MIN_POINT_FOV) {
				int x = fovToX(Math.min(current, Fov360Config.MAX_POINT_FOV));
				for (int y = top - HANDLE_OVERHANG - 2; y < bottom + HANDLE_OVERHANG + 2; y += DASH * 2) {
					graphics.fill(x, y, x + 1, Math.min(y + DASH, bottom + HANDLE_OVERHANG + 2), 0xFFFFFFFF);
				}
			}
			Component currentLabel = Component.translatable("fov360.thresholds.current", Math.round(current));
			graphics.text(font, currentLabel, getRight() - PADDING - font.width(currentLabel), getY() + 4, 0xFFFFFFFF, false);
		}

		int hovered = isHovered() ? pointAt(mouseX, mouseY) : -1;
		for (int i = 0; i < points.size(); i++) {
			Fov360Config.ProjectionPoint point = points.get(i);
			int x = fovToX(point.fov);
			boolean isSelected = i == selected;
			int border = isSelected ? 0xFFFFFFFF : i == hovered ? 0xFFD0D0D0 : 0xFF000000;
			int half = isSelected ? HANDLE_HALF_WIDTH + 1 : HANDLE_HALF_WIDTH;
			graphics.fill(x - half - 1, top - HANDLE_OVERHANG - 1, x + half + 2, bottom + HANDLE_OVERHANG + 1, border);
			graphics.fill(x - half, top - HANDLE_OVERHANG, x + half + 1, bottom + HANDLE_OVERHANG, point.projection.color());
		}

		Fov360Config.ProjectionPoint selectedPoint = selectedPoint();
		graphics.text(font, Component.translatable("fov360.thresholds.selected", selectedPoint.fov, selectedPoint.projection.displayName()),
			getX() + PADDING, getY() + 4, 0xFFFFFFFF, false);

		if (dragging) {
			return;
		}
		if (hovered >= 0) {
			graphics.setComponentTooltipForNextFrame(font, pointTooltip(hovered), mouseX, mouseY);
		} else if (isHovered() && overTrack(mouseX, mouseY)) {
			graphics.setComponentTooltipForNextFrame(font, trackTooltip(xToFov(mouseX)), mouseX, mouseY);
		}
	}

	private List<Component> pointTooltip(int index) {
		Fov360Config.ProjectionPoint point = points.get(index);
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("fov360.thresholds.selected", point.fov, point.projection.displayName()));
		if (index > 0) {
			lines.add(Component.translatable("fov360.thresholds.blendFrom", points.get(index - 1).projection.displayName(), point.blend.displayName()));
		}
		lines.add(Component.translatable("fov360.thresholds.pointHint"));
		return lines;
	}

	private List<Component> trackTooltip(int fov) {
		Fov360Config.ProjectionBlend blend = Fov360Config.sample(points, fov);
		List<Component> lines = new ArrayList<>();
		if (blend.t() > 0.0F && blend.from() != blend.to()) {
			lines.add(Component.translatable("fov360.thresholds.blending", fov, blend.from().displayName(), blend.to().displayName(), Math.round(blend.t() * 100)));
		} else {
			lines.add(Component.translatable("fov360.thresholds.selected", fov, blend.from().displayName()));
		}
		lines.add(points.size() < Fov360Config.MAX_POINTS
			? Component.translatable("fov360.thresholds.addHint")
			: Component.translatable("fov360.thresholds.full", Fov360Config.MAX_POINTS));
		lines.add(Component.translatable("fov360.thresholds.setFovHint"));
		return lines;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		Fov360Config.ProjectionPoint point = selectedPoint();
		output.add(NarratedElementType.TITLE, Component.translatable("fov360.thresholds.selected", point.fov, point.projection.displayName()));
		output.add(NarratedElementType.USAGE, Component.translatable("fov360.thresholds.keysHint"));
	}
}
