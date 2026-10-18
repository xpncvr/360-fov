package xpncvr.fov360;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

public enum Fov360Projection {
	RECTILINEAR(0xFF4FA3E0),
	PANINI_STEREOGRAPHIC(0xFF4CC38A),
	PANINI(0xFF9AD455),
	STEREOGRAPHIC(0xFF2BB5B0),
	FISHEYE(0xFFE8B23A),
	MERCATOR(0xFFE0703C),
	EQUIRECTANGULAR(0xFFD9506A);

	private final int color;

	Fov360Projection(int color) {
		this.color = color;
	}

	public int color() {
		return color;
	}

	public float maxFov() {
		Fov360Config config = Fov360Renderer.currentConfig();
		return switch (this) {
			case RECTILINEAR -> usableFov(180.0);
			case PANINI -> paniniMaxFov(config.paniniD);
			case STEREOGRAPHIC -> stereographicMaxFov(config.stereographicK);
			case PANINI_STEREOGRAPHIC -> Math.min(paniniMaxFov(config.paniniD), stereographicMaxFov(config.stereographicK));
			default -> Float.POSITIVE_INFINITY;
		};
	}

	public boolean hasMaxFov() {
		return maxFov() != Float.POSITIVE_INFINITY;
	}

	public Component displayName() {
		return Component.translatable("fov360.projection." + name().toLowerCase(Locale.ROOT));
	}

	private static float paniniMaxFov(float d) {
		return usableFov(2.0 * Math.toDegrees(Math.acos(-Math.min(d, 1.0F / d))));
	}

	private static float stereographicMaxFov(float k) {
		return k <= 0.0F ? Float.POSITIVE_INFINITY : usableFov(180.0 / k);
	}

	private static float usableFov(double limit) {
		return (float) (limit - 10.0 - 20.0 * Mth.clamp((limit - 180.0) / 180.0, 0.0, 1.0));
	}
}
