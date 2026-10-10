package xpncvr.fov360.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.fov360.Fov360Keys;
import xpncvr.fov360.Fov360OptionsScreen;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	@Final
	public Options options;

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void fov360$openSettings(CallbackInfo ci) {
		Minecraft client = (Minecraft) (Object) this;
		while (Fov360Keys.OPEN_SETTINGS.consumeClick()) {
			if (client.gui.screen() == null) {
				client.gui.setScreen(new Fov360OptionsScreen(null, options));
			}
		}
	}
}
