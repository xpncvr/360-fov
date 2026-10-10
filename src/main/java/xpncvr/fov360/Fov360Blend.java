package xpncvr.fov360;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public enum Fov360Blend {
	EASE_OUT,
	LINEAR,
	SMOOTH;

	public float apply(float t) {
		return switch (this) {
			case EASE_OUT -> 1.0F - (t - 1.0F) * (t - 1.0F);
			case LINEAR -> t;
			case SMOOTH -> t * t * (3.0F - 2.0F * t);
		};
	}

	public Component displayName() {
		return Component.translatable("fov360.blend." + name().toLowerCase(Locale.ROOT));
	}
}
