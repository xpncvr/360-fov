package xpncvr.fov360;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class Fov360Keys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fov360", "main"));

	public static final KeyMapping OPEN_SETTINGS = new KeyMapping(
		"key.fov360.openSettings",
		InputConstants.UNKNOWN.getValue(),
		CATEGORY);

	public static final KeyMapping TOGGLE_REAR_VIEW = new KeyMapping(
		"key.fov360.toggleRearView",
		InputConstants.UNKNOWN.getValue(),
		CATEGORY);

	public static final KeyMapping[] ALL = {OPEN_SETTINGS, TOGGLE_REAR_VIEW};

	private Fov360Keys() {
	}
}
