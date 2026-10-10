package xpncvr.fov360;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class Fov360Keys {
	public static final KeyMapping OPEN_SETTINGS = new KeyMapping(
		"key.fov360.openSettings",
		InputConstants.UNKNOWN.getValue(),
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fov360", "main")));

	private Fov360Keys() {
	}
}
