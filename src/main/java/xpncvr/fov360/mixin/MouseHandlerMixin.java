package xpncvr.fov360.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xpncvr.fov360.Fov360Renderer;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

	@Shadow private double xpos;

	@Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;D)D", at = @At("HEAD"), cancellable = true)
	private static void panini$scaleXHalf(Window window, double x, CallbackInfoReturnable<Double> cir) {
		if (Fov360Renderer.splitGuiActive()) {
			double originX = Fov360Renderer.splitGuiOnRight() ? window.getScreenWidth() / 2.0 : 0.0;
			cir.setReturnValue((x - originX) * window.getGuiScaledWidth() / (window.getScreenWidth() / 2.0));
		}
	}

	@Redirect(
		method = "releaseMouse",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/platform/InputConstants;releaseMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"))
	private void panini$centerCursorOnForwardHalf(Window window, double x, double y) {
		if (Fov360Renderer.splitGuiActive()) {
			double forwardCenterX = window.getScreenWidth() * (Fov360Renderer.splitGuiOnRight() ? 0.75 : 0.25);
			this.xpos = forwardCenterX;
			InputConstants.releaseMouse(window, forwardCenterX, y);
		} else {
			InputConstants.releaseMouse(window, x, y);
		}
	}
}
