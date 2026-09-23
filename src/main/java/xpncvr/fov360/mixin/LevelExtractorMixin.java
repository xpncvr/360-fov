package xpncvr.fov360.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xpncvr.fov360.Fov360Renderer;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

	@Inject(method = "extract", at = @At("HEAD"), cancellable = true)
	private void panini$deferExtractionToCapture(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick,
			CallbackInfo ci) {
		if (!Fov360Renderer.capturing && Fov360Renderer.willCapture(Minecraft.getInstance())) {
			ci.cancel();
		}
	}

	@Inject(method = "shouldShowEntityOutlines", at = @At("HEAD"), cancellable = true)
	private static void panini$enableOutlinesDuringCapture(Camera camera, PlayerRenderState playerRenderState,
			CallbackInfoReturnable<Boolean> cir) {
		if (Fov360Renderer.capturing && Fov360Renderer.captureOutlines) {
			cir.setReturnValue(true);
		}
	}
}
