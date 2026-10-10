package xpncvr.fov360;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public enum Fov360Projection {
	RECTILINEAR(0xFF4FA3E0, 170.0F),
	PANINI_STEREOGRAPHIC(0xFF4CC38A, 330.0F),
	PANINI(0xFF9AD455, 330.0F),
	STEREOGRAPHIC(0xFF2BB5B0, 330.0F),
	FISHEYE(0xFFE8B23A, Float.POSITIVE_INFINITY),
	MERCATOR(0xFFE0703C, Float.POSITIVE_INFINITY),
	EQUIRECTANGULAR(0xFFD9506A, Float.POSITIVE_INFINITY);

	private final int color;
	private final float maxFov;

	Fov360Projection(int color, float maxFov) {
		this.color = color;
		this.maxFov = maxFov;
	}

	public int color() {
		return color;
	}

	public float maxFov() {
		return maxFov;
	}

	public boolean hasMaxFov() {
		return maxFov != Float.POSITIVE_INFINITY;
	}

	public Component displayName() {
		return Component.translatable("fov360.projection." + name().toLowerCase(Locale.ROOT));
	}
}
